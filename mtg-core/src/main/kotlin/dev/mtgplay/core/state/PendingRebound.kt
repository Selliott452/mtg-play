package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * A resolved rebound delayed trigger awaiting its controller's yes/no free cast (CR 702.88b). Additive,
 * flagged core (`FW-BLINK`, docs/design/exile-and-return.md §5).
 *
 * The exact shape of [PendingMadness], for the exact reason: the CR 702.88b "you **may** cast this card
 * from exile without paying its mana cost" is a genuine player choice, so it flows through a
 * `DecisionRequest` (ADR-004) rather than out of a [dev.mtgplay.core.definition.ResolutionEffect], and
 * recording it here keeps the pending decision a pure function of the state once the delayed ability has
 * left the stack.
 *
 * **The one rules difference from madness, and it is the whole reason this is a separate record.** On
 * **no** — or when the cast turns out impossible — madness puts the card into its owner's graveyard
 * (CR 702.35b), while rebound simply **leaves it in exile**: CR 702.88a says the card is exiled and may
 * be cast at the upkeep, and says nothing about what happens if it is not. A card that stops rebounding
 * stays exiled for the rest of the game. Reusing [PendingMadness] would have made that difference a flag
 * on a shared record and put two different CR paragraphs behind one branch.
 *
 * @property controller the spell's controller, who may cast it (CR 702.88b); the deciding seat of the
 *   yes/no. Control is ownership in the current pool.
 * @property exiledObjectId the rebounding exile card ([GameObject.reboundTurn] set) the choice is about.
 */
data class PendingRebound(
    val controller: PlayerId,
    val exiledObjectId: ObjectId,
)
