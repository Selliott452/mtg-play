package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.ManaSourceChoice
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SymbolPayment
import kotlinx.collections.immutable.PersistentList

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
    val (proposed, entry) = proposeSpell(casting, cast.caster, cast.cardObjectId, targets)
    val moded = chooseModes(proposed)
    val targeted = establishTargets(moded, entry)
    val totalCost = determineTotalCost(entry)
    val paid = payCosts(targeted, entry, totalCost, plan)
    val complete = completeCast(paid, entry)
    return priorityTo(clearPriorityRound(complete), cast.caster)
}

/**
 * Stage CR 601.2a — propose: the card moves from the caster's hand to the top of the stack,
 * becoming a new object (CR 400.7) whose controller is the caster (CR 601.2), and the cast
 * record — controller, targets, definition — is fixed on the stack entry. Emits
 * [GameEvent.SpellProposed].
 *
 * Casting from zones other than the hand (madness's exile cast, flashback's graveyard cast —
 * Phase 5, docs/decklists.md) generalizes this stage's source zone; nothing else in the
 * pipeline knows where the card came from.
 */
private fun proposeSpell(
    state: GameState,
    caster: PlayerId,
    cardObjectId: ObjectId,
    targets: PersistentList<Target>,
): Pair<GameState, StackEntry.Spell> {
    val hand = state.player(caster).hand
    val index = hand.indexOfFirst { it.id == cardObjectId }
    require(index >= 0) { "CR 601.2a: object $cardObjectId is not in $caster's hand" }
    val card = hand[index]
    val definition = spellDefinitionOf(state, card.card)
    val (id, allocated) = state.allocateObjectId()
    val entry = StackEntry.Spell(card.copy(id = id), caster, targets, definition)
    val proposed =
        allocated
            .updatePlayer(caster) { it.copy(hand = it.hand.removingAt(index)) }
            .updateStack { it.adding(entry) }
            .emit(GameEvent.SpellProposed(caster, id, card.card))
    return proposed to entry
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
        // Any-target and an Aura's enchant target (CR 303.4a) both demand exactly one legal target.
        TargetSpec.AnyTarget, is TargetSpec.Enchantable -> {
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
 * Stage CR 601.2f — cost determination: the total cost of the spell. In P2.1 the total cost is
 * exactly the printed mana cost; this function is the hook where the Phase 5 cost work slots
 * in without touching the rest of the pipeline (docs/decklists.md):
 * - **additional costs** (CR 601.2b/f — Grab the Prize's discard) will add non-mana components
 *   and record their linked information on the cast record;
 * - **alternative costs** (CR 601.2f — Fireblast's sacrifice) will replace the mana cost
 *   entirely, which is also when a `null` printed cost stops being an error here.
 */
private fun determineTotalCost(entry: StackEntry.Spell): ManaCost =
    entry.definition.manaCost
        ?: error(
            "CR 601.2f: ${entry.obj.card.name} has no mana cost and no alternative cost exists to cast " +
                "it with; alternative costs arrive in Phase 5 (docs/decklists.md)",
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
): GameState {
    validatePlanShape(cost, plan)
    return plan.payments.fold(state) { current, payment ->
        when (payment) {
            is SymbolPayment.WithMana ->
                when (val source = payment.source) {
                    ManaSourceChoice.FromPool ->
                        removeManaFromPool(current, entry.controller, payment.mana)
                    is ManaSourceChoice.ByTapping -> {
                        val produced = resolveTapForMana(current, entry.controller, source.sourceClass, payment.mana)
                        removeManaFromPool(produced, entry.controller, payment.mana)
                    }
                }
            SymbolPayment.WithTwoLife ->
                changeLife(current, entry.controller, -PHYREXIAN_LIFE_COST)
        }
    }
}

/**
 * Stage CR 601.2i — the cast completes: the spell is cast, and "when a player casts a spell"
 * abilities would trigger now (Guttersnipe — the Phase 5 cast-trigger hook lives at this
 * seam). Emits [GameEvent.SpellCast].
 */
private fun completeCast(
    state: GameState,
    entry: StackEntry.Spell,
): GameState = state.emit(GameEvent.SpellCast(entry.controller, entry.obj.id, entry.obj.card))
