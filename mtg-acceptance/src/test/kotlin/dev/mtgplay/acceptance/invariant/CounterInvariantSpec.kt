package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.zone.ZoneId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.toPersistentMap

/**
 * The [Invariant.COUNTER_SCOPE] suite (CR 122). Each of the three arms gets a handcrafted violating
 * residence that yields exactly that violation, plus clean-state coverage.
 *
 * The checks operate on the residence list rather than a `GameState` for the reason the checker's
 * header gives. One arm is nonetheless unreachable even from there — a non-positive multiplicity is
 * refused by `GameObject`'s own `init`, so no object carrying one can be built at all; that arm's
 * test pins the construction guarantee instead, which is the same treatment MARKED_DAMAGE_SCOPE's
 * non-negativity arm gets.
 */
class CounterInvariantSpec :
    StringSpec({

        fun residence(
            zone: ZoneId,
            counters: Map<Counter, Int>,
        ): ZoneResidence =
            ZoneResidence(
                zone = zone,
                obj =
                    GameObject(
                        id = ObjectId(1),
                        card = CardRef("Grizzly Bears"),
                        owner = PlayerId(0),
                        counters = counters.toPersistentMap(),
                    ),
            )

        "CR 122.1: a battlefield permanent's counters are clean when every count is positive" {
            checkCounterScope(
                listOf(
                    residence(
                        ZoneId.Battlefield,
                        mapOf(
                            Counter.PLUS_ONE_PLUS_ONE to 2,
                            Counter.KeywordCounter(Keyword.LIFELINK) to 1,
                        ),
                    ),
                ),
            ).shouldBeEmpty()
        }

        "CR 122.1: a non-positive multiplicity is refused at construction, so the arm is defence in depth" {
            // The strictly-positive arm of the invariant re-derives a `GameObject` construction
            // guarantee and is therefore **unreachable** through the public constructor — exactly like
            // the non-negativity arm of MARKED_DAMAGE_SCOPE, which InvariantCheckerSpec likewise does
            // not exercise. What is testable is the guarantee itself, so that is what is pinned; the
            // checker arm stays because the checker's charter is to re-derive core guarantees that a
            // later packet could quietly weaken.
            val error =
                shouldThrow<IllegalArgumentException> {
                    GameObject(
                        id = ObjectId(1),
                        card = CardRef("Grizzly Bears"),
                        owner = PlayerId(0),
                        counters = mapOf<Counter, Int>(Counter.PLUS_ONE_PLUS_ONE to 0).toPersistentMap(),
                    )
                }
            error.message.orEmpty() shouldContain "122.1"
        }

        "CR 122.2: counters on an object off the battlefield are a violation" {
            // Counters cease to exist when an object changes zones; a graveyard card carrying one
            // means a zone move copied state the CR 400.7 rebirth should have dropped.
            val violations =
                checkCounterScope(
                    listOf(
                        residence(
                            ZoneId.Graveyard(PlayerId(0)),
                            mapOf(
                                Counter.PLUS_ONE_PLUS_ONE to 1,
                            ),
                        ),
                    ),
                )
            violations.map { it.invariant } shouldBe listOf(Invariant.COUNTER_SCOPE)
            violations.single().detail shouldContain "122.2"
        }

        "CR 704.5q: unannihilated +1/+1 and -1/-1 counters at a pause are a violation" {
            val violations =
                checkCounterScope(
                    listOf(
                        residence(
                            ZoneId.Battlefield,
                            mapOf(Counter.PLUS_ONE_PLUS_ONE to 2, Counter.MINUS_ONE_MINUS_ONE to 3),
                        ),
                    ),
                )
            violations.map { it.invariant } shouldBe listOf(Invariant.COUNTER_SCOPE)
            violations.single().detail shouldContain "704.5q"
        }

        "CR 704.5q: a +1/+1 beside a -0/-1 counter is clean — the rule names only the -1/-1 pair" {
            checkCounterScope(
                listOf(
                    residence(
                        ZoneId.Battlefield,
                        mapOf(Counter.PLUS_ONE_PLUS_ONE to 1, Counter.MINUS_ZERO_MINUS_ONE to 1),
                    ),
                ),
            ).shouldBeEmpty()
        }

        "CR 122.2: an object off the battlefield with no counters is clean" {
            checkCounterScope(listOf(residence(ZoneId.Graveyard(PlayerId(0)), emptyMap()))).shouldBeEmpty()
        }
    })
