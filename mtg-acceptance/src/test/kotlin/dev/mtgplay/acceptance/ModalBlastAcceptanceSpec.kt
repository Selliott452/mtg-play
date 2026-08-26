package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `FW-MODAL` end to end on the real cards (docs/design/countering-spells.md §8): the CR 601.2b mode
 * decision, its precedence over CR 601.2c targets, and — the packet's correctness core — the two Blast
 * templates behaving differently on the same board.
 *
 * Played through the whole engine under the invariant checker, so enumeration (ADR-005), the cast
 * pipeline, and resolution are exercised together. `mtg-rules`' `ModalCastingSpec` makes the same claims
 * on synthetic fixtures where the two templates can be held identical in every other respect; this file
 * makes them on the printed cards, where a wrong encoding of Pyroblast would show up and a fixture could
 * not catch it.
 */
class ModalBlastAcceptanceSpec :
    StringSpec({

        "CR 601.2b/CR 601.2c: Red Elemental Blast asks for its mode before its target" {
            // Alice holds priority with a blue spell (Counterspell) on the stack and a blue permanent
            // (Wind Drake) out, so both of Red Elemental Blast's modes are live.
            var game = ScriptedGame.startFrom(blastBoard())
            val blueSpellId = topSpellId(game)
            game = beginCast(game, "Red Elemental Blast")

            // The mode request comes first, and it names the card and both printed bullets.
            val modes =
                game.pendingRequest as? DecisionRequest.ChooseModes
                    ?: error("CR 601.2b: expected the mode request, was ${game.pendingRequest}")
            modes.card shouldBe CardRef("Red Elemental Blast")
            modes.options.map { it.modeIndex } shouldContainExactly listOf(0, 1)
            modes.options.map { it.text } shouldContainExactly
                listOf("Counter target blue spell.", "Destroy target blue permanent.")
            // CR 700.2a: "Choose one —", so the answer is a one-element subset and nothing else (`W9-B`).
            modes.minimumCount shouldBe 1
            modes.maximumCount shouldBe 1

            // Only after it is answered does a target request appear — and which targets depend on it.
            game = game.apply(Decision.MultiSelect(modes.id, listOf(0)))
            val targets =
                game.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("CR 601.2c: expected targets after the mode, was ${game.pendingRequest}")
            // Mode 0 counters a blue *spell*: the only option is the Counterspell on the stack.
            targets.options shouldHaveSize 1
            targets.options.single() shouldBe Target.SpellOnStack(blueSpellId)
        }

        "CR 601.2c: Red Elemental Blast's destroy mode offers a permanent, its counter mode a spell" {
            // Same board, same card, different mode — and the two option lists share nothing. This is
            // why the mode must be settled first: there is no single "what does this target?" to ask.
            val board = blastBoard()

            val counterMode = targetsForMode(board, "Red Elemental Blast", printedMode = 0)
            counterMode.options shouldContainExactly listOf(Target.SpellOnStack(ObjectId(STACKED_SPELL_ID)))

            val destroyMode = targetsForMode(board, "Red Elemental Blast", printedMode = 1)
            destroyMode.options shouldContainExactly listOf(Target.Permanent(ObjectId(BLUE_PERMANENT_ID)))
        }

        // ---- The two templates on one board ------------------------------------------------------

        "ADR-005/CR 115.1: Blue Elemental Blast is not offered at all with no red object present" {
            // The board holds a blue spell and blue permanents and nothing red. Blue Elemental Blast
            // counters target *red* spell and destroys target *red* permanent, so neither mode has a
            // legal target and the card is absent from the priority window entirely (CR 601.2c).
            val game = ScriptedGame.startFrom(hoserBoard())

            enumeratedCasts(game) shouldContainExactly listOf("Hydroblast", "Pyroblast")
        }

        "ADR-005/CR 608.2c: Pyroblast IS offered against a white spell — the enumeration-completeness card" {
            // "Counter target spell **if it's blue**" restricts the *effect*, not the target. A white
            // spell on the stack is a perfectly legal target, so casting Pyroblast is legal and must be
            // enumerated. Getting this backwards removes a legal play from the agent's option list
            // without any test noticing — the silent ADR-005 defect §1.2 warns about.
            val board = whiteSpellBoard()

            // Both effect-conditional Blasts are offered; Red Elemental Blast, whose targeting line
            // names blue, is correctly absent from the same window.
            enumeratedCasts(ScriptedGame.startFrom(board)) shouldContainExactly listOf("Hydroblast", "Pyroblast")

            // And it is specifically the *counter* mode that is available against the white spell —
            // not merely the destroy mode keeping the card in the window on a technicality.
            val targets = targetsForMode(board, "Pyroblast", printedMode = 0)
            targets.options shouldContainExactly listOf(Target.SpellOnStack(ObjectId(STACKED_SPELL_ID)))
        }

        "CR 608.2c: Pyroblast resolves against a white spell and counters nothing — resolved, not fizzled" {
            // The behavioural half. The white spell survives, and the log says why: Pyroblast
            // *resolved* (its condition was false) rather than *fizzled* (its target was gone). Those
            // are different rules with different verdicts, and only this template can tell them apart.
            var game = ScriptedGame.startFrom(whiteSpellBoard())
            game = castModal(game, "Pyroblast", printedMode = 0, targetIndex = 0)
            game = game.pass().pass()

            game.state.events
                .filterIsInstance<GameEvent.SpellCountered>()
                .shouldBeEmpty()
            game.state.events
                .filterIsInstance<GameEvent.SpellFizzled>()
                .shouldBeEmpty()
            game.state.events
                .filterIsInstance<GameEvent.SpellResolved>()
                .map { it.card } shouldContainExactly listOf(CardRef("Pyroblast"))
            // The white spell is untouched, still waiting on the stack.
            stackedSpellNames(game) shouldContainExactly listOf("Union of the Third Path")
        }

        "CR 701.5a: the same Pyroblast counters a blue spell — the condition changed, nothing else did" {
            // The controlled comparison against the test above: identical card, identical mode,
            // identical script, and only the colour of the targeted spell differs.
            var game = ScriptedGame.startFrom(blueSpellBoard())
            game = castModal(game, "Pyroblast", printedMode = 0, targetIndex = 0)
            game = game.pass().pass()

            val countered =
                game.state.events
                    .filterIsInstance<GameEvent.SpellCountered>()
                    .single()
            countered.card shouldBe CardRef("Counterspell")
            stackedSpellNames(game).shouldBeEmpty()
        }

        "CR 701.7a: Red Elemental Blast's destroy mode kills a blue permanent, and the mode is on the record" {
            var game = ScriptedGame.startFrom(blastBoard())
            game = castModal(game, "Red Elemental Blast", printedMode = 1, targetIndex = 0)

            // CR 700.2c: the mode is fixed on the cast record before the targets are chosen, and the
            // event log carries that order.
            val announced =
                game.state.events
                    .filterIsInstance<GameEvent.ModesChosen>()
                    .single()
            announced.modes shouldContainExactly listOf(1)
            announced.modeTexts shouldContainExactly listOf("Destroy target blue permanent.")
            val modesAt = game.state.events.indexOfFirst { it is GameEvent.ModesChosen }
            val targetsAt = game.state.events.indexOfFirst { it is GameEvent.TargetsChosen }
            (modesAt < targetsAt) shouldBe true

            game = game.pass().pass()
            game.state.events
                .filterIsInstance<GameEvent.PermanentDestroyed>()
                .map { it.card } shouldContainExactly listOf(CardRef("Wind Drake"))
        }

        "CR 400.7: Steel Sabotage's bounce mode returns an artifact to its owner's hand" {
            // The mode that needed a battlefield-to-hand primitive the design note expected to already
            // exist. The artifact leaves the battlefield for its *owner's* hand as a new object.
            var game = ScriptedGame.startFrom(sabotageBoard())
            game = castModal(game, "Steel Sabotage", printedMode = 1, targetIndex = 0)
            game = game.pass().pass()

            game.state.events
                .filterIsInstance<GameEvent.CardReturnedToHand>()
                .map { it.card } shouldContainExactly listOf(CardRef("Ichor Wellspring"))
            game.state.sharedZones.battlefield
                .map { it.card.name }
                .contains("Ichor Wellspring") shouldBe false
            game.state.players
                .getValue(bob)
                .hand
                .map { it.card } shouldContainExactly listOf(CardRef("Ichor Wellspring"))
        }
    })

/** The stack-residence id (CR 400.7) every board below gives its one pre-placed spell. */
private const val STACKED_SPELL_ID = 50L

/** The battlefield id every board below gives bob's blue permanent (Wind Drake). */
private const val BLUE_PERMANENT_ID = 30L

/** The cards whose casts the pending priority window enumerates, in option order. */
private fun enumeratedCasts(game: ScriptedGame): List<String> {
    val window =
        game.pendingRequest as? DecisionRequest.ChooseAction
            ?: error("expected a priority window, was ${game.pendingRequest}")
    return window.options.filterIsInstance<PriorityOption.CastSpell>().map { it.card.name }
}

/** Begins casting the named card from the pending priority window. */
private fun beginCast(
    game: ScriptedGame,
    card: String,
): ScriptedGame {
    val window =
        game.pendingRequest as? DecisionRequest.ChooseAction
            ?: error("expected a priority window, was ${game.pendingRequest}")
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(card) }
    check(index >= 0) { "no cast of $card enumerated in ${window.options}" }
    return game.apply(Decision.SingleSelect(window.id, index))
}

/** Answers a pending mode request by the mode's **printed** index (CR 601.2b). */
private fun chooseMode(
    game: ScriptedGame,
    printedMode: Int,
): ScriptedGame {
    val modes =
        game.pendingRequest as? DecisionRequest.ChooseModes
            ?: error("CR 601.2b: expected the mode request, was ${game.pendingRequest}")
    val index = modes.options.indexOfFirst { it.modeIndex == printedMode }
    check(index >= 0) { "mode $printedMode is not offered; options were ${modes.options}" }
    // CR 601.2b: a mode choice is a subset since `W9-B`; "choose one" answers with a one-element one.
    return game.apply(Decision.MultiSelect(modes.id, listOf(index)))
}

/**
 * The target options a modal cast offers once [printedMode] is chosen (CR 601.2c), from a **fresh** game
 * on [board].
 *
 * It takes the board rather than a [ScriptedGame] deliberately: `ScriptedGame` advances in place, so two
 * calls sharing one game would have the second read the first's leftover request. Asking each branch of
 * the mode comparison to start from the same board is also what makes it a comparison — the boards are
 * identical by construction, and only the mode differs.
 */
private fun targetsForMode(
    board: GameState,
    card: String,
    printedMode: Int,
): DecisionRequest.ChooseTargets {
    val chosen = chooseMode(beginCast(ScriptedGame.startFrom(board), card), printedMode)
    return chosen.pendingRequest as? DecisionRequest.ChooseTargets
        ?: error("CR 601.2c: expected targets after the mode, was ${chosen.pendingRequest}")
}

/** Casts the modal [card] choosing [printedMode] and the target at [targetIndex], paying the first plan. */
private fun castModal(
    game: ScriptedGame,
    card: String,
    printedMode: Int,
    targetIndex: Int,
): ScriptedGame {
    val moded = chooseMode(beginCast(game, card), printedMode)
    val targets =
        moded.pendingRequest as? DecisionRequest.ChooseTargets
            ?: error("CR 601.2c: expected targets, was ${moded.pendingRequest}")
    val targeted = moded.apply(Decision.SingleSelect(targets.id, targetIndex))
    val payment =
        targeted.pendingRequest as? DecisionRequest.ChoosePaymentPlan
            ?: error("CR 601.2g: expected the payment request, was ${targeted.pendingRequest}")
    return targeted.apply(Decision.SingleSelect(payment.id, 0))
}

/** The stack-residence id (CR 400.7) of the topmost spell. */
private fun topSpellId(game: ScriptedGame): ObjectId =
    (
        game.state.sharedZones.stack
            .last() as StackEntry.Spell
    ).obj.id

/** The names of the spells on the stack, bottom-up. */
private fun stackedSpellNames(game: ScriptedGame): List<String> =
    game.state.sharedZones.stack
        .filterIsInstance<StackEntry.Spell>()
        .map { it.obj.card.name }

/**
 * Alice holds Red Elemental Blast over Mountains, with **bob's** blue Counterspell on the stack and
 * bob's blue Wind Drake on the battlefield — so both of the Blast's modes are choosable and each has
 * exactly one legal target.
 */
private fun blastBoard(): GameState =
    handcraftedMain(
        aliceHand = handOf(alice, 0L, "Red Elemental Blast"),
        bobHand = persistentListOf(),
        battlefield =
            cards("Mountain", 20L..22L, alice) +
                cards("Wind Drake", BLUE_PERMANENT_ID..BLUE_PERMANENT_ID, bob),
        stack = listOf(stackedSpell(STACKED_SPELL_ID, "Counterspell", bob)),
    )

/**
 * Alice holds all three Blasts over Islands **and Mountains**, with a **blue** spell on the stack and a
 * **blue** permanent out — and nothing red anywhere. Blue Elemental Blast (which hoses red) therefore has
 * no legal target for either mode.
 *
 * Both land types are present on purpose: Pyroblast costs `{R}`, and an Islands-only board would keep it
 * out of the window for want of **mana** rather than for want of a target. The test would still have
 * passed and would have been measuring the wrong thing.
 */
private fun hoserBoard(): GameState =
    handcraftedMain(
        aliceHand = handOf(alice, 0L, "Blue Elemental Blast", "Hydroblast", "Pyroblast"),
        bobHand = persistentListOf(),
        battlefield =
            cards("Island", 20L..21L, alice) +
                cards("Mountain", 22L..23L, alice) +
                cards("Wind Drake", BLUE_PERMANENT_ID..BLUE_PERMANENT_ID, bob),
        stack = listOf(stackedSpell(STACKED_SPELL_ID, "Counterspell", bob)),
    )

/**
 * The enumeration-completeness board: a **white** spell (Union of the Third Path, `{2}{W}`) on the stack
 * with Hydroblast, Pyroblast and Red Elemental Blast in hand. Only the two effect-conditional Blasts may
 * be cast — and the white spell is the point, since it is a colour *neither* Blast hoses, so any card
 * that is offered here is offered because its targeting line is genuinely unrestricted.
 */
private fun whiteSpellBoard(): GameState =
    handcraftedMain(
        aliceHand = handOf(alice, 0L, "Hydroblast", "Pyroblast", "Red Elemental Blast"),
        bobHand = persistentListOf(),
        battlefield = cards("Island", 20L..21L, alice) + cards("Mountain", 22L..23L, alice),
        stack = listOf(stackedSpell(STACKED_SPELL_ID, "Union of the Third Path", bob)),
    )

/** The same board with a **blue** spell on the stack, so Pyroblast's condition is true. */
private fun blueSpellBoard(): GameState =
    handcraftedMain(
        aliceHand = handOf(alice, 0L, "Pyroblast"),
        bobHand = persistentListOf(),
        battlefield = cards("Mountain", 22L..23L, alice),
        stack = listOf(stackedSpell(STACKED_SPELL_ID, "Counterspell", bob)),
    )

/** Alice holds Steel Sabotage over Islands; bob's Ichor Wellspring is the artifact to bounce. */
private fun sabotageBoard(): GameState =
    handcraftedMain(
        aliceHand = handOf(alice, 0L, "Steel Sabotage"),
        bobHand = persistentListOf(),
        battlefield = cards("Island", 20L..21L, alice) + cards("Ichor Wellspring", 30L..30L, bob),
        stack = persistentListOf(),
    )

/** Hand objects for [owner], ids allocated from [firstId]. */
private fun handOf(
    owner: PlayerId,
    firstId: Long,
    vararg names: String,
): PersistentList<GameObject> =
    names
        .mapIndexed { index, name -> GameObject(ObjectId(firstId + index), CardRef(name), owner) }
        .toPersistentList()

/** A spell already on the stack under [controller]'s control, at stack-residence id [id] (CR 400.7). */
private fun stackedSpell(
    id: Long,
    card: String,
    controller: PlayerId,
): StackEntry.Spell =
    StackEntry.Spell(
        obj = GameObject(ObjectId(id), CardRef(card), controller),
        controller = controller,
        targets = persistentListOf(),
        definition = MvpCards.definitions.getValue(CardRef(card)) as dev.mtgplay.core.definition.SpellDefinition,
    )

/** A turn-8 precombat main with alice holding priority and the real [MvpCards] registry. */
private fun handcraftedMain(
    aliceHand: PersistentList<GameObject>,
    bobHand: PersistentList<GameObject>,
    battlefield: List<GameObject>,
    stack: List<StackEntry>,
): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to playerWithZones(hand = aliceHand).copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                bob to playerWithZones(hand = bobHand),
            ),
        turn = Turn(alice, 8, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = stack.toPersistentList(),
                exile = persistentListOf(),
            ),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
