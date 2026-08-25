package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.LibraryLookSource
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The **filtered** private look (CR 701.14a, CR 701.16a) — `LibraryLookMode.RevealMatchingToHandRestToBottom`,
 * the mode docs/design/library-look.md §12 left open as "a filter on the keep". Fixture cards only; the
 * `mtg-rules`-names-no-card rule holds.
 *
 * The properties that make it different from every mode that came before it, and that nothing downstream
 * re-checks (ADR-005): only a *matching* card may be kept, the keep is *optional*, and the kept cards — and
 * only the kept cards — become public.
 */
class FilteredLookSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 701.16a: only matching cards are offered to the hand, and a non-matching one never is" {
            val paused = arrangementPause(engine, filteredState(STIRRINGS_FIXTURE, MIXED_TOP))
            val poolNames = paused.request.pool.map { it.card.name }
            val colorless = poolNames.indices.filter { poolNames[it] in COLORLESS_FIXTURE_CARDS }

            paused.request.options
                .flatMap { it.toHand }
                .toSet() shouldBe colorless.toSet()
            // Every arrangement is total, keeps at most one, and puts nothing on top.
            val everyIndex = poolNames.indices.toList()
            paused.request.options.all {
                it.toHand.size <= 1 &&
                    it.toTop.isEmpty() &&
                    (it.toHand + it.toBottom).sorted() == everyIndex
            } shouldBe true
        }

        "CR 701.16a: 'You may reveal' enumerates the decline, unlike a mandatory keep" {
            val paused = arrangementPause(engine, filteredState(STIRRINGS_FIXTURE, MIXED_TOP))

            // The asymmetry against OneToHandRestToBottom, which enumerates no empty-hand arrangement at all.
            paused.request.options
                .count { it.toHand.isEmpty() } shouldBe FACTORIAL_THREE
            paused.request.options
                .first()
                .toHand
                .shouldBeEmpty()
        }

        "CR 701.14a: a look whose pool holds no matching card still resolves, keeping nothing" {
            val paused = arrangementPause(engine, filteredState(STIRRINGS_FIXTURE, listOf(COLOURED_INSTANT)))

            paused.request.options.all { it.toHand.isEmpty() } shouldBe true
            paused.request.options.size shouldBe 1
        }

        "CR 701.16a: the kept card is revealed and the bottomed cards are not" {
            val start = filteredState(STIRRINGS_FIXTURE, MIXED_TOP)
            val paused = arrangementPause(engine, start)
            val poolNames = paused.request.pool.map { it.card.name }
            val keepArtifact = poolNames.indexOf(COLOURLESS_ARTIFACT)
            val chosen =
                paused.request.options.indexOfFirst { it.toHand == listOf(keepArtifact) }
            val done = engine.advance(paused.state, Decision.SingleSelect(paused.request.id, chosen)).pausedState

            // CR 701.16a for the keep — one event, naming exactly the card that went to the hand …
            done.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .single()
                .cards
                .map { it.name } shouldContainExactly listOf(COLOURLESS_ARTIFACT)
            // … and CR 701.14a for the rest: the bottomed cards never appear in any reveal.
            done.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .flatMap { it.cards }
                .map { it.name } shouldNotContain COLOURED_INSTANT
        }

        "CR 701.16a: declining the keep reveals nothing at all" {
            val start = filteredState(STIRRINGS_FIXTURE, MIXED_TOP)
            val paused = arrangementPause(engine, start)
            val decline = paused.request.options.indexOfFirst { it.toHand.isEmpty() }
            val done = engine.advance(paused.state, Decision.SingleSelect(paused.request.id, decline)).pausedState

            done.events.filterIsInstance<GameEvent.CardsRevealed>() shouldBe emptyList()
            // The whole pool went to the bottom, and the library is the same size it started.
            handNames(done, alice).shouldBeEmpty()
            libraryNames(done, alice).size shouldBe MIXED_TOP.size
        }

        "CR 701.14a: the bottomed cards stay private — the opponent's view names none of them" {
            val paused = arrangementPause(engine, filteredState(STIRRINGS_FIXTURE, MIXED_TOP))
            val opponentView = viewFor(paused.state, bob)

            MIXED_TOP.forEach { name -> opponentView.cards.keys.map { it.name } shouldNotContain name }
            opponentView.pendingLibraryLook shouldBe
                PendingLibraryLookView(alice, LibraryLookSource.TOP_OF_LIBRARY, MIXED_TOP.size, false)
        }

        "CR 701.16a: an any-number keep takes every matching card at once, in pool order" {
            val start = filteredState(STAMPEDE_FIXTURE, MIXED_TOP)
            val paused = arrangementPause(engine, start)
            val poolNames = paused.request.pool.map { it.card.name }
            val creatures = poolNames.indices.filter { poolNames[it] in CREATURE_FIXTURE_CARDS }
            val takeAll = paused.request.options.indexOfFirst { it.toHand == creatures }
            val done = engine.advance(paused.state, Decision.SingleSelect(paused.request.id, takeAll)).pausedState

            // Both creatures reach the hand from one decision — Lead the Stampede's shape, not two rounds.
            handNames(done, alice) shouldContainExactly creatures.map { poolNames[it] }
            done.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .single()
                .cards
                .size shouldBe creatures.size
        }

        "CR 400.7: a kept card is reborn in the hand and a bottomed one keeps its object id" {
            val start = filteredState(STIRRINGS_FIXTURE, MIXED_TOP)
            val libraryIds =
                start.players
                    .getValue(alice)
                    .library
                    .map { it.id }
            val paused = arrangementPause(engine, start)
            val poolNames = paused.request.pool.map { it.card.name }
            val keep = poolNames.indexOf(COLOURLESS_ARTIFACT)
            val chosen = paused.request.options.indexOfFirst { it.toHand == listOf(keep) }
            val done = engine.advance(paused.state, Decision.SingleSelect(paused.request.id, chosen)).pausedState

            // The card that changed zone is a new object; the ones merely re-seated in the library are not.
            (
                done.players
                    .getValue(alice)
                    .hand
                    .single()
                    .id in libraryIds
            ) shouldBe false
            done.players
                .getValue(alice)
                .library
                .all { it.id in libraryIds } shouldBe true
        }

        "ADR-004/ADR-006: a filtered arrangement request re-derives identically and ignores the seed" {
            val paused = arrangementPause(engine, filteredState(STAMPEDE_FIXTURE, MIXED_TOP))

            pendingRequestOf(paused.state) shouldBe paused.request
            pendingRequestOf(paused.state.copy(rng = Rng(4242))) shouldBe paused.request
        }
    })

/** The paused arrangement request and the state it is paused in. */
private data class Pause(
    val state: GameState,
    val request: DecisionRequest.ChooseLibraryArrangement,
)

/** Casts the fixture in Alice's hand and drives to the arrangement pause. */
private fun arrangementPause(
    engine: GameEngine,
    state: GameState,
): Pause {
    val cardName =
        state.players
            .getValue(alice)
            .hand
            .first()
            .card.name
    var current: AdvanceResult = engine.advance(state, castDecision(pausedRequestOf(state), cardName))
    current = engine.advance(current.pausedState, planDecision(current.pending()))
    while (true) {
        val paused = current as? AdvanceResult.NeedsDecision ?: error("game ended before the arrangement pause")
        when (val request = paused.request) {
            is DecisionRequest.ChooseLibraryArrangement -> return Pause(paused.state, request)
            is DecisionRequest.ChooseAction -> current = engine.advance(paused.state, passDecision(request))
            else -> error("unexpected request before the arrangement pause: $request")
        }
    }
}

private fun libraryNames(
    state: GameState,
    seat: PlayerId,
) = state.players
    .getValue(seat)
    .library
    .map { it.card.name }

private fun handNames(
    state: GameState,
    seat: PlayerId,
) = state.players
    .getValue(seat)
    .hand
    .map { it.card.name }

private const val STIRRINGS_FIXTURE = "Fixture Filtered Look"
private const val STAMPEDE_FIXTURE = "Fixture Any Number Look"

private const val COLOURLESS_ARTIFACT = "Filter Ornament"
private const val COLOURLESS_BEAST = "Filter Behemoth"
private const val COLOURED_INSTANT = "Filter Shock"

/** The three-card library top every filtered case looks at: one artifact, one colorless creature, one red instant. */
private val MIXED_TOP = listOf(COLOURLESS_ARTIFACT, COLOURLESS_BEAST, COLOURED_INSTANT)

/** Which of [MIXED_TOP] have no printed mana cost symbols of a colour (CR 202.2). */
private val COLORLESS_FIXTURE_CARDS = setOf(COLOURLESS_ARTIFACT, COLOURLESS_BEAST)

/** Which of [MIXED_TOP] are creature cards (CR 302.1). */
private val CREATURE_FIXTURE_CARDS = setOf(COLOURLESS_BEAST)

/** `3!` — the orderings of a three-card pool put entirely on the bottom, i.e. the decline group's size. */
private const val FACTORIAL_THREE = 6

private fun filteredFixture(
    name: String,
    look: LibraryLook,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { s, _ -> s }
        override val libraryLook = look
    }

/** A pool card with printed [type] and printed [cost] — the cost is what decides its colour (CR 202.2). */
private fun poolCard(
    name: String,
    type: CardType,
    cost: String?,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = cost?.let { ManaCost.parse(it) },
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(type),
                subtypes = persistentSetOf(),
                powerToughness = if (type == CardType.CREATURE) PrintedPowerToughness(2, 2) else null,
            )
        override val manaAbilities =
            if (type == CardType.LAND) {
                persistentListOf(ManaAbility(persistentListOf(ManaType.RED)))
            } else {
                persistentListOf()
            }
    }

private val filteredRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef(STIRRINGS_FIXTURE) to
            filteredFixture(
                STIRRINGS_FIXTURE,
                LibraryLook(
                    LibraryLookMode.RevealMatchingToHandRestToBottom(
                        count = 3,
                        toHand = RevealedCardFilter.COLORLESS_CARD,
                        maxToHand = 1,
                    ),
                ),
            ),
        CardRef(STAMPEDE_FIXTURE) to
            filteredFixture(
                STAMPEDE_FIXTURE,
                LibraryLook(
                    LibraryLookMode.RevealMatchingToHandRestToBottom(
                        count = 3,
                        toHand = RevealedCardFilter.CREATURE_CARD,
                        maxToHand = 3,
                    ),
                ),
            ),
        // {2} is generic, so this artifact is colorless (CR 202.2) — the Ancient Stirrings target shape.
        CardRef(COLOURLESS_ARTIFACT) to poolCard(COLOURLESS_ARTIFACT, CardType.ARTIFACT, "{2}"),
        // A colorless *creature*: the case "artifact or land card" would get wrong, and a creature card too.
        CardRef(COLOURLESS_BEAST) to poolCard(COLOURLESS_BEAST, CardType.CREATURE, "{4}"),
        // {R} makes this red, so it matches neither filter.
        CardRef(COLOURED_INSTANT) to poolCard(COLOURED_INSTANT, CardType.INSTANT, "{R}"),
        CardRef("Filter Mountain") to poolCard("Filter Mountain", CardType.LAND, null),
    )

/** Alice holding priority with [fixture] in hand, a land to pay with, and a known library top. */
private fun filteredState(
    fixture: String,
    libraryTop: List<String>,
): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = objects(libraryTop, alice),
                        hand = objects(listOf(fixture), alice),
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
        sharedZones = SharedZones(objects(listOf("Filter Mountain"), alice), persistentListOf(), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(7),
        events = persistentListOf(),
        definitions = filteredRegistry.toPersistentMap(),
    )
}
