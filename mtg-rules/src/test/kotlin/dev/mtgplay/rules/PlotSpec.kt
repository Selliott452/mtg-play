package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
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
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.effect.loseLife
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P6.2a plot mechanic (CR 702.140): the plot special action (pay the plot cost, exile face-up with
 * a plotted-turn marker) and the free cast from exile on a later turn — mirroring Highway Robbery's
 * "Plot {1}{R}". The `mtg-rules`-names-no-card rule holds.
 */
class PlotSpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val highway = CardRef("Fixture Highway")
        val mountain = CardRef("Plot Mountain")

        "CR 702.140: a plottable card in hand offers the plot special action at sorcery speed" {
            val state = plotHandState(turnNumber = 3)
            val request = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            request.options
                .filterIsInstance<PriorityOption.PlotCard>()
                .single()
                .card shouldBe highway
        }

        "CR 702.140: plotting pays the plot cost and exiles the card with this turn's plotted marker" {
            val state = plotHandState(turnNumber = 3)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val plotIndex = window.options.indexOfFirst { it is PriorityOption.PlotCard }
            var current = engine.advance(state, Decision.SingleSelect(window.id, plotIndex))
            // Pay {1}{R} with the two Mountains.
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            val paused = current.pausedState
            // The card is in exile with plottedTurn = 3, and gone from hand; the caster keeps priority.
            val exiled = paused.sharedZones.exile.single { it.card == highway }
            exiled.plottedTurn shouldBe 3
            paused.players
                .getValue(alice)
                .hand
                .none { it.card == highway } shouldBe true
            paused.sharedZones.battlefield.count { it.card == mountain && it.tapped } shouldBe 2
            current.pending<DecisionRequest.ChooseAction>().seat shouldBe alice
        }

        "CR 702.140: a card plotted this turn is not castable for free this turn" {
            // Highway already plotted on turn 3; current turn is still 3.
            val state = plottedExileState(plottedTurn = 3, currentTurn = 3)
            val request = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            request.options
                .filterIsInstance<PriorityOption.CastSpell>()
                .none { it.permission is CastingPermission.Plot } shouldBe true
        }

        "CR 702.140: a card plotted on an earlier turn is castable for free from exile" {
            // Highway plotted on turn 3; current turn 4.
            val state = plottedExileState(plottedTurn = 3, currentTurn = 4)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val freeCastIndex =
                window.options.indexOfFirst {
                    it is PriorityOption.CastSpell && it.permission is CastingPermission.Plot
                }
            (freeCastIndex >= 0) shouldBe true
            var current = engine.advance(state, Decision.SingleSelect(window.id, freeCastIndex))
            // No target, then the {0} free payment.
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            // Resolve: alice loses 1 life (the fixture effect), and the spell leaves exile for the graveyard.
            while (current is AdvanceResult.NeedsDecision &&
                current.state.sharedZones.stack
                    .isNotEmpty()
            ) {
                val request = current.request as? DecisionRequest.ChooseAction ?: break
                current = engine.advance(current.state, passDecision(request))
            }
            val done = current.pausedState
            done.players.getValue(alice).life shouldBe STARTING_LIFE - 1
            done.sharedZones.exile.none { it.card == highway } shouldBe true
            done.players
                .getValue(alice)
                .graveyard
                .any { it.card == highway } shouldBe true
        }
    })

/** The Highway Robbery fixture: {5} sorcery whose controller loses 1 life; Plot {1}{R}. */
private val highwayFixture: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Highway",
                manaCost = ManaCost.parse("{5}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, context -> loseLife(state, context.controller, 1) }
        override val castingPermissions = listOf(CastingPermission.Plot(ManaCost.parse("{1}{R}")))
    }

private val plotRegistry: Map<CardRef, dev.mtgplay.core.definition.CardDefinition> =
    mapOf(
        CardRef("Plot Mountain") to
            object : dev.mtgplay.core.definition.CardDefinition {
                override val characteristics =
                    PrintedCharacteristics(
                        name = "Plot Mountain",
                        manaCost = null,
                        supertypes = persistentSetOf(),
                        cardTypes = persistentSetOf(CardType.LAND),
                        subtypes = persistentSetOf(),
                        powerToughness = null,
                    )
                override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.RED)))
            },
        CardRef("Fixture Highway") to highwayFixture,
    )

/** Alice active on [turnNumber], holding priority, Highway in hand, two Mountains to pay the plot cost. */
private fun plotHandState(turnNumber: Int): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val hand = objects(listOf("Fixture Highway"), alice)
    val field = objects(listOf("Plot Mountain", "Plot Mountain"), alice)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
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
        turn = Turn(alice, turnNumber, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(field, persistentListOf(), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = plotRegistry.toPersistentMap(),
    )
}

/** Alice active on [currentTurn], holding priority, Highway already plotted in exile on [plottedTurn]. */
private fun plottedExileState(
    plottedTurn: Int,
    currentTurn: Int,
): GameState {
    val exiled = GameObject(ObjectId(0), CardRef("Fixture Highway"), alice, plottedTurn = plottedTurn)
    return GameState(
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
        turn = Turn(alice, currentTurn, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf(exiled)),
        nextObjectId = 1,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = plotRegistry.toPersistentMap(),
    )
}
