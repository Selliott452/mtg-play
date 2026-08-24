package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.DrawThenDiscard
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.LibraryReveal
import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.core.definition.OptionalCostThenDraw
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
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
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.resolutionClauses
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.pendingDecisionRequest
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The `FW-CLAUSEHOOK` post-resolution clause hook (docs/design/resolution-clause-hook.md): the four
 * clauses `mtg-rules` orchestrates around a mid-resolution pause — the CR 701.16 library reveal, the
 * CR 701.14a private library look, the CR 601.3b optional cost-then-draw, and the CR 601.2c
 * draw-then-discard — are carried by [dev.mtgplay.core.definition.ResolutionClauses], so a **resolving
 * ability** carries them exactly as a resolving spell does.
 *
 * The contract these tests pin is that there is *one* implementation, not two: an ability's clause runs
 * through the same orchestration, surfaces the same enumerated request, and differs only in how the
 * resolving object leaves the stack — CR 113.7a cessation instead of the CR 608.2m graveyard move.
 * Fixture abilities only; the `mtg-rules`-names-no-card rule holds.
 */
class ResolutionClauseHookSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 701.17a via CR 603: a triggered ability's scry clause pauses with the same six arrangements" {
            val paused = resolveTopOfStack(triggerOnStack(scryTwo)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseLibraryArrangement>()

            // Byte-for-byte the enumeration a resolving *spell* with the same clause produces: the hook
            // generalised the carrier, not the CR 701.17a `(n + 1)!` option space.
            request.options.size shouldBe SCRY_TWO_ARRANGEMENTS
            request.pool.map { it.card.name } shouldContainExactly listOf(TOP_CARD, SECOND_CARD)
            request.id.seat shouldBe alice
        }

        "CR 113.7a: an ability that finished a clause ceases to exist — no card reaches a graveyard" {
            val paused = resolveTopOfStack(triggerOnStack(scryTwo)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseLibraryArrangement>()
            val chosen = request.options.indexOfFirst { it.toBottom == listOf(0, 1) }

            val done = engine.advance(paused.state, Decision.SingleSelect(request.id, chosen)).pausedState

            // The whole difference between the two resolution paths, asserted directly: a spell's card
            // would now be in a graveyard (CR 608.2m); an ability was never a card, so nothing moved.
            done.sharedZones.stack.shouldBeEmpty()
            done.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            done.events
                .filterIsInstance<GameEvent.TriggeredAbilityResolved>()
                .single()
                .sourceCard shouldBe CardRef(SOURCE_CARD)
            // The clause itself still happened: both looked-at cards went to the bottom, in order.
            done.players
                .getValue(alice)
                .library
                .map { it.card.name } shouldContainExactly listOf(THIRD_CARD, TOP_CARD, SECOND_CARD)
        }

        "CR 701.14a: a look driven by an ability is private — it emits a count and no CardsRevealed" {
            val paused = resolveTopOfStack(triggerOnStack(scryTwo)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()

            paused.state.events.filterIsInstance<GameEvent.CardsRevealed>() shouldBe emptyList()
            paused.state.events
                .filterIsInstance<GameEvent.CardsLookedAt>()
                .single()
                .count shouldBe 2
        }

        "ADR-005: a mandatory keep on an ability enumerates no arrangement that keeps nothing" {
            val paused =
                resolveTopOfStack(triggerOnStack(oneToHand)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseLibraryArrangement>()

            // Sea Gate Oracle's "put one of them into your hand" is not a "you may". The illegal decline has
            // no index, on the ability path exactly as on the spell path.
            request.options.all { it.toHand.size == 1 } shouldBe true
        }

        "CR 701.16: a triggered ability's reveal clause reveals publicly, then the ability ceases" {
            val paused =
                resolveTopOfStack(triggerOnStack(revealOne)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            paused.request.shouldBeInstanceOf<DecisionRequest.ChooseFromRevealed>()

            // The public counterpart of the look: CR 701.16a shows the cards to every player.
            paused.state.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .single()
                .cards
                .map { it.name } shouldContainExactly listOf(TOP_CARD)
        }

        "CR 601.2c: a triggered ability's draw-then-discard clause draws, then pauses for the discard" {
            val paused =
                resolveTopOfStack(triggerOnStack(drawTwoDiscardOne))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseResolutionDiscards>()

            // The draw half ran as part of the clause; the discard is the mandatory selection.
            paused.state.players
                .getValue(alice)
                .hand.size shouldBe 2
            request.count shouldBe 1
        }

        "CR 601.3b: an activated ability's optional cost-then-draw clause offers its performable modes" {
            val state = activatedOnStack(OptionalCostThenDraw(drawCount = 1, modes = DISCARD_ONLY))
            val paused = resolveTopOfStack(state).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseCostMode>()

            // The second ability kind reaches the same orchestration: an activated ability is a clause
            // carrier too, so nothing about this flow is trigger-specific.
            request.options shouldContainExactly listOf(OptionalCostMode.DiscardCard)
        }

        "CR 113.7c: an ability-carried clause's yes/no names the ability's source, not a card on the stack" {
            val start = triggerOnStack(reorderThenShuffle)
            val arrangementPause =
                resolveTopOfStack(start).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val arrangement =
                arrangementPause.request.shouldBeInstanceOf<DecisionRequest.ChooseLibraryArrangement>()

            val shufflePause =
                engine
                    .advance(arrangementPause.state, Decision.SingleSelect(arrangement.id, 0))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val yesNo = shufflePause.request.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()

            // An ability is not a card (CR 113.7a), so the request cannot point at a card on the stack the
            // way a spell's does. It points at the source as last known when the ability went on the stack.
            yesNo.card shouldBe CardRef(SOURCE_CARD)
            yesNo.cardObjectId shouldBe SOURCE_ID
        }

        "ADR-004: an ability's clause pause re-derives its own request from the state alone" {
            val paused = resolveTopOfStack(triggerOnStack(scryTwo)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()

            // The resolving ability stays on top of the stack through the pause, so the request is a pure
            // derivation — the same guarantee the spell path gives, reached through resolutionClauses.
            paused.state.sharedZones.stack
                .last()
                .resolutionClauses.libraryLook shouldBe scryTwo
            pendingDecisionRequest(paused.state) shouldBe paused.request
        }

        "CR 608.2c: a definition declaring two post-resolution clauses fails loudly rather than sequencing" {
            // The engine orchestrates at most one clause; running two in field order would be exactly the
            // silent approximation CONVENTIONS.md forbids, so the ambiguity is rejected at construction.
            val failure =
                shouldThrow<IllegalArgumentException> {
                    TriggeredAbility(
                        condition = TriggerCondition.EnteredBattlefieldSelf,
                        effect = noOp,
                        libraryLook = scryTwo,
                        drawThenDiscard = DrawThenDiscard(drawCount = 1, discardCount = 1),
                    )
                }
            failure.message.orEmpty() shouldContain "at most one"
        }
    })

/** How many arrangements a scry 2 admits: `(2 + 1)!` — the same count the spell path enumerates. */
private const val SCRY_TWO_ARRANGEMENTS = 6

private const val TOP_CARD = "Hook Bear"
private const val SECOND_CARD = "Hook Bolt"
private const val THIRD_CARD = "Hook Wall"
private const val SOURCE_CARD = "Hook Source"

/** The ability source's last-known object id (CR 113.7c) — deliberately not any object on the stack. */
private val SOURCE_ID = ObjectId(900)

private val noOp = ResolutionEffect { state, _ -> state }

private val DISCARD_ONLY = persistentListOf(OptionalCostMode.DiscardCard)

private val scryTwo = LibraryLook(mode = LibraryLookMode.Scry(2))
private val oneToHand = LibraryLook(mode = LibraryLookMode.OneToHandRestToBottom(2))
private val reorderThenShuffle = LibraryLook(mode = LibraryLookMode.ReorderTop(2), optionalShuffle = true)
private val revealOne = LibraryReveal(count = 1, toHand = RevealedCardFilter.PERMANENT_CARD, toHandCount = 1)
private val drawTwoDiscardOne = DrawThenDiscard(drawCount = 2, discardCount = 1)

/** A resolving triggered ability carrying [look], on top of an otherwise ordinary state. */
private fun triggerOnStack(look: LibraryLook): GameState =
    clauseState(
        StackEntry.Ability(
            PendingTrigger(
                SOURCE_ID,
                CardRef(SOURCE_CARD),
                alice,
                TriggeredAbility(TriggerCondition.EnteredBattlefieldSelf, noOp, libraryLook = look),
            ),
        ),
    )

/** A resolving triggered ability carrying [reveal]. */
private fun triggerOnStack(reveal: LibraryReveal): GameState =
    clauseState(
        StackEntry.Ability(
            PendingTrigger(
                SOURCE_ID,
                CardRef(SOURCE_CARD),
                alice,
                TriggeredAbility(TriggerCondition.EnteredBattlefieldSelf, noOp, libraryReveal = reveal),
            ),
        ),
    )

/** A resolving triggered ability carrying [drawDiscard]. */
private fun triggerOnStack(drawDiscard: DrawThenDiscard): GameState =
    clauseState(
        StackEntry.Ability(
            PendingTrigger(
                SOURCE_ID,
                CardRef(SOURCE_CARD),
                alice,
                TriggeredAbility(TriggerCondition.EnteredBattlefieldSelf, noOp, drawThenDiscard = drawDiscard),
            ),
        ),
    )

/** A resolving *activated* ability carrying [clause] — the second carrier kind. */
private fun activatedOnStack(clause: OptionalCostThenDraw): GameState =
    clauseState(
        StackEntry.ActivatedAbilityOnStack(
            sourceId = SOURCE_ID,
            sourceCard = CardRef(SOURCE_CARD),
            controller = alice,
            ability =
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.TapSelf),
                    effect = noOp,
                    optionalCostThenDraw = clause,
                ),
        ),
        aliceHand = listOf(TOP_CARD),
    )

/**
 * Alice's ability [entry] resolving on top of the stack, with a known three-card library so a look's pool
 * and a reveal's top card are both pinned. No card object is on the stack — an ability is not a card.
 */
private fun clauseState(
    entry: StackEntry,
    aliceHand: List<String> = emptyList(),
): GameState {
    var nextId = 0L

    fun objects(names: List<String>) =
        names.map { GameObject(ObjectId(nextId), CardRef(it), alice).also { _ -> nextId += 1 } }.toPersistentList()

    val library = objects(listOf(TOP_CARD, SECOND_CARD, THIRD_CARD))
    val hand = objects(aliceHand)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = library,
                        hand = hand,
                        graveyard = persistentListOf(),
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
        sharedZones = SharedZones(persistentListOf(), persistentListOf(entry), persistentListOf()),
        nextObjectId = 500,
        rng = Rng(11),
        events = persistentListOf(),
        definitions = hookRegistry.toPersistentMap(),
    )
}

/** The looked-at and revealed fixture cards, typed so a reveal's permanent-card filter has something to match. */
private val hookRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef(TOP_CARD) to hookCard(TOP_CARD, CardType.CREATURE),
        CardRef(SECOND_CARD) to hookCard(SECOND_CARD, CardType.INSTANT),
        CardRef(THIRD_CARD) to hookCard(THIRD_CARD, CardType.CREATURE),
        CardRef(SOURCE_CARD) to hookCard(SOURCE_CARD, CardType.ENCHANTMENT),
    )

private fun hookCard(
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
                // CR 208.1: a creature card, and only a creature card, has a printed power/toughness.
                powerToughness = if (type == CardType.CREATURE) PrintedPowerToughness(2, 2) else null,
            )
    }
