package dev.mtgplay.rules

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption

/*
 * Board builders and cast drivers for [CostModificationSpec], kept out of the spec so each test reads
 * as the property it asserts. Every board is a valid engine input by construction (ADR-004): alice is
 * active and mid-priority-window, so the engine re-derives the pending request from the state alone.
 */

/** A cost-fixture board: [alice] and [bob]'s zones against the cost-modification registry. */
internal fun costState(
    alice: SeatSetup,
    bob: SeatSetup = SeatSetup(),
): GameState = fixtureState(aliceSetup = alice, bobSetup = bob, definitions = costFixtureDefinitions)

/**
 * The determined cost of casting [card] from alice's hand in [state], as rendered text — read off the
 * `ChoosePaymentPlan` the engine derives, so what is asserted is exactly what an agent is handed
 * rather than a direct call to the cost function.
 */
internal fun determinedCost(
    state: GameState,
    card: String,
): String = paymentRequestFor(state, card).cost.render()

/** The determined cost of [card] with [artifacts] Fixture Relics on alice's battlefield. */
internal fun costOf(
    card: String,
    artifacts: Int,
): String = determinedCost(affinityBoard(artifacts, inHand = card), card)

/** The determined cost of [card] with [permanents] alongside alice's lands. */
internal fun costOfBoard(
    card: String,
    permanents: List<String>,
): String =
    determinedCost(
        costState(alice = SeatSetup(hand = listOf(card), battlefield = permanents + costLands(LANDS_FOR_COST_READ))),
        card,
    )

/** The determined cost of [card] with [graveyard] in alice's graveyard. */
internal fun costOfGraveyard(
    card: String,
    graveyard: List<String>,
): String =
    determinedCost(
        costState(
            alice =
                SeatSetup(
                    hand = listOf(card),
                    battlefield = costLands(LANDS_FOR_COST_READ),
                    graveyard = graveyard,
                ),
        ),
        card,
    )

/**
 * A board with [artifacts] Fixture Relics and [lands] mana lands, [inHand] the one card in alice's
 * hand. The relics are plain artifacts with no mana ability, so raising [artifacts] changes the
 * *reduction* and never the mana available — which is what lets one board vary a single axis.
 */
internal fun affinityBoard(
    artifacts: Int,
    lands: Int = LANDS_FOR_COST_READ,
    inHand: String = "Fixture Scrapper",
): GameState =
    costState(
        alice =
            SeatSetup(
                hand = listOf(inHand),
                battlefield = List(artifacts) { "Fixture Relic" } + costLands(lands),
            ),
    )

/** A board where affinity reduces Fixture Colossus's `{6}` to the CR 601.2f `{0}` floor. */
internal fun zeroCostBoard(): GameState =
    costState(
        alice =
            SeatSetup(
                hand = listOf("Fixture Colossus"),
                battlefield = List(ARTIFACTS_FOR_ZERO_COST) { "Fixture Relic" } + costLands(1),
            ),
    )

/** A board for the additional-discard lock-in: one sorcery already in the graveyard, one in hand. */
internal fun discardCostBoard(): GameState =
    costState(
        alice =
            SeatSetup(
                hand = listOf("Fixture Reckoning", "Fixture Rite"),
                battlefield = costLands(LANDS_FOR_COST_READ),
                graveyard = listOf("Fixture Rite"),
            ),
    )

/** A board for the graveyard-cast self-exclusion: the flashback card sits among what it counts. */
internal fun flashbackBoard(): GameState =
    costState(
        alice =
            SeatSetup(
                battlefield = costLands(LANDS_FOR_COST_READ),
                graveyard = listOf("Fixture Rite", "Fixture Spark", "Fixture Recall"),
            ),
    )

/**
 * The `ChoosePaymentPlan` the engine surfaces for casting [card] in [state], reached by taking the
 * real cast option and answering every gathering request ahead of the payment with its first legal
 * choice. [permission] selects the permission cast (flashback, or a hand alternative cost) rather than
 * the normal one where a card offers both.
 */
internal fun paymentRequestFor(
    state: GameState,
    card: String,
    permission: Boolean = false,
): DecisionRequest.ChoosePaymentPlan {
    var result = DefaultGameEngine().advance(state, castOption(state, card, permission))
    while (true) {
        val request = (result as AdvanceResult.NeedsDecision).request
        if (request is DecisionRequest.ChoosePaymentPlan) return request
        result = DefaultGameEngine().advance(result.state, firstChoice(request))
    }
}

/**
 * Whether the whole cast of [card] executes without throwing — the end-to-end guard on lock-in.
 *
 * A cost divergence between the request derivation and the pipeline surfaces here as a loud failure in
 * `validatePlanShape`, because the plan was enumerated against one cost and validated against another.
 * That is the failure mode this framework exists to make impossible, so "it completes" is the
 * assertion rather than a formality.
 */
internal fun completes(
    state: GameState,
    card: String,
    permission: Boolean = false,
): Boolean {
    val engine = DefaultGameEngine()
    var result = engine.advance(state, castOption(state, card, permission))
    while (true) {
        val pending = (result as AdvanceResult.NeedsDecision).request
        val paying = pending is DecisionRequest.ChoosePaymentPlan
        result = engine.advance(result.state, firstChoice(pending))
        if (paying) return true
    }
}

/** The cast option for [card], normal or via a permission (CR 601.2, ADR-005). */
private fun castOption(
    state: GameState,
    card: String,
    permission: Boolean,
): Decision.SingleSelect {
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell &&
                it.card == CardRef(card) &&
                (it.permission != null) == permission
        }
    check(index >= 0) { "no ${if (permission) "permission" else "normal"} cast of $card in ${window.options}" }
    return Decision.SingleSelect(window.id, index)
}

/**
 * The first legal answer to a gathering request. Every request between the cast choice and the payment
 * in these scenarios is a single-index selection (targets, sacrifices, discards), and any first option
 * is legal by enumeration (ADR-005), so index 0 always advances.
 */
private fun firstChoice(request: DecisionRequest): Decision =
    when (request) {
        is DecisionRequest.ChooseSacrifices -> Decision.MultiSelect(request.id, listOf(0))
        is DecisionRequest.ChooseCardsToDiscardForCost -> Decision.MultiSelect(request.id, listOf(0))
        is DecisionRequest.ChooseCardsToExile -> Decision.MultiSelect(request.id, listOf(0))
        else -> Decision.SingleSelect(request.id, 0)
    }

/** [n] lands that between them pay any fixture cost under test: one blue source, the rest colorless. */
internal fun costLands(n: Int): List<String> =
    listOf("Fixture Atoll") + List((n - 1).coerceAtLeast(0)) { "Fixture Waste" }

/** Enough lands that every unreduced fixture cost in the spec is payable. */
internal const val LANDS_FOR_COST_READ: Int = 8

/** Artifacts enough to reduce a `{5}` generic component to nothing (CR 601.2f's `{0}` floor). */
internal const val ARTIFACTS_FOR_ZERO_COST: Int = 6
