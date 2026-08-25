package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.gainLife
import dev.mtgplay.rules.engine.announceBattlefieldDeparture
import dev.mtgplay.rules.engine.finishCleanup
import dev.mtgplay.rules.engine.priorityTo
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P5.1 triggered-ability framework (CR 603) exercised at the rules level with fixture triggers
 * (the `mtg-rules`-names-no-card rule holds): trigger placement in APNAP order (CR 603.3b), the
 * OrderTriggers decision for a controller's simultaneous triggers, ability resolution with no card
 * move (CR 113.7a), the cast-trigger seam, and the CR 514.3a cleanup repeat path.
 */
class TriggeredAbilitySpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 603.3b: simultaneous triggers are put on the stack in APNAP order, non-active on top" {
            // Active player is alice; alice and bob each control one fired trigger.
            val state =
                triggerState(
                    pendingTriggers =
                        listOf(
                            firedTrigger(bob, gainLifeAbility(1), CardRef("Bob Source")),
                            firedTrigger(alice, gainLifeAbility(1), CardRef("Alice Source")),
                        ),
                )
            val paused = priorityTo(state, alice).pausedState
            val stack = paused.sharedZones.stack.map { (it as StackEntry.Ability).trigger.sourceCard }
            // CR 603.3b: the active player (alice) puts hers on the stack first (bottom); bob's is on
            // top and resolves first.
            stack shouldBe listOf(CardRef("Alice Source"), CardRef("Bob Source"))
            // Every fired trigger was placed; the queue is empty.
            paused.pendingTriggers.shouldBeEmpty()
        }

        "CR 603.3b: a controller with two simultaneous triggers is asked to order them (OrderTriggers)" {
            val state =
                triggerState(
                    pendingTriggers =
                        listOf(
                            firedTrigger(alice, gainLifeAbility(2), CardRef("First")),
                            firedTrigger(alice, gainLifeAbility(3), CardRef("Second")),
                        ),
                )
            val request = priorityTo(state, alice).pending<DecisionRequest.OrderTriggers>()
            request.seat shouldBe alice
            request.options.map { it.sourceCard } shouldBe listOf(CardRef("First"), CardRef("Second"))

            // Order them Second-then-First: Second is put on the stack first (bottom), First on top.
            val ordered =
                engine.advance(
                    priorityTo(state, alice).pausedState,
                    Decision.MultiSelect(request.id, listOf(1, 0)),
                )
            val stack =
                ordered.pausedState.sharedZones.stack
                    .map { (it as StackEntry.Ability).trigger.sourceCard }
            stack shouldBe listOf(CardRef("Second"), CardRef("First"))
        }

        "CR 113.7a: a triggered ability resolves, performs its effect, and no card moves" {
            val ability = gainLifeAbility(GAIN)
            val state =
                triggerState(
                    stack = listOf(StackEntry.Ability(firedTrigger(alice, ability, CardRef("Gainer")))),
                )
            val resolved = resolveTopOfStack(state).pausedState
            // The effect happened: alice gained life.
            resolved.players.getValue(alice).life shouldBe STARTING_LIFE + GAIN
            // The ability ceased to exist: the stack is empty and no card entered any graveyard
            // (an ability is not a card, CR 113.7a).
            resolved.sharedZones.stack.shouldBeEmpty()
            resolved.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            resolved.players
                .getValue(bob)
                .graveyard
                .shouldBeEmpty()
            resolved.events.filterIsInstance<GameEvent.TriggeredAbilityResolved>() shouldHaveSize 1
        }

        "CR 603.10: a put-into-graveyard trigger carries the fresh graveyard object as its subject" {
            val source = CardRef("Fixture Rancor")
            val definition =
                triggerCard(source, TriggerCondition.PutIntoGraveyardFromBattlefieldSelf, gainLifeAbility(0))
            val leftObject = GameObject(ObjectId(5), source, alice)
            val state = triggerState(definitions = mapOf(source to definition))
            // The object left the battlefield and was reborn in the graveyard as object 9.
            val detected = announceBattlefieldDeparture(state, leftObject, ObjectId(9))
            val fired = detected.pendingTriggers.single()
            fired.sourceId shouldBe ObjectId(5)
            fired.sourceCard shouldBe source
            fired.controller shouldBe alice
            // The trigger carries the graveyard object it will act on (CR 603.10).
            fired.subject shouldBe ObjectId(9)
        }

        "CR 601.2i: the cast-trigger seam fires a fixture cast trigger onto the stack after the cast" {
            val watcher = CardRef("Cast Watcher")
            val watcherDef = triggerCard(watcher, TriggerCondition.SpellCast(), gainLifeAbility(2))
            val registry = fixtureDefinitions + (watcher to watcherDef)
            val state =
                castState(
                    aliceHand = listOf("Fixture Bolt"),
                    aliceBattlefield = listOf("Fixture Mountain", watcher.name),
                    definitions = registry,
                )
            // Cast Fixture Bolt at bob and pay for it.
            var current = engine.advance(state, castDecision(pausedRequestOf(state), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            // The stack now holds the Bolt (bottom) and the fired cast-trigger ability (top).
            val stack = current.pausedState.sharedZones.stack
            stack shouldHaveSize 2
            stack.first().shouldBeInstanceOf<StackEntry.Spell>()
            val ability = stack.last().shouldBeInstanceOf<StackEntry.Ability>()
            ability.trigger.sourceCard shouldBe watcher
            ability.trigger.controller shouldBe alice
        }

        "CR 514.3a: a trigger waiting at cleanup grants priority and forces another cleanup step" {
            // A cleanup step (its opening StepBegan already emitted) with one fired trigger waiting; no
            // natural MVP trigger fires in cleanup, so a fixture trigger exercises this repeat path
            // (packet report).
            val state =
                triggerState(
                    turn = Turn(alice, 3, TurnPhase.ENDING, TurnStep.CLEANUP),
                    holder = null,
                    pendingTriggers = listOf(firedTrigger(alice, gainLifeAbility(1), CardRef("Cleanup Trigger"))),
                ).copy(events = persistentListOf(GameEvent.StepBegan(TurnStep.CLEANUP)))
            // finishCleanup with a waiting trigger grants a priority round (CR 514.3a): the trigger is
            // placed on the stack and the active player receives priority.
            val afterCleanup = finishCleanup(state)
            val window = afterCleanup.pending<DecisionRequest.ChooseAction>()
            window.seat shouldBe alice
            afterCleanup.pausedState.sharedZones.stack shouldHaveSize 1

            // Both players pass: the trigger resolves, and because the round happened during cleanup a
            // second cleanup step begins (CR 514.3a) — a fresh StepBegan(CLEANUP).
            var current = engine.advance(afterCleanup.pausedState, passDecision(afterCleanup.pending()))
            while (current.pausedState.sharedZones.stack
                    .isNotEmpty() ||
                current.pausedState.turn.step == TurnStep.CLEANUP
            ) {
                val request = current.pending<DecisionRequest.ChooseAction>()
                current = engine.advance(current.pausedState, passDecision(request))
                if (current.pausedState.turn.step != TurnStep.CLEANUP) break
            }
            // The cleanup step ran at least twice: the original plus the CR 514.3a repeat.
            current.pausedState.events.count { it is GameEvent.StepBegan && it.step == TurnStep.CLEANUP } shouldBe 2
        }
    })

private const val GAIN: Int = 5

/** A fixture gain-life triggered ability: on resolution its controller gains [amount] life. */
private fun gainLifeAbility(amount: Int): TriggeredAbility =
    TriggeredAbility(
        condition = TriggerCondition.EnteredBattlefieldSelf,
        effect = ResolutionEffect { state, context -> gainLife(state, context.controller, amount) },
    )

/** A fixture permanent card carrying one triggered [ability] with [condition]. */
private fun triggerCard(
    name: CardRef,
    condition: TriggerCondition,
    ability: TriggeredAbility,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name.name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val triggeredAbilities =
            persistentListOf(ability.copy(condition = condition))
    }

/** A fired [PendingTrigger] for [controller] carrying [ability], sourced from [sourceCard]. */
private fun firedTrigger(
    controller: PlayerId,
    ability: TriggeredAbility,
    sourceCard: CardRef,
): PendingTrigger = PendingTrigger(ObjectId(0), sourceCard, controller, ability)

/** A minimal two-player state for framework tests: alice active, no priority holder by default. */
private fun triggerState(
    pendingTriggers: List<PendingTrigger> = emptyList(),
    stack: List<StackEntry> = emptyList(),
    definitions: Map<CardRef, CardDefinition> = emptyMap(),
    turn: Turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
    holder: PlayerId? = null,
): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to seatState(alice, holder),
                bob to seatState(bob, holder),
            ),
        turn = turn,
        sharedZones = SharedZones(persistentListOf(), stack.toPersistentList(), persistentListOf()),
        nextObjectId = 1000,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = definitions.toPersistentMap(),
        pendingTriggers = pendingTriggers.toPersistentList(),
    )

private fun seatState(
    seat: PlayerId,
    holder: PlayerId?,
): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
        priorityStatus = if (seat == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
    )

/** A handcrafted cast window: alice holds priority in her precombat main with the given zones. */
private fun castState(
    aliceHand: List<String>,
    aliceBattlefield: List<String>,
    definitions: Map<CardRef, CardDefinition>,
): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val aliceField = objects(aliceBattlefield, alice)
    val aliceHandObjects = objects(aliceHand, alice)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = aliceHandObjects,
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
        sharedZones = SharedZones(aliceField, persistentListOf(), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = definitions.toPersistentMap(),
    )
}
