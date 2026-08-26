package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.TargetContext
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingActivation
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.persistentListOf

/*
 * The CR 601.2b announcement of X on the **activation** path (CR 602.2b), split from
 * ActivationGathering.kt so each file stays inside detekt's function budget. The *bound* on the
 * announcement and the argument for its position live in `AbilityXCost.kt`; this file is only the
 * gathering stage — surfacing the request, recording the answer, and the one consequence the answer has
 * for the stage below it.
 *
 * That consequence is the whole reason the stage exists here rather than beside the cast path's: an
 * announcement can *open* the target stage (Gorilla Shaman's legal targets are unknowable until X has a
 * value) and can equally *close* it (an "up to N" X ability may find nothing to point at once the value
 * is known). Both are questions [beginActivation] could not answer.
 */

/**
 * The CR 601.2b request for the value of X on the open activation, offering only the values that are
 * payable **and** leave the ability a legal target ([abilityXValueOptions]).
 */
internal fun abilityXAnnouncementRequest(
    state: GameState,
    pending: PendingActivation,
    source: GameObject,
    ability: ActivatedAbility,
    id: DecisionRequestId,
): DecisionRequest.ChooseXValue =
    DecisionRequest.ChooseXValue(
        id = id,
        cardObjectId = pending.sourceObjectId,
        card = source.card,
        values = abilityXValueOptions(state, pending.activator, source, ability),
    )

/**
 * Records the announced value of X on the open activation (CR 601.2b via CR 602.2b) and continues
 * gathering — the activation-path sibling of the cast branch of `applyChosenXValue`.
 *
 * Settled before the targets, which is CR 601.2b's printed order; `AbilityXCost.kt`'s header argues why
 * this path takes that order and the cast path does not.
 */
internal fun applyChosenAbilityX(
    state: GameState,
    value: Int,
): AdvanceResult {
    val pending = state.pendingActivation ?: error("no activation is gathering costs")
    require(pending.chosenX == null) { "CR 601.2b: this activation's value of X is already announced" }
    return advanceActivationGathering(state.copy(pendingActivation = pending.copy(chosenX = value)))
}

/**
 * Settles an open activation's (empty) target list when the CR 601.2c choice turns out to be vacuous now
 * that the value of X is known (`W9-C`), leaving every other state untouched.
 *
 * Only ever reached for an ability that announces X: every other activation answered the same question in
 * [beginActivation], where the answer could not change. It exists because that answer genuinely *is*
 * different before and after the announcement — "target noncreature artifact with mana value X" enumerates
 * nothing at all until X has a value — so deciding it early would either settle the stage wrongly or
 * surface a request with an empty option list, which both target request kinds refuse in their `init`.
 *
 * For an ability demanding a target the branch never fires, because [abilityXValueOptions] offers only
 * values that leave a legal one; it is here for the "up to N" shape rather than for the card, and it fails
 * closed either way.
 */
internal fun settleVacuousActivationTargets(state: GameState): GameState {
    val pending = state.pendingActivation
    val announced = pending?.chosenX
    if (pending == null || announced == null || pending.chosenTargets != null) return state
    val vacuous =
        activationSource(state, pending.activator, pending.source, pending.sourceObjectId)?.let { source ->
            targetChoiceIsVacuous(
                state,
                abilityAt(state, pending.activator, pending.sourceObjectId, pending.source, pending.abilityIndex)
                    .targetSpec,
                pending.activator,
                Chooser.Ability(source.card),
                TargetContext(chosenX = announced),
            )
        } == true
    return if (vacuous) state.copy(pendingActivation = pending.copy(chosenTargets = persistentListOf())) else state
}
