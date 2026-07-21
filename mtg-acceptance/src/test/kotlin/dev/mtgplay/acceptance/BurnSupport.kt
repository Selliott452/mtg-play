package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.Responder
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption

/*
 * Deterministic drivers for the real-card bolt-duel suites (P2.2).
 */

/**
 * The deterministic burn policy: play a land whenever one is enumerated, otherwise cast the
 * first enumerated spell, otherwise pass; always target an opponent (never itself); take the
 * first payment plan; discard lowest-indexed. Purely a function of the request, so a game it
 * drives is a reproducible decision script (ADR-006) — the scripted way to accumulate Bolts
 * until the CR 704.5a state-based action ends the game.
 */
internal val BURN_OPPONENT: Responder =
    Responder { request, _ ->
        when (request) {
            is DecisionRequest.ChooseAction -> {
                val playLand = request.options.indexOfFirst { it is PriorityOption.PlayLand }
                val cast = request.options.indexOfFirst { it is PriorityOption.CastSpell }
                val pass = request.options.indexOfFirst { it is PriorityOption.Pass }
                val index =
                    when {
                        playLand >= 0 -> playLand
                        cast >= 0 -> cast
                        else -> pass
                    }
                Decision.SingleSelect(request.id, index)
            }
            is DecisionRequest.ChooseTargets -> {
                val index = request.options.indexOfFirst { it != Target.Player(request.seat) }
                check(index >= 0) { "no opponent target among ${request.options}" }
                Decision.SingleSelect(request.id, index)
            }
            is DecisionRequest.ChoosePaymentPlan -> Decision.SingleSelect(request.id, 0)
            is DecisionRequest.ChooseDiscards ->
                Decision.MultiSelect(request.id, (0 until request.count).toList())
        }
    }

/**
 * The first seed in `0..999` whose game from [configOf] deals [seat] an opening hand
 * satisfying [predicate] — a deterministic search (ADR-006: the engine is a pure function of
 * the config), so scripted suites can rely on a hand shape without hardcoding a magic seed.
 */
internal fun seedWithOpeningHand(
    seat: PlayerId,
    configOf: (Long) -> MatchConfig,
    predicate: (List<String>) -> Boolean,
): Long {
    for (seed in 0L..999L) {
        val started = DefaultGameEngine().start(configOf(seed))
        val state =
            when (started) {
                is AdvanceResult.NeedsDecision -> started.state
                is AdvanceResult.GameOver -> continue
            }
        val hand =
            state.players
                .getValue(seat)
                .hand
                .map { it.card.name }
        if (predicate(hand)) return seed
    }
    error("no seed in 0..999 deals $seat a qualifying opening hand")
}
