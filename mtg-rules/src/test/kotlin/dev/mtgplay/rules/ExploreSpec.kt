package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.Explore
import dev.mtgplay.core.definition.ExploreDestination
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.applyExplore
import dev.mtgplay.rules.engine.orchestrateExplore
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `W10-D`: explore (CR 701.40a) and the library disclosure its *reveal* forces (ADR-007).
 *
 * `mtg-rules` names no card (ADR-003): the fixture is an artifact with "{1}, {T}, Sacrifice this: target
 * creature you control explores", which is the Map token's shape without its name.
 *
 * The claims, in the order they can go wrong:
 * 1. a revealed **land** card goes to the hand, no counter — and opens **no pause**, because a decision
 *    with one legal answer is an enumerated illegal action (ADR-005);
 * 2. a revealed **nonland** card puts the counter on **first** and then pauses;
 * 3. an **empty library** reveals nothing, so the "otherwise" arm runs: counter placed, still no pause —
 *    the case a reader gets wrong by treating the reveal as a precondition;
 * 4. **back on top** moves nothing, and the card keeps its object id, because it never changed zones;
 * 5. **into the graveyard** moves it as a new object (CR 400.7);
 * 6. the revealed card is visible **to the opponent** while the pause is open, out of a library — the
 *    disclosure that is the larger half of this keyword.
 */
class ExploreSpec :
    StringSpec({
        val bob = PlayerId(1)

        "CR 701.40a: a revealed land card goes to the hand, with no counter and no pause" {
            val state = exploreState(topOfLibrary = FOREST)
            val result = orchestrateExplore(state, state.sharedZones.stack.last())
            val finished = result.shouldBeInstanceOf<AdvanceResult.NeedsDecision>().state
            finished.players
                .getValue(alice)
                .hand
                .map { it.card } shouldBe listOf(FOREST)
            finished.players
                .getValue(alice)
                .library
                .shouldBeEmpty()
            // No counter on the explorer, and the clause is over: nothing is pending.
            finished.sharedZones.battlefield
                .first { it.card == BEAR }
                .counters[Counter.PLUS_ONE_PLUS_ONE]
                .shouldBeNull()
            finished.pendingExplore.shouldBeNull()
            finished.sharedZones.stack.shouldBeEmpty()
        }

        "CR 701.40a: a revealed nonland card places the counter first, then pauses" {
            val state = exploreState(topOfLibrary = BOLT)
            val result = orchestrateExplore(state, state.sharedZones.stack.last())
            val paused = result.shouldBeInstanceOf<AdvanceResult.NeedsDecision>().state
            paused.sharedZones.battlefield
                .first { it.card == BEAR }
                .counters[Counter.PLUS_ONE_PLUS_ONE] shouldBe 1
            // The card has not moved: it is still the top of the library, revealed but in place.
            paused.players
                .getValue(alice)
                .library
                .map { it.card } shouldBe listOf(BOLT)
            paused.pendingExplore?.decider shouldBe alice
            val request = result.request.shouldBeInstanceOf<DecisionRequest.ChooseExploreDestination>()
            request.revealedCard shouldBe BOLT
            request.options shouldBe listOf(ExploreDestination.LIBRARY_TOP, ExploreDestination.GRAVEYARD)
        }

        "CR 701.40a: an empty library reveals nothing, so the otherwise arm runs and nothing pauses" {
            val state = exploreState(topOfLibrary = null)
            val result = orchestrateExplore(state, state.sharedZones.stack.last())
            val finished = result.shouldBeInstanceOf<AdvanceResult.NeedsDecision>().state
            // No land card was revealed, so the counter is placed — and there is no card to place, so
            // the clause finishes without asking anything.
            finished.sharedZones.battlefield
                .first { it.card == BEAR }
                .counters[Counter.PLUS_ONE_PLUS_ONE] shouldBe 1
            finished.pendingExplore.shouldBeNull()
            finished.sharedZones.stack.shouldBeEmpty()
        }

        "CR 701.40a: back on top moves nothing — the card keeps its object id" {
            val state = exploreState(topOfLibrary = BOLT)
            val paused = orchestrateExplore(state, state.sharedZones.stack.last()).pausedState
            val before =
                paused.players
                    .getValue(alice)
                    .library
                    .single()
                    .id
            val done = applyExplore(paused, ExploreDestination.LIBRARY_TOP).pausedState
            done.players
                .getValue(alice)
                .library
                .single()
                .id shouldBe before
            done.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            done.pendingExplore.shouldBeNull()
        }

        "CR 400.7: into the graveyard is a zone change, so the card arrives as a new object" {
            val state = exploreState(topOfLibrary = BOLT)
            val paused = orchestrateExplore(state, state.sharedZones.stack.last()).pausedState
            val before =
                paused.players
                    .getValue(alice)
                    .library
                    .single()
                    .id
            val done = applyExplore(paused, ExploreDestination.GRAVEYARD).pausedState
            done.players
                .getValue(alice)
                .library
                .shouldBeEmpty()
            val inGraveyard =
                done.players
                    .getValue(alice)
                    .graveyard
                    .single()
            inGraveyard.card shouldBe BOLT
            (inGraveyard.id == before) shouldBe false
        }

        "ADR-007: the revealed card is visible to the opponent while the pause is open" {
            val state = exploreState(topOfLibrary = BOLT)
            val paused = orchestrateExplore(state, state.sharedZones.stack.last()).pausedState
            val theirs = viewFor(paused, bob)
            // The one card of alice's library bob may name, and the reason is CR 701.40a's word "reveal".
            theirs.pendingExplore?.revealed?.card shouldBe BOLT
            theirs.pendingExplore?.exploring?.card shouldBe BEAR
            theirs.cards.keys shouldContain BOLT
            // Everything else about the library stays secret: the view exposes one card, not a zone.
            theirs.players.first { it.seat == alice }.libraryCount shouldBe 1
        }

        "ADR-007: the disclosure ends with the pause — a card put back on top is secret again" {
            val state = exploreState(topOfLibrary = BOLT)
            val paused = orchestrateExplore(state, state.sharedZones.stack.last()).pausedState
            val done = applyExplore(paused, ExploreDestination.LIBRARY_TOP).pausedState
            val theirs = viewFor(done, bob)
            theirs.pendingExplore.shouldBeNull()
            theirs.cards.keys.filter { it == BOLT } shouldHaveSize 0
        }
    })

private val BEAR: CardRef = CardRef("Fixture Explorer")
private val FOREST: CardRef = CardRef("Fixture Explore Forest")
private val BOLT: CardRef = CardRef("Fixture Explore Spell")
private val COMPASS: CardRef = CardRef("Fixture Compass")

private val exploreAbility: ActivatedAbility =
    ActivatedAbility(
        cost =
            persistentListOf(
                AbilityCost.Mana(ManaCost.parse("{1}")),
                AbilityCost.TapSelf,
                AbilityCost.SacrificeSelf,
            ),
        timing = TimingClass.SORCERY_SPEED,
        targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_YOU_CONTROL),
        effect = ResolutionEffect { s, _ -> s },
        explore = Explore,
    )

private fun definition(
    name: String,
    types: Set<CardType>,
    creature: Boolean = false,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = if (CardType.LAND in types) null else ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf<CardType>().addingAll(types),
                subtypes = persistentSetOf(),
                powerToughness = if (creature) PrintedPowerToughness(power = 2, toughness = 2) else null,
            )
    }

private val compassDefinition: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Compass",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(Subtype("Clue")),
                powerToughness = null,
            )
        override val activatedAbilities = persistentListOf(exploreAbility)
    }

private val exploreRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        BEAR to definition("Fixture Explorer", setOf(CardType.CREATURE), creature = true),
        FOREST to definition("Fixture Explore Forest", setOf(CardType.LAND)),
        BOLT to definition("Fixture Explore Spell", setOf(CardType.INSTANT)),
        COMPASS to compassDefinition,
    )

/**
 * A state with the exploring ability already resolving on top of the stack, its creature target on the
 * battlefield, and [topOfLibrary] (or nothing) as alice's whole library — the position
 * [orchestrateExplore] is called from.
 */
private fun exploreState(topOfLibrary: CardRef?): GameState {
    var nextId = 0L

    fun obj(
        card: CardRef,
        owner: PlayerId,
    ) = GameObject(ObjectId(nextId), card, owner).also { nextId += 1 }

    val bear = obj(BEAR, alice)
    val compass = obj(COMPASS, alice)
    val library = listOfNotNull(topOfLibrary).map { obj(it, alice) }.toPersistentList()

    fun seat(lib: kotlinx.collections.immutable.PersistentList<GameObject>) =
        PlayerState(
            life = STARTING_LIFE,
            library = lib,
            hand = persistentListOf(),
            graveyard = persistentListOf(),
            priorityStatus = PriorityStatus.NONE,
        )
    return GameState(
        players = persistentMapOf(alice to seat(library), PlayerId(1) to seat(persistentListOf())),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(bear, compass),
                stack =
                    persistentListOf(
                        StackEntry.ActivatedAbilityOnStack(
                            sourceId = compass.id,
                            sourceCard = COMPASS,
                            controller = alice,
                            ability = exploreAbility,
                            targets = persistentListOf(Target.Permanent(bear.id)),
                        ),
                    ),
                exile = persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = exploreRegistry.toPersistentMap(),
    )
}
