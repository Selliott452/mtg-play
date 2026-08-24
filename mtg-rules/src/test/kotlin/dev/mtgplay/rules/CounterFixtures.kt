package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.CounterUnlessPaid
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.counterSpell
import kotlinx.collections.immutable.persistentSetOf

/*
 * Fixture definitions for the `FW-COUNTER` specs (docs/design/countering-spells.md F1.1). `mtg-rules`
 * names no real card (ADR-003), so the counter primitive, the stack target spec, and the unless-pay
 * clause are all exercised against synthetic spells here; the six real counters live in `mtg-cards`.
 *
 * Every counter fixture composes the published [counterSpell] primitive over its single settled target,
 * which is exactly what a card definition does — so if the composition shape were wrong for cards it
 * would be wrong here too.
 */

/** A counter fixture: `{U}` instant, "counter target &lt;restriction&gt; spell" (CR 701.5). */
private fun counterFixture(
    name: String,
    restriction: SpellRestriction,
    cost: String = "{U}",
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics = instantCharacteristics(name, cost)
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.SpellOnStack(restriction)
        override val resolution =
            ResolutionEffect { state, context -> counterSpell(state, context.targets.single(), context.source) }
    }

/** The printed characteristics of a blue instant fixture with no P/T box (CR 304). */
private fun instantCharacteristics(
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

/** "Fixture Counter" — `{U}` instant: counter target spell, unrestricted ([SpellRestriction.Any]). */
internal val fixtureCounter: SpellDefinition = counterFixture("Fixture Counter", SpellRestriction.Any)

/** "Fixture Dispel" — `{U}` instant: counter target **instant** spell. */
internal val fixtureDispel: SpellDefinition =
    counterFixture("Fixture Dispel", SpellRestriction.OfCardType(CardType.INSTANT))

/** "Fixture Negate" — `{U}` instant: counter target **noncreature** spell — the pool's one negation. */
internal val fixtureNegate: SpellDefinition =
    counterFixture("Fixture Negate", SpellRestriction.NotOfCardType(CardType.CREATURE))

/** "Fixture Annul" — `{U}` instant: counter target **artifact or enchantment** spell. */
internal val fixtureAnnul: SpellDefinition =
    counterFixture(
        "Fixture Annul",
        SpellRestriction.OfAnyCardType(persistentSetOf(CardType.ARTIFACT, CardType.ENCHANTMENT)),
    )

/** "Fixture Blast" — `{U}` instant: counter target **red** spell — the derived-colour restriction (CR 202.2). */
internal val fixtureBlast: SpellDefinition =
    counterFixture("Fixture Blast", SpellRestriction.OfColor(dev.mtgplay.core.mana.Color.RED))

/**
 * "Fixture Spike" — `{U}` instant: counter target spell **unless its controller pays `{1}`** (CR 118.3a).
 * Its own [SpellDefinition.resolution] is the identity: the whole card is the declarative clause, which
 * the engine orchestrates in place of a resolution effect.
 */
internal val fixtureSpike: SpellDefinition =
    object : SpellDefinition {
        override val characteristics = instantCharacteristics("Fixture Spike", "{U}")
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.SpellOnStack(SpellRestriction.Any)
        override val counterUnlessPaid = CounterUnlessPaid(ManaCost.parse("{1}"))
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/**
 * "Fixture Pierce" — `{U}` instant: counter target **noncreature** spell unless its controller pays
 * `{2}`. Restricted *and* unless-pay, the pairing that makes the CR 608.2b-before-CR 118.3a ordering
 * observable (Spell Pierce's shape).
 */
internal val fixturePierce: SpellDefinition =
    object : SpellDefinition {
        override val characteristics = instantCharacteristics("Fixture Pierce", "{U}")
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.SpellOnStack(SpellRestriction.NotOfCardType(CardType.CREATURE))
        override val counterUnlessPaid = CounterUnlessPaid(ManaCost.parse("{2}"))
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/** "Fixture Bear" — `{R}` creature 2/2: the permanent spell a counter can stop from ever entering. */
internal val fixtureBear: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Bear",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/**
 * "Fixture Echo" — `{R}` instant with flashback `{R}` (CR 702.34): the spell whose *counter* must send it
 * to **exile** rather than a graveyard, cashing the promise
 * [dev.mtgplay.core.state.StackEntry.Spell.castVia]'s KDoc has carried since P5.2.
 */
internal val fixtureEcho: SpellDefinition =
    object : SpellDefinition {
        override val characteristics = instantCharacteristics("Fixture Echo", "{R}")
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val castingPermissions = listOf(CastingPermission.Flashback(ManaCost.parse("{R}")))
    }

/** The `FW-COUNTER` fixtures merged into the shared registry, ready for `fixtureState`. */
internal val counterDefinitions: Map<CardRef, CardDefinition> =
    fixtureDefinitions +
        listOf(
            fixtureCounter,
            fixtureDispel,
            fixtureNegate,
            fixtureAnnul,
            fixtureBlast,
            fixtureSpike,
            fixturePierce,
            fixtureBear,
            fixtureEcho,
        ).associateBy { CardRef(it.characteristics.name) }
