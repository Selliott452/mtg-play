package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaCost

/**
 * One alternative way a card may be cast, beyond a normal cast from the hand — the unifying data
 * behind the four "cast-from-elsewhere" mechanics of the MVP pool (madness, flashback, plot, escape;
 * docs/decklists.md). Additive, flagged core (P5.2). Card-definition *declaration*; `mtg-rules` owns
 * when a permission is legal, enumerates it (ADR-005), and runs the cast pipeline from its
 * [source] zone.
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** A permission states its
 * alternative [cost] (which *replaces* the printed mana cost, CR 118.9 / CR 601.2f), its source
 * zone, its additional non-mana cost, and the replacement it rides (below); the engine decides
 * whether it can be used right now and executes it.
 *
 * Sealed so the enumerator and cast pipeline handle every permission exhaustively; the plot slot
 * (cast from exile for free at sorcery speed, CR 702.140) is the documented extension point — it adds
 * a member here and its enumeration case, without reshaping this contract (P6).
 *
 * @property cost the alternative mana cost this permission casts for (CR 118.9): it *replaces* the
 *   card's printed mana cost entirely, never adds to it.
 * @property source which zone the cast draws the card from (CR 601.2a).
 */
sealed interface CastingPermission {
    val cost: ManaCost
    val source: CastSource

    /**
     * How many *other* cards must be exiled from the [source] zone as an additional cost of casting
     * this way (CR 601.2b, CR 118.9) — escape's "exile N other cards from your graveyard"; `0` for a
     * permission with no such additional cost (madness, flashback). The engine surfaces the selection
     * decision and performs the exile during payment (CR 601.2h).
     */
    val additionalExileCount: Int get() = 0

    /**
     * A non-mana sacrifice cost component of casting this way (CR 601.2h), or `null` for a permission
     * with none (madness, escape, plain flashback). Lava Dart's flashback ([Flashback.sacrifice]) is
     * "Sacrifice a Mountain"; Fireblast's [AlternativeCost] is "sacrifice two Mountains rather than pay
     * the mana cost". The engine surfaces the selection decision (which matching permanents) and
     * performs the sacrifice during payment (CR 601.2h), alongside the mana [cost].
     */
    val sacrifice: SacrificeRequirement? get() = null

    /**
     * A non-mana **tap** cost component of casting this way (CR 601.2h, CR 702.34c), or `null` for a
     * permission with none (every permission but Prismatic Strands' flashback). Additive, flagged core
     * (`FW-PREVENT2`). Prismatic Strands' is "Tap an untapped white creature you control".
     *
     * The sibling of [sacrifice], and a second field rather than a widening of it because the two costs
     * differ in the one way that matters: a sacrificed permanent is *gone* and a tapped one is alive,
     * still on the battlefield, and untaps next turn. Encoding either as the other would be a
     * plausible-looking wrong card (PLAN.md §7). They are also filtered on different axes —
     * [SacrificeRequirement] is keyed on a printed subtype and cannot say "white" — and nothing stops a
     * future permission carrying both, which a single field would.
     *
     * The engine surfaces the selection decision (which untapped matching permanents) and taps them
     * during payment (CR 601.2h), alongside the mana [cost].
     */
    val tap: TapRequirement? get() = null

    /**
     * A condition on the game state that must hold for this permission to be usable (CR 118.9), or
     * `null` for a permission that is always available once its card is in the right zone. Additive,
     * flagged core (`FW-ALTCOST`). Land Grant's "If you have no land cards in hand".
     *
     * **The first state-conditional member of this interface**, and deliberately a declaration rather
     * than a predicate: `mtg-rules` evaluates it and excludes the permission from enumeration when it
     * fails, so a permission a seat can see is one it can complete (ADR-005). See [CastCondition] for
     * why the shape is closed and what ADR-007 has to say about reading a hidden hand.
     *
     * Every permission that predates this one gates on *where the card is* — a marker the engine reads
     * off the object itself — so the two kinds of gate are checked in different places and neither
     * subsumes the other.
     */
    val condition: CastCondition? get() = null

    /**
     * Whether paying this permission's cost requires the caster to **reveal their hand** (CR 701.16a),
     * as a non-mana component of the alternative cost. Additive, flagged core (`FW-ALTCOST`). `false`
     * for every permission but Land Grant's, whose whole printed cost is "reveal your hand".
     *
     * **A third kind of non-mana cost component, and not expressible as either existing one.**
     * [sacrifice] names permanents to destroy and [additionalExileCount] names cards to exile; this
     * consumes nothing and moves nothing. What it does is *publish* information — the hand becomes
     * known to every player (CR 701.16a) — which is why it is a `Boolean` here and an emitted event in
     * `mtg-rules` rather than a selection: there is nothing to choose, the whole hand is revealed, and
     * the reveal is momentary. It needs no decision point and opens no pause.
     *
     * It is paid at CR 601.2h alongside the mana, and — unlike a sacrifice — it can never fail: a
     * player with an empty hand reveals an empty hand, which is a legal payment of this cost. The
     * *condition* that gates the permission is a separate field ([condition]) for exactly that reason.
     */
    val revealsHand: Boolean get() = false

    /**
     * Whether a spell cast via this permission is **exiled instead of** being put into a graveyard as
     * it leaves the stack (CR 702.34e / CR 614) — the flashback replacement, covering resolution,
     * countering, and fizzling. `false` for madness, escape, and plot: their spells leave the stack
     * by the ordinary rules.
     */
    val exilesOnLeaveStack: Boolean get() = false

    /**
     * Whether this permission is offered as a priority-window cast option (CR 117.1a) — `true` for
     * flashback, escape, and plot, each cast when the caster has priority. `false` for madness, whose
     * cast is offered only as its reflexive triggered ability resolves (CR 702.35b), never at a plain
     * priority window; see the reflexive-trigger flow in `mtg-rules`.
     */
    val offeredAtPriority: Boolean get() = true

    /**
     * Madness (CR 702.35): a card with madness that would be discarded is exiled instead
     * ([DiscardToExileInstead], the CR 702.35a replacement declared separately on the definition), and
     * its owner may then cast it from exile for its madness [cost] as a reflexive triggered ability
     * resolves (CR 702.35b). Cast from [CastSource.EXILE]; not offered at a plain priority window.
     */
    data class Madness(
        override val cost: ManaCost,
    ) : CastingPermission {
        override val source: CastSource = CastSource.EXILE
        override val offeredAtPriority: Boolean = false
    }

    /**
     * Flashback (CR 702.34): the card may be cast from the graveyard for its flashback [cost] plus an
     * optional non-mana [sacrifice] cost component (CR 702.34c — a flashback cost may include more than
     * mana), and if that spell would leave the stack it is exiled instead (CR 702.34e —
     * [exilesOnLeaveStack]). Cast from [CastSource.GRAVEYARD] at the card's own timing. Faithless
     * Looting's flashback is `Flashback({2}{R})`; Lava Dart's is `Flashback({0}, sacrifice a Mountain)`.
     *
     * @property sacrifice the sacrifice part of the flashback cost (Lava Dart's "Sacrifice a Mountain"),
     *   or `null` when the flashback cost demands no sacrifice.
     * @property tap the tap part of the flashback cost (Prismatic Strands' "Tap an untapped white
     *   creature you control"), or `null` when it demands no tap. CR 702.34c is explicit that a
     *   flashback cost may include more than mana, and the gauntlet prints both non-mana shapes.
     */
    data class Flashback(
        override val cost: ManaCost,
        override val sacrifice: SacrificeRequirement? = null,
        override val tap: TapRequirement? = null,
    ) : CastingPermission {
        override val source: CastSource = CastSource.GRAVEYARD
        override val exilesOnLeaveStack: Boolean = true
    }

    /**
     * A generic alternative cost cast from the hand (CR 118.9, CR 601.2f): the card may be cast for
     * [cost] plus an optional [sacrifice] or hand [revealsHand] reveal instead of its printed mana
     * cost, and only while [condition] holds. Fireblast's "You may sacrifice two Mountains rather than
     * pay this spell's mana cost" is `AlternativeCost({0}, sacrifice = two Mountains)`; Land Grant's
     * "If you have no land cards in hand, you may reveal your hand rather than pay this spell's mana
     * cost" is `AlternativeCost({0}, condition = NoLandCardsInHand, revealsHand = true)`. Offered at a
     * priority window like a normal cast (the same card is also castable normally, a distinct option);
     * the alternative cost replaces the printed mana cost entirely (CR 118.9).
     *
     * **The two fields `FW-ALTCOST` added are independent, and Land Grant needs both.** A permission
     * could in principle reveal without a gate, or be gated without revealing; conflating them into one
     * "Land Grant mode" flag would make the next card of either half unencodable. What the card
     * demonstrates is that the pre-`FW-ALTCOST` shape — mana plus an optional sacrifice, always
     * available — was two gaps rather than one.
     *
     * @property sacrifice the non-mana part of the alternative cost (Fireblast's two Mountains), or
     *   `null` when the alternative cost demands no sacrifice.
     * @property condition the state condition gating this permission (Land Grant's "no land cards in
     *   hand"), or `null` when it is always available.
     * @property revealsHand whether paying the cost reveals the caster's hand (CR 701.16a).
     */
    data class AlternativeCost(
        override val cost: ManaCost,
        override val sacrifice: SacrificeRequirement? = null,
        override val condition: CastCondition? = null,
        override val revealsHand: Boolean = false,
    ) : CastingPermission {
        override val source: CastSource = CastSource.HAND
    }

    /**
     * Evoke (CR 702.74): the card may be cast from the hand for its evoke [cost] instead of its mana
     * cost, and the permanent it becomes is sacrificed when it enters. Mulldrifter's `Evoke {2}{U}`.
     * Additive, flagged core (`W8-D`).
     *
     * **Not [AlternativeCost] with a different label, and the difference is a whole trigger.** CR 702.74a
     * spells the keyword out as two abilities: a static one that permits the cheap cast (which
     * [AlternativeCost] does model) *and* a triggered one — "When this permanent enters, if its evoke
     * cost was paid, sacrifice it." Casting a card via an [AlternativeCost] leaves no mark on the
     * permanent it becomes, because a spell and the permanent it becomes are different objects
     * (CR 400.7); an evoked creature must know it was evoked a moment after it stops being a spell. So
     * the permission is its own member, the engine carries the fact onto the entering object as
     * [dev.mtgplay.core.state.GameObject.evokedWhenCast], and the card declares the trigger with an
     * [InterveningIf.SourceWasEvoked] clause. Reusing [AlternativeCost] would have produced a Mulldrifter
     * that draws two cards for `{2}{U}` and *stays on the battlefield* — a plausible-looking wrong card
     * (PLAN.md §7).
     *
     * **The sacrifice does not replace the card's other enters-the-battlefield triggers.** Both of
     * Mulldrifter's fire from the same event and go on the stack together, and their order is its
     * controller's choice (CR 603.3b) — an enumerated decision, and a real one: the draw resolves either
     * way, but the ordering decides whether anything can respond to a Mulldrifter that is still on the
     * battlefield.
     *
     * **The sacrifice is a trigger, so it uses the stack and can be responded to** (CR 603.3), which is
     * why it is not modelled as a leave-stack replacement the way flashback's exile is. It is also the
     * reason [exilesOnLeaveStack] stays `false`: an evoked spell resolves normally into a permanent, and
     * the card reaches its owner's graveyard by the sacrifice, not off the stack.
     */
    data class Evoke(
        override val cost: ManaCost,
    ) : CastingPermission {
        override val source: CastSource = CastSource.HAND
    }

    /**
     * Prototype (CR 702.160, CR 718): the card may be cast from the hand for its **prototyped** [cost]
     * instead of its printed mana cost, and the spell — and the permanent it becomes — then has the
     * card's *alternative* set of mana cost, colour, power and toughness (CR 718.3b). Boulderbranch
     * Golem's `Prototype {3}{G} — 3/3`. Additive, flagged core (`W9-G`).
     *
     * **The only permission that changes what the spell *is*, not merely what it costs**, which is why
     * it cannot be an [AlternativeCost] with a different label. Every other member of this interface
     * replaces a cost and leaves the object alone: a Fiery Temper cast for its madness cost is still a
     * red spell with mana value 3 ([dev.mtgplay.core.definition.SpellDefinition] and the stack-
     * characteristics seam in `mtg-rules` both say so). A prototyped Boulderbranch Golem is a **green**
     * spell with mana value 4 that becomes a **3/3**, and the card's own "you gain life equal to its
     * power" reads 3 rather than 6. Encoding it as an alternative cost would produce a `{3}{G}` 6/5 —
     * a plausible-looking wrong card (PLAN.md §7), and wrong in the direction that makes the card
     * strictly better than it is printed.
     *
     * **Not a CR 613 continuous effect, and this is the correction worth carrying forward.**
     * docs/gauntlet-deferred-ten.md filed prototype as "a CR 613 layer 1/7b effect keyed to how the
     * spell was cast", which would make it depend on the layer system's type/P-T slots. CR 718.2a says
     * the opposite: *"The existence and values of these alternative characteristics are part of the
     * object's copiable values"* — so a prototyped object has no layer effect applied to it at all, it
     * simply has a different **base**. That is why the engine's change is one base-characteristics seam
     * rather than a new layer, and why no counter, no aura and no pump interacts with it specially.
     *
     * **The permanent must know**, because a spell and the permanent it becomes are different objects
     * (CR 400.7) — [kickedWhenCast]'s situation exactly, one keyword over. `mtg-rules` carries the fact
     * onto the entering object as [dev.mtgplay.core.state.GameObject.prototyped], and every read of the
     * permanent's base characteristics goes through it.
     *
     * Only power, toughness and mana cost are alternative (CR 702.160a): the card keeps its printed
     * name, supertypes, card types, subtypes, keywords and every ability, which is why this carries
     * three values and not a whole [dev.mtgplay.core.card.PrintedCharacteristics].
     *
     * @property cost the prototyped mana cost (CR 718.2), which is also what gives the prototyped
     *   object its colours (CR 718.3b, CR 105.2) and its mana value (CR 202.3).
     * @property power the prototyped power (CR 718.2).
     * @property toughness the prototyped toughness (CR 718.2).
     */
    data class Prototype(
        override val cost: ManaCost,
        val power: Int,
        val toughness: Int,
    ) : CastingPermission {
        override val source: CastSource = CastSource.HAND
    }

    /**
     * Cascade (CR 702.85): the card was exiled by a resolving cascade ability and its controller may now
     * cast it **without paying its mana cost** ([cost] is `{0}`) from [CastSource.EXILE]. Additive,
     * flagged core (`W9-G`); Maelstrom Colossus's cascade is what exiles it.
     *
     * **The one permission a card never declares about itself.** Every other member is printed on the
     * card it lets you cast — a Fiery Temper carries its own madness cost — so `mtg-rules` finds it by
     * reading [SpellDefinition.castingPermissions] of the card in question. Cascade's is granted by a
     * *different* spell's triggered ability to whatever happened to come off the top of a library, so
     * this permission is handed to the cast pipeline directly by the resolving cascade ability and is
     * never declared anywhere. [offeredAtPriority] is `false` for that reason as well as madness's: the
     * cast happens as the cascade ability resolves (CR 702.85a) and a card sitting in exile is not
     * castable on demand.
     *
     * Note what [cost] `{0}` does **not** mean. CR 702.85a's "without paying its mana cost" also fixes
     * the value of any variable in that cost at zero (CR 601.2b), which falls out here because a `{0}`
     * carries no [dev.mtgplay.core.mana.ManaSymbol.X] to announce; and CR 702.85a's own proviso —
     * "if the resulting spell's mana value is less than this spell's mana value" — is then automatically
     * satisfied, because the exiled card was chosen by that same comparison and X adds nothing to it.
     */
    data object Cascade : CastingPermission {
        // CR 702.85a: cast without paying its mana cost — a {0} cost yields a single empty payment plan.
        override val cost: ManaCost = ManaCost.parse("{0}")
        override val source: CastSource = CastSource.EXILE
        override val offeredAtPriority: Boolean = false
    }

    /**
     * Escape (CR 702.139): the card may be cast from the graveyard for its escape [cost] plus an
     * additional cost of exiling [additionalExileCount] *other* cards from the graveyard. A permanent
     * cast this way resolves onto the battlefield normally (no leave-stack replacement) and behaves as
     * an ordinary permanent thereafter. Cast from [CastSource.GRAVEYARD] at the card's own timing.
     *
     * @property exileOthers how many other graveyard cards the additional cost exiles (CR 702.139a);
     *   Sentinel's Eyes is two (architect-verified).
     */
    data class Escape(
        override val cost: ManaCost,
        val exileOthers: Int,
    ) : CastingPermission {
        override val source: CastSource = CastSource.GRAVEYARD
        override val additionalExileCount: Int = exileOthers
    }

    /**
     * Plot (CR 702.140): the card was plotted — paid its [plotCost] and exiled from hand face-up as a
     * special action ([dev.mtgplay.core.state.GameObject.plottedTurn] records the turn) — and may now be
     * cast **without paying its mana cost** ([cost] is `{0}`) from [CastSource.EXILE], but only as a
     * sorcery and **not the turn it was plotted** (`mtg-rules` checks the plotted-turn marker against the
     * current turn). Highway Robbery's is `Plot({1}{R})`.
     *
     * The [plotCost] is paid when the card is plotted (the plot special action), not when it is later
     * cast; the free cast is what this permission enumerates from exile.
     *
     * @property plotCost the mana cost paid to plot the card (CR 702.140a), e.g. Highway Robbery's `{1}{R}`.
     */
    data class Plot(
        val plotCost: ManaCost,
    ) : CastingPermission {
        // CR 702.140: cast without paying its mana cost — a {0} cost yields a single empty payment plan.
        override val cost: ManaCost = ManaCost.parse("{0}")
        override val source: CastSource = CastSource.EXILE
    }

    /**
     * Rebound (CR 702.88b): the spell was cast from its owner's hand, exiled itself instead of going to
     * the graveyard as it resolved ([dev.mtgplay.core.state.GameObject.reboundTurn] records the turn),
     * and its delayed ability is now offering a free cast from exile at the beginning of the
     * controller's next upkeep. Cast **without paying its mana cost** ([cost] is `{0}`) from
     * [CastSource.EXILE]. Ephemerate's. Additive, flagged core (`FW-BLINK`,
     * docs/design/exile-and-return.md §5).
     *
     * `offeredAtPriority` is `false` for exactly madness's reason (CR 702.88b): the cast is offered only
     * as the delayed triggered ability resolves, never at a plain priority window — a rebounding card
     * sitting in exile is not castable on demand, and enumerating it at priority would let a seat cast
     * it on any turn it liked.
     *
     * A rebound cast is a cast **from exile**, so [SpellDefinition.rebound]'s own "if this spell was cast
     * from your hand" condition is false for it and the spell goes to the graveyard this time. That is
     * how CR 702.88a stops the loop, and it needs no separate guard.
     */
    data object Rebound : CastingPermission {
        // CR 702.88b: cast without paying its mana cost — a {0} cost yields a single empty payment plan.
        override val cost: ManaCost = ManaCost.parse("{0}")
        override val source: CastSource = CastSource.EXILE
        override val offeredAtPriority: Boolean = false
    }

    /**
     * Adventure (CR 715.3): the card is being cast as the Adventure printed in its inset frame — a
     * *sorcery or instant* with [faceName]'s own name, type line, targeting and resolution, for
     * [cost] instead of the card's printed one. Cast from [CastSource.HAND]. Additive, flagged core
     * (`W10-B`); Fang Dragon's *Forktail Sweep*.
     *
     * **The only permission that changes what the spell *does*.** [Prototype] was the first to change
     * what a spell *is* (CR 718.3b — a different cost, colour and size), and its KDoc is careful that
     * the card "keeps its printed name, supertypes, card types, subtypes, keywords and every ability".
     * An Adventure keeps none of those: CR 715.3b says the spell "has **only** its alternative
     * characteristics", so a Fang Dragon cast as Forktail Sweep is a `{1}{R}` Sorcery that sweeps the
     * board and is not a creature spell at all. That is why the face lives on the card
     * ([SpellDefinition.alternativeFace]) as a whole second definition, and why this permission is only
     * the *pointer* at it.
     *
     * **Cost and name and nothing else, because the wire has to carry it.** A permission travels to a
     * remote seat as an enumerated option and comes back (ADR-005, the protocol's `PriorityOptionDto`);
     * a permission carrying resolution effects — function values — could not round-trip. These two
     * fields are what a seat needs to tell "Cast Fang Dragon" from "Cast Forktail Sweep", and both are
     * derived by the engine from the declared face, so they cannot disagree with it.
     *
     * **What resolution does with it (CR 715.3d).** An Adventure spell that *resolves* is exiled
     * instead of being put into its owner's graveyard, marked
     * [dev.mtgplay.core.state.GameObject.onAnAdventure], and that player may then play the card's
     * **normal** half from exile — never the Adventure again. A countered or fizzled Adventure goes to
     * the graveyard like any other spell, which is why this is decided by the resolution path rather
     * than by [exilesOnLeaveStack] (rebound's distinction exactly, one keyword over).
     *
     * @property cost the face's printed mana cost (CR 715.3a), which replaces the card's (CR 118.9).
     * @property faceName the face's printed name (CR 201, CR 715.5) — what the spell on the stack is
     *   called, and what a seat sees in the enumerated option.
     */
    data class Adventure(
        override val cost: ManaCost,
        val faceName: String,
    ) : CastingPermission {
        override val source: CastSource = CastSource.HAND
    }

    /**
     * Omen (CR 720.3): the card is being cast as the Omen printed in its inset frame — a *sorcery or
     * instant* with [faceName]'s own name, type line, targeting and resolution, for [cost] instead of
     * the card's printed one. Cast from [CastSource.HAND]. Additive, flagged core (`W10-B`); Sagu
     * Wildling's *Sagu Wilds*.
     *
     * [Adventure]'s twin in every respect but one, and the one is the point: **an Omen spell that
     * resolves is shuffled into its owner's library** (CR 720.3d) rather than exiled. There is no later
     * cast, no exile marker and no permission from exile — the card goes back into the deck and may be
     * drawn again. That makes an Omen the cheaper half of the two-faces framework and, for a seat,
     * a genuinely different decision: an Adventure banks the creature for later, an Omen spends it.
     *
     * A separate member rather than a flag on [Adventure] because CR 715 and CR 720 are separate rules
     * with separate spell types, and because the difference decides which *zone* the card ends in —
     * the one thing a permission's identity most needs to be honest about.
     *
     * @property cost the face's printed mana cost (CR 720.3a), which replaces the card's (CR 118.9).
     * @property faceName the face's printed name (CR 201, CR 720.5).
     */
    data class Omen(
        override val cost: ManaCost,
        val faceName: String,
    ) : CastingPermission {
        override val source: CastSource = CastSource.HAND
    }

    /**
     * Bestow (CR 702.103): the creature card may be cast **as an Aura spell with enchant creature**, for
     * its bestow [cost], from [CastSource.HAND]. Additive, flagged core (`W10-C`) — Nyxborn Hydra's
     * "Bestow `{X}{G}{G}`".
     *
     * **The one permission that changes what the spell *is*, not merely what it costs.** Every other
     * member here answers "where is the card and what does it cost"; this one also answers "what kind of
     * spell is it" (CR 702.103b: a card cast for its bestow cost is an Aura spell) and therefore "what
     * does it target" — an Aura spell targets the permanent it will enchant (CR 601.2c, CR 303.4a),
     * where the same card cast normally is a creature spell that targets nothing. That is why
     * `mtg-rules` reads the targeting line in force through the *permission* since this member existed:
     * one card, two target specs, decided by how it was cast.
     *
     * **What it becomes on the battlefield is a static ability, not this permission** (CR 702.103a's
     * third ability): "as long as this permanent is attached to a creature, it's an Aura enchantment
     * and not a creature", applied in CR 613 layer 4 and re-evaluated continuously. So a bestowed
     * permanent whose host leaves *stops being an Aura and becomes a creature* (CR 702.103c) rather
     * than being put into a graveyard — which is the whole of what makes bestow not an ordinary Aura,
     * and which falls out of the layer system rather than needing a special case: the moment the
     * condition fails the permanent is a creature, so CR 704.5m has no Aura to act on. The card
     * declares that ability itself, as a [StaticContinuousEffect] conditioned on
     * [StaticCondition.AttachedToCreature].
     *
     * **The enchant restriction is fixed at "creature" and is therefore not a property here.**
     * CR 702.103b writes it into the keyword: every bestow card ever printed is an Aura *with enchant
     * creature*, exactly as CR 301.5b gives every Equipment the same host requirement. A per-card
     * restriction would be a field no printing sets.
     *
     * [offeredAtPriority] is `true`: bestow is an alternative cost for a card in hand, offered whenever
     * that card could be cast, so both ways of casting a Hydra are enumerated side by side (ADR-005).
     * Its timing is the card's own — a creature card, so sorcery speed — because CR 702.103b changes
     * the spell's type and not when it may be cast.
     */
    data class Bestow(
        override val cost: ManaCost,
    ) : CastingPermission {
        override val source: CastSource = CastSource.HAND
    }
}
