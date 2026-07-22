package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * One game object (CR 109): a card existing in a zone.
 *
 * The MVP-minimal shape — identity, printed-card reference, owner, the tapped status, marked
 * combat/other damage, and the summoning-sickness fact. The [id] is fresh per zone residence
 * (CR 400.7: an object that moves zones becomes a new object; ids come from
 * [GameState.allocateObjectId]), while the [card] is the stable printed identity carried across
 * those rebirths. Controller (CR 108.4 — equals [owner] until control-changing effects arrive in
 * Phase 4), counters, attachments, and token-ness are deliberately absent: each arrives with the
 * rules packet that gives it meaning (Phases 3–5).
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
 * @property damageMarked how much damage is marked on this object (CR 120.3, CR 122 — the
 *   noun for it). Additive, flagged core (P3.1): non-negative, and a battlefield-only quantity
 *   like [tapped] — an object off the battlefield carries none, and the fresh object born of a
 *   zone move has none (CR 400.7); the acceptance invariant checker enforces the scope. Marked
 *   damage is set when a source deals damage to the object (CR 120.3d) and wears off as the
 *   turn's cleanup step ends (CR 514.2). Lethal-damage destruction is the CR 704.5g state-based
 *   action, which arrives in P3.2 — a creature does not die from marked damage in P3.1.
 * @property summoningSick whether this object has *not* been continuously controlled by its
 *   controller since the start of that player's most recent turn (CR 302.6). Additive, flagged
 *   core (P3.1): a creature that is summoning sick cannot be declared as an attacker (CR 508.1a).
 *   A creature entering the battlefield is summoning sick; the fact is cleared for a player's
 *   permanents when their turn begins (rules engine). Meaningful only for battlefield creatures;
 *   harmless elsewhere.
 */
data class GameObject(
    val id: ObjectId,
    val card: CardRef,
    val owner: PlayerId,
    val tapped: Boolean = false,
    val damageMarked: Int = 0,
    val summoningSick: Boolean = true,
) {
    init {
        require(damageMarked >= 0) { "CR 120.3: marked damage is non-negative, was $damageMarked" }
    }
}
