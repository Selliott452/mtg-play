package dev.mtgplay.core.state

import dev.mtgplay.core.definition.RevealedCardOutcome
import dev.mtgplay.core.definition.RevealedCardRestriction
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * An open "target opponent reveals their hand and you choose a card from it" clause, awaiting the
 * resolving object's controller's choice (CR 701.16a). Additive, flagged core (`FW-HIDDENCHOICE`,
 * docs/design/exile-and-return.md §7). Duress's and Mesmeric Fiend's mid-resolution pause.
 *
 * **The reveal is already done when this record exists.** CR 701.16a's reveal is not a decision — a
 * player told to reveal their hand reveals all of it — so the engine performs the reveal and emits
 * [dev.mtgplay.core.event.GameEvent.CardsRevealed] as it opens the pause, and this record's existence is
 * what makes [revealer]'s hand **public to both seats** for the duration (ADR-007). The cards are read
 * live off [revealer]'s hand rather than snapshotted here, and that is safe rather than lazy: no player
 * receives priority between the reveal and the choice, so the hand cannot change underneath the request,
 * and re-deriving it satisfies ADR-004's "the request is a pure function of the state" without a second
 * copy that could disagree with the first.
 *
 * @property decider the resolving spell's or ability's **controller** — the player who chooses, per the
 *   printed "*you* choose a … card from it". Not [revealer]; see
 *   [dev.mtgplay.core.definition.HandRevealChoice] for why this is not a non-controller decision.
 * @property revealer the targeted opponent whose hand is revealed (CR 701.16a) and, for
 *   [RevealedCardOutcome.DISCARD], who discards the chosen card.
 * @property restriction which revealed cards are legal choices.
 * @property outcome what happens to the chosen card.
 * @property sourceId the resolving object's own id (CR 113.7c last-known information) — the battlefield
 *   permanent whose [GameObject.linkedExiled] record an [RevealedCardOutcome.EXILE_LINKED] choice writes
 *   to (CR 607.2), and the object a decision request names so the deciding seat can identify it.
 * @property sourceCard the resolving object's printed identity, for display.
 */
data class PendingHandReveal(
    val decider: PlayerId,
    val revealer: PlayerId,
    val restriction: RevealedCardRestriction,
    val outcome: RevealedCardOutcome,
    val sourceId: ObjectId,
    val sourceCard: CardRef,
) {
    init {
        require(decider != revealer) {
            "CR 115.1a: a hand-reveal clause targets an opponent, so the chooser and the revealer differ; " +
                "both were $decider"
        }
    }
}
