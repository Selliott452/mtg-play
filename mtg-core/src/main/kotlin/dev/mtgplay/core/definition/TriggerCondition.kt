package dev.mtgplay.core.definition

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.mana.Color
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * The event pattern that fires a [TriggeredAbility] (CR 603.2) — the "when/whenever/at" condition,
 * expressed as card-definition data. Additive, flagged core (P5.1).
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This is the *declaration* of
 * what the ability watches for; `mtg-rules` owns *detecting* that the condition matched a game event
 * (CR 603.3) and *queueing* the ability. Core says "watch for this creature dealing damage"; rules
 * decides, at each transition, whether that happened.
 *
 * Sealed so the rules detector handles every pattern exhaustively and a new pattern breaks
 * compilation rather than going silently undetected. The MVP pool exercises exactly the patterns the
 * four deferred Bogles halves need plus the cast-trigger seam; targeted triggers (CR 603.3d) and
 * intervening-if conditions (CR 603.4) are not needed by any MVP card and are the sealed extension
 * point (P5.2/P6).
 */
sealed interface TriggerCondition {
    /**
     * "When this permanent enters the battlefield" (CR 603.6a) — a self-referential enters-the-
     * battlefield trigger. Cartouche of Solidarity (create a token) and Abundant Growth (draw a
     * card) trigger on their own entry. The trigger carries the entered object as its subject.
     */
    data object EnteredBattlefieldSelf : TriggerCondition

    /**
     * "When this permanent enters **untapped**" (CR 603.6a) — Gingerbread Cabin's "When this land
     * enters untapped, create a Food token". Added by `P-TRIGCOND`.
     *
     * A narrower [EnteredBattlefieldSelf], not an intervening-if condition (CR 603.4). The
     * distinction matters and the CR draws it sharply: the entering permanent's tapped status is part
     * of the *event*, fixed by whatever CR 614.1c replacement applied as it entered, so the condition
     * is evaluated once at that instant. An intervening-if would be re-checked when the ability would
     * resolve (CR 603.4), and tapping the land in response would wrongly stop the token. Making it a
     * condition rather than a check inside the effect is what keeps the trigger from firing at all
     * when the land entered tapped — a trigger that never fires and a trigger that resolves doing
     * nothing are different observable games (the second uses the stack).
     *
     * Paired with [EntersTapped.UnlessYouControl] by construction: a card that prints this condition
     * prints a conditional enters-tapped clause, because a permanent with an unconditional clause
     * would never fire it and one with no clause would always fire it.
     */
    data object EnteredBattlefieldUntappedSelf : TriggerCondition

    /**
     * "When this permanent is put into a graveyard from the battlefield" (CR 603.6b, CR 603.10) — a
     * leaves-the-battlefield trigger. Rancor's "return this to its owner's hand" fires as the Aura
     * arrives in the graveyard (most often via the CR 704.5m fall-off when its creature dies). Per
     * CR 603.10 it is checked against the game state just before the object left; the fired trigger
     * carries, as its subject, the fresh graveyard object (CR 400.7) the ability then acts on.
     */
    data object PutIntoGraveyardFromBattlefieldSelf : TriggerCondition

    /**
     * "When this permanent leaves the battlefield" (CR 603.6c, CR 603.10) — the **general**
     * leaves-the-battlefield trigger, fired by *every* departure rather than only by the one that ends in
     * a graveyard. Journey to Nowhere's "When this enchantment leaves the battlefield, return the exiled
     * card" and Mesmeric Fiend's "When this creature leaves the battlefield, return the exiled card to
     * its owner's hand" both print it. Added by `FW-TRIGLTB` (docs/design/exile-and-return.md §3).
     *
     * **Strictly wider than [PutIntoGraveyardFromBattlefieldSelf], and the difference is the whole
     * reason this member exists.** CR 603.6b's condition is "is put into a graveyard from the
     * battlefield"; CR 603.6c's is "leaves the battlefield", which a permanent also does by being
     * exiled (CR 701.3a), returned to a hand (CR 701.4a), or shuffled into a library. Encoding Journey
     * to Nowhere with the narrower condition would be exactly the silent approximation CONVENTIONS.md
     * forbids: exile the Journey in response and the creature it holds would stay exiled forever,
     * which is a different card. The two conditions are deliberately *both* kept rather than the
     * narrower one being expressed as a filter over this one — Rancor's "when this is put into a
     * graveyard from the battlefield" genuinely does not fire when Rancor is exiled, and collapsing
     * them would make that wrong in the opposite direction.
     *
     * Per CR 603.10 the condition is checked against the game state just before the object left, and
     * the fired trigger carries the departed permanent's last-known information — including, for a
     * linked ability (CR 607), the [dev.mtgplay.core.state.GameObject.linkedExiled] ids it recorded
     * while it was on the battlefield. A permanent that leaves the battlefield for a graveyard fires
     * this *and* [PutIntoGraveyardFromBattlefieldSelf], in that order; no card in the pool prints both.
     */
    data object LeftBattlefieldSelf : TriggerCondition

    /**
     * Rebound's delayed "at the beginning of your next upkeep, you may cast this card from exile without
     * paying its mana cost" ability (CR 702.88a). Added by `FW-BLINK` (docs/design/exile-and-return.md
     * §5) for Ephemerate.
     *
     * Like [MadnessCast] this is never *detected* against a game event in the ordinary way: the rebound
     * replacement exiles the resolving spell instead of putting it into its owner's graveyard and marks
     * the exile object, and the upkeep turn-based check synthesizes the fired trigger directly
     * (functioning from [TriggerZoneScope.Exile]). On resolution the reflexive-cast path offers the
     * owner a yes/no free cast and, if declined or impossible, **leaves the card in exile** — which is
     * the one place rebound differs from madness, whose decline puts the card into a graveyard
     * (CR 702.35b). Its [TriggeredAbility.effect] is unused because the may-cast is the engine's, not a
     * [ResolutionEffect].
     *
     * This is a **narrow** rebound, not the general delayed-triggered-ability framework (CR 603.7),
     * which the engine still lacks; docs/design/exile-and-return.md §5.1 records the difference.
     */
    data object ReboundCast : TriggerCondition

    /**
     * "Whenever enchanted creature deals damage" (CR 603.2) — the Aura watches the object it is
     * attached to (CR 611.2c) and fires when that creature deals damage, combat or noncombat.
     * Armadillo Cloak's "you gain that much life" fires here; the fired trigger carries the amount
     * of damage dealt (CR 118.9 "that much") and the enchanted creature as its subject. Only combat
     * damage occurs in the MVP pool (the enchanted creatures are vanilla), but the condition is not
     * combat-restricted — a noncombat damage source that is itself an enchanted creature would fire
     * it identically, from the same detection seam (`mtg-rules`).
     */
    data object EnchantedCreatureDealsDamage : TriggerCondition

    /**
     * "Whenever a spell is cast" (CR 603.2, CR 601.2i) — the cast-trigger seam, now filterable
     * (P6.2a; [excludedSpellTypes] added in P6.3, [spellColors] by `W8-E`). A card carrying this fires
     * as a spell finishes casting (CR 601.2i) **iff** the cast spell matches all four filters:
     * - [spellTypes]: the cast spell's printed card types must include at least one of these
     *   (CR 205.2); an **empty** set matches any spell (the bare "whenever a spell is cast").
     * - [excludedSpellTypes]: the cast spell's printed card types must include **none** of these —
     *   the "noncreature spell" shape (CR 205.2); an **empty** set excludes nothing.
     * - [spellColors]: the cast spell must be at least one of these colours (CR 105.2); an **empty**
     *   set imposes no colour requirement.
     * - [controlledByYou]: when `true`, the spell's controller must be the trigger source's
     *   controller (CR 603.2e "a spell you control") — control is ownership in the MVP pool; when
     *   `false` any player's cast matches.
     *
     * Guttersnipe's "whenever you cast an instant or sorcery spell" is `SpellCast(spellTypes =
     * {INSTANT, SORCERY}, controlledByYou = true)`; Kessig Flamebreather's "whenever you cast a
     * noncreature spell" is `SpellCast(excludedSpellTypes = {CREATURE}, controlledByYou = true)` —
     * a multi-type spell (an artifact creature) is a creature spell and so is excluded, which is why
     * the exclusion is a type set rather than the complement of [spellTypes]. God-Pharaoh's Faithful's
     * "whenever you cast a blue, black, or red spell" is `SpellCast(spellColors = {BLUE, BLACK, RED},
     * controlledByYou = true)`. `mtg-rules` applies all four filters at the `completeCast` detection
     * site; the fired ability's [TriggeredAbility.effect] runs on resolution.
     *
     * **Colour is a set membership, not a card-type test, and the two axes are independent** — which is
     * why [spellColors] is its own property rather than a member of [spellTypes]. A spell is *every*
     * colour in its mana cost (CR 105.2, CR 202.2), so a multicolour spell qualifies as soon as one of
     * its colours is listed and a **colourless** spell qualifies for no non-empty list at all. That last
     * part is the load-bearing half for God-Pharaoh's Faithful, whose deck plays artifacts: casting a
     * colourless artifact gains nothing, and reading the printed line as "any spell" would quietly hand
     * the seat life it never had.
     *
     * @property spellTypes the printed card types that qualify a cast (any one suffices); empty means
     *   any spell.
     * @property excludedSpellTypes the printed card types that disqualify a cast (any one suffices to
     *   disqualify); empty means nothing is excluded.
     * @property spellColors the colours that qualify a cast (any one suffices, CR 105.2); empty means
     *   no colour requirement. Additive, flagged core (`W8-E`).
     * @property controlledByYou whether the cast spell must be controlled by the trigger's controller.
     */
    data class SpellCast(
        val spellTypes: PersistentSet<CardType> = persistentSetOf(),
        val excludedSpellTypes: PersistentSet<CardType> = persistentSetOf(),
        val spellColors: PersistentSet<Color> = persistentSetOf(),
        val controlledByYou: Boolean = false,
    ) : TriggerCondition {
        init {
            require(spellTypes.none { it in excludedSpellTypes }) {
                "CR 603.2: a cast trigger cannot both require and exclude a card type, got " +
                    "$spellTypes and $excludedSpellTypes"
            }
        }
    }

    /**
     * "When you draw your [n]th card in a turn" (CR 603.2) — a per-turn draw-count trigger. Sneaky
     * Snacker's "when you draw your third card in a turn, return this card from your graveyard to the
     * battlefield tapped" is [DrewNthCardThisTurn]`(3)`, functioning from [TriggerZoneScope.Graveyard].
     * "You" is the ability's controller — the card's owner in the MVP pool — so the trigger fires only
     * when *that* player's [dev.mtgplay.core.state.PlayerState.drawsThisTurn] reaches exactly [n]
     * (`mtg-rules` detects it at the draw that crosses the threshold, never re-firing on later draws).
     *
     * @property n which draw of the turn fires the ability (Sneaky Snacker's is 3); at least 1.
     */
    data class DrewNthCardThisTurn(
        val n: Int,
    ) : TriggerCondition {
        init {
            require(n >= 1) { "CR 603.2: the draw ordinal a per-turn draw trigger watches is at least 1, was $n" }
        }
    }

    /**
     * Madness's reflexive "when this card is discarded this way, its owner may cast it" ability
     * (CR 702.35b) — the condition of the ability the madness replacement synthesizes on a card it
     * exiles instead of discarding (CR 702.35a). Added in P5.2. Unlike the four battlefield conditions
     * this is never *detected* against a game event: the madness replacement creates the fired trigger
     * directly (functioning from [TriggerZoneScope.Exile]), so the trigger detector never produces it.
     * On resolution the reflexive-cast path offers the owner a yes/no cast for the card's madness cost
     * and, if declined or impossible, puts the card into its owner's graveyard (`mtg-rules`); the
     * ability's [TriggeredAbility.effect] is unused because the may-cast is the engine's, not a
     * [ResolutionEffect].
     */
    data object MadnessCast : TriggerCondition

    /**
     * "Whenever this creature deals combat damage to a player" (CR 603.2, CR 510.2) — a self-referential
     * combat-damage trigger. Additive, flagged core (`FW-TRIGCOMBAT`). Ninja of the Deep Hours' *"you may
     * draw a card"* fires here.
     *
     * **Three narrowings, and each one is the difference between this and a condition the engine already
     * had.** [EnchantedCreatureDealsDamage] watches damage dealt by the creature an *Aura* is attached to,
     * of *any* kind, to *any* recipient. This one:
     *
     * - watches the trigger source **itself** rather than an attachment's host (CR 603.2e "this
     *   creature");
     * - is restricted to **combat** damage (CR 510.2) — damage from a resolving spell or ability does not
     *   fire it, which is why it cannot be expressed as a filter over the general damage condition;
     * - is restricted to damage dealt **to a player** (CR 510.1c) — a creature whose whole damage went to
     *   a blocker fires nothing, and one that split its damage between a blocker and the defending player
     *   (trample, CR 702.19) fires once.
     *
     * CR 120.8: zero damage is not dealt, so an attacker with no power fires nothing. Combat damage is one
     * event per step (CR 510.2), so a source that split its damage among several recipients has dealt it
     * **once** and this fires at most once per combat-damage step — the same aggregation the lifelink and
     * Aura-damage results already use in `mtg-rules`.
     *
     * The fired trigger carries the amount dealt to players as [dev.mtgplay.core.state.PendingTrigger.amount]
     * (CR 118.9 "that much"), which no pool card reads yet but which is the linked information a
     * damage-scaled version of this trigger would need.
     */
    data object DealtCombatDamageToPlayerSelf : TriggerCondition

    /**
     * "When enchanted creature **becomes tapped**" (CR 603.2, CR 701.20a) — the Aura watches the object it
     * is attached to (CR 611.2c) and fires when that permanent's status changes from untapped to tapped.
     * Additive, flagged core (`W8-C`). Cryoshatter's half of a two-condition ability prints it; Removal.kt
     * has recorded the card as blocked on exactly this member since the removal packet.
     *
     * **"Becomes" is a state *change*, not a state.** CR 701.20a: "a permanent that's already tapped can't
     * be tapped again", so tapping an already-tapped permanent is not an event and fires nothing. That is
     * why the detection site is the one place the status flips rather than any place that *asks* for a tap,
     * and it is what stops a second `{T}` attempt or a redundant Sleep of the Dead from re-firing it.
     *
     * **Why it is not restricted to any particular way of tapping.** The reason a permanent became tapped
     * is not part of the condition: paying a `{T}` cost (CR 602.2a), paying a mana ability's (CR 605.1a),
     * being declared as an attacker (CR 508.1f), and a resolving "tap target creature" all satisfy it
     * identically. The attacker case is the one that matters in play — a Cryoshattered creature dies the
     * moment it attacks — and it is also the one an implementation most easily misses, which is why
     * `mtg-rules` funnels every tap through a single announcement.
     *
     * **A permanent that *enters* tapped does not become tapped**, and the distinction is CR 701.20a's
     * again: it was never untapped, so no status changed. A Bridge land arriving tapped, a Ninjutsu'd
     * Ninja entering tapped and attacking, and a Landscape's search putting a basic onto the battlefield
     * tapped all fire nothing. The sibling condition [EnteredBattlefieldUntappedSelf] draws the same line
     * from the other side.
     */
    data object EnchantedPermanentBecomesTapped : TriggerCondition

    /**
     * "When enchanted creature **is dealt damage**" (CR 603.2, CR 120.3d) — the Aura watches the object it
     * is attached to (CR 611.2c) and fires when damage is *marked* on that permanent. Additive, flagged
     * core (`W8-C`). Cryoshatter's other half.
     *
     * **The mirror of [EnchantedCreatureDealsDamage], and genuinely the other direction.** That condition
     * fires when the enchanted creature is the damage's **source** (CR 120.1) — Armadillo Cloak's "you gain
     * that much life"; this one fires when it is the damage's **recipient**. Nothing about one implies the
     * other: a creature that deals combat damage to an unblocked player fires the first and not this, and a
     * creature Lightning Bolted while it sits at home fires this and not the first. Both fire when two
     * creatures trade blows, and they fire as two separate abilities on two separate Auras.
     *
     * Any damage counts — combat (CR 510.2) and noncombat alike — because the printed line says "is dealt
     * damage" and names no kind, which is the same reading [EnchantedCreatureDealsDamage] already records
     * for its side.
     *
     * Two exclusions, and both are the damage rules rather than this condition's:
     * - **Zero damage is not dealt** (CR 120.8), so nothing fires.
     * - **Prevented damage never happens** (CR 615.6), so a prevention effect stops the trigger as well as
     *   the mark. Both fall out of the detection site sitting where damage is actually marked, past the
     *   two exits `dealDamage` already takes.
     */
    data object EnchantedPermanentIsDealtDamage : TriggerCondition

    /**
     * **One** triggered ability whose single trigger condition is satisfied by **any** of [conditions]
     * (CR 603.2) — the "When X **or** Y, do Z" shape. Additive, flagged core (`W8-C`). Cryoshatter's
     * "When enchanted creature becomes tapped **or** is dealt damage, destroy it" is the pool's first
     * printing, and Ichor Wellspring's "enters or dies" is a second the pool already holds (encoded as two
     * abilities before this member existed).
     *
     * **A combinator rather than a card-shaped member, and that is the whole point.** The alternative was a
     * `EnchantedCreatureBecomesTappedOrIsDealtDamage` member, which would name one card in the shared
     * vocabulary (ADR-003) and would have to be duplicated for the next card that ORs a different pair.
     * This member instead says what the CR says: a trigger condition may be a disjunction of event
     * patterns, and the ability is still one ability.
     *
     * **Why it is not simply two abilities.** Declaring the two halves as two [TriggeredAbility] entries
     * produces the same game for every event *this* pool can generate — no single event both taps a
     * permanent and damages it — so the difference is not currently observable. It is modelled honestly
     * anyway, for the reason CONVENTIONS.md gives: the printed card has one ability, the split would fire
     * twice for a single event that ever satisfied both patterns, and a reader of the definition would have
     * to reconstruct that the two entries are one printed line.
     *
     * **Not nested and not empty.** An empty disjunction matches nothing, which is a trigger that can never
     * fire and is therefore a defect rather than a card; a nested one would make the flattening rule
     * something `mtg-rules` has to decide, and no card prints it. Both fail loudly here.
     *
     * @property conditions the event patterns any one of which fires the ability, in printed order.
     */
    data class AnyOf(
        val conditions: PersistentList<TriggerCondition>,
    ) : TriggerCondition {
        init {
            require(conditions.size >= 2) {
                "CR 603.2: a disjunctive trigger condition names at least two event patterns, got $conditions"
            }
            require(conditions.none { it is AnyOf }) {
                "CR 603.2: a disjunctive trigger condition is not nested; flatten $conditions instead"
            }
        }
    }

    /**
     * "Whenever this permanent becomes the target of a spell or ability an opponent controls"
     * (CR 702.21a) — the condition ward's synthesized trigger carries. Additive, flagged core
     * (`FW-WARD`).
     *
     * **It fires while the targeting object is being put on the stack**, not when that object resolves:
     * CR 601.2c for a spell, CR 602.2b for an activated ability, CR 603.3d for a triggered one. For a
     * spell that is *before* CR 601.2g's payment — which is the whole of ward's tempo cost, since an
     * opponent who tapped out to cast the removal has nothing left to pay with.
     *
     * **No detector matches it against a printed ability list.** `mtg-rules` fires ward from the
     * [CardDefinition.ward] declaration at the three target-establishment sites, and this member is what
     * the fired record names as its condition. A card printing the clause longhand would need a detector
     * built for it; nothing in the pool does.
     */
    data object BecameTargetOfOpponentsSpellOrAbility : TriggerCondition
}
