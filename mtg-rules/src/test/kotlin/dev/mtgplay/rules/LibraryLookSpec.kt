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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The `FW-LIBLOOK` private look-and-arrange framework (CR 701.14a, CR 701.17a, CR 400.7) — the sibling of
 * the CR 701.16 reveal flow, and deliberately *not* a mode of it. Fixture cards only; the
 * `mtg-rules`-names-no-card rule holds (docs/design/library-look.md).
 */
class LibraryLookSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 701.14a: a look is private — the opponent's view names no looked-at card and carries a count only" {
            val paused = lookUntilArrangement(engine, lookState(SCRY_FIXTURE, listOf("Look Bear", "Look Bolt")))
            val opponentView = viewFor(paused.state, bob)

            // The whole point of the framework: the looked-at identities are absent from every other seat.
            opponentView.cards.keys.map { it.name } shouldNotContain "Look Bear"
            opponentView.cards.keys.map { it.name } shouldNotContain "Look Bolt"
            // What an opponent *does* get: that a look is happening, whose, over how many, and its stage.
            opponentView.pendingLibraryLook shouldBe
                PendingLibraryLookView(alice, LibraryLookSource.TOP_OF_LIBRARY, 2, awaitingShuffle = false)
            // ADR-007: a non-deciding seat receives no request options at all.
            opponentView.pendingDecision shouldBe
                DecisionView.Elsewhere(alice, DecisionRequestKind.CHOOSE_LIBRARY_ARRANGEMENT)
        }

        "CR 701.14a: the looking seat's own view carries the looked-at cards' printed characteristics" {
            val paused = lookUntilArrangement(engine, lookState(SCRY_FIXTURE, listOf("Look Bear", "Look Bolt")))
            val ownView = viewFor(paused.state, alice)

            // The ADR-007 widening (docs/design/library-look.md §3c): a scry is decided *on characteristics*,
            // so the deciding seat must be able to tell the creature from the instant.
            val bear = ownView.cards.getValue(CardRef("Look Bear")).characteristics
            val bolt = ownView.cards.getValue(CardRef("Look Bolt")).characteristics
            bear.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            bolt.cardTypes shouldBe persistentSetOf(CardType.INSTANT)
            ownView.pendingDecision
                .shouldBeToDecide()
                .options.size shouldBe SCRY_TWO_ARRANGEMENTS
        }

        "CR 701.16 vs CR 701.14a: a look emits no CardsRevealed, only a count" {
            val paused = lookUntilArrangement(engine, lookState(SCRY_FIXTURE, listOf("Look Bear", "Look Bolt")))

            paused.state.events.filterIsInstance<GameEvent.CardsRevealed>() shouldBe emptyList()
            paused.state.events
                .filterIsInstance<GameEvent.CardsLookedAt>()
                .single()
                .count shouldBe 2
        }

        "CR 701.17a: scry 2 enumerates all six ordered splits, and no more" {
            val paused = lookUntilArrangement(engine, lookState(SCRY_FIXTURE, listOf("Look Bear", "Look Bolt")))

            // (n + 1)! outcomes: every permutation of the two cards crossed with every split point.
            paused.request.options shouldContainExactly
                listOf(
                    arrangement(top = listOf(0, 1)),
                    arrangement(top = listOf(1, 0)),
                    arrangement(bottom = listOf(0), top = listOf(1)),
                    arrangement(bottom = listOf(1), top = listOf(0)),
                    arrangement(bottom = listOf(0, 1)),
                    arrangement(bottom = listOf(1, 0)),
                )
        }

        "CR 701.17a: a scry that bottoms both cards leaves them in the chosen order under the rest" {
            val start = lookState(SCRY_FIXTURE, listOf("Look Bear", "Look Bolt", "Look Wall"))
            val paused = lookUntilArrangement(engine, start)
            // Bottom [Bolt, Bear] — the first placed ends up above the last (the CR 103.5 convention).
            val chosen = paused.request.options.indexOf(arrangement(bottom = listOf(1, 0)))
            val done = engine.advance(paused.state, Decision.SingleSelect(paused.request.id, chosen)).pausedState

            libraryNames(done, alice) shouldContainExactly listOf("Look Wall", "Look Bolt", "Look Bear")
        }

        "CR 400.7: a card left on top of the library by a scry keeps its object id" {
            val start = lookState(SCRY_FIXTURE, listOf("Look Bear", "Look Bolt"))
            val idsBefore =
                start.players
                    .getValue(alice)
                    .library
                    .map { it.id }
            val paused = lookUntilArrangement(engine, start)
            val chosen = paused.request.options.indexOf(arrangement(top = listOf(1, 0)))
            val done = engine.advance(paused.state, Decision.SingleSelect(paused.request.id, chosen)).pausedState

            // Reordering within a zone is not a zone change, so no card is reborn.
            done.players
                .getValue(alice)
                .library
                .map { it.id } shouldContainExactly idsBefore.reversed()
        }

        "CR 400.7: a looked-at card put into the hand becomes a new object" {
            val start = lookState(KEEP_FIXTURE, listOf("Look Bear", "Look Bolt"))
            val libraryIds =
                start.players
                    .getValue(alice)
                    .library
                    .map { it.id }
            val paused = lookUntilArrangement(engine, start)
            val chosen = paused.request.options.indexOf(arrangement(hand = listOf(0), bottom = listOf(1)))
            val done = engine.advance(paused.state, Decision.SingleSelect(paused.request.id, chosen)).pausedState

            val kept =
                done.players
                    .getValue(alice)
                    .hand
                    .single { it.card == CardRef("Look Bear") }
            (kept.id in libraryIds) shouldBe false
        }

        "ADR-005: a mandatory keep enumerates no arrangement that keeps nothing" {
            val paused = lookUntilArrangement(engine, lookState(KEEP_FIXTURE, listOf("Look Bear", "Look Bolt")))

            // Impulse's "put one of them into your hand" is not a "you may": the illegal decline the reveal
            // clause's up-to-M allowance would have offered simply has no index here.
            paused.request.options.all { it.toHand.size == 1 } shouldBe true
            paused.request.options.size shouldBe 2
        }

        "CR 701.14a: looking at more cards than the library holds looks at as many as possible" {
            val paused = lookUntilArrangement(engine, lookState(SCRY_FIXTURE, emptyList()))

            // An empty library still surfaces the pause with its one (empty) arrangement — the engine never
            // collapses a decision (ADR-004).
            paused.request.pool shouldBe emptyList()
            paused.request.options shouldContainExactly listOf(arrangement())
        }

        "ADR-004/ADR-006: the arrangement request re-derives identically and does not depend on the seed" {
            val paused = lookUntilArrangement(engine, lookState(SCRY_FIXTURE, listOf("Look Bear", "Look Bolt")))

            pendingRequestOf(paused.state) shouldBe paused.request
            pendingRequestOf(paused.state.copy(rng = Rng(999))) shouldBe paused.request
        }

        "ADR-006: Ponder's optional shuffle draws from the match PRNG; declining keeps the chosen order" {
            val libraryTop = listOf("Look Bear", "Look Bolt", "Look Wall")
            val paused = lookUntilArrangement(engine, lookState(SHUFFLE_FIXTURE, libraryTop))
            val chosen = paused.request.options.indexOf(arrangement(top = listOf(2, 1, 0)))
            val afterOrder = engine.advance(paused.state, Decision.SingleSelect(paused.request.id, chosen))
            val yesNo = afterOrder.pending<DecisionRequest.ChooseYesNo>()

            // Declining leaves the chosen order standing; the trailing draw then takes the new top card.
            val decline = Decision.SingleSelect(yesNo.id, DecisionRequest.ChooseYesNo.DECLINE)
            val declined = engine.advance(afterOrder.pausedState, decline).pausedState
            handNames(declined, alice) shouldContainExactly listOf("Look Wall")

            // Accepting consumes seeded entropy (ADR-006), so the PRNG state moves and replay reproduces it.
            val accept = Decision.SingleSelect(yesNo.id, DecisionRequest.ChooseYesNo.ACCEPT)
            val shuffled = engine.advance(afterOrder.pausedState, accept).pausedState
            shuffled.rng shouldNotBe declined.rng
        }

        "CR 400.7: a hand-to-top placement reborns the placed cards and keeps the residue's hand order" {
            val start = handToTopState()
            val paused = lookUntilArrangement(engine, start)
            val poolNames = paused.request.pool.map { it.card.name }
            val bearIndex = poolNames.indexOf("Look Bear")
            val wallIndex = poolNames.indexOf("Look Wall")
            val residue = poolNames.indices.filter { it != bearIndex && it != wallIndex }
            val chosen =
                paused.request.options.indexOf(arrangement(hand = residue, top = listOf(wallIndex, bearIndex)))
            val handIdsBefore =
                paused.state.players
                    .getValue(alice)
                    .hand
                    .map { it.id }
            val done = engine.advance(paused.state, Decision.SingleSelect(paused.request.id, chosen)).pausedState

            // The chosen order is the library order, topmost first, and each placed card is a new object.
            libraryNames(done, alice).take(2) shouldContainExactly listOf("Look Wall", "Look Bear")
            done.players
                .getValue(alice)
                .library
                .take(2)
                .none { it.id in handIdsBefore } shouldBe true
            // Everything not placed stays in the hand, in the order it was already in.
            handNames(done, alice) shouldContainExactly residue.map { poolNames[it] }
        }
    })

/** The paused arrangement request and the state it is paused in. */
private data class ArrangementPause(
    val state: GameState,
    val request: DecisionRequest.ChooseLibraryArrangement,
)

/** Casts the fixture in Alice's hand and drives to the arrangement pause. */
private fun lookUntilArrangement(
    engine: GameEngine,
    state: GameState,
): ArrangementPause {
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
            is DecisionRequest.ChooseLibraryArrangement -> return ArrangementPause(paused.state, request)
            is DecisionRequest.ChooseAction -> current = engine.advance(paused.state, passDecision(request))
            else -> error("unexpected request before the arrangement pause: $request")
        }
    }
}

private fun arrangement(
    hand: List<Int> = emptyList(),
    top: List<Int> = emptyList(),
    bottom: List<Int> = emptyList(),
) = DecisionRequest.ChooseLibraryArrangement.Option(toHand = hand, toTop = top, toBottom = bottom)

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

private fun DecisionView?.shouldBeToDecide(): DecisionRequest.ChooseLibraryArrangement {
    val request = (this as? DecisionView.ToDecide)?.request
    return request as? DecisionRequest.ChooseLibraryArrangement
        ?: error("expected the viewer's own arrangement request, got $this")
}

/** How many arrangements a scry 2 admits: `(2 + 1)!`. */
private const val SCRY_TWO_ARRANGEMENTS = 6

private const val SCRY_FIXTURE = "Fixture Scry"
private const val KEEP_FIXTURE = "Fixture Impulse"
private const val SHUFFLE_FIXTURE = "Fixture Ponder"
private const val HAND_FIXTURE = "Fixture Brainstorm"

private fun lookFixture(
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

private fun typedLookCard(
    name: String,
    type: CardType,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
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

private val lookRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef(SCRY_FIXTURE) to lookFixture(SCRY_FIXTURE, LibraryLook(LibraryLookMode.Scry(2))),
        CardRef(KEEP_FIXTURE) to
            lookFixture(KEEP_FIXTURE, LibraryLook(LibraryLookMode.OneToHandRestToBottom(2))),
        CardRef(SHUFFLE_FIXTURE) to
            lookFixture(
                SHUFFLE_FIXTURE,
                LibraryLook(LibraryLookMode.ReorderTop(3), optionalShuffle = true, thenDraw = 1),
            ),
        CardRef(HAND_FIXTURE) to lookFixture(HAND_FIXTURE, LibraryLook(LibraryLookMode.HandToTop(2))),
        CardRef("Look Mountain") to typedLookCard("Look Mountain", CardType.LAND),
        CardRef("Look Bear") to typedLookCard("Look Bear", CardType.CREATURE),
        CardRef("Look Wall") to typedLookCard("Look Wall", CardType.CREATURE),
        CardRef("Look Bolt") to typedLookCard("Look Bolt", CardType.INSTANT),
    )

/** Alice holding priority with [fixture] in hand, a Mountain to pay with, and a known library top. */
private fun lookState(
    fixture: String,
    libraryTop: List<String>,
    extraHand: List<String> = emptyList(),
): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val hand = objects(listOf(fixture) + extraHand, alice)
    val field = objects(listOf("Look Mountain"), alice)
    val library = objects(libraryTop, alice)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = library,
                        hand = hand,
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
        sharedZones = SharedZones(field, persistentListOf(), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(7),
        events = persistentListOf(),
        definitions = lookRegistry.toPersistentMap(),
    )
}

/** The hand-sourced fixture, with three other cards in hand to place from and a library to place onto. */
private fun handToTopState(): GameState =
    lookState(HAND_FIXTURE, listOf("Look Mountain"), extraHand = listOf("Look Bear", "Look Wall", "Look Bolt"))
