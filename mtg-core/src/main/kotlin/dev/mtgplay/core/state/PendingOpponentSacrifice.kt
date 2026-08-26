package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList

/**
 * An open "each opponent sacrifices a permanent of their choice" clause, awaiting one opponent's choice
 * of which of their own permanents to sacrifice (CR 701.17a). Additive, flagged core (`FW-NONCTRLDEC`,
 * `W9-B`). Extract a Confession's mid-resolution pause.
 *
 * The sibling of [PendingOpponentDiscard], carrying the same queue for the same reason: "each opponent"
 * is one clause producing one decision *per opponent*, and [dev.mtgplay.rules.AdvanceResult] surfaces
 * exactly one request at a time, so the clause walks [remaining] in APNAP order (CR 101.4). The pool is
 * two-player, so [remaining] is empty in every real game; it is modelled anyway so the printed "each
 * opponent" is not quietly a "target opponent".
 *
 * **Unlike its sibling, nothing here is secret.** A discard's options are the decider's own hand
 * (CR 402.1), which is why that record needed a count-only projection for every other seat. These options
 * are battlefield permanents, which are public (CR 400.2) — the controller can already see every one of
 * them and could work out the option list unaided. So this record needs no seat-view projection of its
 * own: what a non-deciding seat learns is that *a* decision is pending and of what kind, which
 * `DecisionView.Elsewhere` already tells it, and the options themselves are on the battlefield it can
 * see. Adding a projection would publish nothing new and would imply an asymmetry that does not exist.
 *
 * **[greatestPowerOnly] is settled here rather than re-derived**, and that is what makes the pause
 * ADR-004-pure across the queue: the narrowing depends on whether the *resolving spell's* optional
 * additional cost was paid, which is a fact about the cast record. Reading it once as the clause begins
 * and carrying it means every opponent in the queue is asked the same question the first one was, and
 * that the question cannot change if the stack shifts underneath.
 *
 * @property decider the opponent now choosing, from **their own** battlefield permanents.
 * @property controller the resolving object's controller — not the decider (CR 701.17a's "of their
 *   choice").
 * @property greatestPowerOnly whether the choice is narrowed to a permanent with the greatest power among
 *   the decider's matching permanents (CR 613 effective power), settled from the resolving object's
 *   linked cost when the clause began.
 * @property remaining the opponents not yet asked, in APNAP order; empty when [decider] is the last.
 * @property sourceCard the resolving object's printed identity, for display.
 */
data class PendingOpponentSacrifice(
    val decider: PlayerId,
    val controller: PlayerId,
    val greatestPowerOnly: Boolean,
    val remaining: PersistentList<PlayerId>,
    val sourceCard: CardRef,
) {
    init {
        require(decider != controller) {
            "CR 701.17a: an each-opponent sacrifice is decided by an opponent, not by the controller $controller"
        }
        require(decider !in remaining) {
            "CR 701.17a: the opponent now deciding ($decider) is not also still queued in $remaining"
        }
        require(controller !in remaining) {
            "CR 701.17a: the controller ($controller) is not one of their own opponents"
        }
        require(remaining.distinct().size == remaining.size) {
            "CR 701.17a: each opponent is asked once, got $remaining"
        }
    }
}
