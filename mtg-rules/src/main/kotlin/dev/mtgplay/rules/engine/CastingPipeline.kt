package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.PaymentPlan
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/*
 * The CR 601 casting pipeline, in two halves.
 *
 * **Gathering** (ADR-004): choosing to cast opens a [PendingCast] on the state and the engine
 * suspends once per choice the cast needs — targets (CR 601.2c), then a payment plan
 * (CR 601.2g) — each re-derivable from the paused state alone. While gathering, the card is
 * still in the caster's hand and nothing about the game has changed.
 *
 * **Execution**: the final choice triggers [executeCastPipeline], which runs every CR 601
 * stage in order as one pure transition. Atomicity (CR 601.3e, CR 728's rewind) is the
 * immutability of the paused state: an illegal or aborted cast — an invalid decision while
 * gathering, or any loud failure inside a stage — throws out of `advance`, and the state the
 * caller holds *is* the pre-cast state; no half-cast residue can exist because no intermediate
 * state ever escapes.
 *
 * Every stage is an explicit function, even where trivial, because the stages are the hooks
 * later phases extend: modes (601.2b) for modal spells, cost determination (601.2f) for
 * additional/alternative costs (Grab the Prize, Fireblast — Phase 5, docs/decklists.md),
 * payment (601.2g–h) for triggered mana abilities (Utopia Sprawl), and cast completion
 * (601.2i) for cast triggers (Guttersnipe).
 */

/**
 * Executes every CR 601 stage in order as one pure transition (see the file comment for the
 * atomicity contract), then gives the caster priority again (CR 117.3b) with every pass-flag
 * reset — casting is an action, so the CR 117.4 "all players pass in succession" count starts
 * over. State-based actions are checked before the caster's new window opens (CR 704.3, inside
 * [priorityTo]), which is how a cast that pays life down to 0 ends the game first (CR 704.5a).
 */
internal fun executeCastPipeline(
    state: GameState,
    plan: PaymentPlan,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    val targets = cast.chosenTargets ?: error("CR 601.2c: targets must be settled before payment is chosen")
    // Close the gathering record first: from here the cast either completes or throws whole.
    val casting = state.copy(pendingCast = null)
    val (proposed, entry) = proposeSpell(casting, cast, targets)
    val moded = chooseModes(proposed)
    val targeted = establishTargets(moded, entry)
    val withAdditional = payAdditionalCosts(targeted, cast)
    val withSacrifice = paySacrificeCosts(withAdditional, cast)
    val withDiscard = payAdditionalDiscardCost(withSacrifice, cast)
    val totalCost = determineTotalCost(entry)
    val paid = payCosts(withDiscard, entry, totalCost, plan)
    val complete = completeCast(paid, entry)
    return priorityAfterCast(complete, cast)
}

/**
 * Grants priority after a completed cast, with every pass-flag reset (a cast starts the CR 117.4 count
 * over). A normal or priority-window cast (hand, flashback, escape) returns priority to the caster, who
 * held it (CR 117.3c). A **madness** cast happened *as a reflexive trigger resolved* (CR 702.35b), not
 * from a priority window — so, like any spell put on the stack during a resolution, the active player
 * receives priority in a fresh round.
 */
private fun priorityAfterCast(
    state: GameState,
    cast: PendingCast,
): AdvanceResult =
    if (cast.castingPermission is CastingPermission.Madness) {
        grantPriorityRound(state)
    } else {
        priorityTo(clearPriorityRound(state), cast.caster)
    }

/**
 * Stage CR 601.2a — propose: the card moves from the caster's hand to the top of the stack,
 * becoming a new object (CR 400.7) whose controller is the caster (CR 601.2), and the cast
 * record — controller, targets, definition — is fixed on the stack entry. Emits
 * [GameEvent.SpellProposed].
 *
 * Casting from zones other than the hand (madness's exile cast, flashback's and escape's graveyard
 * cast — docs/decklists.md) generalizes this stage's source zone via the [PendingCast.source] seam;
 * nothing else in the pipeline knows where the card came from. The permission the cast used
 * ([PendingCast.castingPermission]) is fixed on the cast record ([StackEntry.Spell.castVia]) because it
 * governs how the spell later leaves the stack (flashback's exile-instead, CR 702.34e).
 */
private fun proposeSpell(
    state: GameState,
    cast: PendingCast,
    targets: PersistentList<Target>,
): Pair<GameState, StackEntry.Spell> {
    val zoneObject =
        objectInZone(state, cast.caster, cast.source, cast.cardObjectId)
            ?: error("CR 601.2a: object ${cast.cardObjectId} is not in ${cast.caster}'s ${cast.source} zone")
    val definition = spellDefinitionOf(state, zoneObject.card)
    val (id, allocated) = state.allocateObjectId()
    // CR 601.2b: the printed identities of the additional-discard cards, captured now (still in hand,
    // discarded at payment) as the resolution's linked information (Grab the Prize).
    val discardedForCost =
        (cast.additionalDiscard ?: persistentListOf())
            .map { discardId ->
                state
                    .player(cast.caster)
                    .hand
                    .firstOrNull { it.id == discardId }
                    ?.card
                    ?: error("CR 601.2b: additional-discard card $discardId is not in ${cast.caster}'s hand")
            }.toPersistentList()
    // CR 400.7: the object on the stack is a fresh object with no zone-status memory (no madness marker).
    val entry =
        StackEntry.Spell(
            obj = GameObject(id = id, card = zoneObject.card, owner = zoneObject.owner),
            controller = cast.caster,
            targets = targets,
            definition = definition,
            castVia = cast.castingPermission,
            discardedForCost = discardedForCost,
        )
    val proposed =
        removeFromZone(allocated, cast.caster, cast.source, cast.cardObjectId)
            .updateStack { it.adding(entry) }
            .emit(GameEvent.SpellProposed(cast.caster, id, zoneObject.card))
    return proposed to entry
}

/**
 * Stage CR 601.2b/h — additional non-mana costs: exiles the cards chosen for an "exile N other cards"
 * additional cost (escape, CR 702.139a) from the caster's source zone, each as a new exile object
 * (CR 400.7), emitting [GameEvent.CardsExiledForCost]. A no-op when the permission has no such cost (the
 * settled list is empty). The cards were chosen legally while gathering (ADR-005), so a missing one is
 * an engine defect and fails loudly.
 */
private fun payAdditionalCosts(
    state: GameState,
    cast: PendingCast,
): GameState {
    val toExile =
        cast.additionalExileCost
            ?: error("CR 601.2h: the additional exile cost of ${cast.cardObjectId} was not settled before payment")
    if (toExile.isEmpty()) return state
    val exiledIds = mutableListOf<ObjectId>()
    val exiled =
        toExile.fold(state) { current, id ->
            val zoneObject =
                objectInZone(current, cast.caster, cast.source, id)
                    ?: error("CR 601.2h: additional-cost card $id is not in ${cast.caster}'s ${cast.source} zone")
            val (newId, allocated) = current.allocateObjectId()
            exiledIds += newId
            removeFromZone(allocated, cast.caster, cast.source, id)
                .updateExile { it.adding(GameObject(id = newId, card = zoneObject.card, owner = zoneObject.owner)) }
        }
    return exiled.emit(GameEvent.CardsExiledForCost(cast.caster, exiledIds))
}

/**
 * Stage CR 601.2b — modes. A documented no-op hook: no modal spell exists in the MVP
 * mainboards (Cast into the Fire and Pyroblast are sideboard, post-MVP — docs/decklists.md)
 * and the [SpellDefinition] SPI cannot express modes, so there is never a mode to choose and
 * nothing to silently mishandle. When modal spells arrive, this stage gains the mode decision
 * and the cast record on [StackEntry.Spell] gains the chosen modes.
 */
private fun chooseModes(state: GameState): GameState = state

/**
 * Stage CR 601.2c — targets: re-validates that the gathered choices satisfy the spec and are
 * legal right now, failing loudly on any mismatch (they were enumerated legally and nothing
 * can have changed while gathering — a violation is an engine defect, ADR-005). Emits
 * [GameEvent.TargetsChosen] for a spell that targets.
 */
private fun establishTargets(
    state: GameState,
    entry: StackEntry.Spell,
): GameState =
    when (val spec = entry.definition.targetSpec) {
        TargetSpec.None -> {
            require(entry.targets.isEmpty()) {
                "CR 601.2c: ${entry.obj.card.name} targets nothing but ${entry.targets} were chosen"
            }
            state
        }
        // Every targeting spec in the pool demands exactly one legal target (CR 303.4a for an Aura).
        TargetSpec.TargetOpponent, TargetSpec.AnyTarget, is TargetSpec.Enchantable -> {
            require(entry.targets.size == 1) {
                "CR 601.2c: ${entry.obj.card.name} demands exactly one target, got ${entry.targets}"
            }
            entry.targets.forEach { target ->
                require(isTargetLegal(state, spec, target, entry.controller)) {
                    "CR 601.2c: $target is not a legal target for ${entry.obj.card.name}"
                }
            }
            state.emit(GameEvent.TargetsChosen(entry.controller, entry.obj.id, entry.targets))
        }
    }

/**
 * Stage CR 601.2f — cost determination: the mana cost the payment plan pays. A cast via an alternative
 * permission (madness, flashback, escape) pays the permission's cost, which **replaces** the printed
 * mana cost entirely (CR 118.9); a normal cast pays the printed cost. The non-mana part of an
 * additional cost (escape's exile-N-others) is paid separately in [payAdditionalCosts] (CR 601.2h).
 * Fails loudly only for a normal cast of a card with no printed cost — no such card is castable.
 */
private fun determineTotalCost(entry: StackEntry.Spell): ManaCost =
    entry.castVia?.cost
        ?: entry.definition.manaCost
        ?: error(
            "CR 601.2f: ${entry.obj.card.name} has no mana cost and no alternative cost to cast it with",
        )

/**
 * Stages CR 601.2g–h — mana abilities and payment, executing the chosen [PaymentPlan] (see
 * docs/design/mana-payment.md): for each payment in plan order, a `ByTapping` payment
 * activates the tap-for-mana ability of its class's first untapped member in battlefield order
 * — resolving immediately, no stack, no priority round (CR 605.3) — then the produced (or
 * pooled) mana pays its symbol, and a `WithTwoLife` payment pays the Phyrexian alternative by
 * losing 2 life (CR 107.4).
 *
 * The plan is validated against the cost before anything executes; enumeration guarantees it
 * fits (ADR-005), so a mismatch is an engine defect and fails loudly.
 */
private fun payCosts(
    state: GameState,
    entry: StackEntry.Spell,
    cost: ManaCost,
    plan: PaymentPlan,
): GameState = payManaPlan(state, entry.controller, cost, plan)

/**
 * Stage CR 601.2i — the cast completes: the spell is cast, and "when a player casts a spell"
 * abilities trigger now. Emits [GameEvent.SpellCast], then fires cast triggers at the wired seam
 * ([detectCastTriggers]) — a fired trigger is queued and placed on the stack at the priority grant
 * that follows (CR 603.3b). No MVP mainboard card carries a cast trigger; the seam exists for
 * Guttersnipe (P6) and is exercised by a rules-test fixture.
 */
private fun completeCast(
    state: GameState,
    entry: StackEntry.Spell,
): GameState =
    detectCastTriggers(state.emit(GameEvent.SpellCast(entry.controller, entry.obj.id, entry.obj.card)), entry)
