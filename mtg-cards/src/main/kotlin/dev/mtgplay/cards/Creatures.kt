package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/*
 * The first real creatures of the MVP pool (CR 302): Pauper-legal commons whose entire printed
 * text is characteristics the engine already supports — a vanilla body, or a single combat keyword
 * the P3.1 combat engine consults (flying, first strike, vigilance; CR 508–510). None has an
 * activated, triggered, or enters-the-battlefield ability, so none needs a DSL primitive that does
 * not exist yet (ADR-003 vocabulary discipline): a creature that needed one would not belong in
 * this packet (Phase 5 brings the trigger framework).
 *
 * A creature is a permanent spell (CR 302, CR 608.3), so its definition is a [SpellDefinition] and
 * it is *cast* (CR 601) like any other spell — at sorcery speed (CR 302.1: a creature spell is not
 * an instant), targeting nothing (CR 601.2c). Its resolution has no instructions of its own: a
 * permanent spell "becomes a permanent and is put onto the battlefield" (CR 608.3), a move the
 * rules engine performs — the [ResolutionEffect] is the documented no-op [entersTheBattlefield].
 * Enters-the-battlefield triggers are Phase 5.
 */

/**
 * The resolution of a permanent spell with no enters-the-battlefield effect (CR 608.3): the spell
 * performs no CR 608.2c instructions; the rules engine moves it from the stack onto the battlefield
 * as a new object (CR 400.7). Shared by every vanilla-and-keyword-only creature here — a reference
 * value (ADR-009), so sharing one instance across definitions is intentional and harmless.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/**
 * A creature card definition (CR 302): a sorcery-speed, untargeted [SpellDefinition] whose printed
 * box is [powerToughness] (CR 208.1), with the given creature [subtypes] (CR 205.3) and printed
 * [keywords] (CR 702). The MVP-pool creatures are pure "printed characteristics only" cards, so no
 * resolution instructions and no intrinsic mana abilities are needed.
 */
private fun creatureCard(
    name: String,
    manaCost: String,
    powerToughness: PrintedPowerToughness,
    subtypes: Set<Subtype>,
    keywords: Set<Keyword> = emptySet(),
): SpellDefinition =
    creatureSpell(
        PrintedCharacteristics(
            name = name,
            manaCost = ManaCost.parse(manaCost),
            supertypes = persistentSetOf(),
            cardTypes = persistentSetOf(CardType.CREATURE),
            subtypes = subtypes.toPersistentSet(),
            powerToughness = powerToughness,
            keywords = keywords.toPersistentSet(),
        ),
    )

/**
 * A creature spell (CR 302, CR 608.3) from its printed [characteristics]: sorcery-speed, untargeted,
 * with the documented no-op enter-the-battlefield resolution. The single-argument primitive both
 * [creatureCard] and the one card that prints an evasion (Silhana Ledgewalker) build on.
 */
private fun creatureSpell(characteristics: PrintedCharacteristics): SpellDefinition =
    object : SpellDefinition {
        override val characteristics = characteristics

        // CR 302.1: a creature spell is cast at sorcery speed (it is not an instant).
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
    }

/**
 * Grizzly Bears — `{1}{G}` Creature — Bear, a 2/2 vanilla (CR 208.1). The archetypal plain body;
 * no keywords, no abilities.
 */
val grizzlyBears: SpellDefinition =
    creatureCard(
        name = "Grizzly Bears",
        manaCost = "{1}{G}",
        powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
        subtypes = setOf(Subtype("Bear")),
    )

/**
 * Hill Giant — `{3}{R}` Creature — Giant, a 3/3 vanilla (CR 208.1). The MVP pool's bigger vanilla
 * body; no keywords, no abilities.
 */
val hillGiant: SpellDefinition =
    creatureCard(
        name = "Hill Giant",
        manaCost = "{3}{R}",
        powerToughness = PrintedPowerToughness(power = 3, toughness = 3),
        subtypes = setOf(Subtype("Giant")),
    )

/**
 * Wind Drake — `{2}{U}` Creature — Drake, a 2/2 with flying (CR 702.9): it can be blocked only by
 * creatures with flying or reach (no reach in the MVP pool).
 */
val windDrake: SpellDefinition =
    creatureCard(
        name = "Wind Drake",
        manaCost = "{2}{U}",
        powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
        subtypes = setOf(Subtype("Drake")),
        keywords = setOf(Keyword.FLYING),
    )

/**
 * Youthful Knight — `{1}{W}` Creature — Human Knight, a 2/2 with first strike (CR 702.7): it deals
 * its combat damage in the first combat-damage step (CR 510.5).
 */
val youthfulKnight: SpellDefinition =
    creatureCard(
        name = "Youthful Knight",
        manaCost = "{1}{W}",
        powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
        subtypes = setOf(Subtype("Human"), Subtype("Knight")),
        keywords = setOf(Keyword.FIRST_STRIKE),
    )

/**
 * Standing Troops — `{2}{W}` Creature — Soldier, a 1/4 with vigilance (CR 702.21): attacking does
 * not cause it to tap (CR 508.1f), so it can attack and still be available to block.
 */
val standingTroops: SpellDefinition =
    creatureCard(
        name = "Standing Troops",
        manaCost = "{2}{W}",
        powerToughness = PrintedPowerToughness(power = 1, toughness = 4),
        subtypes = setOf(Subtype("Soldier")),
        keywords = setOf(Keyword.VIGILANCE),
    )

/*
 * The three real GW-Bogles hexproof one-drops (docs/decklists.md). Each is a 1/1 with hexproof
 * (CR 702.11): an opponent's spells and abilities can't target it, but its own controller enchants
 * it freely — the deck's whole plan of stacking Auras on an untouchable body. Slippery Bogle is the
 * pool's first hybrid-cost card ({G/U}); Silhana Ledgewalker adds a printed evasion.
 */

/**
 * Gladecover Scout — `{G}` Creature — Elf Scout, a 1/1 with hexproof (CR 702.11): can't be the
 * target of spells or abilities an opponent controls.
 */
val gladecoverScout: SpellDefinition =
    creatureCard(
        name = "Gladecover Scout",
        manaCost = "{G}",
        powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
        subtypes = setOf(Subtype("Elf"), Subtype("Scout")),
        keywords = setOf(Keyword.HEXPROOF),
    )

/**
 * Slippery Bogle — `{G/U}` Creature — Beast, a 1/1 with hexproof (CR 702.11). The MVP pool's first
 * hybrid-cost card: its `{G/U}` symbol is payable with either green or blue mana (CR 107.4), so
 * payment enumeration offers both a green plan and a blue plan when both colours are available.
 */
val slipperyBogle: SpellDefinition =
    creatureCard(
        name = "Slippery Bogle",
        manaCost = "{G/U}",
        powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
        subtypes = setOf(Subtype("Beast")),
        keywords = setOf(Keyword.HEXPROOF),
    )

/**
 * Silhana Ledgewalker — `{1}{G}` Creature — Elf Rogue, a 1/1 with hexproof (CR 702.11) and the
 * evasion "This creature can't be blocked except by creatures with flying"
 * ([Evasion.BLOCKABLE_ONLY_BY_FLYING], CR 509.1b) — a printed evasion the block-legality seam reads
 * beside flying.
 */
val silhanaLedgewalker: SpellDefinition =
    creatureSpell(
        PrintedCharacteristics(
            name = "Silhana Ledgewalker",
            manaCost = ManaCost.parse("{1}{G}"),
            supertypes = persistentSetOf(),
            cardTypes = persistentSetOf(CardType.CREATURE),
            subtypes = persistentSetOf(Subtype("Elf"), Subtype("Rogue")),
            powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
            keywords = persistentSetOf(Keyword.HEXPROOF),
            evasions = persistentSetOf(Evasion.BLOCKABLE_ONLY_BY_FLYING),
        ),
    )
