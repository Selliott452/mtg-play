package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList

/**
 * A resolving cascade ability that has finished exiling and is now either awaiting its controller's
 * yes/no free cast or waiting to bottom what it exiled (CR 702.85a). Additive, flagged core (`W9-G`).
 *
 * **The one pending record that outlives its own decision**, and that is what makes cascade different
 * from madness and rebound rather than a third copy of them. CR 702.85a is a sequence: exile, *then* the
 * may-cast, *then* "put all cards exiled this way that weren't cast on the bottom of your library in a
 * random order". The bottoming is the last thing the cascade ability does, so it happens **after** the
 * free cast has completed — and the free cast is a whole CR 601 pipeline with its own pauses. Nothing
 * else in the engine has to survive a nested cast, so nothing else needed this shape.
 *
 * The record therefore has two lives, distinguished by [candidateObjectId]:
 * - **non-null** — the exiling is done, a legal free cast exists, and the controller owes a yes/no. This
 *   is the [PendingMadness]/[PendingRebound] shape exactly.
 * - **null** — the question is answered (or there was never one to ask) and all that remains is the
 *   bottoming. The engine derives *no* decision request from this state, which is what stops the yes/no
 *   being re-asked while the free cast gathers its own decisions.
 *
 * Clearing [candidateObjectId] before the cast begins rather than deleting the whole record is the point:
 * a record deleted at "yes" would take the exiled cards' identities with it, and there is nowhere else
 * they are written down once they are no longer the top of a library.
 *
 * @property controller the cascading spell's controller (CR 702.85a "you"), the deciding seat of the
 *   yes/no and the owner of the library the cards return to. Control is ownership in the current pool.
 * @property exiledObjectIds every card this cascade exiled, in the order exiled (top of library first).
 *   The bottoming filters this to those **still in exile**, which is CR 702.85a's "that weren't cast"
 *   read off the state rather than tracked separately: a card that was cast has left exile for the
 *   stack, and one that was never cast has not.
 * @property candidateObjectId the exiled nonland card the controller may cast for free (CR 702.85a),
 *   while that question is open; `null` once it is answered, declined, or was never askable. Always one
 *   of [exiledObjectIds] when set.
 */
data class PendingCascade(
    val controller: PlayerId,
    val exiledObjectIds: PersistentList<ObjectId>,
    val candidateObjectId: ObjectId? = null,
) {
    init {
        require(candidateObjectId == null || candidateObjectId in exiledObjectIds) {
            "CR 702.85a: a cascade's free-cast candidate is one of the cards it exiled, but " +
                "$candidateObjectId is not among $exiledObjectIds"
        }
        require(exiledObjectIds.distinct().size == exiledObjectIds.size) {
            "CR 400.7: a cascade exiles each card once, got $exiledObjectIds"
        }
    }
}
