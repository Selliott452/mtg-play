package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.LibraryReveal
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
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
import dev.mtgplay.rules.effect.createToken
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P6.2a library-manipulation primitives (CR 701.16): reveal the top N, put up to one permanent card
 * into hand, the rest into the graveyard — mirroring Malevolent Rumble's reveal-four clause (its token
 * creation, an independent clause, is the resolution effect). The `mtg-rules`-names-no-card rule holds.
 */
class LibraryRevealSpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val rumble = CardRef("Fixture Rumble")
        val spawn = CardRef("Rumble Spawn")

        "CR 701.16: resolving the reveal effect reveals the top four and offers only permanent cards to keep" {
            val state = rumbleState(libraryTop = listOf("Bear", "Bolt Card", "Wall", "Bolt Card"))
            val reveal = castRumbleUntilReveal(engine, state)
            // Four cards revealed; the two permanent cards (Bear, Wall) are keepable, the instants are not.
            reveal.request.options.map { it.card.name } shouldContainExactly listOf("Bear", "Wall")
            reveal.state.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .single()
                .cards.size shouldBe 4
            // The independent clause (create a token) already ran during resolution.
            reveal.state.sharedZones.battlefield
                .count { it.card == spawn } shouldBe 1
        }

        "CR 701.16: keeping a permanent card puts it in hand and the rest into the graveyard" {
            val state = rumbleState(libraryTop = listOf("Bear", "Bolt Card", "Wall", "Bolt Card"))
            val reveal = castRumbleUntilReveal(engine, state)
            val bearIndex = reveal.request.options.indexOfFirst { it.card.name == "Bear" }
            val done = engine.advance(reveal.state, Decision.SingleSelect(reveal.request.id, bearIndex)).pausedState
            done.players
                .getValue(alice)
                .hand
                .count { it.card == CardRef("Bear") } shouldBe 1
            // The other three revealed cards are in the graveyard; the library shrank by four.
            done.players
                .getValue(alice)
                .graveyard
                .map { it.card.name }
                .sorted() shouldContainExactly
                listOf("Bolt Card", "Bolt Card", "Fixture Rumble", "Wall").sorted()
            done.players
                .getValue(alice)
                .library
                .isEmpty() shouldBe true
        }

        "CR 701.16: keeping none puts every revealed card into the graveyard" {
            val state = rumbleState(libraryTop = listOf("Bear", "Bolt Card", "Wall", "Bolt Card"))
            val reveal = castRumbleUntilReveal(engine, state)
            val done =
                engine
                    .advance(
                        reveal.state,
                        Decision.SingleSelect(reveal.request.id, reveal.request.keepNoneIndex),
                    ).pausedState
            done.players
                .getValue(alice)
                .hand
                .none { it.card == CardRef("Bear") } shouldBe true
            // All four revealed cards plus the Rumble itself are in the graveyard.
            done.players
                .getValue(alice)
                .graveyard
                .count { it.card == CardRef("Bear") } shouldBe 1
            done.players
                .getValue(alice)
                .graveyard
                .count { it.card == CardRef("Wall") } shouldBe 1
        }

        "CR 701.16: revealing no permanent cards needs no choice — all go to the graveyard" {
            // Top four are all instants: no keepable card, so resolution finishes with no pause.
            val state = rumbleState(libraryTop = listOf("Bolt Card", "Bolt Card", "Bolt Card", "Bolt Card"))
            var current: AdvanceResult = engine.advance(state, castDecision(pausedRequestOf(state), rumble.name))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            // Pass to resolve; no ChooseFromRevealed ever surfaces.
            while (current is AdvanceResult.NeedsDecision &&
                current.state.sharedZones.stack
                    .isNotEmpty()
            ) {
                val request = current.request as? DecisionRequest.ChooseAction ?: break
                current = engine.advance(current.state, passDecision(request))
            }
            val done = current.pausedState
            done.players
                .getValue(alice)
                .graveyard
                .count { it.card == CardRef("Bolt Card") } shouldBe 4
            done.players
                .getValue(alice)
                .library
                .isEmpty() shouldBe true
        }
    })

/** The paused reveal request and the state it is paused in. */
private data class RevealPause(
    val state: GameState,
    val request: DecisionRequest.ChooseFromRevealed,
)

/** Casts Fixture Rumble and drives to the keep-one reveal pause. */
private fun castRumbleUntilReveal(
    engine: GameEngine,
    state: GameState,
): RevealPause {
    var current: AdvanceResult = engine.advance(state, castDecision(pausedRequestOf(state), "Fixture Rumble"))
    current = engine.advance(current.pausedState, planDecision(current.pending()))
    while (true) {
        val paused = current as? AdvanceResult.NeedsDecision ?: error("game ended before the reveal pause")
        when (val request = paused.request) {
            is DecisionRequest.ChooseFromRevealed -> return RevealPause(paused.state, request)
            is DecisionRequest.ChooseAction -> current = engine.advance(paused.state, passDecision(request))
            else -> error("unexpected request before the reveal pause: $request")
        }
    }
}

private val rumbleSpawnToken =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Rumble Spawn",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(0, 1),
            ),
    )

private val rumbleFixture: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Rumble",
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // The independent "create a token" clause is the ordinary resolution effect.
        override val resolution = ResolutionEffect { s, ctx -> createToken(s, ctx.controller, rumbleSpawnToken) }
        override val libraryReveal = LibraryReveal(4, RevealedCardFilter.PERMANENT_CARD)
    }

private fun typedCard(
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
            if (type ==
                CardType.LAND
            ) {
                persistentListOf(ManaAbility(persistentListOf(ManaType.RED)))
            } else {
                persistentListOf()
            }
    }

private val revealRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef("Fixture Rumble") to rumbleFixture,
        CardRef("Rumble Spawn") to rumbleSpawnToken,
        CardRef("Reveal Mountain") to typedCard("Reveal Mountain", CardType.LAND),
        CardRef("Bear") to typedCard("Bear", CardType.CREATURE),
        CardRef("Wall") to typedCard("Wall", CardType.CREATURE),
        CardRef("Bolt Card") to typedCard("Bolt Card", CardType.INSTANT),
    )

/** Alice holding priority with Fixture Rumble in hand, a Mountain to pay, and a known library top. */
private fun rumbleState(libraryTop: List<String>): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val hand = objects(listOf("Fixture Rumble"), alice)
    val field = objects(listOf("Reveal Mountain"), alice)
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
        rng = Rng(0),
        events = persistentListOf(),
        definitions = revealRegistry.toPersistentMap(),
    )
}
