package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AsEntersColorChoice
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggeredManaAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Aura fixtures with CR 605.1b triggered mana abilities — the two shapes the MVP pool has, without
 * naming either card (`mtg-rules` never references a specific card). They are the fixtures the
 * multi-mana payment tests need: a source whose one activation yields two mana.
 */

/**
 * A chosen-colour ramp Aura (Utopia Sprawl's shape): "Enchant Forest. As this Aura enters, choose a
 * colour. Whenever enchanted Forest is tapped for mana, its controller adds an additional one mana
 * of the chosen colour" (CR 605.1b, CR 614.12). The colour lives on the attached object's
 * `chosenColor`.
 */
internal val fixtureChosenColorRamp: SpellDefinition =
    rampAura("Fixture Chosen Ramp", EnchantRestriction.FOREST, TriggeredManaAbility.AddChosenColor(1)) { true }

/**
 * A printed-mana ramp Aura (Wild Growth's shape): "Enchant land. Whenever enchanted land is tapped
 * for mana, its controller adds an additional `{G}`" (CR 605.1b) — no colour choice at all.
 */
internal val fixtureFixedManaRamp: SpellDefinition =
    rampAura("Fixture Fixed Ramp", EnchantRestriction.LAND, TriggeredManaAbility.AddFixedMana(ManaType.GREEN, 1)) {
        false
    }

/** Both ramp-Aura fixtures, keyed by ref — for registries alongside [fixtureDefinitions]. */
internal val rampAuraFixtures: Map<CardRef, CardDefinition> =
    listOf(fixtureChosenColorRamp, fixtureFixedManaRamp).associateBy { CardRef(it.characteristics.name) }

private fun rampAura(
    name: String,
    restriction: EnchantRestriction,
    ability: TriggeredManaAbility,
    choosesColor: () -> Boolean,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(Subtype("Aura")),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.Enchantable(restriction)
        override val resolution = ResolutionEffect { state, _ -> state }
        override val asEntersColorChoice = if (choosesColor()) AsEntersColorChoice() else null
        override val triggeredManaAbilities = persistentListOf(ability)
    }
