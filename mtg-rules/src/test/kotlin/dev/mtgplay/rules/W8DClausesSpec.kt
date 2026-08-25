package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ChosenTypeReveal
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.OptionalManaThenDraw
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.TargetPlayerExilesFromGraveyard
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
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
 * The three post-resolution clauses `W8-D` added to the `FW-CLAUSEHOOK` family
 * (docs/design/resolution-clause-hook.md), driven through the engine on fixture abilities — the
 * `mtg-rules`-names-no-card rule holds, so nothing here mentions Winding Way, Nihil Spellbomb, or Relic
 * of Progenitus by name.
 *
 * Each test pins the property that made the clause a clause rather than a
 * [dev.mtgplay.core.definition.ResolutionEffect]: the type choice happens **before** the reveal, the
 * mana payment is a *fused* decline-or-plan request, and the graveyard exile is decided by a seat that
 * is not the resolving object's controller.
 */
class W8DClausesSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 609.4: the type choice is offered before anything is revealed" {
            val paused =
                resolveTopOfStack(triggerCarrying(creatureOrLand)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseRevealedCardType>()

            request.options shouldContainExactly
                listOf(RevealedCardFilter.CREATURE_CARD, RevealedCardFilter.LAND_CARD)
            request.revealCount shouldBe REVEAL_COUNT
            // The whole reason this is not a mode and not the CR 701.16 reveal clause: the library is
            // untouched and nothing has been shown to anybody at the moment of the choice.
            paused.state.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .shouldBeEmpty()
            paused.state.players
                .getValue(alice)
                .library
                .size shouldBe LIBRARY.size
        }

        "CR 701.16: naming a type puts *every* matching revealed card into hand and the rest in the graveyard" {
            val paused =
                resolveTopOfStack(triggerCarrying(creatureOrLand)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseRevealedCardType>()
            val creature = request.options.indexOf(RevealedCardFilter.CREATURE_CARD)

            val done = engine.advance(paused.state, Decision.SingleSelect(request.id, creature)).pausedState

            // Two of the four revealed cards are creatures; both go to hand, and there is no second pause
            // asking whether to keep fewer — "put all cards of the chosen type" is mandatory.
            done.players
                .getValue(alice)
                .hand
                .map { it.card.name } shouldContainExactly listOf(BEAR, SECOND_BEAR)
            done.players
                .getValue(alice)
                .graveyard
                .map { it.card.name } shouldContainExactly listOf(WASTE, BOLT)
            done.sharedZones.stack.shouldBeEmpty()
            // CR 701.16a: the reveal is public, and it happened after the choice.
            done.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .single()
                .cards
                .size shouldBe REVEAL_COUNT
        }

        "CR 609.4: naming the other type partitions the same four cards the other way" {
            val paused =
                resolveTopOfStack(triggerCarrying(creatureOrLand)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseRevealedCardType>()
            val land = request.options.indexOf(RevealedCardFilter.LAND_CARD)

            val done = engine.advance(paused.state, Decision.SingleSelect(request.id, land)).pausedState

            done.players
                .getValue(alice)
                .hand
                .map { it.card.name } shouldContainExactly listOf(WASTE)
            done.players
                .getValue(alice)
                .graveyard
                .map { it.card.name } shouldContainExactly listOf(BEAR, SECOND_BEAR, BOLT)
        }

        "CR 601.3b: an optional mana payment fuses the decline with one option per affordable plan" {
            val paused =
                resolveTopOfStack(triggerCarrying(payBlackDrawOne, untappedSwamps = 1))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseOptionalManaPayment>()

            // ADR-005: a bare yes/no would have to offer "yes" to a seat that cannot pay. Index 0 declines,
            // and every later index is a plan the seat can actually execute.
            request.options.first() shouldBe DecisionRequest.ChooseOptionalManaPayment.Option.Decline
            request.options.size shouldBe 2
            request.cost shouldBe ManaCost.parse("{B}")
            request.drawCount shouldBe 1
            request.id.seat shouldBe alice
        }

        "CR 601.3b: declining the payment draws nothing and spends nothing" {
            val paused =
                resolveTopOfStack(triggerCarrying(payBlackDrawOne, untappedSwamps = 1))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseOptionalManaPayment>()

            val done = engine.advance(paused.state, Decision.SingleSelect(request.id, 0)).pausedState

            done.players
                .getValue(alice)
                .hand
                .shouldBeEmpty()
            done.sharedZones.battlefield
                .single()
                .tapped shouldBe false
            done.sharedZones.stack.shouldBeEmpty()
        }

        "CR 118.4: paying the cost in full taps the source and then draws" {
            val paused =
                resolveTopOfStack(triggerCarrying(payBlackDrawOne, untappedSwamps = 1))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseOptionalManaPayment>()

            val done = engine.advance(paused.state, Decision.SingleSelect(request.id, 1)).pausedState

            done.players
                .getValue(alice)
                .hand
                .map { it.card.name } shouldContainExactly listOf(BEAR)
            done.sharedZones.battlefield
                .single()
                .tapped shouldBe true
        }

        "ADR-004: a seat that cannot pay still gets the request, decline-only" {
            val paused =
                resolveTopOfStack(triggerCarrying(payBlackDrawOne, untappedSwamps = 0))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseOptionalManaPayment>()

            // Not skipped: a state-dependent hole in the decision sequence is what a canonical replay log
            // cannot have. Declining and being unable to pay reach the same outcome, by different routes.
            request.options shouldContainExactly listOf(DecisionRequest.ChooseOptionalManaPayment.Option.Decline)
        }

        "CR 701.3a: the graveyard-exile clause is answered by the *targeted* player, not the controller" {
            val paused =
                resolveTopOfStack(exileClauseTargeting(bob)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseGraveyardCardToExile>()

            request.id.seat shouldBe bob
            request.controller shouldBe alice
            // A graveyard is a public zone (CR 400.2), so the options need no ADR-007 filtering.
            request.options.map { it.card.name } shouldContainExactly listOf(BOLT, WASTE)
        }

        "CR 701.3a: the chosen card leaves that player's graveyard for exile" {
            val paused =
                resolveTopOfStack(exileClauseTargeting(bob)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseGraveyardCardToExile>()

            val done = engine.advance(paused.state, Decision.SingleSelect(request.id, 0)).pausedState

            done.players
                .getValue(bob)
                .graveyard
                .map { it.card.name } shouldContainExactly listOf(WASTE)
            done.sharedZones.exile.map { it.card.name } shouldContainExactly listOf(BOLT)
        }

        "ADR-005: a targeted player with an empty graveyard is asked nothing and the clause does nothing" {
            // A request with no options would be an enumerated decision with no legal answer. The printed
            // line is mandatory, so there is no decline to fall back on either — the clause simply ends.
            val done = resolveTopOfStack(exileClauseTargeting(alice))
            done.pausedState.sharedZones.stack
                .shouldBeEmpty()
            done.pausedState.sharedZones.exile
                .shouldBeEmpty()
        }

        "ADR-004: each clause pause re-derives its own request from the state alone" {
            listOf(
                triggerCarrying(creatureOrLand),
                triggerCarrying(payBlackDrawOne, untappedSwamps = 1),
                exileClauseTargeting(bob),
            ).forEach { start ->
                val paused = resolveTopOfStack(start).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
                pendingDecisionRequest(paused.state) shouldBe paused.request
            }
        }
    })

private const val BEAR = "Clause Bear"
private const val SECOND_BEAR = "Clause Cub"
private const val BOLT = "Clause Bolt"
private const val WASTE = "Clause Waste"
private const val SOURCE = "Clause Source"
private const val SWAMP = "Clause Swamp"

/** How many cards the fixture type-choice clause reveals — the printed four. */
private const val REVEAL_COUNT = 4

/** The fixture library, top-first: two creatures, a land, and an instant, so either type matches some. */
private val LIBRARY = listOf(BEAR, SECOND_BEAR, WASTE, BOLT)

private val SOURCE_ID = ObjectId(900)

private val noOp = ResolutionEffect { state, _ -> state }

private val creatureOrLand =
    ChosenTypeReveal(
        count = REVEAL_COUNT,
        choices = persistentListOf(RevealedCardFilter.CREATURE_CARD, RevealedCardFilter.LAND_CARD),
    )

private val payBlackDrawOne = OptionalManaThenDraw(cost = ManaCost.parse("{B}"), drawCount = 1)

/** Alice's resolving trigger carrying the type-choice clause. */
private fun triggerCarrying(clause: ChosenTypeReveal): GameState =
    clauseState(
        StackEntry.Ability(
            PendingTrigger(
                SOURCE_ID,
                CardRef(SOURCE),
                alice,
                TriggeredAbility(TriggerCondition.EnteredBattlefieldSelf, noOp, chosenTypeReveal = clause),
            ),
        ),
    )

/** Alice's resolving trigger carrying the optional-mana clause, with [untappedSwamps] to pay it. */
private fun triggerCarrying(
    clause: OptionalManaThenDraw,
    untappedSwamps: Int,
): GameState =
    clauseState(
        StackEntry.Ability(
            PendingTrigger(
                SOURCE_ID,
                CardRef(SOURCE),
                alice,
                TriggeredAbility(
                    condition = TriggerCondition.PutIntoGraveyardFromBattlefieldSelf,
                    effect = noOp,
                    optionalManaThenDraw = clause,
                ),
            ),
        ),
        swamps = untappedSwamps,
    )

/**
 * Alice's resolving **activated** ability carrying the graveyard-exile clause, targeting [target] — the
 * second carrier kind, and the one whose decider may be the opposing seat.
 */
private fun exileClauseTargeting(target: dev.mtgplay.core.identity.PlayerId): GameState =
    clauseState(
        StackEntry.ActivatedAbilityOnStack(
            sourceId = SOURCE_ID,
            sourceCard = CardRef(SOURCE),
            controller = alice,
            ability =
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.TapSelf),
                    effect = noOp,
                    targetSpec = TargetSpec.TargetPlayer(),
                    targetPlayerExilesFromGraveyard = TargetPlayerExilesFromGraveyard,
                ),
            targets = persistentListOf(Target.Player(target)),
        ),
        bobGraveyard = listOf(BOLT, WASTE),
    )

/**
 * Alice's ability [entry] resolving on top of the stack, with a pinned four-card library. Bob's graveyard
 * is populated only for the exile clause; Alice's is always empty, which is what makes the "targeted
 * player with an empty graveyard" case reachable by pointing the same fixture at Alice.
 */
private fun clauseState(
    entry: StackEntry,
    swamps: Int = 0,
    bobGraveyard: List<String> = emptyList(),
): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: dev.mtgplay.core.identity.PlayerId,
    ) = names.map { GameObject(ObjectId(nextId++), CardRef(it), owner) }.toPersistentList()

    val library = objects(LIBRARY, alice)
    val battlefield = objects(List(swamps) { SWAMP }, alice)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = library,
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = objects(bobGraveyard, bob),
                    ),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(battlefield, persistentListOf(entry), persistentListOf()),
        nextObjectId = 500,
        rng = Rng(11),
        events = persistentListOf(),
        definitions = clauseRegistry.toPersistentMap(),
    )
}

private val clauseRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef(BEAR) to clauseCard(BEAR, CardType.CREATURE),
        CardRef(SECOND_BEAR) to clauseCard(SECOND_BEAR, CardType.CREATURE),
        CardRef(BOLT) to clauseCard(BOLT, CardType.INSTANT),
        CardRef(WASTE) to clauseCard(WASTE, CardType.LAND),
        CardRef(SOURCE) to clauseCard(SOURCE, CardType.ARTIFACT),
        CardRef(SWAMP) to clauseSwamp(),
    )

private fun clauseCard(
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
                // CR 208.1: a creature card has printed power/toughness and nothing else does.
                powerToughness = if (type == CardType.CREATURE) PrintedPowerToughness(1, 1) else null,
            )
    }

/** A fixture land that taps for `{B}`, so the optional-mana clause has something to be paid with. */
private fun clauseSwamp(): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = SWAMP,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.BLACK)))
    }
