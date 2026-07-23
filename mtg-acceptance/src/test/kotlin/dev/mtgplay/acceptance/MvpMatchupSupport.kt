package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.Responder
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameState
import dev.mtgplay.pauper.DeckLoader
import dev.mtgplay.pauper.MvpCardPool
import dev.mtgplay.pauper.MvpDecks
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption

/*
 * Shared support for the P6.2b real-matchup suites: the Mono-Red-Madness-vs-GW-Bogles config over the
 * completed [MvpCards] pool, and the responder that plays random-legal while routing around the three
 * STOP-flagged card actions whose resolution/effect is an unbuilt engine mechanism (P6.2b report).
 */

/** The Mono-Red Madness main library over [MvpCards] (all 60 mainboard cards now defined). */
internal fun monoRedMadnessLibrary(): List<CardRef> =
    DeckLoader(MvpCardPool.catalog).load(MvpDecks.monoRedMadness).mainLibrary()

/** The GW Bogles main library over [MvpCards] (all 60 mainboard cards now defined). */
internal fun gwBoglesLibrary(): List<CardRef> = DeckLoader(MvpCardPool.catalog).load(MvpDecks.gwBogles).mainLibrary()

/**
 * The real MVP matchup config (P6.2b): Mono-Red Madness ([alice]) vs GW Bogles ([bob]) over the full
 * [MvpCards] definitions, mulligans on (the default), seed-determined starting player (ADR-006).
 */
internal fun mvpMatchupConfig(seed: Long): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to monoRedMadnessLibrary(), bob to gwBoglesLibrary()),
        definitions = MvpCards.definitions,
        startingPlayer = null,
    )

/**
 * The three cards whose action a random game must avoid: their resolution/effect fails loudly because it
 * needs an engine mechanism P6.2a did not build (spell-resolution optional-discard-draw, mandatory
 * resolution discard-N, and library search), each STOP-flagged for the architect in the P6.2b report.
 * Casting Highway Robbery or Faithless Looting, or activating Ash Barrens' basic landcycling, throws on
 * resolution — so the corpus-scale suites route around exactly these while exercising the rest of both
 * real decks end to end. Every other card resolves normally.
 */
internal val STOP_FLAGGED_SPELLS: Set<CardRef> =
    setOf(CardRef("Highway Robbery"), CardRef("Faithless Looting"))

/** The card whose activated ability (basic landcycling) is STOP-flagged (its search effect is unbuilt). */
internal val STOP_FLAGGED_ACTIVATION: CardRef = CardRef("Ash Barrens")

/**
 * A [Responder] that plays a uniformly random legal decision ([RandomLegalResponder]) but never chooses a
 * STOP-flagged action: it substitutes a pass for any priority option that would cast [STOP_FLAGGED_SPELLS]
 * (a normal cast, a plot free-cast, or a flashback) or activate [STOP_FLAGGED_ACTIVATION]'s landcycling.
 * Deterministic given [seed] (the delegate's frozen [dev.mtgplay.core.random.Rng] advances exactly once
 * per decision, ADR-006). This is the ignition-scope compromise: those three card actions resolve into an
 * unbuilt mechanism, so a real game routes around them while every other transition runs and is
 * invariant-checked (P6.2b report; the full corpus with the missing mechanisms is P6.3).
 */
internal class GapAvoidingResponder(
    seed: Long,
) : Responder {
    private val delegate = RandomLegalResponder(seed)

    override fun respond(
        request: DecisionRequest,
        state: GameState,
    ): Decision {
        val decision = delegate.respond(request, state)
        if (request is DecisionRequest.ChooseAction && decision is Decision.SingleSelect) {
            if (isStopFlagged(request.options[decision.index])) {
                val passIndex = request.options.indexOfFirst { it is PriorityOption.Pass }
                check(passIndex >= 0) { "CR 117.3d: passing is always enumerated" }
                return Decision.SingleSelect(request.id, passIndex)
            }
        }
        return decision
    }

    private fun isStopFlagged(option: PriorityOption): Boolean =
        when (option) {
            is PriorityOption.CastSpell -> option.card in STOP_FLAGGED_SPELLS
            is PriorityOption.PlotCard -> option.card in STOP_FLAGGED_SPELLS
            is PriorityOption.ActivateAbility -> option.card == STOP_FLAGGED_ACTIVATION
            PriorityOption.Pass, is PriorityOption.PlayLand -> false
        }
}
