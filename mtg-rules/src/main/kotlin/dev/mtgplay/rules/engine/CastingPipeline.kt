package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastingPermission
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
    val modes = cast.chosenModes ?: error("CR 601.2b: modes must be settled before targets are chosen")
    val targets = cast.chosenTargets ?: error("CR 601.2c: targets must be settled before payment is chosen")
    // CR 601.2b: both cost announcements are settled before the plan was ever enumerated; a null here
    // means the gathering order was violated, which would price the cast differently from the plan.
    cast.kicked ?: error("CR 601.2b: the kicker announcement must be settled before payment is chosen")
    cast.optionalCostTaken
        ?: error("CR 601.2b: the optional additional cost must be announced before payment is chosen")
    cast.chosenX ?: error("CR 601.2b: the value of X must be announced before payment is chosen")
    // Close the gathering record first: from here the cast either completes or throws whole.
    val casting = state.copy(pendingCast = null)
    val (proposed, entry) = proposeSpell(casting, cast, modes, targets)
    val moded = chooseModes(proposed, entry)
    val targeted = establishTargets(moded, entry)
    // CR 601.2f runs *here*, ahead of every cost-payment stage: see [determineTotalCost] for why the
    // three stages below must not be able to re-price the spell.
    val totalCost = determineTotalCost(targeted, cast, entry)
    val withAdditional = payAdditionalCosts(targeted, cast)
    val withSacrifice = paySacrificeCosts(withAdditional, cast)
    val withTap = payTapCosts(withSacrifice, cast)
    val withDiscard = payAdditionalDiscardCost(withTap, cast)
    val revealed = payHandRevealCost(withDiscard, cast)
    val paid = payCosts(revealed, entry, totalCost, plan)
    // CR 601.2h after CR 601.2g: the intrinsic sacrifice cost is paid **after** the mana, so a land
    // tapped by the plan may be the one sacrificed (docs/design/mana-payment.md §2.2).
    val sacrificed = payAdditionalSacrificeCost(paid, cast)
    // CR 601.2h: the optional additional cost is paid beside the intrinsic one, after the mana, so a
    // permanent tapped for mana by the plan may be the one sacrificed.
    val bargained = payOptionalAdditionalCost(sacrificed, cast)
    val complete = completeCast(bargained, entry)
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
    modes: PersistentList<Int>,
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
    // CR 601.2b: the printed identities of the permanents sacrificed to the intrinsic sacrifice cost,
    // captured now (still on the battlefield, sacrificed at payment) as the resolution's linked
    // information — Reckoner's Bargain's "the sacrificed permanent's mana value" is read from this
    // last-known information (CR 608.2h), the permanent itself being long gone by resolution.
    val sacrificedForCost =
        (cast.additionalSacrifice ?: persistentListOf())
            .map { sacrificeId ->
                state.sharedZones.battlefield
                    .firstOrNull { it.id == sacrificeId }
                    ?.card
                    ?: error("CR 601.2b: additional-sacrifice permanent $sacrificeId is not on the battlefield")
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
            sacrificedForCost = sacrificedForCost,
            // CR 700.2c: the modes chosen while gathering are fixed on the cast record now and can never
            // change; the target stage below and every later CR 608.2b re-check read the spell's
            // targeting line through them.
            chosenModes = modes,
            // CR 702.33f: the linked information "this spell was kicked", fixed here for the same reason
            // the modes are — it is settled while casting and everything downstream depends on it.
            kicked = cast.kicked ?: false,
            // CR 702.166b: the linked information "this spell was bargained", fixed here for the reason
            // the kicker flag is — it is settled while casting and read long after the spell is gone.
            optionalCostPaid = cast.optionalCostTaken ?: false,
            // CR 202.3b: the announced value, which is what X *is* while this spell is on the stack.
            chosenX = cast.chosenX ?: 0,
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
 * Stage CR 601.2b — modes: announces the modes chosen for a modal spell (CR 700.2), emitting
 * [GameEvent.ModesChosen]. A no-op for a card with no modes, which announces nothing.
 *
 * The *choice* happened while gathering (CR 601.2b is a choice, and ADR-004 forbids a callback out of a
 * pipeline stage), and it was already fixed onto the cast record by [proposeSpell]; what happens here is
 * the announcement and the validation that the settled value names a real mode. So the stage is thin,
 * but its **position** is the whole of what this packet added: it sits between [proposeSpell] (CR 601.2a)
 * and [establishTargets] (CR 601.2c), which is the order CR 601.2 prints, and the target stage
 * immediately below reads its spec *through* the modes settled here. Reversing the two would ask the
 * engine to enumerate targets for a card whose targeting line is not yet determined.
 *
 * Modes are not re-chosen and cannot be: CR 700.2c fixes them when the spell is put on the stack.
 */
private fun chooseModes(
    state: GameState,
    entry: StackEntry.Spell,
): GameState {
    if (entry.definition.modes.isEmpty()) {
        require(entry.chosenModes.isEmpty()) {
            "CR 700.2: ${entry.obj.card.name} has no modes but ${entry.chosenModes} were chosen"
        }
        return state
    }
    // Fails loudly on a wrong arity or an out-of-range printed index (ADR-005: the mode was enumerated).
    val mode = chosenMode(entry.definition, entry.chosenModes)
    return state.emit(
        GameEvent.ModesChosen(entry.controller, entry.obj.id, entry.chosenModes, listOf(mode.text)),
    )
}

/**
 * Stage CR 601.2c — targets: re-validates that the gathered choices satisfy the spec and are
 * legal right now, failing loudly on any mismatch (they were enumerated legally and nothing
 * can have changed while gathering — a violation is an engine defect, ADR-005). Emits
 * [GameEvent.TargetsChosen] for a spell that chose at least one target.
 *
 * Three checks, and since `FW-MULTITGT` the first two are shared with the activation pipeline
 * ([requireWellFormedTargetChoice]): the **arity** lies within the spec's [TargetCount] clamped to what
 * the board offers, the choice obeys **CR 601.2c's same-object rule**, and each chosen target is still
 * legal. The `when` this replaced asked every targeting member for "exactly one"; a spell that targets
 * nothing and one whose controller declined both of its optional targets now travel the same path and
 * differ only in the count their spec carries. The spec comes from [effectiveTargetSpec], so for a
 * modal spell it is the **chosen mode's** (`FW-MODAL`).
 */
private fun establishTargets(
    state: GameState,
    entry: StackEntry.Spell,
): GameState {
    // For a modal spell this is the *chosen mode's* spec: the CR 601.2b answer settled one stage above
    // determines the CR 601.2c question asked here (`FW-MODAL`).
    val spec = effectiveTargetSpec(entry.definition, entry.chosenModes)
    // CR 601.2a ran before this stage, so the spell is already on the stack under `entry.obj.id`;
    // naming it here keeps this re-validation's enumeration equal to the gathering-time one, in which
    // the card was still in its source zone.
    // CR 601.2c: announceable, not merely legal — a targeting requirement standing against the caster
    // narrowed what could be offered, so it must narrow what is re-validated (`W8-G`).
    val options = announceableTargets(state, spec, entry.controller, Chooser.Spell(entry.obj.id))
    requireWellFormedTargetChoice(spec, entry.targets, options.size, entry.obj.card.name)
    entry.targets.forEach { target ->
        require(target in options) {
            "CR 601.2c: $target is not a legal target for ${entry.obj.card.name}"
        }
    }
    // A spell that announced no targets — one that targets nothing, or an "up to N" whose controller
    // declined them all — has no target choice to narrate.
    val narrated =
        if (entry.targets.isEmpty()) {
            state
        } else {
            state.emit(GameEvent.TargetsChosen(entry.controller, entry.obj.id, entry.targets))
        }
    // CR 702.21a: the targets are now chosen, so any warded permanent among them has *become* a target
    // and its ward triggers — here, at CR 601.2c, and therefore before the CR 601.2g payment below.
    return detectWardTriggers(narrated, entry.targets, entry.controller, entry.obj.id)
}

/**
 * Stage CR 601.2f — cost determination: the mana cost the payment plan pays, after cost modification
 * (docs/design/cost-modification.md). A cast via an alternative permission (madness, flashback,
 * escape) starts from the permission's cost, which **replaces** the printed mana cost entirely
 * (CR 118.9); a normal cast starts from the printed cost. [totalCost] then applies every cost
 * increase (none exist) and every cost reduction, clamped at `{0}`. The non-mana part of an
 * additional cost (escape's exile-N-others) is paid separately in [payAdditionalCosts] (CR 601.2h).
 *
 * **This stage runs before every cost-payment stage, and the order is load-bearing.** CR 601.2f fixes
 * the total cost and locks it in: "If effects would change the total cost after this time, they have
 * no effect." Until `FW-COST` this stage sat *after* [payAdditionalCosts], [paySacrificeCosts], and
 * [payAdditionalDiscardCost], which was harmless only while the cost was a constant. Each of those
 * three breaks lock-in in a different direction the moment a reduction counts anything:
 *
 * - [paySacrificeCosts] removes permanents from the battlefield, so sacrificing an artifact would
 *   re-price an affinity spell **upward** mid-cast. This is the CR 601.2h example exactly — "You cast
 *   Altar's Reap … You sacrifice Thunderscape Familiar, whose effect makes your black spells cost {1}
 *   less to cast. Because a spell's total cost is 'locked in' before payments are actually made, you
 *   pay {B}, not {1}{B}" — and the engine got it backwards.
 * - [payAdditionalDiscardCost] puts cards into the **graveyard**, so discarding an instant would make
 *   a graveyard-counting spell one cheaper than the cost it was enumerated against.
 * - [payAdditionalCosts] exiles cards **from** the graveyard (escape). Those cards must still count:
 *   CR 601.2f precedes CR 601.2h, so escape fodder is counted and *then* exiled.
 *
 * The consequence of violating it is not a rules nicety — it is ADR-005's silent defect. The cast was
 * enumerated and its `ChoosePaymentPlan` derived against one cost; paying a different one means the
 * agent is handed plans that under- or over-pay, and `validatePlanShape` throws mid-cast at best.
 *
 * Determination deliberately does **not** hoist above [proposeSpell]: the card must already have left
 * its source zone (CR 601.2a) when the count is taken, which is what stops a graveyard-cast spell from
 * counting itself. It sits in the only position where both properties hold.
 */
private fun determineTotalCost(
    state: GameState,
    cast: PendingCast,
    entry: StackEntry.Spell,
): ManaCost =
    totalCost(
        state,
        entry.controller,
        CastSubject(entry.definition, entry.castVia, cast.cardObjectId),
        // CR 601.2b: the announcements made while gathering, read back off the cast record — the same
        // values the ChoosePaymentPlan the caster answered was derived against, so the plan and the cost
        // cannot disagree.
        CostAnnouncements(entry.kicked, entry.chosenX),
    )

/**
 * Stage CR 601.2h — the hand-reveal component of an alternative cost (CR 701.16a): a cast via a
 * permission whose [dev.mtgplay.core.definition.CastingPermission.revealsHand] is set publishes the
 * caster's hand as the cost is paid. Land Grant's "you may **reveal your hand** rather than pay this
 * spell's mana cost". A no-op for every other cast.
 *
 * **No decision, no pause, and nothing moves.** Unlike the sacrifice and discard cost components either
 * side of it, this cost has nothing to select — the whole hand is revealed — and nothing to consume, so
 * it is paid by emitting the CR 701.16a event that makes the cards public. It can never fail: an empty
 * hand is a legal thing to reveal.
 *
 * **The card being cast is not in the revealed hand, and that is CR 601.2a rather than a choice.** The
 * spell left the caster's hand for the stack in [proposeSpell], several stages above, so what is
 * revealed here is the hand as it stands while the cost is paid — which is what the printed card means
 * and what an opponent would see at a table.
 *
 * ADR-007: this **widens** what the opposing seat may see, for exactly as long as the event log records
 * it, and it is the printed card that widens it. Nothing here discloses anything the card does not
 * publish.
 */
private fun payHandRevealCost(
    state: GameState,
    cast: PendingCast,
): GameState {
    if (cast.castingPermission?.revealsHand != true) return state
    return state.emit(
        GameEvent.CardsRevealed(cast.caster, state.player(cast.caster).hand.map { it.card }),
    )
}

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
