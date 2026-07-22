package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.engine.CreatureDeathCause
import dev.mtgplay.rules.engine.StateBasedAction
import dev.mtgplay.rules.engine.applicableStateBasedActions
import dev.mtgplay.rules.engine.isTargetLegal
import dev.mtgplay.rules.engine.legalTargets
import dev.mtgplay.rules.engine.performCreatureDeaths
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The P3.2 lethality core, unit-level: permanent-spell resolution (CR 608.3), the creature-death
 * state-based actions (CR 704.5f/g), and creature targetability (CR 115.4). Combat's end-to-end
 * deaths live in `CombatDamageScenarioSpec`; this pins the pieces directly, including the
 * CR 704.5f case that is unreachable end-to-end in the P3.2 pool (no effect lowers printed
 * toughness until the layer system, Phase 4).
 */
class CreatureLethalitySpec :
    StringSpec({

        "CR 608.3 and CR 400.7: a resolving creature spell enters the battlefield as a fresh, summoning-sick object" {
            val ref = CardRef("Sapling")
            val definition = creatureSpell("Sapling", power = 2, toughness = 2)
            val stackObject = GameObject(ObjectId(0), ref, alice)
            val state =
                bareState(
                    stack = persistentListOf(StackEntry.Spell(stackObject, alice, persistentListOf(), definition)),
                    nextObjectId = 1,
                    definitions = persistentMapOf(ref to definition),
                )

            val entered = resolveTopOfStack(state).pausedState

            val permanent = entered.sharedZones.battlefield.single()
            permanent.card shouldBe ref
            permanent.owner shouldBe alice
            // CR 302.6 / CR 608.3: enters summoning sick, untapped, with no marked damage.
            permanent.summoningSick.shouldBeTrue()
            permanent.tapped.shouldBeFalse()
            permanent.damageMarked shouldBe 0
            // CR 400.7: the battlefield object is a *new* object, not the stack object.
            permanent.id shouldNotBe stackObject.id
            entered.sharedZones.stack.shouldBeEmpty()

            val event = entered.events.filterIsInstance<GameEvent.PermanentEntered>().single()
            event.objectId shouldBe stackObject.id
            event.battlefieldObjectId shouldBe permanent.id
            event.card shouldBe ref
        }

        "CR 704.5g: a creature with lethal marked damage is a destruction death, not a toughness death" {
            val bearId = ObjectId(0)
            val ref = CardRef("Bear")
            val state =
                bareState(
                    battlefield = persistentListOf(GameObject(bearId, ref, bob, damageMarked = 2)),
                    nextObjectId = 1,
                    definitions = persistentMapOf(ref to creatureBody("Bear", 2, 2)),
                )
            applicableStateBasedActions(state) shouldContainExactly
                listOf(StateBasedAction.CreatureDies(bearId, CreatureDeathCause.LETHAL_DAMAGE))
        }

        "CR 704.5g: sublethal marked damage is not a death (marked below toughness)" {
            val ref = CardRef("Bear")
            val state =
                bareState(
                    battlefield = persistentListOf(GameObject(ObjectId(0), ref, bob, damageMarked = 1)),
                    nextObjectId = 1,
                    definitions = persistentMapOf(ref to creatureBody("Bear", 2, 2)),
                )
            applicableStateBasedActions(state).shouldBeEmpty()
        }

        "CR 704.5f: 0 toughness is a graveyard move (not destruction) that outranks CR 704.5g" {
            // Toughness 0 *and* marked damage: CR 704.5f applies (a non-destruction graveyard move),
            // not CR 704.5g, which only ever applies to a creature with toughness greater than 0.
            val wispId = ObjectId(0)
            val ref = CardRef("Wisp")
            val state =
                bareState(
                    battlefield = persistentListOf(GameObject(wispId, ref, alice, damageMarked = 5)),
                    nextObjectId = 1,
                    definitions = persistentMapOf(ref to creatureBody("Wisp", 1, 0)),
                )
            applicableStateBasedActions(state) shouldContainExactly
                listOf(StateBasedAction.CreatureDies(wispId, CreatureDeathCause.ZERO_OR_LESS_TOUGHNESS))
        }

        "CR 704.5 and CR 400.7: a dying creature goes to its owner's graveyard as a fresh object" {
            val bearId = ObjectId(0)
            val ref = CardRef("Bear")
            val state =
                bareState(
                    battlefield = persistentListOf(GameObject(bearId, ref, bob, damageMarked = 2)),
                    nextObjectId = 1,
                    definitions = persistentMapOf(ref to creatureBody("Bear", 2, 2)),
                )

            val after = performCreatureDeaths(state, listOf(bearId))

            after.sharedZones.battlefield.shouldBeEmpty()
            val corpse =
                after.players
                    .getValue(bob)
                    .graveyard
                    .single()
            corpse.card shouldBe ref
            // CR 400.7: a new object, with no marked damage carried across the move.
            corpse.id shouldNotBe bearId
            corpse.damageMarked shouldBe 0
            after.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            after.events.filterIsInstance<GameEvent.CreatureDied>().single().let {
                it.objectId shouldBe bearId
                it.graveyardObjectId shouldBe corpse.id
            }
        }

        "CR 115.4: any-target enumerates players then battlefield creatures — a creature is targetable" {
            val creatureId = ObjectId(0)
            val ref = CardRef("Bear")
            val state =
                bareState(
                    battlefield = persistentListOf(GameObject(creatureId, ref, bob)),
                    nextObjectId = 1,
                    definitions = persistentMapOf(ref to creatureBody("Bear", 2, 2)),
                )
            legalTargets(state, TargetSpec.AnyTarget, alice) shouldContainExactly
                listOf(Target.Player(alice), Target.Player(bob), Target.Permanent(creatureId))
            isTargetLegal(state, TargetSpec.AnyTarget, Target.Permanent(creatureId), alice).shouldBeTrue()
        }

        "CR 608.2b: a targeted creature stops being a legal target the moment it leaves the battlefield" {
            val creatureId = ObjectId(0)
            val ref = CardRef("Bear")
            val onBattlefield =
                bareState(
                    battlefield = persistentListOf(GameObject(creatureId, ref, bob)),
                    nextObjectId = 1,
                    definitions = persistentMapOf(ref to creatureBody("Bear", 2, 2)),
                )
            isTargetLegal(onBattlefield, TargetSpec.AnyTarget, Target.Permanent(creatureId), alice).shouldBeTrue()

            // The same creature, gone from the battlefield: no longer in the legal enumeration.
            val gone =
                onBattlefield.copy(
                    sharedZones = onBattlefield.sharedZones.copy(battlefield = persistentListOf()),
                )
            isTargetLegal(gone, TargetSpec.AnyTarget, Target.Permanent(creatureId), alice).shouldBeFalse()
        }
    })

// A creature card definition (battlefield body only): printed type, P/T box, no abilities.
private fun creatureBody(
    name: String,
    power: Int,
    toughness: Int,
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
            )
    }

// A castable creature definition: a sorcery-speed, untargeted permanent spell with a no-op
// resolution effect (CR 608.3: the engine, not the effect, performs the enter-the-battlefield move).
private fun creatureSpell(
    name: String,
    power: Int,
    toughness: Int,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power, toughness),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

// A minimal paused two-player state: alice active in her precombat main, both seats at 20 life with
// empty personal zones, and the given shared zones / registry. Valid engine input by construction.
private fun bareState(
    battlefield: PersistentList<GameObject> = persistentListOf(),
    stack: PersistentList<StackEntry> = persistentListOf(),
    nextObjectId: Long,
    definitions: PersistentMap<CardRef, CardDefinition> = persistentMapOf(),
): GameState {
    fun seat() = PlayerState(STARTING_LIFE, persistentListOf(), persistentListOf(), persistentListOf())
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(battlefield = battlefield, stack = stack, exile = persistentListOf()),
        nextObjectId = nextObjectId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = definitions,
    )
}
