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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P6.3 extension of the library-reveal primitive (CR 701.16): a keep **allowance** greater than one
 * ("put up to three … into your hand") and the enchantment-card filter. The allowance is gathered as up
 * to N enumerated single selections (ADR-005) accumulating in
 * [dev.mtgplay.core.state.PendingRevealSelection.keptIds]; nothing moves zones until the selection
 * closes, so every subset up to the allowance is reachable. Driven through the real engine on a fixture
 * card — the `mtg-rules`-names-no-card rule holds.
 */
class MultiKeepRevealSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 701.16: an up-to-three keep offers the enchantment cards only — lands and instants are not keepable" {
            val state = insightState(listOf("Fixture Aura", "Fixture Land", "Fixture Bolt", "Fixture Shrine"))
            val reveal = castInsightUntilReveal(engine, state)
            reveal.request.options.map { it.card.name } shouldContainExactly
                listOf("Fixture Aura", "Fixture Shrine")
        }

        "CR 701.16: keeping three enchantment cards puts all three in hand and the rest into the graveyard" {
            val state =
                insightState(
                    listOf("Fixture Aura", "Fixture Shrine", "Fixture Aura", "Fixture Bolt", "Fixture Land"),
                )
            var current = castInsightUntilReveal(engine, state)
            // Three rounds of "keep the first remaining candidate" spend the allowance exactly.
            val kept = mutableListOf<String>()
            repeat(KEEP_ALLOWANCE - 1) {
                kept +=
                    current.request.options
                        .first()
                        .card.name
                val next = engine.advance(current.state, Decision.SingleSelect(current.request.id, 0))
                current = KeepPause(next.pausedState, next.pending())
            }
            kept +=
                current.request.options
                    .first()
                    .card.name
            val done = engine.advance(current.state, Decision.SingleSelect(current.request.id, 0)).pausedState

            kept shouldHaveSize KEEP_ALLOWANCE
            done.players
                .getValue(alice)
                .hand
                .map { it.card.name }
                .sorted() shouldContainExactly kept.sorted()
            // Everything revealed but not kept went to the graveyard, alongside the spent sorcery.
            done.players
                .getValue(alice)
                .graveyard
                .map { it.card.name }
                .sorted() shouldContainExactly
                listOf("Fixture Bolt", "Fixture Insight", "Fixture Land").sorted()
        }

        "CR 701.16: the allowance is a maximum — keeping fewer leaves the declined enchantments in the graveyard" {
            val state = insightState(listOf("Fixture Aura", "Fixture Shrine", "Fixture Aura"))
            val first = castInsightUntilReveal(engine, state)
            val afterOne = engine.advance(first.state, Decision.SingleSelect(first.request.id, 0))
            val second: DecisionRequest.ChooseFromRevealed = afterOne.pending()
            // Two candidates remain and the allowance is unspent, so a second round is offered; stop here.
            second.options shouldHaveSize 2
            val done =
                engine
                    .advance(afterOne.pausedState, Decision.SingleSelect(second.id, second.keepNoneIndex))
                    .pausedState
            done.players
                .getValue(alice)
                .hand
                .map { it.card.name } shouldContainExactly listOf("Fixture Aura")
            // The two enchantment cards left behind are "the rest of the revealed cards" (CR 701.16).
            done.players
                .getValue(alice)
                .graveyard
                .count { it.card.name == "Fixture Aura" } shouldBe 1
            done.players
                .getValue(alice)
                .graveyard
                .count { it.card.name == "Fixture Shrine" } shouldBe 1
        }

        "CR 701.16: the reveal closes early when the last matching card is kept, before the allowance is spent" {
            // Only one enchantment card among the revealed cards, but the allowance is three.
            val state = insightState(listOf("Fixture Aura", "Fixture Bolt", "Fixture Land"))
            val reveal = castInsightUntilReveal(engine, state)
            val done = engine.advance(reveal.state, Decision.SingleSelect(reveal.request.id, 0)).pausedState
            // No second pause: the selection is closed and the spell has left the stack.
            done.pendingRevealSelection shouldBe null
            done.sharedZones.stack shouldHaveSize 0
            done.players
                .getValue(alice)
                .hand
                .map { it.card.name } shouldContainExactly listOf("Fixture Aura")
        }

        "CR 701.16: the pause carries the keeps so far as public information — no hidden position (ADR-004)" {
            val state = insightState(listOf("Fixture Aura", "Fixture Shrine", "Fixture Land"))
            val first = castInsightUntilReveal(engine, state)
            first.state.pendingRevealSelection
                .shouldNotBeNull()
                .keptIds
                .shouldHaveSize(0)
            val afterOne = engine.advance(first.state, Decision.SingleSelect(first.request.id, 0)).pausedState
            val pending = afterOne.pendingRevealSelection.shouldNotBeNull()
            pending.keptIds shouldHaveSize 1
            // Both seats see the keep: the revealed cards are public (CR 701.16), so the view exposes it.
            viewFor(afterOne, bob)
                .pendingReveal
                .shouldNotBeNull()
                .kept
                .map { it.card.name } shouldContainExactly listOf("Fixture Aura")
            // Nothing has moved zones yet — the kept card is still in the library until the reveal closes.
            afterOne.players
                .getValue(alice)
                .hand
                .shouldHaveSize(0)
        }

        "CR 701.16: a keep that is not among the revealed cards is rejected, not silently ignored" {
            val state = insightState(listOf("Fixture Aura", "Fixture Bolt", "Fixture Land"))
            val reveal = castInsightUntilReveal(engine, state)
            shouldThrow<IllegalArgumentException> {
                engine.advance(reveal.state, Decision.SingleSelect(reveal.request.id, OUT_OF_RANGE_INDEX))
            }
        }
    })

/** Fixture Insight's keep allowance — the "up to three" of the clause under test. */
private const val KEEP_ALLOWANCE: Int = 3

/** Fixture Insight's reveal count. */
private const val REVEAL_COUNT: Int = 5

/** An index past the last legal option of any reveal request in this spec (CR 701.16). */
private const val OUT_OF_RANGE_INDEX: Int = 99

/** The paused reveal request and the state it is paused in. */
private data class KeepPause(
    val state: GameState,
    val request: DecisionRequest.ChooseFromRevealed,
)

/** Casts Fixture Insight and drives to the first keep pause. */
private fun castInsightUntilReveal(
    engine: GameEngine,
    state: GameState,
): KeepPause {
    var current: AdvanceResult = engine.advance(state, castDecision(pausedRequestOf(state), "Fixture Insight"))
    current = engine.advance(current.pausedState, planDecision(current.pending()))
    while (true) {
        val paused = current as? AdvanceResult.NeedsDecision ?: error("game ended before the reveal pause")
        when (val request = paused.request) {
            is DecisionRequest.ChooseFromRevealed -> return KeepPause(paused.state, request)
            is DecisionRequest.ChooseAction -> current = engine.advance(paused.state, passDecision(request))
            else -> error("unexpected request before the reveal pause: $request")
        }
    }
}

/** The fixture "reveal five, put up to three enchantment cards into your hand" sorcery (CR 701.16). */
private val insightFixture: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Insight",
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
            LibraryReveal(REVEAL_COUNT, RevealedCardFilter.ENCHANTMENT_CARD, toHandCount = KEEP_ALLOWANCE)
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
            if (type == CardType.LAND) {
                persistentListOf(ManaAbility(persistentListOf(ManaType.RED)))
            } else {
                persistentListOf()
            }
    }

private val insightRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef("Fixture Insight") to insightFixture,
        CardRef("Fixture Source") to typedCard("Fixture Source", CardType.LAND),
        CardRef("Fixture Land") to typedCard("Fixture Land", CardType.LAND),
        CardRef("Fixture Aura") to typedCard("Fixture Aura", CardType.ENCHANTMENT),
        CardRef("Fixture Shrine") to typedCard("Fixture Shrine", CardType.ENCHANTMENT),
        CardRef("Fixture Bolt") to typedCard("Fixture Bolt", CardType.INSTANT),
    )

/** Alice holding priority with Fixture Insight in hand, a land to pay with, and a known library top. */
private fun insightState(libraryTop: List<String>): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val hand = objects(listOf("Fixture Insight"), alice)
    val field = objects(listOf("Fixture Source"), alice)
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
        definitions = insightRegistry.toPersistentMap(),
    )
}
