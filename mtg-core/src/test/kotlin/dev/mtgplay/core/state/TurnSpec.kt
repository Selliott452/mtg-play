package dev.mtgplay.core.state

import dev.mtgplay.core.identity.PlayerId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Construction validation of the turn position against the CR 500 phase/step shape.
 */
class TurnSpec :
    StringSpec({
        val alice = PlayerId(0)

        "CR 505: a main phase carries no step" {
            Turn(alice, 1, TurnPhase.PRECOMBAT_MAIN, null).step shouldBe null
        }

        "CR 505: a step inside a main phase is rejected" {
            shouldThrow<IllegalArgumentException> {
                Turn(alice, 1, TurnPhase.PRECOMBAT_MAIN, TurnStep.UPKEEP)
            }
        }

        "CR 500.1: a stepped phase without a current step is rejected" {
            shouldThrow<IllegalArgumentException> { Turn(alice, 1, TurnPhase.BEGINNING, null) }
        }

        "a step must belong to the current phase: upkeep is not a combat step" {
            shouldThrow<IllegalArgumentException> { Turn(alice, 1, TurnPhase.COMBAT, TurnStep.UPKEEP) }
        }

        "CR 500.1: every step constructs inside its own phase" {
            TurnStep.entries.forEach { step ->
                Turn(alice, 1, step.phase, step).step shouldBe step
            }
        }

        "turn numbers start at 1" {
            shouldThrow<IllegalArgumentException> { Turn(alice, 0, TurnPhase.PRECOMBAT_MAIN, null) }
        }
    })
