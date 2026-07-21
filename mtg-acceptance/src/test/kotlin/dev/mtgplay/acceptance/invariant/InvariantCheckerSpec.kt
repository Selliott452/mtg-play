package dev.mtgplay.acceptance.invariant

import dev.mtgplay.acceptance.alice
import dev.mtgplay.acceptance.bob
import dev.mtgplay.acceptance.mountains
import dev.mtgplay.acceptance.playerWithZones
import dev.mtgplay.acceptance.twoPlayerState
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.zone.ZoneId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf

/**
 * The invariant checker suite: each invariant gets a handcrafted violating input that yields
 * exactly that violation, plus clean-state coverage. The two invariants that mtg-core enforces at
 * construction (zone conservation, id sanity) are violated through the checker's extracted-data
 * entry points, since a corrupt `GameState` cannot be built through the public constructor.
 */
class InvariantCheckerSpec :
    StringSpec({
        val precombatMain = Turn(alice, 1, TurnPhase.PRECOMBAT_MAIN, null)

        // --- ZONE_CONSERVATION -------------------------------------------------------------

        "CR 400.7: an object id occupying two zones is exactly one ZONE_CONSERVATION violation" {
            val duplicated = GameObject(ObjectId(1), CardRef("Mountain"), alice)
            val residences =
                listOf(
                    ZoneResidence(ZoneId.Hand(alice), duplicated),
                    ZoneResidence(ZoneId.Battlefield, duplicated),
                )
            val violations = InvariantChecker.checkZoneConservation(residences)
            violations.map { it.invariant } shouldContainExactly listOf(Invariant.ZONE_CONSERVATION)
        }

        "zone conservation: distinct ids across zones produce no violation" {
            val residences =
                listOf(
                    ZoneResidence(ZoneId.Hand(alice), GameObject(ObjectId(1), CardRef("Mountain"), alice)),
                    ZoneResidence(ZoneId.Battlefield, GameObject(ObjectId(2), CardRef("Mountain"), alice)),
                )
            InvariantChecker.checkZoneConservation(residences).shouldBeEmpty()
        }

        // --- ID_SANITY ---------------------------------------------------------------------

        "CR 400.7: an object id at or above the allocation counter is exactly one ID_SANITY violation" {
            val residences =
                listOf(ZoneResidence(ZoneId.Library(alice), GameObject(ObjectId(5), CardRef("Mountain"), alice)))
            val violations = InvariantChecker.checkIdSanity(residences, nextObjectId = 3, decisionCounts = emptyList())
            violations.map { it.invariant } shouldContainExactly listOf(Invariant.ID_SANITY)
        }

        "id sanity: a negative answered-decision count is exactly one ID_SANITY violation" {
            val violations =
                InvariantChecker.checkIdSanity(
                    residences = emptyList(),
                    nextObjectId = 10,
                    decisionCounts = listOf(SeatDecisionCount(seat = 0, count = -1)),
                )
            violations.map { it.invariant } shouldContainExactly listOf(Invariant.ID_SANITY)
        }

        "id sanity: ids below the counter and non-negative counts produce no violation" {
            val residences =
                listOf(ZoneResidence(ZoneId.Library(alice), GameObject(ObjectId(2), CardRef("Mountain"), alice)))
            InvariantChecker
                .checkIdSanity(residences, nextObjectId = 10, decisionCounts = listOf(SeatDecisionCount(0, 4)))
                .shouldBeEmpty()
        }

        // --- PRIORITY ----------------------------------------------------------------------

        "CR 117.1a: two seats holding priority is exactly one PRIORITY violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState =
                        playerWithZones(library = mountains(0L..2L, alice))
                            .copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                    bobState =
                        playerWithZones(library = mountains(10L..12L, bob))
                            .copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).map { it.invariant } shouldContainExactly listOf(Invariant.PRIORITY)
        }

        "priority: one holder and one passer produce no violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState =
                        playerWithZones(library = mountains(0L..2L, alice))
                            .copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                    bobState =
                        playerWithZones(library = mountains(10L..12L, bob))
                            .copy(priorityStatus = PriorityStatus.HAS_PASSED),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).shouldBeEmpty()
        }

        // --- DRAW_FAILURE_HONESTY ----------------------------------------------------------

        "CR 704.5c: a set empty-draw flag over a non-empty library is exactly one DRAW_FAILURE_HONESTY violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState =
                        playerWithZones(library = mountains(0L..2L, alice))
                            .copy(attemptedDrawFromEmptyLibrary = true),
                    bobState = playerWithZones(library = mountains(10L..12L, bob)),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.DRAW_FAILURE_HONESTY)
        }

        "draw-failure honesty: a set flag over an empty library is honest and produces no violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState = playerWithZones(library = mountains(0L..2L, alice)),
                    bobState =
                        playerWithZones()
                            .copy(attemptedDrawFromEmptyLibrary = true),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).shouldBeEmpty()
        }

        // --- CARD_CONSERVATION -------------------------------------------------------------

        "card conservation: a state missing a card against the baseline is exactly one CARD_CONSERVATION violation" {
            val baselineState =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState = playerWithZones(library = mountains(0L..1L, alice)),
                    bobState = playerWithZones(library = mountains(10L..11L, bob)),
                    nextObjectId = 100,
                )
            val baseline = CardCensus.of(baselineState)
            val shrunk =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState = playerWithZones(library = mountains(0L..1L, alice)),
                    bobState = playerWithZones(library = mountains(10L..10L, bob)),
                    nextObjectId = 100,
                )
            InvariantChecker.check(shrunk, baseline).map { it.invariant } shouldContainExactly
                listOf(Invariant.CARD_CONSERVATION)
        }

        "card conservation: an unchanged multiset against the baseline produces no violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState = playerWithZones(library = mountains(0L..1L, alice)),
                    bobState = playerWithZones(library = mountains(10L..11L, bob)),
                    nextObjectId = 100,
                )
            val baseline = CardCensus.of(state)
            InvariantChecker.check(state, baseline).shouldBeEmpty()
        }

        // --- MANA_POOL_EMPTY_AT_PAUSE --------------------------------------------------------

        "CR 500.4: a nonempty mana pool at an observed pause is exactly one MANA_POOL_EMPTY_AT_PAUSE violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState =
                        playerWithZones(library = mountains(0L..2L, alice))
                            .copy(manaPool = persistentListOf(ManaType.RED)),
                    bobState = playerWithZones(library = mountains(10L..12L, bob)),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.MANA_POOL_EMPTY_AT_PAUSE)
        }

        // --- TAP_STATUS_SCOPE ----------------------------------------------------------------

        "CR 110.5: a tapped object outside the battlefield is exactly one TAP_STATUS_SCOPE violation" {
            val residences =
                listOf(
                    ZoneResidence(
                        ZoneId.Hand(alice),
                        GameObject(ObjectId(1), CardRef("Mountain"), alice, tapped = true),
                    ),
                )
            InvariantChecker.checkTapStatusScope(residences).map { it.invariant } shouldContainExactly
                listOf(Invariant.TAP_STATUS_SCOPE)
        }

        "tap-status scope: a tapped battlefield object produces no violation" {
            val residences =
                listOf(
                    ZoneResidence(
                        ZoneId.Battlefield,
                        GameObject(ObjectId(1), CardRef("Mountain"), alice, tapped = true),
                    ),
                )
            InvariantChecker.checkTapStatusScope(residences).shouldBeEmpty()
        }

        // --- LAND_DROP_BOUND -----------------------------------------------------------------

        "CR 305.2: a land-drop count above one is exactly one LAND_DROP_BOUND violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain.copy(landsPlayedThisTurn = 2),
                    aliceState = playerWithZones(library = mountains(0L..2L, alice)),
                    bobState = playerWithZones(library = mountains(10L..12L, bob)),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.LAND_DROP_BOUND)
        }

        "land-drop bound: a count of one — the normal used drop — produces no violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain.copy(landsPlayedThisTurn = 1),
                    aliceState = playerWithZones(library = mountains(0L..2L, alice)),
                    bobState = playerWithZones(library = mountains(10L..12L, bob)),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).shouldBeEmpty()
        }

        // --- clean multi-invariant coverage -----------------------------------------------

        "a well-formed state with a baseline reports no violations at all" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState =
                        playerWithZones(
                            library = mountains(0L..5L, alice),
                            hand = mountains(6L..12L, alice),
                        ),
                    bobState =
                        playerWithZones(
                            library = mountains(20L..25L, bob),
                            hand = mountains(26L..32L, bob),
                        ),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state, CardCensus.of(state)).shouldBeEmpty()
            // A lone-state check (no baseline) also finds nothing, and skips only card conservation.
            InvariantChecker.check(state) shouldBe emptyList()
        }
    })
