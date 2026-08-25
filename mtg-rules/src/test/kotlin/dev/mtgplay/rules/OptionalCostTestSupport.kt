package dev.mtgplay.rules

import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.spellManaValue

/*
 * Scaffolding shared by the `FW-X`, `FW-OPTCOST` and `FW-ALTCOST` specs: builders that drive a fixture
 * cast to a named stage. Each returns the *paused* state so a spec can assert on the request it stopped
 * at, which is the ADR-004 property these frameworks all hang off.
 */

/** A board of [lands] for alice with [card] in hand, paused in her precombat main phase. */
internal fun optionalCostState(
    card: String,
    lands: List<String>,
    extraHand: List<String> = emptyList(),
): GameState =
    fixtureState(
        SeatSetup(hand = listOf(card) + extraHand, battlefield = lands),
        SeatSetup(),
        definitions = optionalCostDefinitions,
    )

/** Chooses [card]'s normal (printed-cost) cast from alice's priority window and returns the pause. */
internal fun beginCast(
    card: String,
    lands: List<String>,
    engine: GameEngine,
    extraHand: List<String> = emptyList(),
): GameState {
    val state = optionalCostState(card, lands, extraHand)
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
    return engine.advance(state, castDecision(window, card)).pausedState
}

/**
 * Drives [card]'s cast to its CR 601.2b announcement of X, answering any earlier stage on the way: a
 * kicker announcement is declined (the unkicked line is the baseline every X assertion is made against)
 * and a player target is pointed at alice herself.
 */
internal fun xGathering(
    card: String,
    lands: List<String>,
    engine: GameEngine,
): GameState {
    var current = beginCast(card, lands, engine)
    while (true) {
        when (val request = requestAwaiting(current)) {
            is DecisionRequest.ChooseXValue -> return current
            is DecisionRequest.ChooseYesNo ->
                current =
                    engine
                        .advance(current, Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.DECLINE))
                        .pausedState
            is DecisionRequest.ChooseTargets ->
                current = engine.advance(current, targetDecision(request, alice)).pausedState
            else -> error("no X announcement was reached; stopped at $request")
        }
    }
}

/** The [DecisionRequest.ChooseXValue] a fixture cast of [card] on [lands] surfaces. */
internal fun xRequestFor(
    card: String,
    lands: List<String>,
    engine: GameEngine,
): DecisionRequest.ChooseXValue = pausedRequestOf(xGathering(card, lands, engine))

/**
 * Casts "Fixture Surge" for an announced [x] at alice, paying the first enumerated plan, and returns the
 * state with the spell on the stack. Fails if [x] is not among the offered values — which is the point:
 * a spec asking for a value the bound did not offer should not quietly test something else.
 */
internal fun castSurgeFor(
    x: Int,
    lands: List<String>,
    engine: GameEngine,
): GameState {
    val paused = xGathering("Fixture Surge", lands, engine)
    val request = pausedRequestOf<DecisionRequest.ChooseXValue>(paused)
    val index = request.values.indexOf(x)
    check(index >= 0) { "CR 107.3b: X = $x is not among the announceable values ${request.values}" }
    val afterX = engine.advance(paused, Decision.SingleSelect(request.id, index)).pausedState
    val plan = pausedRequestOf<DecisionRequest.ChoosePaymentPlan>(afterX)
    return engine.advance(afterX, planDecision(plan)).pausedState
}

/** The mana value of the spell [entry] as it sits on the stack (CR 202.3b). */
internal fun spellManaValueOfEntry(
    state: GameState,
    entry: StackEntry.Spell,
): Int = spellManaValue(state, entry.obj.id)

/**
 * The request a paused state is waiting on, untyped, failing loudly when there is none (ADR-004).
 *
 * Deliberately **not** named `pendingRequestOf`: that is the published nullable accessor in `mtg-rules`
 * main ([dev.mtgplay.rules.pendingRequestOf]), and a same-package declaration here shadows it for every
 * spec in this source set — silently turning "no pending request" from a `null` into a thrown error,
 * which is exactly the answer a terminal state legitimately gives (CR 104.2a).
 */
internal fun requestAwaiting(state: GameState): DecisionRequest =
    pendingRequestOf(state) ?: error("state is not paused at a decision point")
