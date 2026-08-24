package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CounterUnlessPaid
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.counterSpell
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's pure counters (`FW-COUNTER`, docs/design/countering-spells.md F1.2/F1.3): the six that
 * simply counter, and the two that counter unless the target's controller pays.
 *
 * Every one of them is the same card with a different targeting line, which is the whole point of the
 * framework — the restriction lives in [SpellRestriction] and the effect is one composed primitive
 * ([counterSpell]), so a new counter is a `SpellRestriction` value and nothing else. `mtg-rules` never
 * names one of these cards (ADR-003).
 *
 * **Restriction, never condition.** All eight restrict what they may *target*, so an ineligible spell is
 * never offered (ADR-005) and a spell that stops qualifying makes the counter fizzle (CR 608.2b). The
 * other shape — "counter target spell **if** it's red" (Hydroblast, Pyroblast) — is an unrestricted
 * target with a conditional effect, is not expressible as a restriction, and is deliberately not here
 * (docs/design/countering-spells.md §1.2). Nor are the modal Blasts and Steel Sabotage (CR 700.2, no mode
 * machinery yet), Prohibit (kicker, CR 702.33), or Spellstutter Sprite (a *triggered* ability that
 * targets, with a dynamic restriction).
 *
 * Oracle text below is Scryfall's, fetched for this packet; where it disagreed with the design note or
 * the upstream brief, the oracle text won.
 */

/**
 * Counterspell — `{U}{U}` Instant. "Counter target spell."
 *
 * The reference card of the framework, and the only one whose restriction is [SpellRestriction.Any]. Two
 * things it demonstrates that no other card does. It may target **any** spell on the stack, including
 * another Counterspell — the counter war of docs/design/countering-spells.md §12, in which the countered
 * counter's own effect never runs and the spell at the bottom resolves. And it may not target *itself*:
 * the enumeration excludes the choosing object, so the set a caster picks from (the card still in hand)
 * and the set CR 601.2c re-validates against (the card now on the stack) are the same set.
 */
val counterspell: SpellDefinition = pureCounter("Counterspell", "{U}{U}", SpellRestriction.Any)

/**
 * Dispel — `{U}` Instant. "Counter target instant spell."
 *
 * [SpellRestriction.OfCardType]`(INSTANT)`. Uncastable — absent from the priority window entirely
 * (ADR-005) — while no instant is on the stack, which is the ordinary state of affairs and exactly why a
 * card like this lives in a sideboard.
 */
val dispel: SpellDefinition = pureCounter("Dispel", "{U}", SpellRestriction.OfCardType(CardType.INSTANT))

/**
 * Negate — `{1}{U}` Instant. "Counter target noncreature spell."
 *
 * [SpellRestriction.NotOfCardType]`(CREATURE)` — the pool's only negation, and the reason
 * [SpellRestriction] has a `NotOfCardType` member rather than a `Not` combinator. Note it is a test of
 * the spell's *type set* (CR 205.2), not of its category: an artifact creature spell is noncreature to
 * nobody, so Negate cannot touch one while [annul] can.
 */
val negate: SpellDefinition = pureCounter("Negate", "{1}{U}", SpellRestriction.NotOfCardType(CardType.CREATURE))

/**
 * Annul — `{U}` Instant. "Counter target artifact or enchantment spell."
 *
 * [SpellRestriction.OfAnyCardType], the disjunction member: a spell qualifies by having *either* type,
 * so an artifact creature spell is a legal target here and not for [removeSoul]'s sibling restriction —
 * a card has a set of types, not one.
 */
val annul: SpellDefinition =
    pureCounter(
        "Annul",
        "{U}",
        SpellRestriction.OfAnyCardType(persistentSetOf(CardType.ARTIFACT, CardType.ENCHANTMENT)),
    )

/**
 * Envelop — `{U}` Instant. "Counter target sorcery spell."
 *
 * [SpellRestriction.OfCardType]`(SORCERY)`, [dispel]'s mirror. Being an instant that answers only
 * sorceries, it is the clearest case of a counter whose legality depends on what is on the stack *right
 * now* rather than on the board — the enumeration property this framework most needs the fuzz probe for.
 */
val envelop: SpellDefinition = pureCounter("Envelop", "{U}", SpellRestriction.OfCardType(CardType.SORCERY))

/**
 * Remove Soul — `{1}{U}` Instant. "Counter target creature spell."
 *
 * [SpellRestriction.OfCardType]`(CREATURE)`, [negate]'s exact complement over the pool and the one
 * counter here that can target a **permanent** spell. Countering one is the tidiest end-to-end case in
 * the framework: the creature never enters the battlefield, so no enters-the-battlefield trigger ever
 * fires — while the "whenever a player casts a spell" trigger that fired at CR 601.2i is already on the
 * stack and resolves regardless (CR 701.5a: the spell was still cast).
 */
val removeSoul: SpellDefinition = pureCounter("Remove Soul", "{1}{U}", SpellRestriction.OfCardType(CardType.CREATURE))

/**
 * Force Spike — `{U}` Instant. "Counter target spell unless its controller pays `{1}`."
 *
 * The first card whose resolution asks a **different player** to decide (CR 118.3a): the targeted spell's
 * controller may pay `{1}` to save it. Three rules facts the engine has to get right, each one a test:
 * paying is not a cast and grants nobody priority (CR 605.3a/b), declining and being unable to pay are
 * the same answer, and a target that has already become illegal fizzles the Spike **without** anyone
 * being asked.
 *
 * The `{1}` is a new payment printed on Force Spike, unrelated to what the target cost or what was paid
 * for it — docs/design/countering-spells.md §1.1 records that the upstream brief had this backwards, and
 * the oracle text confirms the note: nothing here inspects the target's cost.
 */
val forceSpike: SpellDefinition = unlessPaidCounter("Force Spike", "{U}", SpellRestriction.Any, "{1}")

/**
 * Spell Pierce — `{U}` Instant. "Counter target noncreature spell unless its controller pays `{2}`."
 *
 * Restricted *and* unless-pay, which is what makes the CR 701.5 / CR 608.2b verdict split observable in
 * one card: kill or bounce its target in response and Spell Pierce does not resolve at all — no payment
 * is offered, nothing is countered, and the log says `SpellFizzled` rather than `SpellCountered`. That
 * ordering (fizzle check first, orchestration second) is the one thing this card pins.
 */
val spellPierce: SpellDefinition =
    unlessPaidCounter("Spell Pierce", "{U}", SpellRestriction.NotOfCardType(CardType.CREATURE), "{2}")

/**
 * A plain "counter target &lt;restriction&gt; spell" instant (CR 701.5): [restriction] is the whole of what
 * distinguishes the six, and the resolution is the published [counterSpell] primitive composed over the
 * single settled target (ADR-003 — a card definition uses only published primitives).
 *
 * The target is still legal when the effect runs: CR 608.2b has already re-checked it, so `single()` and
 * the primitive's own kind check can only fail on an engine defect.
 */
private fun pureCounter(
    name: String,
    cost: String,
    restriction: SpellRestriction,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics = counterCharacteristics(name, cost)
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.SpellOnStack(restriction)
        override val resolution =
            ResolutionEffect { state, context -> counterSpell(state, context.targets.single(), context.source) }
    }

/**
 * A "counter target &lt;restriction&gt; spell unless its controller pays [payment]" instant (CR 118.3a).
 *
 * Its [SpellDefinition.resolution] is deliberately the identity: the whole of the card is the declarative
 * [CounterUnlessPaid] clause, which the engine orchestrates in place of a resolution effect because the
 * payment is a decision and ADR-004 forbids a callback out of one. A card that both did something and
 * countered-unless-paid would need both halves; none exists.
 */
private fun unlessPaidCounter(
    name: String,
    cost: String,
    restriction: SpellRestriction,
    payment: String,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics = counterCharacteristics(name, cost)
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.SpellOnStack(restriction)
        override val counterUnlessPaid = CounterUnlessPaid(ManaCost.parse(payment))
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/** The printed characteristics shared by all eight: a plain blue instant with no P/T box (CR 304). */
private fun counterCharacteristics(
    name: String,
    cost: String,
): PrintedCharacteristics =
    PrintedCharacteristics(
        name = name,
        manaCost = ManaCost.parse(cost),
        supertypes = persistentSetOf(),
        cardTypes = persistentSetOf(CardType.INSTANT),
        subtypes = persistentSetOf(),
        powerToughness = null,
    )
