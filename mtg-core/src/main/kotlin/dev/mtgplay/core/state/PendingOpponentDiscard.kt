package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList

/**
 * An open "each opponent discards a card" clause, awaiting one opponent's choice of which card of their
 * own to discard (CR 701.7a). Additive, flagged core (`FW-NONCTRLDEC`,
 * docs/design/exile-and-return.md §6). Refurbished Familiar's mid-resolution pause.
 *
 * **The one pending record whose decider is not the resolving object's controller *and* whose option
 * list is hidden from that controller.** `FW-COUNTER`'s [PendingCounterPayment] was the first record of
 * the first kind, but its options are payment plans drawn from the battlefield, which is public
 * (CR 400.2); a discard's options are the decider's own hand, which is not (CR 402.1). That is the
 * ADR-007 question this packet answers, and it is answered by projection rather than by a new secrecy
 * mechanism: `mtg-rules` surfaces the enumerated hand only in the request handed to [decider], and every
 * other seat sees a count-only view — the same asymmetry [PendingLibraryLook] already gets for a private
 * look (CR 701.14a).
 *
 * **Why the record carries a queue.** "Each opponent discards a card" is one clause producing one
 * decision *per opponent*, and [dev.mtgplay.rules.AdvanceResult] surfaces exactly one request at a time.
 * So the clause walks [remaining] in APNAP order (CR 101.4), asking each opponent in turn; an opponent
 * with an empty hand is never asked at all and increments [drawsOwed] instead, which is CR 701.7a's
 * "for each opponent who can't". The current pool is two-player, so [remaining] is empty in every real
 * game — it is modelled anyway so the printed "each opponent" is not quietly a "target opponent".
 *
 * @property decider the opponent now choosing, from **their own hand**; the deciding seat.
 * @property controller the resolving object's controller — who draws for each opponent who cannot
 *   discard, and who must **not** see [decider]'s options.
 * @property count how many cards [decider] discards (CR 701.7a).
 * @property remaining the opponents not yet asked, in APNAP order; empty when [decider] is the last.
 * @property drawsOwed how many cards [controller] draws once every opponent has been dealt with —
 *   accumulated as opponents who cannot discard are skipped.
 * @property sourceCard the resolving object's printed identity, for display.
 */
data class PendingOpponentDiscard(
    val decider: PlayerId,
    val controller: PlayerId,
    val count: Int,
    val remaining: PersistentList<PlayerId>,
    val drawsOwed: Int,
    val sourceCard: CardRef,
) {
    init {
        require(decider != controller) {
            "CR 701.7a: an each-opponent discard is decided by an opponent, not by the controller $controller"
        }
        require(count >= 1) { "CR 701.7a: a pending opponent discard discards at least one card, was $count" }
        require(drawsOwed >= 0) { "CR 701.7a: the accumulated draw is non-negative, was $drawsOwed" }
        require(decider !in remaining) {
            "CR 701.7a: the opponent now deciding ($decider) is not also still queued in $remaining"
        }
        require(controller !in remaining) {
            "CR 701.7a: the controller ($controller) is not one of their own opponents"
        }
        require(remaining.distinct().size == remaining.size) {
            "CR 701.7a: each opponent is asked once, got $remaining"
        }
    }
}
