package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
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
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.loseLife
import dev.mtgplay.rules.engine.opponentOf
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P6.2a additional-discard cost (CR 601.2b) with linked information at resolution and the
 * cost-discard→madness interception (CR 702.35a), mirroring Grab the Prize's "As an additional cost to
 * cast this spell, discard a card. Draw two cards. If the discarded card wasn't a land card, [it] deals
 * 2 damage to each opponent." The `mtg-rules`-names-no-card rule holds; the linked-info effect here uses
 * the opponent's life as a proxy for "each opponent".
 */
class AdditionalDiscardCostSpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val grab = CardRef("Fixture Grab")
        val fieryTemper = CardRef("Fixture Fiery Temper")

        "CR 601.2b: Grab surfaces an additional-discard selection during the cast" {
            val state = grabState(aliceHand = listOf(grab.name, "Fixture Bolt"))
            val current = engine.advance(state, castDecision(pausedRequestOf(state), grab.name))
            val discardRequest = current.pending<DecisionRequest.ChooseCardsToDiscardForCost>()
            discardRequest.count shouldBe 1
            // The card being cast is not itself a discard option; only the other hand card is.
            discardRequest.options.map { it.card } shouldBe listOf(CardRef("Fixture Bolt"))
        }

        "CR 601.2b: Grab is not castable with no other card to discard" {
            val state = grabState(aliceHand = listOf(grab.name))
            val request = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            request.options.filterIsInstance<PriorityOption.CastSpell>().none { it.card == grab } shouldBe true
        }

        "CR 601.2b: the discarded non-land card's identity reaches resolution ('wasn't a land')" {
            // Discard the non-land Fixture Bolt; resolution deals 2 to the opponent.
            val state = grabState(aliceHand = listOf(grab.name, "Fixture Bolt"))
            val resolved = castGrabDiscarding(engine, state, "Fixture Bolt")
            // The linked info was 'not a land', so bob lost 2 life.
            (STARTING_LIFE - resolved.players.getValue(bob).life) shouldBe GRAB_DAMAGE
            // And Grab drew two cards.
            resolved.players.getValue(alice).drawsThisTurn shouldBe 2
        }

        "CR 601.2b: discarding a land card suppresses the 'wasn't a land' effect" {
            // 'Fixture Mountain' is a land; discarding it means bob takes no damage.
            val state = grabState(aliceHand = listOf(grab.name, "Fixture Mountain"))
            val resolved = castGrabDiscarding(engine, state, "Fixture Mountain")
            resolved.players.getValue(bob).life shouldBe STARTING_LIFE
        }

        "CR 702.35a: discarding a madness card to the cost exiles it and fires its reflexive trigger" {
            // Discard the madness Fixture Fiery Temper to Grab's cost: it is exiled, not put into the graveyard.
            val state = grabState(aliceHand = listOf(grab.name, fieryTemper.name))
            var current = engine.advance(state, castDecision(pausedRequestOf(state), grab.name))
            val discardRequest = current.pending<DecisionRequest.ChooseCardsToDiscardForCost>()
            val fieryIndex = discardRequest.options.indexOfFirst { it.card == fieryTemper }
            current = engine.advance(current.pausedState, Decision.MultiSelect(discardRequest.id, listOf(fieryIndex)))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            val paused = current.pausedState
            // The Fiery Temper is in exile, marked awaiting madness — not in the graveyard.
            val exiled = paused.sharedZones.exile.singleOrNull { it.card == fieryTemper }
            (exiled != null && exiled.awaitingMadness) shouldBe true
            paused.players
                .getValue(alice)
                .graveyard
                .none { it.card == fieryTemper } shouldBe true
            // Its reflexive madness trigger is on the stack (placed after the cast).
            paused.sharedZones.stack.any {
                it is StackEntry.Ability && it.trigger.ability.condition == TriggerCondition.MadnessCast
            } shouldBe true
        }
    })

/** How much a Grab whose discarded card wasn't a land deals to each opponent. */
private const val GRAB_DAMAGE: Int = 2

/** Casts Grab, choosing to discard the named hand card, and resolves it; returns the post-resolution state. */
private fun castGrabDiscarding(
    engine: GameEngine,
    state: GameState,
    discard: String,
): GameState {
    var current = engine.advance(state, castDecision(pausedRequestOf(state), "Fixture Grab"))
    val discardRequest = current.pending<DecisionRequest.ChooseCardsToDiscardForCost>()
    val index = discardRequest.options.indexOfFirst { it.card == CardRef(discard) }
    current = engine.advance(current.pausedState, Decision.MultiSelect(discardRequest.id, listOf(index)))
    current = engine.advance(current.pausedState, planDecision(current.pending()))
    // Both players pass; Grab resolves.
    while (current is AdvanceResult.NeedsDecision &&
        current.state.sharedZones.stack
            .isNotEmpty()
    ) {
        val request = current.request as? DecisionRequest.ChooseAction ?: break
        current = engine.advance(current.state, passDecision(request))
    }
    return current.pausedState
}

/** A Grab-the-Prize fixture: {R} sorcery, additional cost discard a card, reads the discard's identity. */
private val grabFixture: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Grab",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val additionalCost = AdditionalCost.DiscardCards(1)
        override val resolution =
            ResolutionEffect { state, context ->
                val drawn = drawCards(state, context.controller, 2)
                val discardedRef = context.discardedForCost.singleOrNull()
                val wasLand =
                    discardedRef != null &&
                        state.definitions[discardedRef]?.let { CardType.LAND in it.characteristics.cardTypes } == true
                if (wasLand) drawn else loseLife(drawn, drawn.opponentOf(context.controller), GRAB_DAMAGE)
            }
    }

/** A handcrafted priority window (alice active, holding) with Grab and the madness/mana fixtures. */
private fun grabState(aliceHand: List<String>): GameState {
    val registry =
        (fixtureDefinitions + castFromElsewhereFixtures + (CardRef("Fixture Grab") to grabFixture))
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val aliceHandObjects = objects(aliceHand, alice)
    val aliceField = objects(listOf("Fixture Mountain"), alice)
    val aliceLibrary = objects(listOf("Fixture Bolt", "Fixture Bolt", "Fixture Bolt"), alice)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = aliceLibrary,
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
        definitions = registry.toPersistentMap(),
    )
}
