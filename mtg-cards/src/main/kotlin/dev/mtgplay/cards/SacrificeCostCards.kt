package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.effect.dealDamageToEachPermanent
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.gainLife
import dev.mtgplay.rules.effect.hasFlyingPermanent
import dev.mtgplay.rules.effect.isCreaturePermanent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's **sacrifice-cost** commons (`FW-ADDSAC`, docs/gauntlet-card-triage.md W1-E): four
 * cards whose printed cost includes sacrificing a permanent the player chooses.
 *
 * They split across the framework's two halves, which is the reason they share a file:
 * - [evisceratorsInsight] and [reckonersBargain] print "As an additional cost to cast this spell,
 *   sacrifice an artifact or creature" — [AdditionalCost.Sacrifice], paid at CR 601.2h;
 * - [krarkClanShaman] and [makeshiftMunitions] print it inside an activated ability's composite cost —
 *   [AbilityCost.Sacrifice], paid at CR 602.2b.
 *
 * **Costs, not effects.** Nothing here uses the stack for the sacrifice: it is paid inside the single
 * transition that completes the cast or activation, cannot be responded to, and a spell or ability
 * whose sacrifice cannot be paid is absent from enumeration rather than offered (ADR-005). The engine
 * enumerates which permanents qualify and the player picks by index; a card definition names only the
 * filter (ADR-003).
 *
 * **Neither ability card prints "another".** Krark-Clan Shaman reads "Sacrifice an artifact" and
 * Makeshift Munitions "Sacrifice an artifact or creature"; each is excluded from its own cost by its
 * *types* (a Goblin Shaman is no artifact, an Enchantment is neither) rather than by a self-exclusion
 * rule — see [AbilityCost.Sacrifice].
 */

/** How many cards Eviscerator's Insight draws (CR 121.1). */
const val EVISCERATORS_INSIGHT_DRAW: Int = 2

/** How many cards Reckoner's Bargain draws (CR 121.1). */
const val RECKONERS_BARGAIN_DRAW: Int = 2

/** The damage Krark-Clan Shaman's ability deals to each creature without flying (CR 120.3d). */
const val KRARK_CLAN_SHAMAN_DAMAGE: Int = 1

/** The damage Makeshift Munitions' ability deals to its target (CR 120.3a). */
const val MAKESHIFT_MUNITIONS_DAMAGE: Int = 1

/**
 * "An artifact or creature" (CR 300.1) — the filter three of this file's four cards print, and the
 * pool's most common sacrifice-cost filter.
 */
private val ARTIFACT_OR_CREATURE: SacrificeFilter =
    SacrificeFilter(persistentSetOf(CardType.ARTIFACT, CardType.CREATURE))

/** "An artifact" (CR 301) — Krark-Clan Shaman's filter. */
private val ARTIFACT: SacrificeFilter = SacrificeFilter(persistentSetOf(CardType.ARTIFACT))

/**
 * Eviscerator's Insight — `{1}{B}` Instant. "As an additional cost to cast this spell, sacrifice an
 * artifact or creature. Draw two cards. Flashback {4}{B}."
 *
 * The framework's witness that **a card's additional cost applies to a permission cast too**: flashback's
 * own reminder text says "for its flashback cost **and any additional costs**" (CR 702.34a), so the
 * graveyard cast sacrifices as well. That falls out of where the cost is declared — on the definition,
 * not on the permission — rather than from a special case, and the engine gates the flashback cast on
 * the sacrifice being payable exactly as it gates the hand cast.
 */
val evisceratorsInsight: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Eviscerator's Insight",
                manaCost = ManaCost.parse("{1}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val additionalCost = AdditionalCost.Sacrifice(count = 1, filter = ARTIFACT_OR_CREATURE)
        override val castingPermissions = listOf(CastingPermission.Flashback(ManaCost.parse("{4}{B}")))
        override val resolution =
            ResolutionEffect { state, context -> drawCards(state, context.controller, EVISCERATORS_INSIGHT_DRAW) }
    }

/**
 * Reckoner's Bargain — `{1}{B}` Instant. "As an additional cost to cast this spell, sacrifice an
 * artifact or creature. You gain life equal to the sacrificed permanent's mana value. Draw two cards."
 *
 * The framework's witness that a sacrifice cost's **result is linked information**. The permanent is
 * gone by the time the spell resolves — it was sacrificed as the cost was paid — so "the sacrificed
 * permanent's mana value" is read from last-known information (CR 608.2h), captured on the cast record
 * and handed to the resolution as
 * [dev.mtgplay.core.definition.ResolutionContext.sacrificedForCost]. This is the [grabThePrize]
 * precedent (its "if the discarded card wasn't a land card") applied to a sacrifice.
 *
 * The mana value is the sacrificed card's printed one (CR 202.3), read from the definition registry; a
 * token and a land both have none, which is mana value 0 and no life gained (CR 119.3 makes gaining 0
 * a no-op).
 */
val reckonersBargain: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Reckoner's Bargain",
                manaCost = ManaCost.parse("{1}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val additionalCost = AdditionalCost.Sacrifice(count = 1, filter = ARTIFACT_OR_CREATURE)
        override val resolution =
            ResolutionEffect { state, context ->
                // CR 202.3 / CR 608.2h: the mana value of what was sacrificed, from last-known information.
                val manaValue =
                    context.sacrificedForCost.sumOf { card -> printedManaValue(state.definitions[card]) }
                val gained = gainLife(state, context.controller, manaValue)
                drawCards(gained, context.controller, RECKONERS_BARGAIN_DRAW)
            }
    }

/** The printed mana value of [definition] (CR 202.3); 0 for a land, a token, or an unregistered card. */
private fun printedManaValue(definition: CardDefinition?): Int = definition?.characteristics?.manaCost?.manaValue ?: 0

/**
 * Krark-Clan Shaman — `{R}` Creature — Goblin Shaman 1/1. "Sacrifice an artifact: This creature deals 1
 * damage to each creature without flying."
 *
 * The framework's witness for an ability whose **whole cost is a chosen sacrifice** — no mana, no `{T}`,
 * so it is repeatable as often as there are artifacts and it works the turn the Shaman arrives (CR 302.6
 * gates only `{T}`). The Shaman itself is no artifact, so it never appears among its own cost's options;
 * "another" is not printed and is not encoded (see [AbilityCost.Sacrifice]).
 *
 * The damage is a sweeper under **either** controller — including the Shaman itself, which is a creature
 * without flying and so damages itself — composing [dealDamageToEachPermanent] with the printed
 * qualifier as its predicate, the [breathWeapon] precedent. Damage is marked (CR 120.3d) and nothing
 * dies during resolution; the lethal-damage state-based action acts at the next check (CR 704.5g).
 */
val krarkClanShaman: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Krark-Clan Shaman",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Goblin"), Subtype("Shaman")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // CR 608.3: a creature spell's resolution is the engine's move onto the battlefield.
        override val resolution = ResolutionEffect { state, _ -> state }
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.Sacrifice(ARTIFACT)),
                    effect =
                        ResolutionEffect { state, context ->
                            dealDamageToEachPermanent(
                                state,
                                context.damageSource(),
                                KRARK_CLAN_SHAMAN_DAMAGE,
                            ) { s, obj ->
                                isCreaturePermanent(s, obj) && !hasFlyingPermanent(s, obj)
                            }
                        },
                ),
            )
    }

/**
 * Makeshift Munitions — `{1}{R}` Enchantment. "{1}, Sacrifice an artifact or creature: This enchantment
 * deals 1 damage to any target."
 *
 * The framework's witness for a **mana component beside a chosen sacrifice**, which is the pair that
 * constrains itself: which permanent is sacrificed decides what may be tapped for the `{1}`. Sacrificing
 * an artifact the player has already tapped for that `{1}` is legal and stays enumerated; only a
 * permanent that produces mana *by* being sacrificed is excluded from funding the cost it is about to
 * pay (docs/design/mana-payment.md §2.2).
 *
 * The Munitions is an enchantment, so it is neither an artifact nor a creature and never appears among
 * its own cost's options.
 */
val makeshiftMunitions: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Makeshift Munitions",
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // CR 608.3: an enchantment spell's resolution is the engine's move onto the battlefield.
        override val resolution = ResolutionEffect { state, _ -> state }
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    // CR 602.1: printed order — the mana is paid before the sacrifice.
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{1}")),
                            AbilityCost.Sacrifice(ARTIFACT_OR_CREATURE),
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            dealDamage(
                                state,
                                context.damageSource(),
                                context.targets.single(),
                                MAKESHIFT_MUNITIONS_DAMAGE,
                            )
                        },
                    targetSpec = TargetSpec.AnyTarget,
                ),
            )
    }
