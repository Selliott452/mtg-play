package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * One game object (CR 109): a card existing in a zone.
 *
 * The MVP-minimal shape — identity, printed-card reference, owner, and the tapped status. The
 * [id] is fresh per zone residence (CR 400.7: an object that moves zones becomes a new object;
 * ids come from [GameState.allocateObjectId]), while the [card] is the stable printed identity
 * carried across those rebirths. Controller (CR 108.4), counters, attachments, and token-ness
 * are deliberately absent: each arrives with the rules packet that gives it meaning
 * (Phases 3–5).
 *
 * @property id this object's identity for as long as it stays in its current zone.
 * @property card the printed card this object represents.
 * @property owner the player whose deck the card began the game in; fixed for the whole game
 *   (CR 108.3).
 * @property tapped whether the object is tapped (CR 110.5b, CR 701.21). Tapped/untapped is a
 *   status only permanents have (CR 110.5), so this is `false` everywhere off the battlefield —
 *   an object enters the battlefield untapped (unless an effect says otherwise), and the fresh
 *   object born of any zone move carries no status memory (CR 400.7); the acceptance invariant
 *   checker enforces the scope.
 */
data class GameObject(
    val id: ObjectId,
    val card: CardRef,
    val owner: PlayerId,
    val tapped: Boolean = false,
)
