package dev.mtgplay.server

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.protocol.ClientMessage
import dev.mtgplay.protocol.PROTOCOL_VERSION
import dev.mtgplay.protocol.ServerMessage
import dev.mtgplay.protocol.decodeClientMessage
import dev.mtgplay.protocol.encode
import dev.mtgplay.protocol.gameOverMessage
import dev.mtgplay.protocol.seatUpdateMessage
import dev.mtgplay.protocol.toDomain
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.GameEngine
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.SeatView
import dev.mtgplay.rules.viewFor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One hosted match (ADR-008): the engine plus the single mutable cell of the whole server — the
 * current [AdvanceResult] — and the live per-seat connections.
 *
 * **Concurrency model.** The engine is pure and immutable (ADR-004): `start`/`advance` never mutate,
 * so the only shared mutable state is [current] (the state/pause reference) and [links] (who is
 * connected). Both are guarded by one per-match [Mutex]; there is **no global lock**, so distinct
 * matches never contend. Every state transition and every outbound send happens under that mutex, so
 * a decision, a (re)connection, and a disconnection for the same match are serialized into a single
 * consistent order — the reference server trades a little send concurrency for an obviously-correct,
 * read-in-one-sitting model. A production host would decouple delivery behind per-seat queues; that
 * is an operational concern (ADR-008 amendment).
 *
 * **Information hiding.** Every frame a seat receives is [viewFor]`(state, seat)` — the pure per-seat
 * projection (ADR-007). The server never sends raw state and never redacts by hand; the deciding
 * seat's view carries its full request, every other seat's carries only who-decides-and-what-kind.
 *
 * @property id this match's registry handle.
 * @property config the match configuration this game was started from (ADR-006); [config]'s seed is
 *   exposed via the [MatchHandle] for reproducibility.
 */
class Match internal constructor(
    val id: MatchId,
    private val engine: GameEngine,
    val config: MatchConfig,
    private val tokens: Map<PlayerId, SeatToken>,
    first: AdvanceResult,
) {
    private val mutex = Mutex()
    private var current: AdvanceResult = first
    private val links: MutableMap<PlayerId, SeatLink> = mutableMapOf()

    /** The seat [token] authenticates, or `null` if it matches no seat of this match. */
    fun seatFor(token: SeatToken): PlayerId? = tokens.entries.firstOrNull { it.value == token }?.key

    /** A snapshot of the current engine result — the paused request or the final outcome (test/inspection). */
    suspend fun currentResult(): AdvanceResult = mutex.withLock { current }

    /**
     * Attaches [link] as the live connection for its seat and immediately delivers that seat's
     * current view (the **resync** path, CR/ADR-007): whether this is a first connect or a
     * reconnection after a drop, the seat receives exactly where the game stands, re-derived purely
     * from state via [viewFor] — the no-hidden-position payoff of ADR-007 (a reconnecting seat needs
     * no server-side session memory; the state alone reconstitutes its whole legal view).
     *
     * **Duplicate connections: latest wins.** If a live link already exists for the seat, it is
     * superseded (closed) and replaced, so a reconnect always succeeds even when a half-dead prior
     * socket has not yet been detected. The supersede is performed outside the lock.
     */
    suspend fun attach(link: SeatLink) {
        val superseded =
            mutex.withLock {
                val previous = links[link.seat]
                links[link.seat] = link
                link.send(messageFor(link.seat).encode())
                previous
            }
        superseded?.supersede()
    }

    /** Detaches [link] if it is still the seat's live connection (a no-op if already superseded). */
    suspend fun detach(link: SeatLink) {
        mutex.withLock {
            if (links[link.seat] === link) links.remove(link.seat)
        }
    }

    /**
     * Handles one raw text frame [rawJson] received from [seat]. Never throws and never corrupts the
     * match: a malformed frame, a version skew, a wrong-seat decision, a stale request id, or a
     * decision the engine rejects each produces a structured [ServerError] plus a re-send of the
     * seat's current view, leaving [current] untouched. A valid decision advances the engine and
     * broadcasts the new views to both seats.
     */
    suspend fun submit(
        seat: PlayerId,
        rawJson: String,
    ) {
        val message: ClientMessage
        try {
            message = decodeClientMessage(rawJson)
        } catch (failure: IllegalArgumentException) {
            // kotlinx SerializationException is an IllegalArgumentException; a broken/unknown-field
            // frame lands here rather than crashing the connection.
            reject(seat, ServerErrorCode.MALFORMED_MESSAGE, failure.message ?: "unparseable client frame")
            return
        }
        if (message.protocolVersion != PROTOCOL_VERSION) {
            reject(
                seat,
                ServerErrorCode.UNSUPPORTED_VERSION,
                "server speaks $PROTOCOL_VERSION, message declared ${message.protocolVersion}",
            )
            return
        }
        when (message) {
            is ClientMessage.DecisionMessage -> applyDecision(seat, message)
        }
    }

    private suspend fun applyDecision(
        seat: PlayerId,
        message: ClientMessage.DecisionMessage,
    ) {
        mutex.withLock {
            val paused = current
            if (paused !is AdvanceResult.NeedsDecision) {
                reject(seat, ServerErrorCode.NO_PENDING_DECISION, "the match is not paused on a decision")
                return@withLock
            }
            val pending = paused.request
            if (pending.seat != seat) {
                reject(
                    seat,
                    ServerErrorCode.WRONG_SEAT,
                    "seat ${seat.seat} answered a request for seat ${pending.seat.seat}",
                )
                return@withLock
            }
            val decision = message.decision.toDomain()
            if (decision.requestId != pending.id) {
                reject(seat, ServerErrorCode.STALE_REQUEST, "decision named a request other than the pending one")
                return@withLock
            }
            val advanced =
                try {
                    engine.advance(paused.state, decision)
                } catch (rejected: IllegalArgumentException) {
                    // The engine is the loud backstop (ADR-004): translate its rejection rather than
                    // letting it escape. State is unchanged because advance is pure.
                    reject(seat, ServerErrorCode.INVALID_DECISION, rejected.message ?: "engine rejected the decision")
                    return@withLock
                }
            current = advanced
            broadcast()
        }
    }

    /** Builds one seat's outbound message from [current] (ADR-007 per-seat filtering). Call under [mutex]. */
    private fun messageFor(seat: PlayerId): ServerMessage =
        when (val result = current) {
            is AdvanceResult.NeedsDecision -> seatUpdateMessage(viewFor(result.state, seat))
            is AdvanceResult.GameOver -> gameOverMessage(result.result, terminalViewFor(result.state, seat))
        }

    /**
     * A seat's terminal view for a `GameOver` message (ADR-007/ADR-008).
     *
     * **FLAGGED — an mtg-rules gap, out of P7.2 scope (STOP protocol).** A final [state] can still
     * carry moot decision-gathering fields the engine left dangling as the game ended — most commonly
     * a single fired-but-unplaced trigger when a player lost to a state-based action. [viewFor] derives
     * `pendingDecision` via `pendingRequestOf`, whose own contract is "null if the state is not a pause
     * point"; but `pendingDecisionRequest` does not short-circuit a finished game, so it tries to build
     * an `OrderTriggers` request from the lone trigger and throws ("CR 603.3b … 1 is an engine defect").
     * The proper fix is in `mtg-rules` (short-circuit a terminal state before deriving a request) and is
     * not this packet's to make.
     *
     * The server therefore suppresses every moot pending field before filtering: the pure per-seat
     * hidden-information filtering (ADR-007) still runs on the real zones, and the terminal view reports
     * no pending decision — exactly right for a finished game. This changes only fields that are moot
     * once the game is over; it never widens what a seat may see.
     */
    private fun terminalViewFor(
        state: GameState,
        seat: PlayerId,
    ): SeatView = viewFor(state.withoutPendingDecisions(), seat).copy(pendingDecision = null)

    /** Delivers the current view to every connected seat. Call under [mutex]. */
    private suspend fun broadcast() {
        for (seat in links.keys.toList()) {
            val link = links[seat] ?: continue
            link.send(messageFor(seat).encode())
        }
    }

    /** Sends [seat] a structured error, then re-sends its current view so it can retry. Call under [mutex]. */
    private suspend fun reject(
        seat: PlayerId,
        code: ServerErrorCode,
        detail: String,
    ) {
        val link = links[seat] ?: return
        link.send(ServerError(code, detail).encode())
        link.send(messageFor(seat).encode())
    }
}

/** A copy of this state with every moot pending-decision field cleared (see [Match]'s GameOver view). */
private fun GameState.withoutPendingDecisions(): GameState =
    copy(
        pendingCast = null,
        pendingTriggers = pendingTriggers.cleared(),
        pendingMadness = null,
        pendingReplacement = null,
        pendingMulligan = null,
        pendingPlot = null,
        pendingColorChoice = null,
        pendingActivation = null,
        pendingRevealSelection = null,
        pendingOptionalDiscardDraw = null,
        pendingOptionalCostDraw = null,
        pendingResolutionDiscard = null,
        pendingLibrarySearch = null,
    )
