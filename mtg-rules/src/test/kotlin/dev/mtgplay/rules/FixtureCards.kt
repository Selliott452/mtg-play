package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.loseLife
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Synthetic fixture definitions for the P2.1 engine specs (architect decision: rules-module
 * tests use fixtures in test source; real cards arrive in `mtg-cards`, P2.2). Every cost shape
 * the payment model must cover is represented: colored, generic, {C}, hybrid {G/U}, and
 * Phyrexian {R/P}, plus mono-color, any-color, and colorless mana sources.
 */

/**
 * A tap-for-mana source fixture: a battlefield object producing [types]. [powerToughness] is
 * non-null only for a creature source (CR 208.1), which is also the only shape for which the
 * CR 302.6 summoning-sickness gate on mana payment is observable.
 */
private fun sourceFixture(
    name: String,
    cardType: CardType,
    vararg types: ManaType,
    powerToughness: PrintedPowerToughness? = null,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(cardType),
                subtypes = persistentSetOf(),
                powerToughness = powerToughness,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(*types.toList().toTypedArray())))
    }

/**
 * A castable spell fixture with the given cost, target spec, and resolution. The timing class
 * derives from the card type — instants at instant speed (CR 304.1), sorceries at sorcery
 * speed (CR 307.1) — which is exactly the fixture pool's shape.
 */
private fun spellFixture(
    name: String,
    cost: String,
    cardType: CardType,
    spec: TargetSpec,
    effect: ResolutionEffect,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse(cost),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(cardType),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing =
            if (cardType == CardType.INSTANT) TimingClass.INSTANT_SPEED else TimingClass.SORCERY_SPEED
        override val targetSpec = spec
        override val resolution = effect
    }

/** Resolution: the single targeted player loses [amount] life (the P2.1 lose-life primitive). */
private fun targetPlayerLosesLife(amount: Int): ResolutionEffect =
    ResolutionEffect { state, context ->
        when (val target = context.targets.single()) {
            is Target.Player -> loseLife(state, target.id, amount)
            // These fixtures target players only (TargetSpec.AnyTarget enumerates players in the
            // P2.x pool); a permanent target reaching here is an engine defect, not a silent case.
            is Target.Permanent -> error("fixture $target unexpectedly targeted a permanent")
        }
    }

/** Resolution: the spell's controller loses [amount] life. */
private fun controllerLosesLife(amount: Int): ResolutionEffect =
    ResolutionEffect { state, context -> loseLife(state, context.controller, amount) }

/** Resolution: no instructions — the fixture only exercises casting and payment. */
private val noInstructions: ResolutionEffect = ResolutionEffect { state, _ -> state }

/** "Fixture Mountain" — a land source: `{T}: add {R}`. */
internal val fixtureMountain = sourceFixture("Fixture Mountain", CardType.LAND, ManaType.RED)

/** "Fixture Forest" — a land source: `{T}: add {G}`. */
internal val fixtureForest = sourceFixture("Fixture Forest", CardType.LAND, ManaType.GREEN)

/** "Fixture Island" — a land source: `{T}: add {U}`. */
internal val fixtureIsland = sourceFixture("Fixture Island", CardType.LAND, ManaType.BLUE)

/** "Fixture Prism" — an artifact source: `{T}: add one mana of any color` (Abundant Growth's shape). */
internal val fixturePrism =
    sourceFixture(
        "Fixture Prism",
        CardType.ARTIFACT,
        ManaType.WHITE,
        ManaType.BLUE,
        ManaType.BLACK,
        ManaType.RED,
        ManaType.GREEN,
    )

/** "Fixture Wastes" — a land source: `{T}: add {C}` (CR 107.4c's specifically colorless mana). */
internal val fixtureWastes = sourceFixture("Fixture Wastes", CardType.LAND, ManaType.COLORLESS)

/**
 * "Fixture Mana Elf" — a **creature** source: a 1/1 with `{T}: add {G}` (Elvish Mystic's shape).
 * The fixture that makes CR 302.6 reachable from mana payment: its mana ability is an activated
 * ability with `{T}` in its cost, so it produces nothing while the creature is summoning sick.
 */
internal val fixtureManaElf =
    sourceFixture(
        "Fixture Mana Elf",
        CardType.CREATURE,
        ManaType.GREEN,
        powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
    )

/**
 * "Fixture Mana Spawn" — a **creature** source whose mana ability's cost is sacrificing it rather
 * than `{T}` (CR 605.1a). CR 302.6 restricts only `{T}` and `{Q}` abilities, so this one is
 * usable the turn it arrives — the counterexample that keeps the summoning-sickness gate from
 * being written too broadly.
 */
internal val fixtureManaSpawn =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Mana Spawn",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power = 0, toughness = 1),
            )
        override val manaAbilities =
            persistentListOf(ManaAbility(persistentListOf(ManaType.COLORLESS), viaSacrifice = true))
    }

/** "Fixture Bolt" — `{R}` instant, any target: the targeted player loses 3 life. */
internal val fixtureBolt =
    spellFixture(
        name = "Fixture Bolt",
        cost = "{R}",
        cardType = CardType.INSTANT,
        spec = TargetSpec.AnyTarget,
        effect = targetPlayerLosesLife(FIXTURE_BOLT_LIFE_LOSS),
    )

/** "Fixture Comet" — `{R}` sorcery, any target: the timing-class counterexample to the Bolt. */
internal val fixtureComet =
    spellFixture(
        name = "Fixture Comet",
        cost = "{R}",
        cardType = CardType.SORCERY,
        spec = TargetSpec.AnyTarget,
        effect = targetPlayerLosesLife(FIXTURE_COMET_LIFE_LOSS),
    )

/** "Fixture Meditation" — `{1}` instant, no targets: its own controller loses 1 life. */
internal val fixtureMeditation =
    spellFixture(
        name = "Fixture Meditation",
        cost = "{1}",
        cardType = CardType.INSTANT,
        spec = TargetSpec.None,
        effect = controllerLosesLife(1),
    )

/** "Fixture Bloom" — `{G/U}` instant, no targets (Slippery Bogle's hybrid cost shape). */
internal val fixtureBloom =
    spellFixture(
        name = "Fixture Bloom",
        cost = "{G/U}",
        cardType = CardType.INSTANT,
        spec = TargetSpec.None,
        effect = noInstructions,
    )

/** "Fixture Gut Punch" — `{R/P}` instant, no targets (Gut Shot's Phyrexian cost shape). */
internal val fixtureGutPunch =
    spellFixture(
        name = "Fixture Gut Punch",
        cost = "{R/P}",
        cardType = CardType.INSTANT,
        spec = TargetSpec.None,
        effect = noInstructions,
    )

/** What resolving a Fixture Bolt costs its target (CR 119.3c via the lose-life primitive). */
internal const val FIXTURE_BOLT_LIFE_LOSS: Int = 3

/** What resolving a Fixture Comet costs its target. */
internal const val FIXTURE_COMET_LIFE_LOSS: Int = 2

/** Every fixture definition, keyed by ref — the registry fixture configs and states use. */
internal val fixtureDefinitions: Map<CardRef, CardDefinition> =
    listOf(
        fixtureMountain,
        fixtureForest,
        fixtureIsland,
        fixturePrism,
        fixtureWastes,
        fixtureManaElf,
        fixtureManaSpawn,
        fixtureBolt,
        fixtureComet,
        fixtureMeditation,
        fixtureBloom,
        fixtureGutPunch,
    ).associateBy { CardRef(it.characteristics.name) }
