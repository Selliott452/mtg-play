package dev.mtgplay.core.definition

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.mana.Color
import kotlinx.collections.immutable.PersistentSet

/**
 * Which spells on the stack a [TargetSpec.SpellOnStack] may choose from (CR 115.1) — the noun half of
 * "counter target *instant* spell", "counter target *noncreature* spell", "counter target *artifact or
 * enchantment* spell". Additive, flagged core (`FW-COUNTER`, docs/design/countering-spells.md §6).
 *
 * **Core/rules split (ADR-009).** This is the *declaration* of what a card's targeting line says;
 * `mtg-rules` owns deciding whether a given stack object satisfies it, reading the spell's
 * characteristics through its own accessor so a future type- or colour-changing effect (CR 613 applies
 * to spells on the stack too) reaches every restriction at once. The same split
 * [PermanentRestriction]/[EnchantRestriction] already make.
 *
 * **These restrict *targeting*, not the effect.** A spell that does not satisfy the restriction is not
 * a legal target at all, so it can never be chosen (ADR-005 exclusion) and a spell that *stops*
 * satisfying it makes the counter fizzle (CR 608.2b). The other shape — "counter target spell **if**
 * it's red" (Hydroblast, Pyroblast) — is an unrestricted target with a conditional *effect*, and is
 * deliberately **not** expressible here: conflating the two produces a gap in enumeration completeness,
 * which is the single finding docs/design/countering-spells.md §1.2 most warns about.
 *
 * A closed member list rather than an `And`/`Or`/`Not` combinator algebra, for the reason
 * [PermanentRestriction] is a closed enum: the enumerator and the CR 608.2b re-check must agree by
 * construction, and a new restriction must break the rules-side `when` rather than slip through.
 * [NotOfCardType] is therefore a real member rather than a generic negation — "noncreature" is the only
 * negation the pool prints, and an algebra with one client is speculative structure. Sealed rather than
 * an enum because two members carry data.
 */
sealed interface SpellRestriction {
    /** "Counter target spell" (CR 115.1): every spell on the stack qualifies. Counterspell, Force Spike. */
    data object Any : SpellRestriction

    /**
     * "Counter target &lt;type&gt; spell" (CR 205.2): a spell whose card types include [cardType].
     * Dispel ([CardType.INSTANT]), Envelop ([CardType.SORCERY]), Remove Soul ([CardType.CREATURE]).
     *
     * @property cardType the card type the spell must have.
     */
    data class OfCardType(
        val cardType: CardType,
    ) : SpellRestriction

    /**
     * "Counter target non&lt;type&gt; spell" (CR 205.2): a spell whose card types do **not** include
     * [cardType]. Negate and Spell Pierce ("noncreature", [CardType.CREATURE]).
     *
     * Note this is not the complement of [OfCardType] over the *pool* but over the *stack*: an artifact
     * creature spell is excluded by `NotOfCardType(CREATURE)` and included by `OfCardType(ARTIFACT)`,
     * because a card has a set of types, not one.
     *
     * @property cardType the card type the spell must not have.
     */
    data class NotOfCardType(
        val cardType: CardType,
    ) : SpellRestriction

    /**
     * "Counter target &lt;type&gt; or &lt;type&gt; spell" (CR 205.2): a spell whose card types include at
     * least one of [cardTypes]. Annul ([CardType.ARTIFACT] or [CardType.ENCHANTMENT]).
     *
     * @property cardTypes the card types, any one of which qualifies the spell; never empty.
     */
    data class OfAnyCardType(
        val cardTypes: PersistentSet<CardType>,
    ) : SpellRestriction {
        init {
            require(cardTypes.isNotEmpty()) {
                "CR 115.1: an \"of any card type\" restriction names at least one card type"
            }
        }
    }

    /**
     * "Counter target &lt;colour&gt; spell" (CR 105, CR 202.2): a spell that **is** [color]. Blue
     * Elemental Blast ([Color.RED]), Red Elemental Blast ([Color.BLUE]).
     *
     * **A spell's colour is derived from its mana cost here** ([dev.mtgplay.core.card.PrintedCharacteristics.colors]),
     * which is correct for every card in the gauntlet and silently wrong for a card whose colour is a
     * colour indicator (CR 204) or is set by an effect. Recorded rather than hidden:
     * docs/design/countering-spells.md §5 flags it as the first counter predicate over a *derived*
     * characteristic.
     *
     * @property color the colour the spell must be.
     */
    data class OfColor(
        val color: Color,
    ) : SpellRestriction
}
