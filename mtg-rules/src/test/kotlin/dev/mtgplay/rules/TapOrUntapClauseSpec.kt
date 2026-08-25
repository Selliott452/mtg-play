package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.OptionalTapOrUntap
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TapOrUntapChoice
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.battlefieldObject
import dev.mtgplay.rules.engine.pendingDecisionRequest
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The CR 608.2c "you may tap **or** untap [target]" clause (`W8-G`) — Sewer-veillance Cam's shape, on
 * fixtures because `mtg-rules` names no card (ADR-003).
 *
 * The point of the framework is that this is **not** modality (CR 700.2): no mode is announced when the
 * ability goes on the stack, and the three-way answer is taken as it resolves. These specs pin the three
 * answers, the always-offered option list, and the vacuous case.
 */
class TapOrUntapClauseSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 608.2c: the clause pauses with all three answers, decline first" {
            val paused =
                resolveTopOfStack(clauseOnStack(targetTapped = false))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseTapOrUntap>()

            request.options shouldContainExactly
                listOf(TapOrUntapChoice.DECLINE, TapOrUntapChoice.TAP, TapOrUntapChoice.UNTAP)
            request.targetId shouldBe TARGET_ID
            request.card shouldBe CardRef(SOURCE_CARD)
            request.id.seat shouldBe alice
        }

        "ADR-004: the pending request is a pure function of the paused state" {
            val paused =
                resolveTopOfStack(clauseOnStack(targetTapped = false))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            pendingDecisionRequest(paused.state) shouldBe paused.request
        }

        "CR 701.20a: answering TAP taps the target and the ability then ceases to exist" {
            val done = answer(engine, targetTapped = false, choice = TapOrUntapChoice.TAP)
            done.battlefieldObject(TARGET_ID).tapped shouldBe true
            // CR 113.7a: an ability is not a card, so nothing moves to a graveyard.
            done.sharedZones.stack.shouldBeEmpty()
            done.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
        }

        "CR 701.21b: answering UNTAP untaps a tapped target" {
            val done = answer(engine, targetTapped = true, choice = TapOrUntapChoice.UNTAP)
            done.battlefieldObject(TARGET_ID).tapped shouldBe false
        }

        "CR 608.2c: declining leaves the target exactly as it was" {
            val done = answer(engine, targetTapped = true, choice = TapOrUntapChoice.DECLINE)
            done.battlefieldObject(TARGET_ID).tapped shouldBe true
        }

        "CR 701.20a: tapping an already-tapped target is a legal answer that simply does nothing" {
            // The two no-op answers are still enumerated: filtering them would make the option list
            // depend on a status nothing the chooser did can be held to (ADR-005).
            val done = answer(engine, targetTapped = true, choice = TapOrUntapChoice.TAP)
            done.battlefieldObject(TARGET_ID).tapped shouldBe true
        }

        "CR 608.2c: an ability that targeted nothing resolves straight through with no pause at all" {
            // Sewer-veillance Cam entering an empty board: the trigger is placed with no target
            // (CR 603.3d's vacuous case), so there is no question whose answers would all be no-ops.
            val resolved = resolveTopOfStack(clauseOnStack(targetTapped = false, withTarget = false))
            resolved.pausedState.sharedZones.stack
                .shouldBeEmpty()
            resolved.pausedState.pendingTapOrUntap shouldBe null
        }
    })

private val SOURCE_ID = ObjectId(90)
private val TARGET_ID = ObjectId(91)
private const val SOURCE_CARD = "Surveillance Fixture"
private const val TARGET_CARD = "Fixture Bear"

/** Resolves the clause and answers it with [choice], returning the state once the ability has finished. */
private fun answer(
    engine: GameEngine,
    targetTapped: Boolean,
    choice: TapOrUntapChoice,
): GameState {
    val paused =
        resolveTopOfStack(clauseOnStack(targetTapped)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
    val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseTapOrUntap>()
    val index = request.options.indexOf(choice)
    return engine.advance(paused.state, Decision.SingleSelect(request.id, index)).pausedState
}

/**
 * Alice's triggered ability carrying the clause, on top of the stack, with a fixture creature on the
 * battlefield as its target (CR 603.3d). [withTarget] `false` is the vacuous placement.
 */
private fun clauseOnStack(
    targetTapped: Boolean,
    withTarget: Boolean = true,
): GameState {
    val source = GameObject(SOURCE_ID, CardRef(SOURCE_CARD), alice)
    val target = GameObject(TARGET_ID, CardRef(TARGET_CARD), bob, tapped = targetTapped)
    val entry =
        StackEntry.Ability(
            PendingTrigger(
                SOURCE_ID,
                CardRef(SOURCE_CARD),
                alice,
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = ResolutionEffect { state, _ -> state },
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE),
                    optionalTapOrUntap = OptionalTapOrUntap,
                ),
            ),
            targets = if (withTarget) persistentListOf(Target.Permanent(TARGET_ID)) else persistentListOf(),
        )
    return GameState(
        players = persistentMapOf(alice to clauseSeat(), bob to clauseSeat()),
        turn = Turn(alice, TURN_NUMBER, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = listOf(source, target).toPersistentList(),
                stack = persistentListOf(entry),
                exile = persistentListOf(),
            ),
        nextObjectId = 500,
        rng = Rng(7),
        events = persistentListOf(),
        definitions = tapClauseRegistry.toPersistentMap(),
    )
}

private fun clauseSeat(): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )

private val tapClauseRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef(SOURCE_CARD) to fixtureCard(SOURCE_CARD, CardType.ARTIFACT),
        CardRef(TARGET_CARD) to fixtureCard(TARGET_CARD, CardType.CREATURE),
    )

private fun fixtureCard(
    name: String,
    type: CardType,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(type),
                subtypes = persistentSetOf(),
                powerToughness = if (type == CardType.CREATURE) PrintedPowerToughness(2, 2) else null,
            )
    }
