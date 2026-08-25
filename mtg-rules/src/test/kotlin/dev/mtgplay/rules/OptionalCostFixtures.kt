package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CastCondition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.dealDamage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Fixtures for the `FW-X`, `FW-OPTCOST` and `FW-ALTCOST` specs (CR 107.3, CR 702.33, CR 118.9).
 *
 * `mtg-rules` names no real card (PLAN.md §3), so every shape is synthetic here. **The `{X}` fixtures
 * carry the framework on their own**, because neither gauntlet card with a variable cost could ship:
 * Kaervek's Torch needs a cost *increase* keyed on another spell's targets and Nyxborn Hydra needs
 * bestow (see the packet report). That makes these fixtures the only witnesses `{X}` has, which is the
 * pattern `CastFromElsewhereFixtures` and `CostModificationFixtures` already set for a framework whose
 * pool cards are blocked elsewhere — and it is why the X specs go further than a card-level spec would,
 * asserting the *bound* rather than a single cast.
 */

/** The damage [fixtureSurge] deals — its announced value of X (CR 202.3b). */
internal const val FIXTURE_SURGE_MARKER: String = "Fixture Surge"

/**
 * "Fixture Surge" — `{X}{R}` Sorcery, "deals X damage to target player" (Kaervek's Torch's shape minus
 * the cost increase that keeps the real card out).
 *
 * The X reference fixture: a coloured pip beside the variable, so the bound is not simply "all the mana
 * I have" and a board of the wrong colour makes it zero.
 */
internal val fixtureSurge: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = FIXTURE_SURGE_MARKER,
                manaCost = ManaCost.parse("{X}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.TargetPlayer
        override val resolution =
            ResolutionEffect { state, context ->
                // CR 202.3b: the announced value, read off the cast record rather than the printed cost.
                dealDamage(state, context.damageSource(), context.targets.single(), context.chosenX)
            }
    }

/**
 * "Fixture Bolt-X" — `{X}` Sorcery with no coloured pip and no target, for the bound arithmetic alone.
 * Deliberately the widest possible X on any board: every mana the seat can produce is spendable on it.
 */
internal val fixtureAllX: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Bolt-X",
                manaCost = ManaCost.parse("{X}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/**
 * "Fixture Whacker" — `{R}` Creature 1/1 with `Kicker {R}` and Goblin Bushwhacker's intervening-if
 * enters trigger, whose effect marks the state by dealing 1 damage to its controller.
 *
 * The damage is a **marker**, not the card: what these specs assert is *whether the ability triggered at
 * all* (CR 603.4's first check), and a self-damage is the smallest observable an untargeted trigger can
 * leave in a fixture registry.
 */
internal val fixtureWhacker: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Whacker",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val kicker = ManaCost.parse("{R}")
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    interveningIf = InterveningIf.SourceWasKicked,
                    effect =
                        ResolutionEffect { state, context ->
                            dealDamage(
                                state,
                                context.damageSource(),
                                dev.mtgplay.core.state.Target
                                    .Player(context.controller),
                                FIXTURE_WHACKER_TRIGGER_DAMAGE,
                            )
                        },
                ),
            )
    }

/** The self-damage a kicked [fixtureWhacker]'s trigger deals, as a marker that it resolved. */
internal const val FIXTURE_WHACKER_TRIGGER_DAMAGE: Int = 1

/**
 * "Fixture Kicked Surge" — `{X}{R}` Sorcery with `Kicker {2}`, the one fixture carrying **both**
 * CR 601.2b announcements. It exists to pin their *interaction*: the affordable values of X are the
 * values affordable given the kicker answer, so the same board offers a wider X unkicked than kicked.
 */
internal val fixtureKickedSurge: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Kicked Surge",
                manaCost = ManaCost.parse("{X}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val kicker = ManaCost.parse("{2}")
    }

/**
 * "Fixture Grant" — `{1}{G}` Sorcery whose alternative cost is Land Grant's: free, gated on holding no
 * land card, and paid by revealing the hand (CR 118.9, CR 701.16a).
 */
internal val fixtureGrant: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Grant",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val castingPermissions =
            listOf(
                CastingPermission.AlternativeCost(
                    cost = ManaCost.parse("{0}"),
                    condition = CastCondition.NoLandCardsInHand,
                    revealsHand = true,
                ),
            )
    }

/** The registry these specs run against: the standard fixtures plus this packet's. */
internal val optionalCostDefinitions: Map<CardRef, dev.mtgplay.core.definition.CardDefinition> =
    fixtureDefinitions +
        listOf(fixtureSurge, fixtureAllX, fixtureWhacker, fixtureKickedSurge, fixtureGrant)
            .associateBy { CardRef(it.characteristics.name) }
