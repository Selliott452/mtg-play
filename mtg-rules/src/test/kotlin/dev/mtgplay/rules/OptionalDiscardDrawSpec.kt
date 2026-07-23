package dev.mtgplay.rules

import dev.mtgplay.core.definition.OptionalDiscardDraw
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
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
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P6.2a optional "you may discard a card; if you do, draw N" flow (CR 601.3b) — Melded Moxite's
 * enters-the-battlefield clause, the madness pattern generalized. Fixtures mirror its shape (the
 * `mtg-rules`-names-no-card rule holds); the cost-discard-madness interception is verified too.
 */
class OptionalDiscardDrawSpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val fieryTemper = CardRef("Fixture Fiery Temper")

        "CR 601.3b: accepting the 'may' discards a card and draws the clause's cards" {
            val state = optionalState(hand = listOf("Filler"), libraryCards = 3)
            // Resolve the trigger; it pauses for the yes/no.
            var current = resolveTopOfStack(state)
            val yesNo = current.pending<DecisionRequest.ChooseYesNo>()
            current =
                engine.advance(current.pausedState, Decision.SingleSelect(yesNo.id, DecisionRequest.ChooseYesNo.ACCEPT))
            // Then a discard selection.
            val discard = current.pending<DecisionRequest.ChooseOptionalDiscard>()
            discard.count shouldBe 1
            val done = engine.advance(current.pausedState, Decision.MultiSelect(discard.id, listOf(0))).pausedState
            // The filler was discarded and two cards were drawn.
            done.players
                .getValue(alice)
                .graveyard
                .count { it.card == CardRef("Filler") } shouldBe 1
            done.players.getValue(alice).drawsThisTurn shouldBe 2
        }

        "CR 601.3b: declining the 'may' discards nothing and draws nothing" {
            val state = optionalState(hand = listOf("Filler"), libraryCards = 3)
            val current = resolveTopOfStack(state)
            val yesNo = current.pending<DecisionRequest.ChooseYesNo>()
            val done =
                engine
                    .advance(
                        current.pausedState,
                        Decision.SingleSelect(yesNo.id, DecisionRequest.ChooseYesNo.DECLINE),
                    ).pausedState
            done.players
                .getValue(alice)
                .graveyard
                .isEmpty() shouldBe true
            done.players.getValue(alice).drawsThisTurn shouldBe 0
        }

        "CR 601.3b: with an empty hand the clause does nothing and never surfaces a choice" {
            val state = optionalState(hand = emptyList(), libraryCards = 3)
            // No card to discard, so the trigger resolves straight to a priority window with no draw.
            val done = resolveTopOfStack(state).pending<DecisionRequest.ChooseAction>()
            done.seat shouldBe alice
            resolveTopOfStack(state)
                .pausedState.players
                .getValue(alice)
                .drawsThisTurn shouldBe 0
        }

        "CR 702.35a: discarding a madness card to the clause exiles it and fires its reflexive trigger" {
            val state = optionalState(hand = listOf(fieryTemper.name), libraryCards = 3)
            var current = resolveTopOfStack(state)
            val yesNo = current.pending<DecisionRequest.ChooseYesNo>()
            current =
                engine.advance(current.pausedState, Decision.SingleSelect(yesNo.id, DecisionRequest.ChooseYesNo.ACCEPT))
            val discard = current.pending<DecisionRequest.ChooseOptionalDiscard>()
            val done = engine.advance(current.pausedState, Decision.MultiSelect(discard.id, listOf(0))).pausedState
            // The Fiery Temper was exiled (madness), not put into the graveyard.
            done.sharedZones.exile
                .singleOrNull { it.card == fieryTemper }
                ?.awaitingMadness shouldBe true
            done.players
                .getValue(alice)
                .graveyard
                .none { it.card == fieryTemper } shouldBe true
        }
    })

/** A state with an optional-discard-draw(2) ability on the stack for alice, plus a hand and library. */
private fun optionalState(
    hand: List<String>,
    libraryCards: Int,
): GameState {
    val ability =
        TriggeredAbility(
            condition = TriggerCondition.EnteredBattlefieldSelf,
            effect = ResolutionEffect { s, _ -> s },
            optionalDiscardDraw = OptionalDiscardDraw(2),
        )
    val trigger = PendingTrigger(ObjectId(0), CardRef("Fixture Moxite"), alice, ability)
    var nextId = 100L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val handObjects = objects(hand, alice)
    val library = objects(List(libraryCards) { "Filler" }, alice)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = library,
                        hand = handObjects,
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.NONE,
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
        sharedZones =
            SharedZones(
                persistentListOf(),
                persistentListOf(StackEntry.Ability(trigger)),
                persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = castFromElsewhereFixtures.toPersistentMap(),
    )
}
