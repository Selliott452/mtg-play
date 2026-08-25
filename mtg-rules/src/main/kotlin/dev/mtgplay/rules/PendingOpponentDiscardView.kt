package dev.mtgplay.rules

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId

/**
 * The per-seat view of an open "each opponent discards a card" pause (CR 701.7a) — Refurbished
 * Familiar's. Additive (`FW-NONCTRLDEC`, docs/design/exile-and-return.md §6).
 *
 * **Count-only, for every seat including the deciding one.** This is the ADR-007 ruling of the packet,
 * stated as a type: the deciding opponent is choosing from **their own hand**, which is hidden from the
 * clause's controller (CR 402.1), so no projection of this pause may name a card. The options exist in
 * exactly one place — the [dev.mtgplay.rules.decision.DecisionRequest.ChooseOpponentDiscards] handed to
 * the deciding seat — and the seat view is not that place.
 *
 * Modelled on [PendingLibraryLookView], whose CR 701.14a private look faces the same problem from the
 * other side (there the *decider* is the one with the secret; here the decider is the one seat entitled
 * to see it). The deciding seat is not given a richer projection than the controller, deliberately: it
 * already receives its own hand in [PlayerView.hand] and its own request, so a second, seat-dependent
 * copy of the same cards here would be a second thing to keep in agreement with the first, and the
 * asymmetry the invariant checks would become "sometimes populated" instead of "never populated".
 *
 * What *is* public is real information and is carried: that the clause is resolving, whose decision it
 * is, how many cards they must discard, and how many are still queued behind them. An opponent watching
 * Refurbished Familiar resolve knows all of that.
 *
 * @property decider the opponent now choosing, from their own hand.
 * @property controller the resolving object's controller, who draws for each opponent who cannot discard.
 * @property count how many cards [decider] must discard.
 * @property remainingCount how many further opponents are queued behind [decider]; zero in a two-player
 *   game.
 * @property sourceCard the resolving object's printed identity.
 */
data class PendingOpponentDiscardView(
    val decider: PlayerId,
    val controller: PlayerId,
    val count: Int,
    val remainingCount: Int,
    val sourceCard: CardRef,
)
