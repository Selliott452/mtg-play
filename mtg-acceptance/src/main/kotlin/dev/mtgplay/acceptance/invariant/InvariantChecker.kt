package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.zone.ZoneId

/**
 * The correctness rig's first line of defence (PLAN.md §2.3): a pure function that inspects a
 * [GameState] and returns every [Invariant] it violates. The scripted-game driver runs it after
 * every transition and fails loudly on any non-empty result, so engine wrongness is caught the
 * instant it appears rather than surviving as silently-wrong-but-plausible state.
 *
 * **Structure for growth.** Each invariant is checked by its own small function of the minimal
 * data it needs — a residence list, or the state itself — so every check is independently
 * testable, including against corruption a real [GameState] cannot express (see below). Later
 * phases add invariants (mana-pool emptiness in Phase 2, battlefield statuses in Phase 3) by
 * adding a member to [Invariant] and a check here; existing checks are untouched.
 *
 * **Relationship to core construction invariants.** [Invariant.ZONE_CONSERVATION] and
 * [Invariant.ID_SANITY] overlap with guarantees `GameState`/`PlayerState` already enforce at
 * construction (id uniqueness, ids below the counter, non-negative counts) — a `GameState`
 * violating them cannot be built through the public constructor today. The checker re-derives
 * them independently anyway: it is the durable, phase-spanning guard for when the state model
 * grows (the stack gains spell objects in P2.1, the battlefield gains statuses in P3) and the
 * constructor's coverage no longer spans everything. Their check functions take the extracted
 * intermediate data precisely so their detection logic stays testable without a corrupt state.
 */
object InvariantChecker {
    /**
     * Checks the single-state invariants of [state]: zone conservation, id sanity, priority
     * uniqueness, and draw-failure honesty. Card conservation is *not* checked here — it is a
     * cross-state property with no meaning for a lone state; use the [expectedCards] overload.
     *
     * Returns every violation found (possibly none), in a deterministic order.
     */
    fun check(state: GameState): List<Violation> = check(state, expectedCards = null)

    /**
     * Checks every invariant of [state], including [Invariant.CARD_CONSERVATION] when
     * [expectedCards] is supplied: the baseline census the state's card multiset must still equal.
     * Pass the census of the game's first state to detect any card created or destroyed since.
     * A `null` [expectedCards] skips only the card-conservation check.
     *
     * Returns every violation found (possibly none), in a deterministic order.
     */
    fun check(
        state: GameState,
        expectedCards: CardCensus?,
    ): List<Violation> {
        val residences = ZoneResidence.of(state)
        return buildList {
            addAll(checkZoneConservation(residences))
            addAll(checkIdSanity(residences, state.nextObjectId, decisionCountsOf(state)))
            addAll(checkPriorityUniqueness(state))
            addAll(checkDrawFailureHonesty(state))
            addAll(checkManaPoolEmptiness(state))
            addAll(checkTapStatusScope(residences))
            if (expectedCards != null) addAll(checkCardConservation(state, expectedCards))
        }
    }

    /**
     * [Invariant.MANA_POOL_EMPTY_AT_PAUSE]: every seat's mana pool is empty (CR 500.4; see the
     * invariant's KDoc for the exact P2.x rule and its Phase 5 revision).
     */
    internal fun checkManaPoolEmptiness(state: GameState): List<Violation> =
        state.players.entries
            .sortedBy { it.key.seat }
            .filter { it.value.manaPool.isNotEmpty() }
            .map { (seat, player) ->
                Violation(
                    Invariant.MANA_POOL_EMPTY_AT_PAUSE,
                    "CR 500.4: seat ${seat.seat}'s mana pool holds ${player.manaPool} at an observed pause",
                )
            }

    /**
     * [Invariant.TAP_STATUS_SCOPE]: only battlefield objects may be tapped (CR 110.5). Operates
     * on the residence list so corrupt placements are directly testable.
     */
    internal fun checkTapStatusScope(residences: List<ZoneResidence>): List<Violation> =
        residences
            .filter { it.zone != ZoneId.Battlefield && it.obj.tapped }
            .map { residence ->
                Violation(
                    Invariant.TAP_STATUS_SCOPE,
                    "CR 110.5: object ${residence.obj.id.value} is tapped in ${residence.zone}, " +
                        "but tapped is a battlefield-only status",
                )
            }

    /**
     * [Invariant.ZONE_CONSERVATION]: no object id occupies more than one zone. Operates on a
     * residence list so it can be tested with duplicate residences a real [GameState] would reject
     * at construction.
     */
    internal fun checkZoneConservation(residences: List<ZoneResidence>): List<Violation> =
        residences
            .groupBy { it.obj.id }
            .filter { (_, occurrences) -> occurrences.size > 1 }
            .map { (id, occurrences) ->
                Violation(
                    Invariant.ZONE_CONSERVATION,
                    "CR 400.7: object ${id.value} occupies ${occurrences.size} zones at once: " +
                        occurrences.map { it.zone },
                )
            }

    /**
     * [Invariant.ID_SANITY]: every object id is strictly below [nextObjectId] (CR 400.7) and every
     * answered-decision count in [decisionCounts] is non-negative. Takes the extracted values so
     * both bounds can be tested with inputs a real [GameState] cannot hold.
     */
    internal fun checkIdSanity(
        residences: List<ZoneResidence>,
        nextObjectId: Long,
        decisionCounts: List<SeatDecisionCount>,
    ): List<Violation> =
        buildList {
            residences
                .filter { it.obj.id.value >= nextObjectId }
                .forEach { residence ->
                    add(
                        Violation(
                            Invariant.ID_SANITY,
                            "CR 400.7: object ${residence.obj.id.value} is not below " +
                                "the allocation counter $nextObjectId",
                        ),
                    )
                }
            decisionCounts
                .filter { it.count < 0 }
                .forEach { seatCount ->
                    add(
                        Violation(
                            Invariant.ID_SANITY,
                            "seat ${seatCount.seat} has a negative answered-decision count ${seatCount.count}",
                        ),
                    )
                }
        }

    /** [Invariant.PRIORITY]: at most one seat is [PriorityStatus.HOLDS_PRIORITY] (CR 117.1a). */
    internal fun checkPriorityUniqueness(state: GameState): List<Violation> {
        val holders =
            state.players.entries
                .filter { it.value.priorityStatus == PriorityStatus.HOLDS_PRIORITY }
                .map { it.key }
                .sortedBy { it.seat }
        return if (holders.size <= 1) {
            emptyList()
        } else {
            listOf(
                Violation(
                    Invariant.PRIORITY,
                    "CR 117.1a: ${holders.size} seats hold priority simultaneously: $holders",
                ),
            )
        }
    }

    /**
     * [Invariant.DRAW_FAILURE_HONESTY]: a set empty-library-draw flag implies an empty library
     * (CR 704.5c).
     */
    internal fun checkDrawFailureHonesty(state: GameState): List<Violation> =
        state.players.entries
            .sortedBy { it.key.seat }
            .filter { it.value.attemptedDrawFromEmptyLibrary && it.value.library.isNotEmpty() }
            .map { (seat, player) ->
                Violation(
                    Invariant.DRAW_FAILURE_HONESTY,
                    "CR 704.5c: seat ${seat.seat} recorded an empty-library draw but its " +
                        "library holds ${player.library.size} card(s)",
                )
            }

    /**
     * [Invariant.CARD_CONSERVATION]: [state]'s card multiset still equals the [expected] baseline.
     */
    internal fun checkCardConservation(
        state: GameState,
        expected: CardCensus,
    ): List<Violation> {
        val actual = CardCensus.of(state)
        return if (actual == expected) {
            emptyList()
        } else {
            listOf(
                Violation(
                    Invariant.CARD_CONSERVATION,
                    "card multiset changed from ${expected.counts} to ${actual.counts}",
                ),
            )
        }
    }

    private fun decisionCountsOf(state: GameState): List<SeatDecisionCount> =
        state.players.entries
            .sortedBy { it.key.seat }
            .map { (seat, player) -> SeatDecisionCount(seat.seat, player.decisionsAnswered) }
}
