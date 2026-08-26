package dev.mtgplay.rules

import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/*
 * Board builders and the cast driver for `ModalCastingSpec`. Split from the spec so the boards read as
 * a table of "what is on the table", which is what the two-template comparison is actually about: the
 * specs below differ from each other only in their board and their card, never in how they are driven.
 */

/** The stack-residence id of the first handcrafted stack entry; well clear of `fixtureState`'s hand ids. */
private const val STACK_BASE_ID = 500L

/** The id allocator's next value on a handcrafted modal board, clear of every id placed by hand. */
private const val MODAL_NEXT_OBJECT_ID = 1000L

/**
 * A two-player board for the modal specs: [aliceHand] in Alice's hand, [aliceBattlefield] under her
 * control, and [stackedSpells] already on the stack under **Bob's** control (bottom-up), with Alice
 * holding priority mid-window (CR 117.1).
 *
 * The stack is handcrafted rather than reached by driving Bob through a cast, because what these specs
 * measure is Alice's enumeration against a given board — driving the opponent's cast would add a second
 * variable to a comparison whose whole point is that only one thing changes.
 */
internal fun modalBoard(
    aliceHand: List<String>,
    aliceBattlefield: List<String>,
    stackedSpells: List<SpellDefinition> = emptyList(),
): GameState {
    val base =
        fixtureState(
            aliceSetup = SeatSetup(hand = aliceHand, battlefield = aliceBattlefield),
            bobSetup = SeatSetup(),
            definitions = modalDefinitions,
        )
    val entries =
        stackedSpells.mapIndexed { index, definition ->
            StackEntry.Spell(
                obj =
                    GameObject(
                        id = ObjectId(STACK_BASE_ID + index),
                        card = CardRef(definition.characteristics.name),
                        owner = bob,
                    ),
                controller = bob,
                targets = persistentListOf(),
                definition = definition,
            )
        }
    return base.copy(
        sharedZones = base.sharedZones.copy(stack = entries.toPersistentList()),
        nextObjectId = MODAL_NEXT_OBJECT_ID,
    )
}

/**
 * A board with a **red spell** ([fixtureBolt]) on the stack and [handCard] in Alice's hand, with an
 * Island to pay `{U}` and — when [artifactOnBattlefield] — a [fixtureRelic] for an artifact mode to
 * point at. The red spell makes a target-restricted counter mode choosable; the Island is colourless,
 * so no destroy mode is.
 */
internal fun boardWithRedSpellOnStack(
    handCard: String,
    artifactOnBattlefield: Boolean,
): GameState =
    modalBoard(
        aliceHand = listOf(handCard),
        aliceBattlefield =
            listOf("Fixture Island") + if (artifactOnBattlefield) listOf("Fixture Relic") else emptyList(),
        stackedSpells = listOf(fixtureBolt),
    )

/**
 * A board carrying an artifact **spell** on the stack *and* an artifact **permanent** on the
 * battlefield, so both of Fixture Sabotage's modes are choosable and their target option lists are
 * disjoint — the board the mode-determines-targets claim is measured on.
 */
internal fun boardWithArtifactSpellAndPermanent(): GameState =
    modalBoard(
        aliceHand = listOf("Fixture Sabotage"),
        aliceBattlefield = listOf("Fixture Island", "Fixture Relic"),
        stackedSpells = listOf(fixtureRelic),
    )

/**
 * A board with **nothing red anywhere**: an empty stack and a single blue-producing but *colourless*
 * land (CR 105.2 — a land has no mana cost). A target-restricted Blast has no legal target for either
 * mode here; an effect-conditional one still has the land to point its destroy mode at.
 */
internal fun colourlessBoard(aliceHand: List<String>): GameState =
    modalBoard(aliceHand = aliceHand, aliceBattlefield = listOf("Fixture Island"))

/**
 * A board with a **white** spell ([fixturePrayer]) on the stack and both Blast templates in hand — the
 * enumeration-completeness board. The effect-conditional Blast must be offered here (its counter mode
 * targets any spell) and the target-restricted one must not (nothing is red).
 */
internal fun boardWithWhiteSpellOnStack(): GameState =
    modalBoard(
        aliceHand = listOf("Fixture Restricted Blast", "Fixture Conditional Blast"),
        aliceBattlefield = listOf("Fixture Island"),
        stackedSpells = listOf(fixturePrayer),
    )

/**
 * A board with a **red permanent** ([fixtureBear], a `{R}` creature) but no spell on the stack: the
 * target-restricted Blast's destroy mode is choosable and its counter mode is not, so exactly one of
 * its two printed modes is offered.
 */
internal fun redPermanentOnlyBoard(): GameState =
    modalBoard(
        aliceHand = listOf("Fixture Restricted Blast"),
        aliceBattlefield = listOf("Fixture Island", "Fixture Bear"),
    )

/**
 * Casts [card] from [start] choosing its [printedMode], taking the first enumerated target and the first
 * payment plan, then passes priority until that spell has left the stack — returning the state right
 * after its verdict (CR 701.5a countered / CR 608.2b fizzled / CR 608.2m resolved).
 *
 * Stopping at the verdict rather than running the stack out is deliberate: what the specs assert is what
 * *this* spell did, and letting the spell below it resolve too would fold a second card's effect into
 * the same event log.
 */
internal fun castModalSpell(
    engine: GameEngine,
    start: GameState,
    card: String,
    printedMode: Int,
): GameState {
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
    var result = engine.advance(start, castDecision(window, card))

    val modeRequest = result.pending<DecisionRequest.ChooseModes>()
    val optionIndex = modeRequest.options.indexOfFirst { it.modeIndex == printedMode }
    check(optionIndex >= 0) { "mode $printedMode of $card is not offered; options were ${modeRequest.options}" }
    result = engine.advance(result.pausedState, Decision.MultiSelect(modeRequest.id, listOf(optionIndex)))

    // CR 601.2c: surfaced only when the chosen mode targets — a targetless mode skips straight to payment.
    val next = (result as AdvanceResult.NeedsDecision).request
    if (next is DecisionRequest.ChooseTargets) {
        result = engine.advance(result.pausedState, Decision.SingleSelect(next.id, 0))
    }

    val payment = result.pending<DecisionRequest.ChoosePaymentPlan>()
    result = engine.advance(result.pausedState, planDecision(payment))

    return passUntilVerdict(engine, result, CardRef(card))
}

/**
 * The target options a modal cast offers once [printedMode] is chosen — the enumeration this packet's
 * ordering claim is about.
 */
internal fun targetsAfterChoosingMode(
    engine: GameEngine,
    start: GameState,
    card: String,
    printedMode: Int,
): DecisionRequest.ChooseTargets {
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
    val afterCast = engine.advance(start, castDecision(window, card))
    val modeRequest = afterCast.pending<DecisionRequest.ChooseModes>()
    val optionIndex = modeRequest.options.indexOfFirst { it.modeIndex == printedMode }
    check(optionIndex >= 0) { "mode $printedMode of $card is not offered; options were ${modeRequest.options}" }
    return engine
        .advance(afterCast.pausedState, Decision.MultiSelect(modeRequest.id, listOf(optionIndex)))
        .pending<DecisionRequest.ChooseTargets>()
}

/** How many priority passes a two-player stack resolution can need before something is wrong. */
private const val PASS_BUDGET = 12

/** Passes priority until [card] has a verdict event, then returns that state; loud if it never does. */
private fun passUntilVerdict(
    engine: GameEngine,
    from: AdvanceResult,
    card: CardRef,
): GameState {
    var result = from
    repeat(PASS_BUDGET) {
        val state = result.pausedState
        if (hasVerdict(state, card)) return state
        val window = (result as AdvanceResult.NeedsDecision).request
        check(window is DecisionRequest.ChooseAction) {
            "only priority windows are expected while waiting for $card to resolve, got $window"
        }
        result = engine.advance(state, passDecision(window))
    }
    val state = result.pausedState
    check(hasVerdict(state, card)) { "$card never left the stack within $PASS_BUDGET passes" }
    return state
}

/**
 * The stack-residence id (CR 400.7) of the one spell on [state]'s stack; loud if the board holds another
 * shape, because a test naming "the spell on the stack" has stopped meaning anything if there are two.
 */
internal fun soleStackedSpellId(state: GameState): ObjectId =
    state.sharedZones.stack
        .filterIsInstance<StackEntry.Spell>()
        .single()
        .obj.id

/** Whether [state]'s log records [card] leaving the stack — resolved, countered, or fizzled. */
private fun hasVerdict(
    state: GameState,
    card: CardRef,
): Boolean =
    state.events.any {
        (it is GameEvent.SpellResolved && it.card == card) ||
            (it is GameEvent.SpellCountered && it.card == card) ||
            (it is GameEvent.SpellFizzled && it.card == card)
    }
