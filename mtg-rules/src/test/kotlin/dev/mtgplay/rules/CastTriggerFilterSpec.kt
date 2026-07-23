package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.gainLife
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P6.2a cast-trigger filter (CR 603.2e) and the caster-retained-priority nuance (CR 601.2i,
 * flagged in P5.1) with a fixture mirroring Guttersnipe's "whenever you cast an instant or sorcery
 * spell" — the `mtg-rules`-names-no-card rule holds; the P6.2b Guttersnipe encoding reuses this shape.
 */
class CastTriggerFilterSpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val watcher = CardRef("Fixture Snipe")

        "CR 603.2e: 'whenever you cast an instant or sorcery' fires on your instant cast" {
            // Alice (active) casts Fixture Bolt (an instant she controls); her Snipe fires.
            val state =
                castTriggerState(
                    active = alice,
                    holder = alice,
                    aliceBoard =
                        Board(
                            hand = listOf("Fixture Bolt"),
                            battlefield = listOf("Fixture Mountain", watcher.name),
                        ),
                    definitions = fixtureDefinitions + (watcher to snipeCard(watcher, controlledByYou = true)),
                )
            var current = engine.advance(state, castDecision(pausedRequestOf(state), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            // The Snipe trigger is on the stack above the Bolt.
            val ability =
                current.pausedState.sharedZones.stack
                    .last()
                    .shouldBeInstanceOf<StackEntry.Ability>()
            ability.trigger.sourceCard shouldBe watcher
            ability.trigger.controller shouldBe alice
        }

        "CR 603.2e: a 'you control' cast trigger does NOT fire on an opponent's cast" {
            // Bob (active) casts Fixture Bolt; alice's Snipe watches 'you cast', so bob's cast is not one.
            val state =
                castTriggerState(
                    active = bob,
                    holder = bob,
                    aliceBoard = Board(battlefield = listOf(watcher.name)),
                    bobBoard = Board(hand = listOf("Fixture Bolt"), battlefield = listOf("Fixture Mountain")),
                    definitions = fixtureDefinitions + (watcher to snipeCard(watcher, controlledByYou = true)),
                )
            var current = engine.advance(state, castDecision(pausedRequestOf(state), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), alice))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            // Only the Bolt is on the stack; alice's Snipe never fired (it is not bob's cast trigger).
            current.pausedState.sharedZones.stack
                .filterIsInstance<StackEntry.Ability>()
                .shouldBeEmpty()
        }

        "CR 603.2: a cast trigger filtered to sorceries does not fire on an instant cast" {
            val sorceryWatcher = CardRef("Fixture Sorcery Snipe")
            val state =
                castTriggerState(
                    active = alice,
                    holder = alice,
                    aliceBoard =
                        Board(
                            hand = listOf("Fixture Bolt"),
                            battlefield = listOf("Fixture Mountain", sorceryWatcher.name),
                        ),
                    definitions =
                        fixtureDefinitions +
                            (
                                sorceryWatcher to
                                    snipeCard(sorceryWatcher, controlledByYou = true, types = setOf(CardType.SORCERY))
                            ),
                )
            var current = engine.advance(state, castDecision(pausedRequestOf(state), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            current.pausedState.sharedZones.stack
                .filterIsInstance<StackEntry.Ability>()
                .shouldBeEmpty()
        }

        "CR 601.2i: casting an instant on the opponent's turn returns priority to the caster after its trigger" {
            // Bob's turn; alice holds priority (bob has passed). Alice casts Fixture Bolt (instant) and
            // her Snipe fires. After the trigger is placed, priority must return to ALICE (the caster),
            // not to bob (the active player) — the P5.1 nuance.
            val state =
                castTriggerState(
                    active = bob,
                    holder = alice,
                    aliceBoard =
                        Board(
                            hand = listOf("Fixture Bolt"),
                            battlefield = listOf("Fixture Mountain", watcher.name),
                        ),
                    definitions = fixtureDefinitions + (watcher to snipeCard(watcher, controlledByYou = true)),
                )
            var current = engine.advance(state, castDecision(pausedRequestOf(state), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            // The trigger has been placed and a priority window opened for the caster, alice.
            val window = current.pending<DecisionRequest.ChooseAction>()
            window.seat shouldBe alice
            current.pausedState.players
                .getValue(alice)
                .priorityStatus shouldBe PriorityStatus.HOLDS_PRIORITY
        }
    })

/** A fixture permanent mirroring Guttersnipe: a filtered cast trigger whose effect gains its controller 1 life. */
private fun snipeCard(
    name: CardRef,
    controlledByYou: Boolean,
    types: Set<CardType> = setOf(CardType.INSTANT, CardType.SORCERY),
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name.name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness =
                    dev.mtgplay.core.card
                        .PrintedPowerToughness(2, 2),
            )
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition =
                        TriggerCondition.SpellCast(
                            spellTypes = types.toPersistentList().let { persistentSetOf(*it.toTypedArray()) },
                            controlledByYou = controlledByYou,
                        ),
                    effect = ResolutionEffect { state, context -> gainLife(state, context.controller, 1) },
                ),
            )
    }

/** A seat's starting hand and battlefield, as printed card names. */
private data class Board(
    val hand: List<String> = emptyList(),
    val battlefield: List<String> = emptyList(),
)

/** A handcrafted priority window with the fixture registry, a chosen active player and priority holder. */
private fun castTriggerState(
    active: PlayerId,
    holder: PlayerId,
    aliceBoard: Board,
    bobBoard: Board = Board(),
    definitions: Map<CardRef, CardDefinition>,
): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val aliceField = objects(aliceBoard.battlefield, alice)
    val aliceHandObjects = objects(aliceBoard.hand, alice)
    val bobField = objects(bobBoard.battlefield, bob)
    val bobHandObjects = objects(bobBoard.hand, bob)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = aliceHandObjects,
                        graveyard = persistentListOf(),
                        priorityStatus = if (holder == alice) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = bobHandObjects,
                        graveyard = persistentListOf(),
                        priorityStatus = if (holder == bob) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
                    ),
            ),
        turn = Turn(active, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones((aliceField + bobField).toPersistentList(), persistentListOf(), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = definitions.toPersistentMap(),
    )
}
