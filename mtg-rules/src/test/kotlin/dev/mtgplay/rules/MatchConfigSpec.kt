package dev.mtgplay.rules

import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.PlayerId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Construction validation of [MatchConfig] and [MatchResult]. */
class MatchConfigSpec :
    StringSpec({
        "P1.2 supports exactly two seats: one seat is rejected" {
            shouldThrow<IllegalArgumentException> {
                MatchConfig(seed = 0, libraries = mapOf(alice to mountainDeck()))
            }
        }

        "P1.2 supports exactly two seats: three seats are rejected" {
            shouldThrow<IllegalArgumentException> {
                MatchConfig(
                    seed = 0,
                    libraries = mapOf(alice to mountainDeck(), bob to mountainDeck(), PlayerId(2) to mountainDeck()),
                )
            }
        }

        "the starting player must be seated" {
            shouldThrow<IllegalArgumentException> {
                MatchConfig(
                    seed = 0,
                    libraries = mapOf(alice to mountainDeck(), bob to mountainDeck()),
                    startingPlayer = PlayerId(9),
                )
            }
        }

        "the starting hand size must be non-negative" {
            shouldThrow<IllegalArgumentException> {
                MatchConfig(
                    seed = 0,
                    libraries = mapOf(alice to mountainDeck(), bob to mountainDeck()),
                    startingHandSize = -1,
                )
            }
        }

        "a zero starting hand size is allowed" {
            MatchConfig(
                seed = 0,
                libraries = mapOf(alice to mountainDeck(), bob to mountainDeck()),
                startingHandSize = 0,
            ).startingHandSize shouldBe 0
        }

        "CR 104.2a: a match result cannot name the same player as winner and loser" {
            shouldThrow<IllegalArgumentException> {
                MatchResult(winner = alice, loser = alice, reason = LossReason.LIFE_TOTAL_ZERO_OR_LESS)
            }
        }
    })
