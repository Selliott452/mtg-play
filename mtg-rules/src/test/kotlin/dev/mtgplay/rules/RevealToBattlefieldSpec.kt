package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CounterAmount
import dev.mtgplay.core.definition.EntersWithCounters
import dev.mtgplay.core.definition.LibraryReveal
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.RevealDisposition
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
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.effectiveKeywords
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The **battlefield** disposition of a CR 701.16 reveal (`W11`) — the shape Throne of the Dead Three
 * prints: reveal the top N, put a chosen creature card onto the battlefield with counters and a
 * keyword grant, leave the rest in the library, then shuffle. A fixture spell, because `mtg-rules`
 * names no card (ADR-003).
 *
 * The four properties the room turns on are one test each, and each is a different way the older
 * hand-and-graveyard reveal would have been wrong: the keep is **mandatory** (a decline the engine
 * offered would be an illegal line, ADR-005), the chosen card enters **with** its counters rather than
 * growing afterwards (CR 614.1c), the grant runs on the "until your next turn" duration rather than the
 * turn, and the *unchosen* cards stay in the library and are shuffled rather than being binned.
 */
class RevealToBattlefieldSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 701.16: a mandatory reveal enumerates no 'keep none' index" {
            val reveal = castThroneUntilReveal(engine, throneState())
            reveal.request.options.map { it.card.name } shouldContainExactly listOf("Bear", "Wall")
            reveal.request.mayKeepNone shouldBe false
            // The decline index would have been options.size; it does not exist, so the request's whole
            // index space is the two creature cards.
            reveal.request.choiceCount shouldBe 2
        }

        "CR 614.1c: the chosen creature card enters the battlefield already carrying its counters" {
            val reveal = castThroneUntilReveal(engine, throneState())
            val bearIndex = reveal.request.options.indexOfFirst { it.card.name == "Bear" }
            val done = engine.advance(reveal.state, Decision.SingleSelect(reveal.request.id, bearIndex)).pausedState
            val bear = done.sharedZones.battlefield.single { it.card == CardRef("Bear") }
            bear.owner shouldBe alice
            bear.counterCount(Counter.PLUS_ONE_PLUS_ONE) shouldBe 3
            // The counters are on the object the entry announced, not placed by a later effect — a 2/2
            // printed body is a 5/5 the first time anything looks at it.
            done.events
                .filterIsInstance<GameEvent.PermanentEntered>()
                .map { it.battlefieldObjectId } shouldContain bear.id
        }

        "CR 611.2: the grant lasts until the revealing player's next turn, not until end of turn" {
            val reveal = castThroneUntilReveal(engine, throneState())
            val bearIndex = reveal.request.options.indexOfFirst { it.card.name == "Bear" }
            val done = engine.advance(reveal.state, Decision.SingleSelect(reveal.request.id, bearIndex)).pausedState
            val bear = done.sharedZones.battlefield.single { it.card == CardRef("Bear") }
            val effect = done.timedEffects.single()
            effect.affected shouldBe bear.id
            effect.duration shouldBe EffectDuration.UntilYourNextTurn(alice)
            effectiveKeywords(done, bear.id) shouldContain Keyword.HEXPROOF
        }

        "CR 701.16: the unchosen revealed cards stay in the library, and the library is shuffled" {
            val before = throneState()
            val reveal = castThroneUntilReveal(engine, before)
            val bearIndex = reveal.request.options.indexOfFirst { it.card.name == "Bear" }
            val done = engine.advance(reveal.state, Decision.SingleSelect(reveal.request.id, bearIndex)).pausedState
            // Every card that was not chosen is still in the library — none was binned, which is the
            // one thing the hand-and-graveyard disposition would have got wrong.
            done.players
                .getValue(alice)
                .library
                .map { it.card.name } shouldContainExactlyInAnyOrder listOf("Bolt Card", "Wall", "Bolt Card")
            // Only the fixture spell itself reached the graveyard (CR 608.2m).
            done.players
                .getValue(alice)
                .graveyard
                .map { it.card.name } shouldContainExactly listOf("Fixture Throne")
            done.players
                .getValue(alice)
                .hand
                .shouldBeEmpty()
            // "Then shuffle" consumes seeded entropy (ADR-006), so the match PRNG has advanced.
            done.rng shouldNotBe before.rng
        }
    })

/** The paused reveal request and the state it is paused in. */
private data class BattlefieldRevealPause(
    val state: GameState,
    val request: DecisionRequest.ChooseFromRevealed,
)

/** Casts Fixture Throne and drives to the choose-one reveal pause. */
private fun castThroneUntilReveal(
    engine: GameEngine,
    state: GameState,
): BattlefieldRevealPause {
    var current: AdvanceResult = engine.advance(state, castDecision(pausedRequestOf(state), "Fixture Throne"))
    current = engine.advance(current.pausedState, planDecision(current.pending()))
    while (true) {
        val paused = current as? AdvanceResult.NeedsDecision ?: error("game ended before the reveal pause")
        when (val request = paused.request) {
            is DecisionRequest.ChooseFromRevealed -> return BattlefieldRevealPause(paused.state, request)
            is DecisionRequest.ChooseAction -> current = engine.advance(paused.state, passDecision(request))
            else -> error("unexpected request before the reveal pause: $request")
        }
    }
}

/** Throne of the Dead Three's shape, at fixture scale: reveal four, one creature card, three counters. */
private val throneFixture: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Throne",
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { s, _ -> s }
        override val libraryReveal =
            LibraryReveal(
                count = 4,
                toHand = RevealedCardFilter.CREATURE_CARD,
                toHandCount = 1,
                disposition = RevealDisposition.CHOSEN_TO_BATTLEFIELD_REST_SHUFFLED,
                mandatory = true,
                entersWithCounters = EntersWithCounters(Counter.PLUS_ONE_PLUS_ONE, CounterAmount.Fixed(3)),
                grantedUntilYourNextTurn = persistentSetOf(Keyword.HEXPROOF),
            )
    }

private fun throneTypedCard(
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

private val throneRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef("Fixture Throne") to throneFixture,
        CardRef("Throne Mountain") to throneTypedCard("Throne Mountain", CardType.LAND),
        CardRef("Bear") to throneTypedCard("Bear", CardType.CREATURE),
        CardRef("Wall") to throneTypedCard("Wall", CardType.CREATURE),
        CardRef("Bolt Card") to throneTypedCard("Bolt Card", CardType.INSTANT),
    )

/** Alice holding priority with Fixture Throne in hand, a land to pay, and a known library top. */
private fun throneState(): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val hand = objects(listOf("Fixture Throne"), alice)
    val field = objects(listOf("Throne Mountain"), alice)
    val library = objects(listOf("Bear", "Bolt Card", "Wall", "Bolt Card"), alice)
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
        definitions = throneRegistry.toPersistentMap(),
    )
}
