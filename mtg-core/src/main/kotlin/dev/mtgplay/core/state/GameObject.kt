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
 * @property dealtDeathtouchDamage whether damage from a source with deathtouch (CR 702.2) has been
 *   marked on this object, which is the condition of the CR 704.5h state-based action: such a creature
 *   is destroyed whatever its toughness and however little damage it took. Additive, flagged core (the
 *   keyword-tail packet).
 *
 *   **It is a separate fact from [damageMarked] because CR 704.5h is a separate rule from CR 704.5g.**
 *   One damage from a deathtoucher destroys a 5/5 that is four short of lethal, so no arithmetic on a
 *   bare [damageMarked] total can express it; and [damageMarked] is an [Int] with no memory of what
 *   dealt it, so the source characteristic has to be recorded when the damage lands or be lost.
 *
 *   A battlefield-only, turn-scoped quantity exactly like [damageMarked]: it is set as the damage is
 *   marked (CR 120.3d), cleared in the same CR 514.2 cleanup transition that wipes marked damage, and
 *   the fresh object born of any zone move carries none (CR 400.7). The acceptance invariant checker
 *   enforces the scope, and additionally that a creature carrying it also carries positive
 *   [damageMarked] — the flag can only ever be set alongside a real, unprevented damage event.
 *
 *   **Deviation from CR 704.5h's wording, recorded rather than hidden.** The rule says "since the last
 *   time state-based actions were checked"; this flag instead persists until cleanup. The two are
 *   observationally identical over this engine's closed list of effects, and the argument is a case
 *   split rather than a hope: a creature carrying the flag is destroyed at the *very next* check unless
 *   it is [dev.mtgplay.core.card.Keyword.INDESTRUCTIBLE] (CR 702.12b), in which case it is never
 *   destroyed by it at any later check either; damage that is prevented is never dealt (CR 615.6) so
 *   sets nothing; and nothing in the pool regenerates or otherwise survives one check to face another.
 *   Clearing the flag on every check would instead mean writing to every battlefield object every time
 *   any player would receive priority, which is state churn the replay fingerprint would carry for no
 *   observable difference. The first effect that lets a creature survive a check while flagged — a
 *   regeneration shield, a totem-armour replacement — makes the distinction real and must move the
 *   clear into the check.
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
 * @property evokedWhenCast whether this permanent entered the battlefield from a spell cast for its
 *   **evoke** cost (CR 702.74a) — Mulldrifter. Additive, flagged core (`W8-D`); the exact sibling of
 *   [kickedWhenCast], for the exact reason.
 *
 *   CR 702.74a's second half is "When this permanent enters, **if its evoke cost was paid**, sacrifice
 *   it", which is an ability of the *permanent* asking a question about the *spell* — the CR 702.33f
 *   situation word for word, one keyword over. The two flags stay separate rather than collapsing into
 *   one "cast with a permission" marker because they answer different questions and a card could print
 *   both: kicked-ness selects between two effects, evoked-ness destroys the permanent.
 *
 *   `false` everywhere but the battlefield and on the fresh object born of any later zone move
 *   (CR 400.7) — which is what makes an evoked Mulldrifter reanimated out of the graveyard *stay* on the
 *   battlefield, since the returned permanent is a new object that was never cast.
 * @property playGrantedTurn the turn on which an effect gave this **exile** card's owner permission to
 *   play it (CR 118.5, CR 601.2a) — Reckless Impulse's *"Until the end of your next turn, you may play
 *   those cards."* `null` for every other object. Additive, flagged core (`W8-D`).
 *
 *   **It records when the permission was granted, not when it ends, and that is the whole design.**
 *   "Your next turn" cannot be named as a turn number when the effect resolves without predicting who
 *   will be active two turns hence — a prediction the engine has no business making. Storing the grant
 *   turn instead lets the CR 514.2 cleanup decide with a rule that needs no foresight: the permission
 *   ends at the cleanup of the first turn that is the owner's **and** is strictly later than
 *   [playGrantedTurn]. Cast on your own turn N it survives N's cleanup (not later than N), survives the
 *   opponent's N+1 (not yours), and ends at N+2 — exactly "the end of your next turn".
 *
 *   **The grantee is the owner**, so no second field names them: every printing exiles from its
 *   controller's own library, so the cards' owner and the player granted the permission are the same
 *   person. A card granting an *opponent* permission to play an exiled card would need one, and it
 *   would be a field here rather than a reinterpretation of this one.
 *
 *   `null` on the fresh object born of any zone move (CR 400.7), which is what stops a card played from
 *   exile and later returned to exile from still being playable.
 * @property enteredTurn the turn number on which this permanent **entered the battlefield** (CR 603.6a),
 *   or `null` for an object that is not on the battlefield. Additive, flagged core (`W9-A`).
 *
 *   **It is not [summoningSick], and the two only coincide by accident.** Summoning sickness is "has
 *   not been continuously controlled since the start of its controller's most recent turn" (CR 302.6) —
 *   a fact about *control*, cleared at the beginning of the controller's turn and irrelevant to a
 *   creature with haste. "Entered this turn" is a fact about *this turn*, true for a hasty creature and
 *   for one that entered during an opponent's turn, and false for a creature that has been on the
 *   battlefield since before the turn began even when it is still summoning sick (put onto the
 *   battlefield during the opponent's turn, it is sick on your turn and did not enter on it). Moon-Circuit
 *   Hacker's *"discard a card **unless this creature entered this turn**"* asks the second question, and
 *   reading the first would answer it wrongly the moment anything grants haste.
 *
 *   **A turn number rather than a boolean**, matching [plottedTurn] and [reboundTurn]: "this turn" is
 *   then `enteredTurn == state.turn.number`, which needs no per-turn sweep to clear and cannot go stale.
 *
 *   Stamped in the single battlefield-entry home every entry path shares, so no path can put a permanent
 *   onto the battlefield without recording when. A battlefield-only quantity like [tapped]: `null`
 *   everywhere else, and the fresh object born of any zone move carries none (CR 400.7) — a creature that
 *   dies and is reanimated entered on the turn it was reanimated, because it is a new object. The
 *   acceptance invariant checker enforces the scope.
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
 * @property activatedAbilitiesActivatedThisTurn the indices of this object's **printed** non-mana
 *   activated abilities that have been activated during the turn now in progress (CR 602.5b). Additive,
 *   flagged core (`FW-TAPUNTAP`). Empty for every object with no "Activate only once each turn"
 *   activated ability — the engine records an activation only when the ability carries the restriction
 *   — so the field stays empty on every ordinary permanent and the replay fingerprint of an ordinary
 *   board does not move.
 *
 *   **The sibling of [manaAbilitiesActivatedThisTurn], and deliberately a second field rather than a
 *   widening of it.** The rule is identical (CR 602.5b, a property of the object and not of its
 *   controller), but the two index *different lists* on the same definition —
 *   [dev.mtgplay.core.definition.CardDefinition.manaAbilities] and
 *   [dev.mtgplay.core.definition.CardDefinition.activatedAbilities] — so a shared set could not say
 *   which ability index 0 named on a card printing both. Turn-scoped and battlefield-only exactly as
 *   its sibling is: cleared for every object as a turn begins (CR 500.1 — "each turn" means each
 *   player's turn), and the fresh object born of any zone move carries none (CR 400.7). The acceptance
 *   invariant checker enforces both the scope and that every recorded index names a printed activated
 *   ability.
 * @property skipsNextUntapStep whether this permanent does **not** untap during its controller's next
 *   untap step (CR 302.6, CR 502.2) — Sleep of the Dead's "It doesn't untap during its controller's
 *   next untap step". Additive, flagged core (`FW-TAPUNTAP`).
 *
 *   A battlefield-only marker like [tapped], and the one turn-spanning marker in this type: unlike
 *   [manaAbilitiesActivatedThisTurn] it is **not** cleared when a turn begins, because the effect names
 *   a specific future step rather than the current turn. It is consumed by the untap step's turn-based
 *   action — the permanent stays as it is and the marker clears — so exactly one untap step is skipped
 *   however many turns pass first. `false` everywhere off the battlefield, and the fresh object born of
 *   any zone move carries none (CR 400.7), which is the rules answer as well as the state one: a
 *   creature bounced and recast is a new object and unaffected. The acceptance invariant checker
 *   enforces the scope.
 */
data class GameObject(
    val id: ObjectId,
    val card: CardRef,
    val owner: PlayerId,
    val tapped: Boolean = false,
    val damageMarked: Int = 0,
    val dealtDeathtouchDamage: Boolean = false,
    val summoningSick: Boolean = true,
    val attachedTo: ObjectId? = null,
    val awaitingMadness: Boolean = false,
    val plottedTurn: Int? = null,
    val chosenColor: Color? = null,
    val counters: PersistentMap<Counter, Int> = persistentMapOf(),
    val linkedExiled: PersistentList<ObjectId> = persistentListOf(),
    val reboundTurn: Int? = null,
    val manaAbilitiesActivatedThisTurn: PersistentSet<Int> = persistentSetOf(),
    val activatedAbilitiesActivatedThisTurn: PersistentSet<Int> = persistentSetOf(),
    val skipsNextUntapStep: Boolean = false,
    val kickedWhenCast: Boolean = false,
    val evokedWhenCast: Boolean = false,
    val playGrantedTurn: Int? = null,
    val optionalCostPaidWhenCast: Boolean = false,
    val enteredTurn: Int? = null,
) {
    init {
        require(damageMarked >= 0) { "CR 120.3: marked damage is non-negative, was $damageMarked" }
        require(!dealtDeathtouchDamage || damageMarked > 0) {
            "CR 704.5h: deathtouch damage is damage, so an object flagged as having been dealt it " +
                "always carries marked damage too (object $id has none)"
        }
        require(manaAbilitiesActivatedThisTurn.all { it >= 0 }) {
            "CR 602.5b: a per-turn activation record indexes a printed mana ability, got " +
                "$manaAbilitiesActivatedThisTurn"
        }
        require(activatedAbilitiesActivatedThisTurn.all { it >= 0 }) {
            "CR 602.5b: a per-turn activation record indexes a printed activated ability, got " +
                "$activatedAbilitiesActivatedThisTurn"
        }
        require(attachedTo != id) { "CR 303.4: an Aura cannot be attached to itself ($id)" }
        require(plottedTurn == null || plottedTurn >= 1) { "CR 702.140: a plotted turn is a real turn number" }
        require(reboundTurn == null || reboundTurn >= 1) { "CR 702.88a: a rebound turn is a real turn number" }
        require(enteredTurn == null || enteredTurn >= 1) { "CR 603.6a: an entry turn is a real turn number" }
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
