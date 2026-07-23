package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * One game object (CR 109): a card existing in a zone.
 *
 * The MVP-minimal shape — identity, printed-card reference, owner, the tapped status, marked
 * combat/other damage, the summoning-sickness fact, and (for an Aura) what it is attached to. The
 * [id] is fresh per zone residence (CR 400.7: an object that moves zones becomes a new object; ids
 * come from [GameState.allocateObjectId]), while the [card] is the stable printed identity carried
 * across those rebirths. Controller (CR 108.4 — equals [owner] until control-changing effects
 * arrive in Phase 4), counters, and token-ness are deliberately absent: each arrives with the
 * rules packet that gives it meaning (Phases 4–5).
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
 * @property attachedTo the object this Aura is attached to (CR 303.4), or `null` when it is not an
 *   Aura or is attached to nothing. Additive, flagged core (P4.1): a battlefield-only status like
 *   [tapped] — an Aura enters the battlefield attached to its chosen target (CR 303.4f) and the
 *   fresh object born of any zone move carries no attachment (CR 400.7), so this is `null`
 *   everywhere off the battlefield; the acceptance invariant checker enforces the scope. An Aura
 *   whose [attachedTo] no longer names a legal battlefield object is put into its owner's graveyard
 *   by the CR 704.5m state-based action. The inverse ("what is attached to me") is a battlefield
 *   scan, matching the "battlefield has no rules-relevant order, scan it" pattern.
 * @property awaitingMadness whether this exiled object is a card that madness exiled instead of
 *   discarding and that is now waiting on its reflexive "you may cast it" trigger (CR 702.35a–b).
 *   Additive, flagged core (P5.2): an exile-only marker — set when the discard→exile replacement
 *   exiles the card, cleared the moment the reflexive trigger resolves (the card is either cast from
 *   exile or put into its owner's graveyard). `false` everywhere but exile, and the fresh object born
 *   of any zone move carries none (CR 400.7); the acceptance invariant checker enforces both the scope
 *   and that a marked object always has a matching pending reflexive trigger.
 */
data class GameObject(
    val id: ObjectId,
    val card: CardRef,
    val owner: PlayerId,
    val tapped: Boolean = false,
    val damageMarked: Int = 0,
    val summoningSick: Boolean = true,
    val attachedTo: ObjectId? = null,
    val awaitingMadness: Boolean = false,
) {
    init {
        require(damageMarked >= 0) { "CR 120.3: marked damage is non-negative, was $damageMarked" }
        require(attachedTo != id) { "CR 303.4: an Aura cannot be attached to itself ($id)" }
    }
}
