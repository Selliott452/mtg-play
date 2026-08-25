package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.effect.dealDamage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Lotleth Giant — the demonstration card for `FW-ABILTGT` (docs/design/targeted-abilities.md §7).
 *
 * Of the thirty-two gauntlet cards that framework blocks, three name it as their only framework
 * blocker, and this is the only one of the three that needs no *further* primitive either: its whole
 * printed text is a vanilla body plus one enters-the-battlefield trigger that targets. So it is the
 * framework and nothing else, which is what a framework packet's proof should be.
 *
 * The damage amount is a **pure read of [GameState]** from inside the resolution effect — the
 * `Grab the Prize` / `Ethereal Armor` precedent: a state-dependent amount is card-side vocabulary and
 * needs no engine work (ADR-003).
 */

/** How much damage Lotleth Giant's trigger deals per creature card in its controller's graveyard. */
private const val LOTLETH_GIANT_DAMAGE_PER_CREATURE_CARD: Int = 1

/**
 * The creature cards in [controller]'s graveyard (CR 404), reading printed types from the definition
 * registry exactly as `Ethereal Armor` counts enchantments. A graveyard card with no definition is
 * inert to the engine and so is not counted — the same reading every other card-type count in the pool
 * makes.
 */
private fun creatureCardsInGraveyard(
    state: GameState,
    controller: PlayerId,
): Int {
    val graveyard =
        state.players[controller]?.graveyard
            ?: error("CR 404: Lotleth Giant's controller $controller is not seated")
    return graveyard.count { card ->
        state.definitions[card.card]
            ?.characteristics
            ?.cardTypes
            ?.contains(CardType.CREATURE) == true
    }
}

/**
 * Lotleth Giant — `{6}{B}` Creature — Zombie Giant, a 6/5 whose enters-the-battlefield trigger
 * (CR 603.6a) deals 1 damage to **target opponent** for each creature card in its controller's
 * graveyard.
 *
 * The first card in the pool whose *ability* targets: the trigger's target is chosen as the ability is
 * put on the stack (CR 603.3d — not when it fires, not when it resolves) and re-checked on resolution
 * (CR 608.2b). The creature spell itself is untargeted and sorcery-speed like any other (CR 302.1);
 * `TargetSpec.TargetOpponent` sits on the [TriggeredAbility], not on the card.
 *
 * "Undergrowth" is an ability word (CR 207.2c) with no rules meaning; it only names the
 * graveyard-counting theme.
 *
 * The count is read from the state **at resolution**, so a creature card that reaches the graveyard
 * while the trigger waits on the stack is counted. That is correct: nothing here is snapshotted —
 * CR 608.2h concerns values an effect has already determined, and this effect determines its amount as
 * it resolves.
 */
val lotlethGiant: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Lotleth Giant",
                manaCost = ManaCost.parse("{6}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Zombie"), Subtype("Giant")),
                powerToughness = PrintedPowerToughness(power = 6, toughness = 5),
            )

        // CR 302.1: a creature spell is cast at sorcery speed, targeting nothing — the *ability* targets.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec = TargetSpec.TargetOpponent,
                    effect =
                        ResolutionEffect { state, context ->
                            dealDamage(
                                state,
                                context.damageSource(),
                                context.targets.single(),
                                LOTLETH_GIANT_DAMAGE_PER_CREATURE_CARD *
                                    creatureCardsInGraveyard(state, context.controller),
                            )
                        },
                ),
            )
    }
