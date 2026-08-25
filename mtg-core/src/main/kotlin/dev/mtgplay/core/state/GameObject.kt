package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * One game object (CR 109): a card existing in a zone.
 *
 * The MVP-minimal shape — identity, printed-card reference, owner, the tapped status, marked
 * combat/other damage, the summoning-sickness fact, and (for an Aura) what it is attached to. The
 * [id] is fresh per zone residence (CR 400.7: an object that moves zones becomes a new object; ids
 * come from [GameState.allocateObjectId]), while the [card] is the stable printed identity carried
 * across those rebirths. Controller (CR 108.4 — equals [owner] until control-changing effects
 * arrive in Phase 4) and token-ness are deliberately absent: each arrives with the rules packet
 * that gives it meaning (Phases 4–5). [counters] arrived with `FW-COUNTERS`.
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
 * @property plottedTurn the turn number on which this exiled card was plotted (CR 702.140), or `null`
 *   when it was not plotted. Additive, flagged core (P6.2a): an exile-only marker recording *when* the
 *   card was plotted, because a plotted card may be cast for free from exile at sorcery speed but **not
 *   the turn it was plotted** — the free cast is legal only on a later turn (`mtg-rules` compares this
 *   to the current turn). `null` everywhere but a plotted exile card, and the fresh object born of any
 *   zone move carries none (CR 400.7); the acceptance invariant checker enforces the scope.
 * @property chosenColor the colour this permanent chose as it entered the battlefield (CR 614.12), or
 *   `null` when it made no such choice. Additive, flagged core (P6.2a): a battlefield-only linked choice
 *   — Utopia Sprawl's "As this Aura enters, choose a colour", read by its triggered mana ability. Set as
 *   the object enters and fixed thereafter; `null` off the battlefield and on the fresh object born of
 *   any zone move (CR 400.7).
 * @property counters the counters on this permanent (CR 122.1), as a multiset: how many of each
 *   [Counter] kind it carries. Additive, flagged core (`FW-COUNTERS`). A battlefield-only quantity
 *   like [tapped] and [damageMarked] — **CR 122.2 is explicit that counters are not retained when an
 *   object changes zones; they are not "removed", they simply cease to exist** — so the fresh object
 *   born of any zone move carries none (CR 400.7), and the acceptance invariant checker enforces both
 *   the scope and that every recorded multiplicity is strictly positive (a kind an object has none of
 *   is absent from the map, never present with a count of zero — otherwise two states that are the
 *   same position would compare unequal and the replay fingerprint would split them).
 *
 *   What the counters *do* is a rules decision, not a state one: `mtg-rules` applies
 *   [Counter.PowerToughness] in CR 613 sublayer 7c (CR 613.4c) and [Counter.KeywordCounter] in layer
 *   6 (CR 613.1f), and annihilates opposing `+1/+1` and `-1/-1` counters as the CR 704.5q
 *   state-based action.
 * @property linkedExiled the exile objects this permanent's own ability exiled, in the order exiled
 *   (CR 607.2 **linked abilities**), or empty when it has exiled nothing. Additive, flagged core
 *   (`FW-LINKEDEXILE`, docs/design/exile-and-return.md §4). Journey to Nowhere's "When this enchantment
 *   enters, exile target creature" and its "When this enchantment leaves the battlefield, return the
 *   exiled card" are a linked pair: the second refers to *the card the first exiled*, and nothing else.
 *   This list is that reference, held on the object whose abilities are linked — which is CR 607.2's own
 *   phrasing ("the second ability refers to the objects the first exiled").
 *
 *   A battlefield-only quantity like [tapped] and [counters]: an object off the battlefield carries
 *   none, and the fresh object born of any zone move carries none (CR 400.7) — which is precisely
 *   CR 607.3's answer to "what happens if the permanent leaves and returns", namely that the returning
 *   permanent is a new object whose new first ability has exiled nothing yet. The acceptance invariant
 *   checker enforces both the scope and that every id named here is really in exile.
 * @property reboundTurn the turn on which rebound exiled this card as it resolved (CR 702.88a), or
 *   `null` when it was not exiled by rebound. Additive, flagged core (`FW-BLINK`,
 *   docs/design/exile-and-return.md §5) — the exile-only marker for Ephemerate, in the shape
 *   [plottedTurn] already set. It records *when* because rebound's delayed ability fires at the
 *   beginning of the controller's **next** upkeep: a spell that resolved during its controller's own
 *   upkeep must not rebound in that same upkeep, so `mtg-rules` fires only on a strictly later turn.
 *   `null` everywhere but a rebounding exile card, and on the fresh object born of any zone move
 *   (CR 400.7); the acceptance invariant checker enforces the scope.
 * @property kickedWhenCast whether this permanent entered the battlefield from a spell whose **kicker**
 *   cost was paid (CR 702.33a-f). Additive, flagged core (`FW-OPTCOST`): a battlefield-only marker in
 *   the shape [plottedTurn] and [chosenColor] already set.
 *
 *   **CR 702.33f is the rule that makes this necessary rather than convenient.** A permanent spell and
 *   the permanent it becomes are different objects (CR 400.7), so nothing about the cast survives the
 *   move on its own -- and Goblin Bushwhacker's "When this creature enters, **if it was kicked**"
 *   is an ability of the *permanent* asking a question about the *spell*. The flag is the bridge, set as
 *   the object enters and fixed thereafter.
 *
 *   `false` everywhere but the battlefield, and on the fresh object born of any later zone move
 *   (CR 400.7) -- a kicked creature that dies and is returned comes back unkicked, because it is a new
 *   object that was never cast at all. The acceptance invariant checker enforces the scope.
 * @property manaAbilitiesActivatedThisTurn the indices of this object's **printed** mana abilities that
 *   have been activated during the turn now in progress (CR 602.5b). Additive, flagged core
 *   (`FW-MANACOST`). Empty for every object that has no "Activate only once each turn" mana ability —
 *   the engine records an activation only when the ability carries the restriction, so the field stays
 *   empty on every ordinary land and the replay fingerprint of an ordinary board does not move.
 *
 *   **CR 602.5b makes this a property of the object, not of its controller**: "the restriction continues
 *   to apply to that object even if its controller changes". It is a turn-scoped battlefield quantity
 *   like [tapped] and [damageMarked], cleared for every object as a turn begins (CR 500.1 — "each turn"
 *   means each player's turn, so a source spent on your turn is available again on your opponent's), and
 *   the fresh object born of any zone move carries none (CR 400.7). The acceptance invariant checker
 *   enforces both the scope and that every recorded index names a printed mana ability.
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
    val plottedTurn: Int? = null,
    val chosenColor: Color? = null,
    val counters: PersistentMap<Counter, Int> = persistentMapOf(),
    val linkedExiled: PersistentList<ObjectId> = persistentListOf(),
    val reboundTurn: Int? = null,
    val manaAbilitiesActivatedThisTurn: PersistentSet<Int> = persistentSetOf(),
    val kickedWhenCast: Boolean = false,
) {
    init {
        require(damageMarked >= 0) { "CR 120.3: marked damage is non-negative, was $damageMarked" }
        require(manaAbilitiesActivatedThisTurn.all { it >= 0 }) {
            "CR 602.5b: a per-turn activation record indexes a printed mana ability, got " +
                "$manaAbilitiesActivatedThisTurn"
        }
        require(attachedTo != id) { "CR 303.4: an Aura cannot be attached to itself ($id)" }
        require(plottedTurn == null || plottedTurn >= 1) { "CR 702.140: a plotted turn is a real turn number" }
        require(reboundTurn == null || reboundTurn >= 1) { "CR 702.88a: a rebound turn is a real turn number" }
        require(id !in linkedExiled) { "CR 607.2: a permanent cannot be its own linked exile ($id)" }
        require(linkedExiled.distinct().size == linkedExiled.size) {
            "CR 607.2: a linked exile record names each exiled object once, got $linkedExiled"
        }
        require(counters.values.all { it > 0 }) {
            "CR 122.1: a counter multiset records only counters that are present; a kind with a " +
                "non-positive count must be absent, got $counters"
        }
    }

    /** How many [kind] counters are on this permanent (CR 122.1); zero when it has none. */
    fun counterCount(kind: Counter): Int = counters[kind] ?: 0
}
