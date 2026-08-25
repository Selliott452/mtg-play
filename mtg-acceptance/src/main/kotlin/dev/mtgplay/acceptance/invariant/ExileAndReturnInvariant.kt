package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.zone.ZoneId

/**
 * The three invariants of the exile-and-return packet — new state, new properties, same packet
 * (CONVENTIONS.md Definition of Done item 4). In their own file so [InvariantChecker] stays within its
 * function budget, in the shape [checkP62aMarkerScopes] set.
 *
 * - [Invariant.LINKED_EXILE_SCOPE]: the CR 607.2 linked-exile record is battlefield-only, and every id
 *   it names is really in exile.
 * - [Invariant.REBOUND_MARKER_SCOPE]: the CR 702.88a rebound marker is exile-only.
 * - [Invariant.HIDDEN_DECISION_OWNERSHIP]: an open each-opponent discard is owned by a seat that is not
 *   the clause's controller, and that seat can actually discard.
 */
internal fun checkExileAndReturnState(
    state: GameState,
    residences: List<ZoneResidence>,
): List<Violation> =
    buildList {
        addAll(checkLinkedExileScope(state, residences))
        addAll(checkReboundMarkerScope(residences))
        addAll(checkHiddenDecisionOwnership(state))
    }

/**
 * [Invariant.LINKED_EXILE_SCOPE]: two arms.
 *
 * **Scope.** A linked-exile record is a battlefield-only status like `tapped` and `counters`: CR 607.3
 * and CR 400.7 together mean the fresh object born of any zone move has exiled nothing, so a Journey to
 * Nowhere that dies and is later returned to the battlefield is a *new* Journey with an empty record.
 * A record surviving a zone change would make a second Journey return the first one's creature.
 *
 * **Referential integrity.** Every id in a record must still be a real object in exile. This is the arm
 * that would catch the packet's most plausible bug: the ability that returns the card removes it from
 * exile, and if the record were not consumed with it, a second departure would try to return a card that
 * has already come back — or, worse, an id since reused. The engine avoids that by never re-reading the
 * record off the battlefield (it is captured into the fired trigger as CR 603.10 last-known information),
 * but that is an argument about the current code, and this is the check that keeps it true.
 */
private fun checkLinkedExileScope(
    state: GameState,
    residences: List<ZoneResidence>,
): List<Violation> =
    buildList {
        residences
            .filter { it.obj.linkedExiled.isNotEmpty() && it.zone != ZoneId.Battlefield }
            .forEach {
                add(
                    Violation(
                        Invariant.LINKED_EXILE_SCOPE,
                        "CR 607.3: object ${it.obj.id.value} carries a linked-exile record in ${it.zone}, but it " +
                            "is a battlefield-only status — the CR 400.7 rebirth must drop it",
                    ),
                )
            }
        val exiledIds =
            state.sharedZones.exile
                .map { it.id }
                .toSet()
        residences
            .filter { it.zone == ZoneId.Battlefield }
            .forEach { residence ->
                residence.obj.linkedExiled
                    .filterNot { it in exiledIds }
                    .forEach { missing ->
                        add(
                            Violation(
                                Invariant.LINKED_EXILE_SCOPE,
                                "CR 607.2: object ${residence.obj.id.value} links to exiled object " +
                                    "${missing.value}, which is not in exile",
                            ),
                        )
                    }
            }
    }

/**
 * [Invariant.REBOUND_MARKER_SCOPE]: the rebound marker (CR 702.88a) is exile-only, exactly as the plot
 * marker is. A rebounding card lives in exile between the resolution that put it there and the upkeep
 * that offers the free cast; anywhere else it is a card that has moved on, and CR 400.7 says the object
 * that arrived carries none of the old one's status.
 */
private fun checkReboundMarkerScope(residences: List<ZoneResidence>): List<Violation> =
    residences
        .filter { it.obj.reboundTurn != null && it.zone != ZoneId.Exile }
        .map {
            Violation(
                Invariant.REBOUND_MARKER_SCOPE,
                "CR 702.88a: object ${it.obj.id.value} is rebound-marked in ${it.zone}, but the marker is an " +
                    "exile-only status",
            )
        }

/**
 * [Invariant.HIDDEN_DECISION_OWNERSHIP]: the structural half of the packet's ADR-007 ruling, checked on
 * the state rather than on a view.
 *
 * An open each-opponent discard (CR 701.7a) must name a decider who is **not** the clause's controller —
 * otherwise the engine would be asking a player to discard to their own Refurbished Familiar — and that
 * decider must have a non-empty hand, because CR 701.7a says an opponent who cannot discard is skipped
 * and the controller draws instead, never asked and never handed an empty option list (ADR-005: an
 * illegal option has no index).
 *
 * The complementary half — that the *options* never reach the controller's view — is a property of the
 * projection rather than of the state, and is pinned by `ViewLeakPropertySpec` instead.
 */
private fun checkHiddenDecisionOwnership(state: GameState): List<Violation> {
    val pending = state.pendingOpponentDiscard ?: return emptyList()
    return buildList {
        if (pending.decider == pending.controller) {
            add(
                Violation(
                    Invariant.HIDDEN_DECISION_OWNERSHIP,
                    "CR 701.7a: an each-opponent discard is decided by an opponent, but seat " +
                        "${pending.decider.seat} is the clause's own controller",
                ),
            )
        }
        val hand = state.players[pending.decider]?.hand
        if (hand != null && hand.isEmpty()) {
            add(
                Violation(
                    Invariant.HIDDEN_DECISION_OWNERSHIP,
                    "CR 701.7a: seat ${pending.decider.seat} was asked to discard with an empty hand; an " +
                        "opponent who cannot discard is skipped, not asked",
                ),
            )
        }
    }
}
