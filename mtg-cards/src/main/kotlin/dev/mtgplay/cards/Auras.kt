package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The seven real Bogles continuous-effect Auras of the MVP pool (CR 303), encoded on top of the
 * P4.1 layer engine (docs/design/layer-system.md §1, the binding effect inventory). Each is an
 * enchantment permanent spell (CR 303) cast at sorcery speed (CR 601.3a) that targets the object it
 * will enchant while on the stack (CR 601.2c) and enters the battlefield attached to it (CR 303.4f);
 * its static ability's continuous effect is one [StaticContinuousEffect] that `mtg-rules` classifies
 * into CR 613 layers (layer 6 for keyword/mana grants, sublayer 7c for +X/+Y). The two variable
 * bonuses (Ethereal Armor, Ancestral Mask) are pure count functions of the live [GameState]
 * ([Magnitude.Dynamic], CR 613.3c) — no snapshot.
 *
 * **P5 obligations (deferred halves).** Only the P4 static half of each card is encoded here; every
 * card's triggered/ETB/escape/graveyard clause is Phase 5 (the trigger and alternative-cost
 * frameworks) and is called out in the owning card's KDoc. Utopia Sprawl is not in this packet — its
 * whole grant is a *triggered* mana ability (CR 605.1b), also P5.
 */

/** The five colors an "add one mana of any color" grant produces, in WUBRG order (CR 105.1). */
private val ANY_COLOR: ManaAbility =
    ManaAbility(persistentListOf(ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN))

/**
 * An Aura card definition (CR 303): an "Enchantment — Aura" permanent spell with the given
 * [manaCost], cast at sorcery speed (CR 601.3a), whose enchant ability is a [TargetSpec.Enchantable]
 * carrying [restriction] (CR 303.4a) and whose single static ability generates [effect]. Resolution
 * is a no-op (CR 303.4f, CR 608.3): the rules engine performs the enter-attached move, not the
 * effect.
 */
private fun aura(
    name: String,
    manaCost: String,
    restriction: EnchantRestriction,
    effect: StaticContinuousEffect,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse(manaCost),
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

/** Whether the battlefield object [obj] is an enchantment permanent (CR 303), reading printed types. */
private fun isEnchantment(
    state: GameState,
    obj: GameObject,
): Boolean =
    state.definitions[obj.card]
        ?.characteristics
        ?.cardTypes
        ?.contains(CardType.ENCHANTMENT) == true

/**
 * A dynamic magnitude of [perEnchantment] per enchantment permanent the effect's controller controls
 * (CR 613.3c) — Ethereal Armor's "+1/+1 for each enchantment you control". Control is ownership in
 * the MVP pool (docs/design/layer-system.md §4), and the count **includes the source Aura itself**
 * and every other enchantment (Aura or not) its controller controls. Read live on every
 * characteristic computation, so it tracks the board with no explicit recompute.
 */
private fun perEnchantmentYouControl(perEnchantment: Int): Magnitude =
    Magnitude.Dynamic { state, source ->
        val controller =
            state.sharedZones.battlefield
                .firstOrNull { it.id == source }
                ?.owner
        if (controller == null) {
            0
        } else {
            perEnchantment *
                state.sharedZones.battlefield.count { it.owner == controller && isEnchantment(state, it) }
        }
    }

/**
 * A dynamic magnitude of [perEnchantment] per **other** enchantment permanent on the battlefield
 * (CR 613.3c) — Ancestral Mask's "+2/+2 for each other enchantment". Counts every enchantment on the
 * battlefield under any controller **except the source Aura itself** ([source]). Read live, so it
 * tracks the board with no explicit recompute.
 */
private fun perOtherEnchantment(perEnchantment: Int): Magnitude =
    Magnitude.Dynamic { state, source ->
        perEnchantment * state.sharedZones.battlefield.count { it.id != source && isEnchantment(state, it) }
    }

/**
 * Rancor — `{G}` Enchantment — Aura. "Enchant creature. Enchanted creature gets +2/+0 and has
 * trample." Encoded here is only the static half: the layer-7c +2/+0 (CR 613.3, sublayer 7c) and the
 * layer-6 trample grant (CR 613.3, layer 6, CR 702.19).
 *
 * **P5 (deferred):** Rancor's graveyard-return triggered ability — "When Rancor is put into a
 * graveyard from the battlefield, return Rancor to its owner's hand" (CR 603) — is omitted; it
 * arrives with the trigger framework in Phase 5.
 */
val rancor: SpellDefinition =
    aura(
        name = "Rancor",
        manaCost = "{G}",
        restriction = EnchantRestriction.CREATURE,
        effect =
            StaticContinuousEffect(
                grantedKeywords = persistentSetOf(Keyword.TRAMPLE),
                powerMod = Magnitude.Fixed(2),
                toughnessMod = Magnitude.Fixed(0),
            ),
    )

/**
 * Armadillo Cloak — `{1}{G}{W}` Enchantment — Aura. "Enchant creature. Enchanted creature gets +2/+2
 * and has trample." Encoded here is only the static half: the layer-7c +2/+2 and the layer-6 trample
 * grant (CR 613.3; CR 702.19).
 *
 * **P5 (deferred):** Armadillo Cloak's damage-triggered lifegain — "Whenever enchanted creature is
 * dealt damage, you gain that much life" (CR 603) — is omitted; it arrives with the trigger framework
 * in Phase 5.
 */
val armadilloCloak: SpellDefinition =
    aura(
        name = "Armadillo Cloak",
        manaCost = "{1}{G}{W}",
        restriction = EnchantRestriction.CREATURE,
        effect =
            StaticContinuousEffect(
                grantedKeywords = persistentSetOf(Keyword.TRAMPLE),
                powerMod = Magnitude.Fixed(2),
                toughnessMod = Magnitude.Fixed(2),
            ),
    )

/**
 * Cartouche of Solidarity — `{W}` Enchantment — Aura. "Enchant creature you control. Enchanted
 * creature gets +1/+1 and has first strike." Encoded here is only the static half: the layer-7c
 * +1/+1 and the layer-6 first-strike grant (CR 613.3; CR 702.7). Its enchant restriction is
 * [EnchantRestriction.CREATURE_YOU_CONTROL] (control is ownership in the MVP pool, §4).
 *
 * **P5 (deferred):** Cartouche of Solidarity's enters-the-battlefield trigger — "When Cartouche of
 * Solidarity enters the battlefield, create a 1/1 white Warrior creature token" (CR 603.6) — is
 * omitted; it arrives with the trigger framework (and token creation) in Phase 5.
 */
val cartoucheOfSolidarity: SpellDefinition =
    aura(
        name = "Cartouche of Solidarity",
        manaCost = "{W}",
        restriction = EnchantRestriction.CREATURE_YOU_CONTROL,
        effect =
            StaticContinuousEffect(
                grantedKeywords = persistentSetOf(Keyword.FIRST_STRIKE),
                powerMod = Magnitude.Fixed(1),
                toughnessMod = Magnitude.Fixed(1),
            ),
    )

/**
 * Sentinel's Eyes — `{W}` Enchantment — Aura. "Enchant creature. Enchanted creature gets +1/+1 and
 * has vigilance." Encoded here is only the static half: the layer-7c +1/+1 and the layer-6 vigilance
 * grant (CR 613.3; CR 702.21).
 *
 * **P5 (deferred):** Sentinel's Eyes' Escape ability — "Escape—{W}, Exile four other cards from your
 * graveyard" (CR 702.139) — is omitted; casting from the graveyard for an alternative cost arrives
 * with the alternative-cost framework in Phase 5.
 */
val sentinelsEyes: SpellDefinition =
    aura(
        name = "Sentinel's Eyes",
        manaCost = "{W}",
        restriction = EnchantRestriction.CREATURE,
        effect =
            StaticContinuousEffect(
                grantedKeywords = persistentSetOf(Keyword.VIGILANCE),
                powerMod = Magnitude.Fixed(1),
                toughnessMod = Magnitude.Fixed(1),
            ),
    )

/**
 * Ethereal Armor — `{W}` Enchantment — Aura. "Enchant creature. Enchanted creature gets +1/+1 for
 * each enchantment you control and has first strike." Encoded in full for P4 (there is no deferred
 * half): the layer-6 first-strike grant (CR 702.7) and the layer-7c **dynamic** +N/+N where N counts
 * every enchantment permanent its controller controls — **including itself and non-Aura
 * enchantments** — read live (CR 613.3c) via [perEnchantmentYouControl].
 */
val etherealArmor: SpellDefinition =
    aura(
        name = "Ethereal Armor",
        manaCost = "{W}",
        restriction = EnchantRestriction.CREATURE,
        effect =
            StaticContinuousEffect(
                grantedKeywords = persistentSetOf(Keyword.FIRST_STRIKE),
                powerMod = perEnchantmentYouControl(1),
                toughnessMod = perEnchantmentYouControl(1),
            ),
    )

/**
 * Ancestral Mask — `{2}{G}` Enchantment — Aura. "Enchant creature. Enchanted creature gets +2/+2 for
 * each other enchantment on the battlefield." Encoded in full for P4 (there is no deferred half): the
 * layer-7c **dynamic** +2N/+2N where N counts every enchantment permanent on the battlefield under
 * any controller **except this Aura itself**, read live (CR 613.3c) via [perOtherEnchantment]. No
 * keyword grant, so it contributes to layer 7c only.
 */
val ancestralMask: SpellDefinition =
    aura(
        name = "Ancestral Mask",
        manaCost = "{2}{G}",
        restriction = EnchantRestriction.CREATURE,
        effect =
            StaticContinuousEffect(
                powerMod = perOtherEnchantment(2),
                toughnessMod = perOtherEnchantment(2),
            ),
    )

/**
 * Abundant Growth — `{G}` Enchantment — Aura. "Enchant land. Enchanted land has '{T}: Add one mana of
 * any color.'" Encoded here is only the static half: the layer-6 mana-ability grant (CR 613.3, layer
 * 6, CR 605.1a) of `{T}: Add one mana of any color` onto the enchanted land, which payment
 * enumeration then reads through the layered production profile (docs/design/layer-system.md §6). The
 * "any color" production is the existing WUBRG [ANY_COLOR] shape. Its enchant restriction is
 * [EnchantRestriction.LAND] (CR 303.4a).
 *
 * **P5 (deferred):** Abundant Growth's enters-the-battlefield trigger — "When Abundant Growth enters
 * the battlefield, draw a card" (CR 603.6) — is omitted; it arrives with the trigger framework in
 * Phase 5.
 */
val abundantGrowth: SpellDefinition =
    aura(
        name = "Abundant Growth",
        manaCost = "{G}",
        restriction = EnchantRestriction.LAND,
        effect = StaticContinuousEffect(grantedManaAbilities = persistentListOf(ANY_COLOR)),
    )
