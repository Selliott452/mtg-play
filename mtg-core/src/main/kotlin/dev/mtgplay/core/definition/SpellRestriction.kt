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

    /**
     * "Counter target spell with mana value X or less" (CR 115.1, CR 202.3). Spellstutter Sprite.
     * Additive, flagged core (`P-ABILSOURCE`'s target-noun half).
     *
     * **The first restriction whose bound is computed from the board rather than printed**, which is
     * why it takes a [ManaValueBound] instead of an `Int`. Spellstutter Sprite's X is "the number of
     * Faeries you control", so the same ability offers a different option list on every board and to
     * every seat — and the bound must be re-read at the CR 608.2b re-check, not cached from the
     * CR 603.3d choice, because a Faerie dying in response genuinely makes an already-chosen target
     * illegal. Routing the answer through the enumeration is what gets that for free (ADR-005).
     *
     * **Mana value is the *spell's*, not the card's, wherever the two differ** — but they do not
     * differ for cost: CR 202.3b is explicit that an alternative or additional cost does not change
     * mana value, so a Fiery Temper cast for its madness cost is still mana value 3. X in a *cost* on
     * the stack would be a real divergence, and no card in the gauntlet prints one.
     *
     * @property bound the inclusive upper bound on the target spell's mana value.
     */
    data class OfManaValueAtMost(
        val bound: ManaValueBound,
    ) : SpellRestriction
}

/**
 * The upper bound of a [SpellRestriction.OfManaValueAtMost] (CR 202.3) — a printed number, or one
 * counted off the board as the restriction is evaluated.
 *
 * Separate from [CostReduction] despite reusing its [CountScope]/[ObjectPredicate] counting vocabulary,
 * because the two answer different questions at different times: a cost reduction is read once at
 * CR 601.2f and fixes a cost, while this is read at every target enumeration and can change between the
 * choice and the resolution. Sharing the *nouns* and not the *type* is what keeps that distinction
 * visible.
 */
sealed interface ManaValueBound {
    /** A printed number: "with mana value 2 or less" (Prohibit's first mode). */
    data class Fixed(
        val value: Int,
    ) : ManaValueBound {
        init {
            require(value >= 0) { "CR 202.3: a mana value bound is non-negative, was $value" }
        }
    }

    /**
     * "…where X is the number of \[objects] you control" — Spellstutter Sprite's "the number of Faeries
     * you control", counted at the moment the restriction is evaluated.
     *
     * **The source counts itself** where it matches, and no exclusion parameter exists to prevent it:
     * Spellstutter Sprite is a Faerie and is on the battlefield when its own enters-the-battlefield
     * trigger chooses targets (CR 603.6a fires *after* it enters), so a lone Sprite counters a
     * one-drop. That is the printed card, and it is the opposite of the cast-time cost reduction, which
     * *must* exclude the card being cast because that card is not yet on the battlefield.
     *
     * @property scope which zone the objects are counted in.
     * @property predicate which objects in that zone count.
     */
    data class PerMatching(
        val scope: CountScope,
        val predicate: ObjectPredicate,
    ) : ManaValueBound
}
