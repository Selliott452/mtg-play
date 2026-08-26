package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.StaticContinuousEffect
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
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.effect.attachPermanent
import dev.mtgplay.rules.effect.destroy
import dev.mtgplay.rules.effect.gainEnergy
import dev.mtgplay.rules.engine.SbaOutcome
import dev.mtgplay.rules.engine.StateBasedAction
import dev.mtgplay.rules.engine.applicableStateBasedActions
import dev.mtgplay.rules.engine.layeredCharacteristics
import dev.mtgplay.rules.engine.performStateBasedActions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * Equipment (CR 301.5, CR 702.6) and energy (CR 107.16) — `FW-EQUIP`, over fixtures, because
 * `mtg-rules` names no card (ADR-003).
 *
 * The property that matters most has no equivalent anywhere else in the engine: **CR 704.5n and
 * CR 704.5m fire on the same condition and do opposite things.** A dangling Aura is put into its
 * owner's graveyard; a dangling Equipment becomes unattached and stays on the battlefield. Both halves
 * are asserted against the same dead host here, because a shared "dangling attachment" implementation
 * would pass either one alone.
 */
class EquipSpec :
    StringSpec({

        val engine = DefaultGameEngine()

        "CR 613.4c: an attached Equipment's static bonus reaches its host, exactly as an Aura's does" {
            val attached = attachPermanent(equipState(), HARNESS, BEAR)
            // The +2/+0 is declared over AffectedSet.Enchanted and needs no Equipment-specific machinery:
            // "equipped creature" and "enchanted creature" are one object relation with two names.
            layeredCharacteristics(attached, BEAR).power shouldBe 4
            layeredCharacteristics(attached, BEAR).toughness shouldBe 2
            layeredCharacteristics(equipState(), BEAR).power shouldBe 2
        }

        "CR 701.3a: attaching an already-attached Equipment moves it, and the old host loses the bonus" {
            val moved = attachPermanent(attachPermanent(equipState(), HARNESS, BEAR), HARNESS, OGRE)
            layeredCharacteristics(moved, BEAR).power shouldBe 2
            layeredCharacteristics(moved, OGRE).power shouldBe 5
            moved.sharedZones.battlefield
                .single { it.id == HARNESS }
                .attachedTo shouldBe OGRE
        }

        "CR 704.5n: when its host dies the Equipment unattaches and *stays on the battlefield*" {
            val hostGone = destroy(attachPermanent(equipState(), HARNESS, BEAR), BEAR)

            // The condition is detected as its own action, not as an Aura fall-off.
            applicableStateBasedActions(hostGone) shouldContainExactly
                listOf(StateBasedAction.EquipmentUnattaches(HARNESS))

            val settled = performStateBasedActions(hostGone).shouldBeInstanceOf<SbaOutcome.Continued>().state
            val harness = settled.sharedZones.battlefield.single { it.id == HARNESS }
            // The whole difference from CR 704.5m: it lets go and remains a permanent, ready to be
            // equipped onto something else. An Aura in the same position is in a graveyard by now.
            harness.attachedTo.shouldBeNull()
            settled.players
                .getValue(alice)
                .graveyard
                .none { it.card == HARNESS_REF } shouldBe true
            settled.events
                .filterIsInstance<GameEvent.EquipmentUnattached>()
                .single()
                .objectId shouldBe HARNESS
        }

        "CR 704.5m vs CR 704.5n: the same dead host bins an Aura and merely frees an Equipment" {
            // One creature wearing both an Equipment and an Aura, then killed. Two state-based actions
            // on one condition, and they must disagree about the outcome.
            val bothAttached = attachPermanent(equipStateWithAura(), HARNESS, BEAR)
            val settled =
                performStateBasedActions(destroy(bothAttached, BEAR))
                    .shouldBeInstanceOf<SbaOutcome.Continued>()
                    .state

            settled.sharedZones.battlefield.map { it.id } shouldContainExactly listOf(HARNESS, OGRE, MEADOW)
            settled.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly listOf(BEAR_REF, CLOAK_REF)
        }

        "CR 301.5b: an Equipment attached to a non-creature permanent unattaches too" {
            // Not merely a *gone* host: a permanent that is present and is not a creature is an illegal
            // host all the same, which is the half a bare dangling-reference check would miss entirely.
            applicableStateBasedActions(attachPermanent(equipState(), HARNESS, MEADOW)) shouldContainExactly
                listOf(StateBasedAction.EquipmentUnattaches(HARNESS))
        }

        "CR 704.5n: a legally attached Equipment is left alone" {
            applicableStateBasedActions(attachPermanent(equipState(), HARNESS, BEAR)).shouldBeEmpty()
        }

        "CR 118.4: the equip ability is enumerated only once its controller has the energy" {
            // ADR-005: below the threshold the option is not offered at all, rather than offered and
            // then dead-ending on an unpayable cost.
            equipOptionCount(equipState()) shouldBe 0
            equipOptionCount(gainEnergy(equipState(), alice, 1)) shouldBe 0
            equipOptionCount(gainEnergy(equipState(), alice, EQUIP_ENERGY)) shouldBe 1
            equipOptionCount(gainEnergy(equipState(), alice, EQUIP_ENERGY + 5)) shouldBe 1
        }

        "CR 602.2b: activating equip pays the energy off the player's running total, as a cost" {
            val ready = gainEnergy(equipState(), alice, 3)
            ready.players.getValue(alice).energyCounters shouldBe 3

            val window = pausedRequestOf<DecisionRequest.ChooseAction>(ready)
            val index =
                window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == HARNESS_REF }
            val activated = engine.advance(ready, Decision.SingleSelect(window.id, index))
            // Equip targets a creature you control, and both are offered (CR 702.6b, ADR-005).
            val targets = activated.pending<DecisionRequest.ChooseTargets>()
            targets.options.size shouldBe 2
            val chosen = engine.advance(activated.pausedState, Decision.SingleSelect(targets.id, 0))

            // Energy is a *cost*, so it is gone the moment the ability reaches the stack (CR 602.2b) —
            // not when it resolves, and not refunded if it is countered.
            val onStack = chosen.pausedState
            onStack.players.getValue(alice).energyCounters shouldBe 1
            onStack.events
                .filterIsInstance<GameEvent.EnergyCountersPaid>()
                .single()
                .amount shouldBe EQUIP_ENERGY
        }

        "CR 107.16: energy accumulates per player and nothing takes it away but a cost" {
            val got = gainEnergy(gainEnergy(equipState(), alice, 2), alice, 2)
            got.players.getValue(alice).energyCounters shouldBe 4
            // Per-player, like life: only the controller of the effect got any.
            got.players.getValue(bob).energyCounters shouldBe 0
        }

        "CR 122.1: a player's energy total is never negative" {
            shouldThrow<IllegalArgumentException> {
                equipState().players.getValue(alice).copy(energyCounters = -1)
            }
        }
    })

private val HARNESS = ObjectId(0)
private val BEAR = ObjectId(1)
private val OGRE = ObjectId(2)
private val MEADOW = ObjectId(3)
private val CLOAK = ObjectId(4)

private val HARNESS_REF = CardRef("Fixture Harness")
private val BEAR_REF = CardRef("Equip Bear")
private val CLOAK_REF = CardRef("Fixture Cloak")

private const val EQUIP_ENERGY: Int = 2
private const val EQUIP_BONUS: Int = 2

/** How many equip activations the priority window offers Alice on [state] (ADR-005). */
private fun equipOptionCount(state: GameState): Int =
    pausedRequestOf<DecisionRequest.ChooseAction>(state)
        .options
        .count { it is PriorityOption.ActivateAbility && it.card == HARNESS_REF }

/**
 * A board with the Equipment, two creatures of different sizes and a non-creature permanent — and
 * deliberately **no Aura**, because an Aura attached to nothing is itself a CR 704.5m fall-off and
 * every one of these tests is about telling that action apart from CR 704.5n.
 */
private fun equipState(): GameState = equipBoard(withAura = false)

/**
 * The same board plus the Aura fixture, already attached to the Bear — the one board that can show
 * CR 704.5m and CR 704.5n doing opposite things to the same dead host.
 */
private fun equipStateWithAura(): GameState = equipBoard(withAura = true)

private fun equipBoard(withAura: Boolean): GameState {
    val aura = if (withAura) listOf(GameObject(CLOAK, CLOAK_REF, alice, attachedTo = BEAR)) else emptyList()
    val battlefield =
        (
            listOf(
                GameObject(HARNESS, HARNESS_REF, alice),
                GameObject(BEAR, BEAR_REF, alice, summoningSick = false),
                GameObject(OGRE, CardRef("Equip Ogre"), alice, summoningSick = false),
                GameObject(MEADOW, CardRef("Meadow"), alice, summoningSick = false),
            ) + aura
        ).toPersistentList()

    fun seat(holder: Boolean) =
        PlayerState(
            life = STARTING_LIFE,
            library = persistentListOf(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
            priorityStatus = if (holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
        )
    return GameState(
        players = persistentMapOf(alice to seat(true), bob to seat(false)),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(battlefield, persistentListOf(), persistentListOf()),
        nextObjectId = 5,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = equipDefinitions.toPersistentMap(),
    )
}

/** A creature body fixture for this spec. */
private fun equipCreature(
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

/**
 * "Fixture Harness" — an Equipment whose equip cost is `{E}{E}` and whose bonus is `+2/+0`. The subtype
 * is the whole of what makes it an Equipment (CR 301.5a), and the static is an ordinary layer-7c effect
 * over the attached object, identical in shape to the Aura fixtures'.
 */
private val fixtureHarness: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Harness",
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(Subtype("Equipment")),
                powerToughness = null,
            )
        override val staticContinuousEffects =
            persistentListOf(
                StaticContinuousEffect(affects = AffectedSet.Enchanted, powerMod = Magnitude.Fixed(EQUIP_BONUS)),
            )
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.Energy(EQUIP_ENERGY)),
                    timing = TimingClass.SORCERY_SPEED,
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_YOU_CONTROL),
                    effect = ResolutionEffect { s, _ -> s },
                ),
            )
    }

private val equipDefinitions: Map<CardRef, CardDefinition> =
    auraDefinitions +
        listOf(
            fixtureHarness,
            equipCreature("Equip Bear", 2, 2),
            equipCreature("Equip Ogre", 3, 3),
        ).associateBy { CardRef(it.characteristics.name) }
