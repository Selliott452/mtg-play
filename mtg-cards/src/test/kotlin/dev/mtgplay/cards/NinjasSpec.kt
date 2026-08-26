package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.DiscardExemption
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * Ninja of the Deep Hours and Harrier Strix against their oracle text (CR 201–208), and the two
 * resolutions Harrier Strix actually performs.
 */
class NinjasSpec :
    StringSpec({
        val alice = PlayerId(0)
        val bob = PlayerId(1)

        "CR 202/205/208: Ninja of the Deep Hours is a {3}{U} 2/2 Human Ninja" {
            val printed = ninjaOfTheDeepHours.characteristics
            printed.manaCost shouldBe ManaCost.parse("{3}{U}")
            printed.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Human"), Subtype("Ninja"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 2, toughness = 2)
        }

        "CR 702.49a: Ninja of the Deep Hours declares Ninjutsu {1}{U}, and it is not a casting permission" {
            ninjaOfTheDeepHours.ninjutsu
                .shouldNotBeNull()
                .cost shouldBe ManaCost.parse("{1}{U}")
            // Ninjutsu never casts anything, so it must not ride the alternative-cast contract.
            ninjaOfTheDeepHours.castingPermissions.shouldHaveSize(0)
        }

        "CR 510.2 and CR 601.3b: the ninja's combat-damage trigger offers an optional single draw" {
            val trigger = ninjaOfTheDeepHours.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.DealtCombatDamageToPlayerSelf
            trigger.optionalDraw.shouldNotBeNull().drawCount shouldBe NINJA_OF_THE_DEEP_HOURS_DRAW
        }

        "CR 202/205/208: Harrier Strix is a {U} 1/1 Bird with flying" {
            val printed = harrierStrix.characteristics
            printed.manaCost shouldBe ManaCost.parse("{U}")
            printed.subtypes shouldBe persistentSetOf(Subtype("Bird"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 1)
            printed.keywords shouldBe persistentSetOf(Keyword.FLYING)
        }

        "CR 115.1: Harrier Strix's enters trigger targets any permanent, not only a creature" {
            val trigger = harrierStrix.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            trigger.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.ANY_PERMANENT)
        }

        "CR 701.21a: the Strix's trigger taps its target and narrates it" {
            val target = GameObject(ObjectId(0), CardRef("Grizzly Bears"), bob)
            val state = boardWith(alice, bob, target)
            val resolved =
                harrierStrix.triggeredAbilities.single().effect.resolve(
                    state,
                    ResolutionContext(alice, persistentListOf(Target.Permanent(target.id))),
                )
            resolved.sharedZones.battlefield
                .single()
                .tapped shouldBe true
            resolved.events.filterIsInstance<GameEvent.ObjectTapped>().shouldHaveSize(1)
        }

        "CR 701.21a: tapping an already-tapped permanent changes nothing and narrates nothing" {
            val target = GameObject(ObjectId(0), CardRef("Grizzly Bears"), bob, tapped = true)
            val state = boardWith(alice, bob, target)
            val resolved =
                harrierStrix.triggeredAbilities.single().effect.resolve(
                    state,
                    ResolutionContext(alice, persistentListOf(Target.Permanent(target.id))),
                )
            resolved shouldBe state
        }

        "CR 601.2c: Harrier Strix's {2}{U} ability loots through the clause hook, not a bespoke effect" {
            val ability = harrierStrix.activatedAbilities.single()
            ability.drawThenDiscard.shouldNotBeNull().let { clause ->
                clause.drawCount shouldBe HARRIER_STRIX_LOOT_DRAW
                clause.discardCount shouldBe HARRIER_STRIX_LOOT_DISCARD
            }
        }

        "CR 202/205/208: Moon-Circuit Hacker is a {1}{U} 2/1 Enchantment Creature — Human Ninja" {
            val printed = moonCircuitHacker.characteristics
            printed.manaCost shouldBe ManaCost.parse("{1}{U}")
            printed.cardTypes shouldBe persistentSetOf(CardType.ENCHANTMENT, CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Human"), Subtype("Ninja"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 2, toughness = 1)
        }

        "CR 702.49a: Moon-Circuit Hacker declares Ninjutsu {U}, and it is not a casting permission" {
            moonCircuitHacker.ninjutsu
                .shouldNotBeNull()
                .cost shouldBe ManaCost.parse("{U}")
            moonCircuitHacker.castingPermissions.shouldHaveSize(0)
        }

        "CR 601.3b/701.8: the Hacker's combat-damage trigger is one optional draw with a conditional discard" {
            val trigger = moonCircuitHacker.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.DealtCombatDamageToPlayerSelf
            // The whole printed tail is a single clause: two clauses side by side would discard even
            // when the draw was declined, which "if you do" forbids.
            trigger.optionalDraw shouldBe null
            trigger.drawThenDiscard shouldBe null
            trigger.optionalDrawThenDiscard.shouldNotBeNull().let { clause ->
                clause.drawCount shouldBe MOON_CIRCUIT_HACKER_DRAW
                clause.discardCount shouldBe MOON_CIRCUIT_HACKER_DISCARD
                // "unless this creature entered this turn" — CR 603.6a, not CR 302.6 summoning sickness.
                clause.skipDiscardWhen shouldBe DiscardExemption.SOURCE_ENTERED_THIS_TURN
            }
        }
    })

/** A minimal two-seat state whose battlefield holds exactly [permanent]. */
private fun boardWith(
    alice: PlayerId,
    bob: PlayerId,
    permanent: GameObject,
): GameState {
    fun seat() =
        PlayerState(
            life = 20,
            library = persistentListOf(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
        )
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(permanent),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = permanent.id.value + 1,
        rng = Rng(0),
        events = persistentListOf(),
    )
}
