package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.dealDamage
import kotlinx.collections.immutable.persistentSetOf

/** The damage a resolving Lightning Bolt deals — the printed "3" of its one instruction. */
const val LIGHTNING_BOLT_DAMAGE: Int = 3

/**
 * Lightning Bolt — `{R}` instant: "Lightning Bolt deals 3 damage to any target."
 *
 * "Any target" (CR 115.4) is one target that may be a creature, player, planeswalker, or
 * battle; until Phase 3 puts targetable objects on the battlefield the engine enumerates
 * players only ([TargetSpec.AnyTarget]). Resolution deals the damage through the published
 * damage primitive (ADR-003 vocabulary discipline — `dev.mtgplay.rules.effect.dealDamage`),
 * so a bolted player loses that much life as the damage's result (CR 120.3a), distinct from
 * pure life loss.
 */
val lightningBolt: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Lightning Bolt",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.AnyTarget
        override val resolution =
            ResolutionEffect { state, context ->
                dealDamage(state, context.damageSource(), context.targets.single(), LIGHTNING_BOLT_DAMAGE)
            }
    }
