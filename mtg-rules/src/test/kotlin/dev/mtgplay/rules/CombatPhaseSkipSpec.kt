package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.effect.skipNextCombatPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * CR 500.10 scheduled combat-phase skips (`W8-G`) — the framework Stonehorn Dignitary needs, driven
 * through the real turn engine rather than asserted on the counter.
 *
 * Skipping a phase means it *does not occur at all*: no turn-based action, no priority window, and no
 * `PhaseBegan`/`StepBegan` narration. These specs therefore read the event log, which is the only place
 * the difference between "combat happened and nothing attacked" and "combat never happened" is visible.
 */
class CombatPhaseSkipSpec :
    StringSpec({

        "CR 500.10: a scheduled skip removes the active player's whole combat phase from the turn" {
            val log = phasesOfFirstTurn(scheduleFor = alice)
            log shouldNotContain GameEvent.PhaseBegan(TurnPhase.COMBAT)
            // Every *other* phase is untouched, and the postcombat main still happens — a skipped
            // combat phase is stepped over, not an early end of turn.
            log shouldContain GameEvent.PhaseBegan(TurnPhase.PRECOMBAT_MAIN)
            log shouldContain GameEvent.PhaseBegan(TurnPhase.POSTCOMBAT_MAIN)
            log shouldContain GameEvent.PhaseBegan(TurnPhase.ENDING)
        }

        "CR 500.10: not one of the five combat steps is narrated either" {
            val log = phasesOfFirstTurn(scheduleFor = alice)
            listOf(
                TurnStep.BEGINNING_OF_COMBAT,
                TurnStep.DECLARE_ATTACKERS,
                TurnStep.DECLARE_BLOCKERS,
                TurnStep.COMBAT_DAMAGE,
                TurnStep.END_OF_COMBAT,
            ).forEach { step -> log shouldNotContain GameEvent.StepBegan(step) }
        }

        "CR 506.1: the skip belongs to the affected player's own turn, not to the next turn to happen" {
            // The skip is scheduled against *bob* while alice is active, so alice's combat still runs.
            val log = phasesOfFirstTurn(scheduleFor = bob)
            log shouldContain GameEvent.PhaseBegan(TurnPhase.COMBAT)
        }

        "CR 500.10: exactly one phase is spent per scheduled skip, and a second skip costs a second phase" {
            val twice = playSkipping(scheduleFor = alice, count = 2)
            // Alice's first two combat phases are gone; the counter is spent one at a time, so after a
            // single turn one skip remains standing.
            afterFirstTurn(twice).players.getValue(alice).combatPhasesToSkip shouldBe 1
        }

        "CR 500.10: a scheduled skip survives the turns in between until its phase is actually skipped" {
            // Scheduled against bob during alice's turn: alice's whole turn passes without spending it.
            val state = afterFirstTurn(playSkipping(scheduleFor = bob, count = 1))
            state.players.getValue(bob).combatPhasesToSkip shouldBe 1
        }
    })

/** A game driven a few turns with [count] combat skips scheduled against [scheduleFor] before turn 1. */
private fun playSkipping(
    scheduleFor: PlayerId,
    count: Int,
): List<RecordedPause> {
    val engine = DefaultGameEngine()
    var current = engine.start(mountainConfig())
    // The skips are written onto the started game exactly as a resolved Dignitary trigger would.
    current =
        when (current) {
            is AdvanceResult.NeedsDecision ->
                AdvanceResult.NeedsDecision(
                    (0 until count).fold(current.state) { s, _ -> skipNextCombatPhase(s, scheduleFor) },
                    current.request,
                )
            is AdvanceResult.GameOver -> error("a fresh game is not over")
        }
    val pauses = mutableListOf<RecordedPause>()
    repeat(TURNS_DRIVEN) {
        when (val result = current) {
            is AdvanceResult.GameOver -> return pauses
            is AdvanceResult.NeedsDecision -> {
                pauses += RecordedPause(result.state, result.request)
                current = engine.advance(result.state, respondTo(result.request))
            }
        }
    }
    return pauses
}

/** The phase/step narration of the game's first turn — everything logged before turn 2 begins. */
private fun phasesOfFirstTurn(scheduleFor: PlayerId): List<GameEvent> {
    val state = afterFirstTurn(playSkipping(scheduleFor, count = 1))
    return state.events.takeWhile { it != GameEvent.TurnBegan(bob, 2) }
}

/** The state at the first pause of turn 2 — i.e. once turn 1 has run to completion. */
private fun afterFirstTurn(pauses: List<RecordedPause>): GameState =
    pauses.firstOrNull { it.state.turn.number >= 2 }?.state
        ?: pauses.last().state

/** Enough passed priority windows to carry a lands-only game through two whole turns. */
private const val TURNS_DRIVEN: Int = 200
