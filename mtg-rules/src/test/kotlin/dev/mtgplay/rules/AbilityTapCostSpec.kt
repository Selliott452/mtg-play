package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.StaticCondition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.ChosenPowerSource
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.effect.powerOfChosenSource
import dev.mtgplay.rules.effect.putCountersIfAny
import dev.mtgplay.rules.engine.layeredCharacteristics
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The **chosen-permanent tap cost** (CR 602.1, CR 701.20a) and the **counter-threshold static ability**
 * (CR 604.3, CR 122.6, CR 613.1d) that `W10-C` added — the two frameworks a Spacecraft's Station needs.
 * Fixtures only; `mtg-rules` names no card (ADR-003).
 *
 * The fixture rig mirrors the printed shape without being it: a noncreature artifact with a printed P/T
 * box (CR 208.1b), an ability whose only cost is "tap **another** creature you control", an effect that
 * puts that creature's layered power in charge counters on the source, and two static abilities
 * conditioned on a three-counter threshold — one adding the creature card type in layer 4, one granting
 * flying in layer 6. The threshold is three rather than seven so a scenario fits in one activation.
 *
 * What each test is actually protecting:
 *
 * - **"another" is enumeration, not decoration.** Once the rig is a creature it matches its own cost's
 *   filter, so the exclusion is the difference between a legal option list and one containing an
 *   activation whose payment would then throw (ADR-005).
 * - **The power is read on resolution, not when the cost is paid** (CR 608.2h), which is what makes
 *   responding to the ability a real line.
 * - **The type change is continuous** (CR 604.3): it appears the instant the threshold is met and would
 *   vanish the instant it stopped being met, with nothing on the stack and no player receiving priority.
 */
class AbilityTapCostSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 602.1: the tap cost's selection offers every untapped matching creature but the source" {
            // The rig is a noncreature artifact here, so it does not match its own "creature" filter
            // anyway; the exclusion is proved by the stationed case below. Two creatures, both offered.
            val state = rigState(counters = 0, bearsTapped = false)
            val request = beginStation(engine, state).pending<DecisionRequest.ChooseTapsForCost>()
            request.count shouldBe 1
            request.options.map { it.card.name } shouldContainExactly listOf(FIXTURE_BEAR, FIXTURE_DORK)
        }

        "CR 601.2h: a tapped creature is never offered to pay a tap cost" {
            val state = rigState(counters = 0, bearsTapped = true)
            val request = beginStation(engine, state).pending<DecisionRequest.ChooseTapsForCost>()
            request.options.map { it.card.name } shouldContainExactly listOf(FIXTURE_DORK)
        }

        "CR 109.5: a rig that is itself a creature is still excluded from its own \"another\" cost" {
            // At the threshold the rig gains the creature card type in CR 613 layer 4, so it now
            // matches `PermanentFilter(cardType = CREATURE, controlledByYou = true)` — and the printed
            // word "another" is the only thing that keeps it out of its own cost's option list.
            val state = rigState(counters = THRESHOLD, bearsTapped = false)
            layeredCharacteristics(state, RIG_ID).cardTypes.contains(CardType.CREATURE) shouldBe true
            val request = beginStation(engine, state).pending<DecisionRequest.ChooseTapsForCost>()
            request.options.none { it.objectId == RIG_ID } shouldBe true
        }

        "CR 602.2b: paying the cost taps the chosen creature and records it on the stack entry" {
            val state = rigState(counters = 0, bearsTapped = false)
            val begun = beginStation(engine, state)
            val request = begun.pending<DecisionRequest.ChooseTapsForCost>()
            val bearIndex = request.options.indexOfFirst { it.card.name == FIXTURE_BEAR }
            val paid = engine.advance(begun.pausedState, Decision.MultiSelect(request.id, listOf(bearIndex)))
            val paused = paid.pausedState
            paused.sharedZones.battlefield
                .single { it.id == BEAR_ID }
                .tapped shouldBe true
            val entry =
                paused.sharedZones.stack
                    .last()
                    .shouldBeInstanceOf<StackEntry.ActivatedAbilityOnStack>()
            entry.tappedForCost shouldContainExactly listOf(BEAR_ID)
            // Nothing has resolved yet: the counters arrive when the ability does.
            paused.sharedZones.battlefield
                .single { it.id == RIG_ID }
                .counters
                .isEmpty() shouldBe true
        }

        "CR 608.2h: the counters placed equal the tapped creature's power at resolution, not at payment" {
            val state = rigState(counters = 0, bearsTapped = false)
            val resolved = stationAndResolve(engine, state, FIXTURE_BEAR)
            resolved.sharedZones.battlefield
                .single { it.id == RIG_ID }
                .counters shouldBe persistentMapOf(Counter.Charge to BEAR_POWER)
        }

        "CR 122.1: a zero-power creature pays the cost and places no counters at all" {
            // The cost is still paid — the dork is tapped — and the ability still resolves. It simply
            // does nothing, which is the case putCountersIfAny exists for; putCounters would have thrown.
            val state = rigState(counters = 0, bearsTapped = false)
            val resolved = stationAndResolve(engine, state, FIXTURE_DORK)
            resolved.sharedZones.battlefield
                .single { it.id == DORK_ID }
                .tapped shouldBe true
            resolved.sharedZones.battlefield
                .single { it.id == RIG_ID }
                .counters
                .isEmpty() shouldBe true
        }

        "CR 604.3 + CR 613.1d: the rig is not a creature below the threshold and is one at it" {
            val below = rigState(counters = THRESHOLD - 1, bearsTapped = false)
            val belowTypes = layeredCharacteristics(below, RIG_ID)
            belowTypes.cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
            belowTypes.keywords.contains(Keyword.FLYING) shouldBe false
            // CR 208.1b: the printed P/T box is there the whole time, creature or not.
            belowTypes.power shouldBe RIG_POWER

            val at = layeredCharacteristics(rigState(counters = THRESHOLD, bearsTapped = false), RIG_ID)
            at.cardTypes shouldBe persistentSetOf(CardType.ARTIFACT, CardType.CREATURE)
            at.keywords.contains(Keyword.FLYING) shouldBe true
            at.power shouldBe RIG_POWER
            at.toughness shouldBe RIG_TOUGHNESS
        }

        "CR 604.3: the type change is continuous — removing a counter takes it away with nothing on the stack" {
            val at = rigState(counters = THRESHOLD, bearsTapped = false)
            layeredCharacteristics(at, RIG_ID).cardTypes.contains(CardType.CREATURE) shouldBe true
            val shrunk = at.withRigCounters(THRESHOLD - 1)
            shrunk.sharedZones.stack.isEmpty() shouldBe true
            layeredCharacteristics(shrunk, RIG_ID).cardTypes.contains(CardType.CREATURE) shouldBe false
            layeredCharacteristics(shrunk, RIG_ID).keywords.contains(Keyword.FLYING) shouldBe false
        }
    })

private const val FIXTURE_RIG = "Fixture Station Rig"

private const val FIXTURE_BEAR = "Fixture Station Bear"

private const val FIXTURE_DORK = "Fixture Station Dork"

private const val THRESHOLD = 3

private const val RIG_POWER = 5

private const val RIG_TOUGHNESS = 5

private const val BEAR_POWER = 2

private val RIG_ID = ObjectId(0)

private val BEAR_ID = ObjectId(1)

private val DORK_ID = ObjectId(2)

/** Starts the rig's one activated ability and returns the result, paused at its tap-cost selection. */
private fun beginStation(
    engine: DefaultGameEngine,
    state: GameState,
): AdvanceResult {
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
    val index =
        window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == CardRef(FIXTURE_RIG) }
    require(index >= 0) { "the rig's ability was not enumerated" }
    return engine.advance(state, Decision.SingleSelect(window.id, index))
}

/** Activates the rig tapping the named creature, then resolves the ability off the stack. */
private fun stationAndResolve(
    engine: DefaultGameEngine,
    state: GameState,
    creatureName: String,
): GameState {
    val begun = beginStation(engine, state)
    val request = begun.pending<DecisionRequest.ChooseTapsForCost>()
    val choice = request.options.indexOfFirst { it.card.name == creatureName }
    var current = engine.advance(begun.pausedState, Decision.MultiSelect(request.id, listOf(choice)))
    // Both seats pass, and the top of the stack resolves (CR 117.4).
    while (current.pausedState.sharedZones.stack
            .isNotEmpty()
    ) {
        val window = current.pending<DecisionRequest.ChooseAction>()
        val pass = window.options.indexOfFirst { it is PriorityOption.Pass }
        current = engine.advance(current.pausedState, Decision.SingleSelect(window.id, pass))
    }
    return current.pausedState
}

/** [this] with the rig carrying exactly [count] charge counters, everything else untouched. */
private fun GameState.withRigCounters(count: Int): GameState {
    val battlefield =
        sharedZones.battlefield
            .map { if (it.id == RIG_ID) it.copy(counters = chargeCounters(count)) else it }
            .toPersistentList()
    return copy(sharedZones = sharedZones.copy(battlefield = battlefield))
}

private fun chargeCounters(count: Int) =
    if (count == 0) persistentMapOf() else persistentMapOf<Counter, Int>(Counter.Charge to count)

/**
 * Alice holding priority in her precombat main with the rig (carrying [counters] charge counters), a
 * 2/2 bear and a 0/1 dork. Sorcery timing is satisfied: her turn, her main phase, an empty stack.
 */
private fun rigState(
    counters: Int,
    bearsTapped: Boolean,
): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield =
                    persistentListOf(
                        GameObject(RIG_ID, CardRef(FIXTURE_RIG), alice, counters = chargeCounters(counters)),
                        GameObject(BEAR_ID, CardRef(FIXTURE_BEAR), alice, tapped = bearsTapped),
                        GameObject(DORK_ID, CardRef(FIXTURE_DORK), alice),
                    ),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 3,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = tapCostRegistry.toPersistentMap(),
    )

/** The fixture definitions: the rig and the two creatures its cost may name. */
private val tapCostRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef(FIXTURE_RIG) to rigDefinition(),
        CardRef(FIXTURE_BEAR) to vanilla(FIXTURE_BEAR, BEAR_POWER, BEAR_POWER),
        // CR 208.3: a zero-power creature is a legal payer whose ability then places nothing.
        CardRef(FIXTURE_DORK) to vanilla(FIXTURE_DORK, 0, 1),
    )

private fun vanilla(
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
 * The fixture Spacecraft: a noncreature artifact with a printed P/T box (CR 208.1b), a sorcery-speed
 * ability costing "tap another creature you control", and two static abilities at a three-counter
 * threshold — the layer-4 type change and the layer-6 flying grant.
 */
private fun rigDefinition(): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = FIXTURE_RIG,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(Subtype("Spacecraft")),
                powerToughness = PrintedPowerToughness(RIG_POWER, RIG_TOUGHNESS),
            )

        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.TapPermanentYouControl(
                                filter = PermanentFilter(cardType = CardType.CREATURE, controlledByYou = true),
                                another = true,
                            ),
                        ),
                    timing = TimingClass.SORCERY_SPEED,
                    effect =
                        ResolutionEffect { state, context ->
                            val tapped = context.tappedForCost.single()
                            val power = powerOfChosenSource(state, ChosenPowerSource.ChosenCreature(tapped))
                            putCountersIfAny(state, requireNotNull(context.source), Counter.Charge, power)
                        },
                ),
            )

        override val staticContinuousEffects =
            persistentListOf(
                StaticContinuousEffect(
                    affects = AffectedSet.Self,
                    condition = StaticCondition.CountersOnSelf(Counter.Charge, THRESHOLD),
                    addedCardTypes = persistentSetOf(CardType.CREATURE),
                ),
                StaticContinuousEffect(
                    affects = AffectedSet.Self,
                    condition = StaticCondition.CountersOnSelf(Counter.Charge, THRESHOLD),
                    grantedKeywords = persistentSetOf(Keyword.FLYING),
                ),
            )
    }
