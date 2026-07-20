package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * One game object (CR 109): a card existing in a zone.
 *
 * The MVP-minimal shape — identity, printed-card reference, and owner. The [id] is fresh per
 * zone residence (CR 400.7: an object that moves zones becomes a new object; ids come from
 * [GameState.allocateObjectId]), while the [card] is the stable printed identity carried across
 * those rebirths. Controller (CR 108.4), status such as tapped/untapped (CR 110.5), counters,
 * attachments, and token-ness are deliberately absent: each arrives with the rules packet that
 * gives it meaning (Phases 2–5).
 *
 * @property id this object's identity for as long as it stays in its current zone.
 * @property card the printed card this object represents.
 * @property owner the player whose deck the card began the game in; fixed for the whole game
 *   (CR 108.3).
 */
data class GameObject(
    val id: ObjectId,
    val card: CardRef,
    val owner: PlayerId,
)
