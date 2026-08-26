package dev.mtgplay.core.definition

/**
 * Which battlefield permanents a [TargetSpec.TargetPermanent] may choose from (CR 115.1b) — the
 * noun half of "target nonlegendary creature", "target artifact", "target permanent". Additive,
 * flagged core (the removal-and-destruction packet).
 *
 * **Core/rules split (ADR-009).** This is the *declaration* of what a card's targeting line says;
 * `mtg-rules` owns deciding whether a given battlefield object satisfies it, which is why a
 * restriction that reads a *computed* characteristic ([CREATURE_POWER_2_OR_LESS]) is expressible
 * here without core knowing anything about the CR 613 layer system.
 *
 * A closed enum rather than a predicate for the same reason [EnchantRestriction] is one: a card
 * definition is data (ADR-003), the enumerator and the CR 608.2b re-check must agree by
 * construction, and a new restriction must break the rules-side `when` rather than slip through.
 * Members exist only where a card in the pool prints them.
 *
 * **Two members are decider-relative** ([PERMANENT_YOU_CONTROL], [CREATURE_AN_OPPONENT_CONTROLS]), and
 * that is why `satisfiesPermanentRestriction` takes the deciding player. Before them every member here
 * was a pure question about the object; these two are questions about the object *and* who is asking,
 * so the same battlefield offers different option lists to the two seats — the shape
 * [TargetSpec.TargetOpponent] and [GraveyardScope.YOURS] already had, arriving on the battlefield.
 */
enum class PermanentRestriction {
    /** "Target permanent" (CR 115.1b): any permanent on the battlefield. Scour from Existence. */
    ANY_PERMANENT,

    /** "Target creature" (CR 302): any creature on the battlefield. Terminate. */
    CREATURE,

    /**
     * "Target nonlegendary creature" (CR 302, CR 205.4): a creature whose printed supertypes do not
     * include legendary. Cast Down. The MVP pool prints no legendary card at all, so the exclusion
     * is currently vacuous in play — it is modelled anyway so the printed line is not quietly wrong,
     * and it is tested against a legendary fixture in `mtg-rules`.
     */
    NONLEGENDARY_CREATURE,

    /**
     * "Target creature with power 2 or less" (CR 115.1b, CR 208.1). Last Breath. The power read is
     * the creature's **in-game** power (CR 613 sublayer 7c), not its printed one, so a creature
     * pumped in response to the spell stops being a legal target and the spell fizzles at the
     * CR 608.2b re-check.
     */
    CREATURE_POWER_2_OR_LESS,

    /** "Target artifact" (CR 301): any artifact on the battlefield, including an artifact land.
     * Smash to Smithereens, Ancient Grudge. */
    ARTIFACT,

    /**
     * "Target land" (CR 115.1b, CR 305). Raze's destroy line. An artifact land satisfies this *and*
     * [ARTIFACT] — a permanent has every card type printed on it (CR 205.1a) — so this is a card-type
     * test rather than an exclusion.
     */
    LAND,

    /**
     * "Target **nonland** permanent" (CR 115.1b, CR 205.2b) — Deem Inferior's *"The owner of target
     * nonland permanent puts it into their library second from the top or on the bottom."* Additive,
     * flagged core (`W9-F`).
     *
     * **The pool's first restriction that is an *exclusion* of a card type rather than a demand for
     * one.** A permanent has every card type printed on it (CR 205.1a), so an artifact land is a land
     * and is **not** a legal choice here even though it is also an artifact — the exclusion reads all
     * of the object's types, not just its first. That is the difference between this and a hypothetical
     * "artifact, creature, or enchantment" disjunction, which would offer a Drossforge Bridge.
     *
     * A token is a nonland permanent and is a legal choice; the printed line does not care, and a token
     * put into a library ceases to exist as a state-based action (CR 704.5d) — which is a fine and
     * frequently correct use of the card.
     */
    NONLAND_PERMANENT,

    /**
     * "Target enchantment" (CR 303): any enchantment on the battlefield, Auras included. Thraben Charm's
     * "Destroy target enchantment" mode. Additive, flagged core.
     *
     * **An Aura is an enchantment** (CR 303.4), so every Aura in the pool — Rancor, Ethereal Armor,
     * Journey to Nowhere — is a legal choice here, which is the whole point of the card printing this
     * line against a GW-Bogles board. Nothing narrows the set to non-Aura enchantments and the card does
     * not ask for one.
     *
     * Hexproof (CR 702.11) is checked by the enumeration's own `targetableBy` gate rather than here, as
     * for every other restriction; no enchantment in the gauntlet has it, but a Bogles creature's
     * hexproof does **not** protect the Auras attached to it — the Aura is a separate permanent with its
     * own qualities, which is exactly why this mode is a real answer to that deck.
     */
    ENCHANTMENT,

    /**
     * "Target permanent you control" (CR 115.1b, CR 108.4). Tamiyo's Safekeeping. The first restriction
     * whose answer depends on **who is choosing**: the same board offers each seat only its own
     * permanents, so the deciding player is an input to `satisfiesPermanentRestriction` rather than a
     * property of the object.
     *
     * Control is read as ownership, the MVP pool's standing simplification (docs/design/layer-system.md
     * §4) — no card in the gauntlet changes control of a permanent, so the two coincide, and the
     * distinction becomes real the moment one does (CR 108.4 owner vs CR 109.4 controller).
     *
     * Hexproof (CR 702.11) never subtracts from this set: a permanent you control is targetable by you
     * whatever keywords it has, which is exactly what makes this the restriction a protective trick
     * prints.
     */
    PERMANENT_YOU_CONTROL,

    /**
     * "Target creature an opponent controls" (CR 115.1b, CR 102.1). Brinebarrow Intruder's
     * enters-the-battlefield trigger. The mirror of [PERMANENT_YOU_CONTROL] and decider-relative for
     * the same reason, narrowed to creatures (CR 302).
     *
     * Unlike [PERMANENT_YOU_CONTROL] this set **is** narrowed by hexproof, and not by this restriction:
     * the enumeration's own `targetableBy` gate already removes an opponent's hexproof creature from
     * anything the deciding player points at (CR 702.11), so a Slippery Bogle is not offered here and
     * this member does not need to say so.
     */
    CREATURE_AN_OPPONENT_CONTROLS,

    /**
     * "Target artifact or enchantment an opponent controls" (CR 115.1b, CR 205.2b, CR 102.1).
     * Troublemaker Ouphe's bargained enters-the-battlefield trigger. Additive, flagged core
     * (`FW-BARGAIN`).
     *
     * **A union over two card types *and* a control test**, which is why it is one member rather than a
     * pairing of [ARTIFACT] and [ENCHANTMENT]: CR 205.1a lets a permanent have several card types, so
     * "artifact or enchantment" is a disjunction — an artifact that is somehow also an enchantment
     * matches once — and the restriction vocabulary is closed data, with no `or` combinator to build one
     * out of the parts. [ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL] is the same shape pointed the other way.
     *
     * Narrowed by hexproof exactly as [CREATURE_AN_OPPONENT_CONTROLS] is, and for the same reason: the
     * enumeration's own `targetableBy` gate handles CR 702.11 before this member is consulted.
     */
    ARTIFACT_OR_ENCHANTMENT_AN_OPPONENT_CONTROLS,

    /**
     * "Target red permanent" (CR 105, CR 202.2). Blue Elemental Blast's destroy mode. Additive, flagged
     * core (`FW-MODAL`).
     *
     * The battlefield sibling of [SpellRestriction.OfColor]`(RED)`, and it carries that member's
     * caveat unchanged: colour is derived from the permanent's printed mana cost
     * ([dev.mtgplay.core.card.PrintedCharacteristics.colors]), which is correct for every card in the
     * gauntlet and would silently mis-answer a permanent whose colour comes from a CR 204 colour
     * indicator or from an effect. Notably a **land** is colourless (CR 105.2, no mana cost), so no
     * land is ever a legal choice here — which is the right answer for the Blasts and worth stating,
     * because "red permanent" reads as though a Mountain should qualify.
     *
     * An enum member per colour rather than a colour-carrying case, because [PermanentRestriction] is
     * an enum for the reason its KDoc gives and the pool prints exactly these two.
     */
    RED_PERMANENT,

    /** "Target blue permanent" (CR 105, CR 202.2). Red Elemental Blast's destroy mode. See
     * [RED_PERMANENT] for the colour derivation and its limits. */
    BLUE_PERMANENT,

    /**
     * "Target creature **you control**" (CR 115.1b, CR 109.5): a creature controlled by the player
     * doing the choosing. Ephemerate. Additive, flagged core (`FW-BLINK`,
     * docs/design/exile-and-return.md §2.1).
     *
     * The **first permanent restriction that is decider-relative** — its legal set depends on who is
     * casting or activating, not only on the board, exactly as [TargetSpec.TargetOpponent] and
     * [GraveyardScope.YOURS] already are for players and graveyards. CR 109.5 is explicit that "you"
     * in an object's text means the object's controller, so the same Ephemerate offers different
     * options in each seat's hand and the enumeration must be asked per chooser rather than cached.
     *
     * Control is ownership in the current pool (no control-changing effect exists), so `mtg-rules`
     * reads [dev.mtgplay.core.state.GameObject.owner]; the day a control-changing effect lands, this
     * is one of the sites that must start reading a real controller, and it says so here rather than
     * leaving the equivalence to be rediscovered.
     */
    CREATURE_YOU_CONTROL,

    /**
     * "Target **artifacts, creatures, and/or lands** you control" (CR 115.1b, CR 109.5) — Ghostly
     * Flicker's targeting line. Additive, flagged core (`FW-MULTITGT`'s second wave).
     *
     * The pool's **first disjunctive** restriction: three card types, any one of which qualifies
     * (CR 205.2b, a permanent may have several). Every restriction before it named one type or none, and
     * "and/or" in a targeting line is the CR's ordinary way of writing that disjunction — it does *not*
     * mean the two chosen targets must differ in type. Two lands is a legal Ghostly Flicker, and so is a
     * creature and an artifact; the only thing stopping the same permanent being named twice is
     * CR 601.2c, which is a property of the choice and not of this restriction
     * (docs/design/multi-target.md §3).
     *
     * Decider-relative, like [PERMANENT_YOU_CONTROL] and [CREATURE_YOU_CONTROL], and read the same way:
     * control is ownership while nothing in the gauntlet changes it (docs/design/layer-system.md §4).
     * Hexproof never subtracts from it for [PERMANENT_YOU_CONTROL]'s reason — you may always target your
     * own permanents (CR 702.11) — which matters because the UWX Familiar list that plays this card also
     * plays hexproof-granting effects.
     *
     * Narrower than [PERMANENT_YOU_CONTROL] by exactly the enchantments and the planeswalkers, and that
     * gap is observable: an Ephemerate deck's own Journey to Nowhere is a permanent it controls and is
     * *not* a legal Ghostly Flicker target, so the blink cannot be pointed at it to re-fire its exile.
     */
    ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL,

    /**
     * "Target **creature or Vehicle**" (CR 115.1b, CR 302, CR 301.7) — Ride's End's targeting line.
     * Additive, flagged core (`W8-C`).
     *
     * **The disjunction is a card type *or* a subtype**, which no restriction before it mixed: a creature
     * qualifies by [dev.mtgplay.core.card.CardType.CREATURE], a Vehicle by the artifact subtype Vehicle
     * (CR 301.7), and a crewed Vehicle — an artifact that has *become* a creature — qualifies twice over.
     * [ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL] is a disjunction over card types alone and cannot say this.
     *
     * **The Vehicle half is currently vacuous in play and is modelled anyway**, the same call
     * [NONLEGENDARY_CREATURE] records: no gauntlet card prints the Vehicle subtype, so today this is
     * behaviourally [CREATURE]. Collapsing it to [CREATURE] would print a line the card does not have and
     * would be silently wrong the first time an uncrewed Vehicle sits on the battlefield — precisely the
     * board Ride's End is printed to answer, since an uncrewed Vehicle is not a creature and no ordinary
     * removal spell can point at it.
     *
     * The subtype is read **printed** (CR 205.3), like every other subtype test in the engine; crew
     * (CR 702.122) is a layer-4 type-changing effect the engine does not have, and when it lands this is
     * one of the sites that must route through the layer system.
     */
    CREATURE_OR_VEHICLE,
}
