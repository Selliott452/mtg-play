package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.gainLife
import dev.mtgplay.rules.effect.returnToOwnersHand
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/*
 * The Auras of the pool (CR 303) — the seven of P4.2, P6.3's [lifelink] (the card named Lifelink),
 * and the gauntlet's [spiritLink], the one Aura here with no static ability at all — encoded on top of
 * the P4.1 layer engine (docs/design/layer-system.md §1, the binding effect inventory). Each is an
 * enchantment permanent spell (CR 303) cast at sorcery speed (CR 601.3a) that targets the object it
 * will enchant while on the stack (CR 601.2c) and enters the battlefield attached to it (CR 303.4f);
 * its static ability's continuous effect is one [StaticContinuousEffect] that `mtg-rules` classifies
 * into CR 613 layers (layer 6 for keyword/mana grants, sublayer 7c for +X/+Y). The two variable
 * bonuses (Ethereal Armor, Ancestral Mask) are pure count functions of the live [GameState]
 * ([Magnitude.Dynamic], CR 613.3c) — no snapshot.
 *
 * **Triggered halves (P5.1).** Four of these cards have a triggered ability (CR 603) alongside their
 * static half, now encoded on top of the P5.1 trigger framework: Rancor's graveyard-return, Armadillo
 * Cloak's damage-triggered lifegain, Cartouche of Solidarity's enters-the-battlefield token, and
 * Abundant Growth's enters-the-battlefield draw. Sentinel's Eyes' Escape (an alternative cost, not a
 * trigger) is still P5.2; Ethereal Armor and Ancestral Mask have no non-static half. Utopia Sprawl is
 * not in this packet — its whole grant is a *triggered* mana ability (CR 605.1b), also P5.
 */

/**
 * The 1/1 white Warrior creature token with vigilance (CR 111.4) that Cartouche of Solidarity creates.
 * A token is not a card ([TokenDefinition]); "white" is flavour the MVP models nowhere (color is
 * derived from a mana cost, and a token has none — the CR 204 color indicator is unmodeled until a
 * card cares about a token's color), and no MVP card interacts with the token's color, so it is left
 * colorless-by-model without loss.
 */
val warriorToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Warrior",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Warrior")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
                keywords = persistentSetOf(Keyword.VIGILANCE),
            ),
    )

/** The five colors an "add one mana of any color" grant produces, in WUBRG order (CR 105.1). */
private val ANY_COLOR: ManaAbility =
    ManaAbility(persistentListOf(ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN))

/**
 * An Aura card definition (CR 303): an "Enchantment — Aura" permanent spell with the given
 * [manaCost], cast at sorcery speed (CR 601.3a), whose enchant ability is a [TargetSpec.Enchantable]
 * carrying [restriction] (CR 303.4a), whose single static ability generates [effect] (`null` for an
 * Aura with **no** static ability — Spirit Link — so the layer engine is never handed an empty
 * effect), and whose triggered abilities are [triggers] (CR 603; empty for an Aura with no triggered
 * half). Resolution is a no-op (CR 303.4f, CR 608.3): the rules engine performs the enter-attached
 * move, not the effect.
 */
private fun aura(
    name: String,
    manaCost: String,
    restriction: EnchantRestriction,
    effect: StaticContinuousEffect? = null,
    triggers: PersistentList<TriggeredAbility> = persistentListOf(),
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
        override val staticContinuousEffects = listOfNotNull(effect).toPersistentList()
        override val triggeredAbilities = triggers
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
 * trample." The static half is the layer-7c +2/+0 (CR 613.3, sublayer 7c) and the layer-6 trample
 * grant (CR 613.3, layer 6, CR 702.19).
 *
 * **Triggered half (P5.1):** "When Rancor is put into a graveyard from the battlefield, return Rancor
 * to its owner's hand" (CR 603.6b, CR 603.10). The trigger fires as Rancor arrives in the graveyard —
 * most often the CR 704.5m fall-off when its enchanted creature dies — and returns the fresh graveyard
 * object it carries (the trigger's subject) to its owner's hand.
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
        triggers =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.PutIntoGraveyardFromBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            returnToOwnersHand(
                                state,
                                context.subject
                                    ?: error(
                                        "CR 603.10: Rancor's return trigger requires the graveyard object it carries",
                                    ),
                            )
                        },
                ),
            ),
    )

/**
 * Armadillo Cloak — `{1}{G}{W}` Enchantment — Aura. "Enchant creature. Enchanted creature gets +2/+2
 * and has trample." The static half is the layer-7c +2/+2 and the layer-6 trample grant (CR 613.3;
 * CR 702.19).
 *
 * **Triggered half (P5.1):** "Whenever enchanted creature deals damage, you gain that much life"
 * (CR 603.2). The trigger fires when the enchanted creature deals damage (combat or noncombat; only
 * combat occurs in the MVP pool) and gains that much life for the **Aura's controller** — its owner in
 * the MVP pool (ownership is control), which the trigger records as its controller, not the enchanted
 * creature's controller. The amount is the damage the creature dealt in that one event (CR 118.9).
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
        triggers =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnchantedCreatureDealsDamage,
                    effect = ResolutionEffect { state, context -> gainLife(state, context.controller, context.amount) },
                ),
            ),
    )

/**
 * Cartouche of Solidarity — `{W}` Enchantment — Aura. "Enchant creature you control. Enchanted
 * creature gets +1/+1 and has first strike." The static half is the layer-7c +1/+1 and the layer-6
 * first-strike grant (CR 613.3; CR 702.7). Its enchant restriction is
 * [EnchantRestriction.CREATURE_YOU_CONTROL] (control is ownership in the MVP pool, §4).
 *
 * **Triggered half (P5.1):** "When Cartouche of Solidarity enters the battlefield, create a 1/1 white
 * Warrior creature token with vigilance" (CR 603.6a). The enters-the-battlefield trigger creates
 * [warriorToken] under the Aura's controller (CR 111.4, CR 707).
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
        triggers =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect =
                        ResolutionEffect {
                            state,
                            context,
                            ->
                            createToken(state, context.controller, warriorToken)
                        },
                ),
            ),
    )

/**
 * Sentinel's Eyes — `{W}` Enchantment — Aura. "Enchant creature. Enchanted creature gets +1/+1 and
 * has vigilance." The static half is the layer-7c +1/+1 and the layer-6 vigilance grant (CR 613.3;
 * CR 702.21).
 *
 * **Escape (P5.2, implemented):** "Escape—`{W}`, Exile two other cards from your graveyard" (CR 702.139)
 * — a [CastingPermission.Escape] casting it from the graveyard for `{W}` plus the additional cost of
 * exiling two other graveyard cards (architect-verified: two, not four). Cast as a normal Aura (it
 * targets the creature it will enchant, CR 601.2c) and, escaped, it resolves onto the battlefield
 * attached and behaves as an ordinary Aura thereafter. The engine enumerates the escape cast only when
 * the graveyard holds two other cards and `{W}` is affordable (ADR-005).
 */
val sentinelsEyes: SpellDefinition =
    object :
        SpellDefinition by aura(
            name = "Sentinel's Eyes",
            manaCost = "{W}",
            restriction = EnchantRestriction.CREATURE,
            effect =
                StaticContinuousEffect(
                    grantedKeywords = persistentSetOf(Keyword.VIGILANCE),
                    powerMod = Magnitude.Fixed(1),
                    toughnessMod = Magnitude.Fixed(1),
                ),
        ) {
        // Escape (CR 702.139) rides on top of the base Aura definition — its only non-static half.
        override val castingPermissions =
            listOf(CastingPermission.Escape(cost = ManaCost.parse("{W}"), exileOthers = SENTINELS_EYES_ESCAPE_EXILE))
    }

/** Sentinel's Eyes' escape additional cost: exile this many *other* cards from the graveyard (CR 702.139a). */
private const val SENTINELS_EYES_ESCAPE_EXILE: Int = 2

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
 * Lifelink — `{W}` Enchantment — Aura. "Enchant creature. Enchanted creature has lifelink." The card
 * *named* Lifelink (P6.3, the GW-Bogles maindeck one-of), not the keyword — though its current Oracle
 * text grants exactly the keyword. Its 1997 printing read "Whenever a creature deals damage, you gain
 * that much life"; the Oracle text this definition implements is the modern errata'd wording above
 * (Scryfall, oracle id `4fe8316a-9cff-43e9-a1d5-993a0c9daf3a`), which is a plain layer-6 keyword grant
 * (CR 613.3, layer 6; CR 702.15) and **not** a triggered ability.
 *
 * That makes it the pool's first real card to grant [Keyword.LIFELINK], and the deliberate contrast
 * with [armadilloCloak]: the Cloak's "whenever enchanted creature deals damage, you gain that much
 * life" is a triggered ability that uses the stack and gains life for the *Aura's* controller, while
 * lifelink is a *result of the damage* — no stack, no trigger — gaining life for the damage source's
 * controller (docs/decklists.md calls the pair out as a test trio with Spirit Link). A creature wearing
 * both gains its controller the damage twice: once immediately, once off the Cloak trigger.
 */
val lifelink: SpellDefinition =
    aura(
        name = "Lifelink",
        manaCost = "{W}",
        restriction = EnchantRestriction.CREATURE,
        effect = StaticContinuousEffect(grantedKeywords = persistentSetOf(Keyword.LIFELINK)),
    )

/**
 * Spirit Link — `{W}` Enchantment — Aura. "Enchant creature. Whenever enchanted creature deals damage,
 * you gain that much life." The third member of the pool's lifegain trio (docs/decklists.md), and the
 * only Aura here with **no static ability at all**: its whole printed text below the enchant line is
 * one triggered ability, so [staticContinuousEffects] is empty and the CR 613 layer engine has nothing
 * to classify.
 *
 * The trigger is the same [TriggerCondition.EnchantedCreatureDealsDamage] (CR 603.2) [armadilloCloak]
 * carries, gaining the Aura's controller (its owner in the MVP pool) the damage the enchanted creature
 * dealt in that one event ("that much", CR 118.9) — combat or noncombat. That makes it deliberately
 * *unlike* [lifelink]: lifelink is a result of the damage with no stack and no trigger, gaining life
 * for the damage **source's** controller (CR 702.15b), while Spirit Link's trigger uses the stack and
 * pays the **Aura's** controller — so a Spirit Link on an opponent's creature gains its own controller
 * the life.
 */
val spiritLink: SpellDefinition =
    aura(
        name = "Spirit Link",
        manaCost = "{W}",
        restriction = EnchantRestriction.CREATURE,
        triggers =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnchantedCreatureDealsDamage,
                    effect = ResolutionEffect { state, context -> gainLife(state, context.controller, context.amount) },
                ),
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
 * any color.'" The static half is the layer-6 mana-ability grant (CR 613.3, layer 6, CR 605.1a) of
 * `{T}: Add one mana of any color` onto the enchanted land, which payment enumeration then reads
 * through the layered production profile (docs/design/layer-system.md §6). The "any color" production
 * is the existing WUBRG [ANY_COLOR] shape. Its enchant restriction is [EnchantRestriction.LAND]
 * (CR 303.4a).
 *
 * **Triggered half (P5.1):** "When Abundant Growth enters the battlefield, draw a card" (CR 603.6a).
 * The enters-the-battlefield trigger draws one card for the Aura's controller.
 */
val abundantGrowth: SpellDefinition =
    aura(
        name = "Abundant Growth",
        manaCost = "{G}",
        restriction = EnchantRestriction.LAND,
        effect = StaticContinuousEffect(grantedManaAbilities = persistentListOf(ANY_COLOR)),
        triggers =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = ResolutionEffect { state, context -> drawCards(state, context.controller, 1) },
                ),
            ),
    )
