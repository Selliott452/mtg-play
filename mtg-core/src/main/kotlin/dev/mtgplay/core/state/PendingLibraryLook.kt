package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList

/**
 * A "look at these cards privately, then arrange them" the engine is gathering mid-resolution
 * (CR 701.14, CR 701.17) — Preordain, Ponder, Impulse, Brainstorm. Additive, flagged core
 * (`FW-LIBLOOK`, docs/design/library-look.md §7). The resolving spell is still the top object of the
 * stack and the [poolIds] are still in their source zone (the top of the [decider]'s library, or their
 * hand); the engine has paused for the arrangement choice and, on the answer, moves the cards, runs any
 * optional shuffle, and draws before the spell leaves the stack. Non-null only at those pauses.
 *
 * **The pool ids never reach a per-seat view.** A look is private to its controller (CR 701.14a), and a
 * *library* object id is a correlatable handle on exactly the hidden state a look manipulates — an
 * opponent who learned it could match it against a later draw or look and reconstruct library order the
 * CR never gave them. So `mtg-rules` projects this record onto `SeatView` as a count-only view type
 * rather than passing it through, which is a deliberate tightening of the "opaque object id" precedent
 * the *hand*-scoped pending records set (docs/design/library-look.md §3). The identities themselves reach
 * the decider only through its own `DecisionRequest`, whose options every non-deciding seat is already
 * denied (ADR-007).
 *
 * @property decider the resolving spell's controller, who looks and who arranges (CR 701.14a).
 * @property poolIds the object ids being arranged, in pool order — top-first for a library pool, hand
 *   order for a hand pool. Empty exactly while [awaitingShuffle], because the cards have already moved.
 * @property awaitingShuffle whether the arrangement is settled and the clause's "you may shuffle"
 *   (CR 601.3b — Ponder's) is what remains. The same two-stage idiom as
 *   [PendingOptionalDiscardDraw.awaitingDiscard].
 */
data class PendingLibraryLook(
    val decider: PlayerId,
    val poolIds: PersistentList<ObjectId>,
    val awaitingShuffle: Boolean = false,
) {
    init {
        require(poolIds.distinct().size == poolIds.size) {
            "CR 701.14a: a card is looked at once, got $poolIds"
        }
        require(!awaitingShuffle || poolIds.isEmpty()) {
            "CR 601.3b: the pool has already moved by the shuffle stage, got $poolIds"
        }
    }
}
