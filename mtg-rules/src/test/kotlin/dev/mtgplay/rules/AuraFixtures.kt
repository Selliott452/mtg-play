package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/*
 * Synthetic Aura and enchantable-object fixtures for the P4.1 layer-system specs (the
 * `mtg-rules`-names-no-card rule holds — the real Bogles Auras arrive in `mtg-cards`, P4.2). Every
 * populated-layer shape is represented: a fixed +X/+Y-and-keyword Aura (layer 6 + 7c), a dynamic
 * count-based Aura (7c with a Magnitude.Dynamic), a mana-ability-granting Aura on a land (layer 6 on
 * a non-creature), and one Aura per enchant restriction (creature / land / Forest / creature you
 * control). Auras are enchantment permanent spells with a Enchantable target spec and a no-op
 * resolution — the engine, not the effect, performs the CR 303.4f enter-attached move.
 */

/** The five colors of mana an "any color" grant produces (WUBRG order, CR 105.1). */
internal val ANY_COLOR: ManaAbility =
    ManaAbility(persistentListOf(ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN))

// A creature body (uncastable battlefield fixture): printed type, P/T, and printed keywords.
private fun creatureBody(
    name: String,
    power: Int,
    toughness: Int,
    vararg keywords: Keyword,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power, toughness),
                keywords = persistentSetOf(*keywords),
            )
    }

// A land source fixture: printed land type, the given subtypes, and a "{T}: add one [type]" ability.
private fun landSource(
    name: String,
    subtypes: Set<Subtype>,
    type: ManaType,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = subtypes.toPersistentSet(),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(type)))
    }

// An Aura fixture: an enchantment permanent spell with the given cost, enchant restriction, and the
// static continuous effect its ability generates. Sorcery-speed (CR 601.3a) with a no-op resolution
// (CR 303.4f: the engine performs the enter-attached move; a permanent spell has no CR 608.2c effect).
private fun aura(
    name: String,
    cost: String,
    restriction: EnchantRestriction,
    effect: StaticContinuousEffect,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse(cost),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(Subtype("Aura")),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.Enchantable(restriction)
        override val resolution = ResolutionEffect { state, _ -> state }
        override val staticContinuousEffects = persistentListOf(effect)
    }

/**
 * A dynamic count of the enchantment permanents on the battlefield (CR 613.3c) — the shape of
 * Ethereal Armor / Ancestral Mask's variable bonus. [perEnchantment] mana per enchantment.
 */
internal fun enchantmentCount(perEnchantment: Int): Magnitude =
    Magnitude.Dynamic { state, _ ->
        perEnchantment *
            state.sharedZones.battlefield.count { obj ->
                state.definitions[obj.card]
                    ?.characteristics
                    ?.cardTypes
                    ?.contains(CardType.ENCHANTMENT) == true
            }
    }

// --- Enchantable-object fixtures ---

/** "Ent" — a 2/2 creature body to enchant. */
internal val fixtureEnt = creatureBody("Ent", 2, 2)

/** "Toad" — a second 2/2 creature body, for the "creature you control" restriction (owned by bob). */
internal val fixtureToad = creatureBody("Toad", 2, 2)

/**
 * "Fixture Anvil" — a plain artifact with **no P/T box at all**, the shape CR 613 layer 4 and sublayer
 * 7b are tested against (`FW-TYPECHANGE`). A creature fixture would hide the half of the behaviour that
 * matters: only an object whose printed power and toughness are `null` can show that 7b *creates* a P/T
 * box where none existed, and that the layer-7c counter guard fires when it does not.
 */
internal val fixtureAnvil: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Anvil",
                manaCost = ManaCost.parse("{2}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
    }

/** "Meadow" — a plain (non-Forest) land producing {G}. */
internal val fixtureMeadow = landSource("Meadow", emptySet(), ManaType.GREEN)

/** "Thicket" — a Forest-subtype land producing {G} (CR 205.3), for the "enchant Forest" restriction. */
internal val fixtureThicket = landSource("Thicket", setOf(Subtype("Forest")), ManaType.GREEN)

// --- Aura fixtures ---

/** "Fixture Cloak" — enchant creature, +2/+2 and grants first strike (layer 7c + layer 6, fixed). */
internal val fixtureCloak =
    aura(
        name = "Fixture Cloak",
        cost = "{G}",
        restriction = EnchantRestriction.CREATURE,
        effect =
            StaticContinuousEffect(
                grantedKeywords = persistentSetOf(Keyword.FIRST_STRIKE),
                powerMod = Magnitude.Fixed(2),
                toughnessMod = Magnitude.Fixed(2),
            ),
    )

/** "Fixture Ward" — enchant creature, +0/+2 (toughness only, fixed) — the headline keep-alive Aura. */
internal val fixtureWard =
    aura(
        name = "Fixture Ward",
        cost = "{G}",
        restriction = EnchantRestriction.CREATURE,
        effect = StaticContinuousEffect(toughnessMod = Magnitude.Fixed(2)),
    )

/** "Fixture Mask" — enchant creature, +N/+N where N = enchantments on the battlefield (dynamic). */
internal val fixtureMask =
    aura(
        name = "Fixture Mask",
        cost = "{G}",
        restriction = EnchantRestriction.CREATURE,
        effect =
            StaticContinuousEffect(
                powerMod = enchantmentCount(1),
                toughnessMod = enchantmentCount(1),
            ),
    )

/** "Fixture Growth" — enchant land, grants "{T}: add one mana of any color" (layer 6 on a land). */
internal val fixtureGrowth =
    aura(
        name = "Fixture Growth",
        cost = "{G}",
        restriction = EnchantRestriction.LAND,
        effect = StaticContinuousEffect(grantedManaAbilities = persistentListOf(ANY_COLOR)),
    )

/** "Fixture Canopy" — enchant Forest, grants "{T}: add one mana of any color" (Forest restriction). */
internal val fixtureCanopy =
    aura(
        name = "Fixture Canopy",
        cost = "{G}",
        restriction = EnchantRestriction.FOREST,
        effect = StaticContinuousEffect(grantedManaAbilities = persistentListOf(ANY_COLOR)),
    )

/** "Fixture Mark" — enchant creature you control, grants first strike (control==ownership, §4). */
internal val fixtureMark =
    aura(
        name = "Fixture Mark",
        cost = "{G}",
        restriction = EnchantRestriction.CREATURE_YOU_CONTROL,
        effect = StaticContinuousEffect(grantedKeywords = persistentSetOf(Keyword.FIRST_STRIKE)),
    )

/** "Fixture Hollow" — enchant creature, an *empty* static effect: the unimplemented-kind loud gate. */
internal val fixtureHollow =
    aura(
        name = "Fixture Hollow",
        cost = "{G}",
        restriction = EnchantRestriction.CREATURE,
        effect = StaticContinuousEffect(),
    )

/** Every Aura/enchantable fixture, keyed by ref — the registry the layer specs build states with. */
internal val auraDefinitions: Map<CardRef, CardDefinition> =
    listOf(
        fixtureEnt,
        fixtureToad,
        fixtureAnvil,
        fixtureMeadow,
        fixtureThicket,
        fixtureCloak,
        fixtureWard,
        fixtureMask,
        fixtureGrowth,
        fixtureCanopy,
        fixtureMark,
        fixtureHollow,
    ).associateBy { CardRef(it.characteristics.name) }

/** The effect a battlefield object's definition generates (fixtures carry exactly one). */
internal fun GameState.staticEffectOf(name: String): StaticContinuousEffect =
    definitions.getValue(CardRef(name)).staticContinuousEffects.single()
