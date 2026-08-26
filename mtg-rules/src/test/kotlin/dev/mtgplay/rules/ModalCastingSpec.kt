package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.chosenSpellModes
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * `FW-MODAL` (docs/design/countering-spells.md §8): modal spells (CR 700.2) and the CR 601.2b mode
 * decision that had been a documented no-op since P2.1.
 *
 * `mtg-rules` names no card (ADR-003), so everything runs on the synthetic modal spells of
 * `ModalFixtures.kt`. Two claims the file turns on:
 *
 * 1. **Modes are chosen before targets, and the ordering is mechanical.** CR 601.2b precedes CR 601.2c,
 *    and for these cards it must: their modes target different *kinds* of object, so there is no target
 *    enumeration to run until the mode is settled.
 * 2. **A target-restricted mode and an effect-conditional mode enumerate differently.**
 *    [fixtureRestrictedBlast] and [fixtureConditionalBlast] are printed to be identical but for where
 *    the colour test lives, so every difference measured below is caused by that and nothing else.
 *    Getting this backwards is the silent ADR-005 enumeration defect §1.2 warns about.
 */
class ModalCastingSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        // ---- CR 601.2b before CR 601.2c: the ordering -----------------------------------------------

        "CR 601.2b/601.2c: a modal cast surfaces its mode decision before its target decision" {
            val start = boardWithRedSpellOnStack("Fixture Sabotage", artifactOnBattlefield = true)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)

            // The very next request after choosing to cast is the *mode* decision, not the targets.
            val afterCast = engine.advance(start, castDecision(window, "Fixture Sabotage"))
            val modeRequest = afterCast.pending<DecisionRequest.ChooseModes>()
            modeRequest.card shouldBe CardRef("Fixture Sabotage")

            // Only after the mode is answered does a target decision appear.
            val afterMode = engine.advance(afterCast.pausedState, Decision.MultiSelect(modeRequest.id, listOf(0)))
            afterMode.pending<DecisionRequest.ChooseTargets>()
        }

        "CR 601.2c: the targets a modal cast offers depend on the mode just chosen" {
            // The whole point of the ordering. Mode 0 counters an artifact *spell*; mode 1 bounces an
            // artifact *permanent*. Same card, same board, two disjoint option lists — which is why
            // enumerating targets first would have nothing to enumerate against.
            val start = boardWithArtifactSpellAndPermanent()

            val counterTargets = targetsAfterChoosingMode(engine, start, "Fixture Sabotage", printedMode = 0)
            counterTargets.options.forEach { it.shouldBeInstanceOf<dev.mtgplay.core.state.Target.SpellOnStack>() }

            val bounceTargets = targetsAfterChoosingMode(engine, start, "Fixture Sabotage", printedMode = 1)
            bounceTargets.options.forEach { it.shouldBeInstanceOf<dev.mtgplay.core.state.Target.Permanent>() }
        }

        "CR 700.2c: the chosen mode is fixed on the cast record and announced before the targets are" {
            val start = boardWithRedSpellOnStack("Fixture Restricted Blast", artifactOnBattlefield = false)
            val resolved = castModalSpell(engine, start, "Fixture Restricted Blast", printedMode = 0)

            // The event log carries the CR 601.2b-before-CR 601.2c order, so the ordering is observable
            // rather than only asserted on the request sequence.
            val modesAt = resolved.events.indexOfFirst { it is GameEvent.ModesChosen }
            val targetsAt = resolved.events.indexOfFirst { it is GameEvent.TargetsChosen }
            (modesAt >= 0) shouldBe true
            (modesAt < targetsAt) shouldBe true

            val announced = resolved.events.filterIsInstance<GameEvent.ModesChosen>().single()
            announced.modes shouldContainExactly listOf(0)
            announced.modeTexts shouldContainExactly listOf("Counter target red spell.")
        }

        "CR 601.2b: a non-modal spell announces no modes and surfaces no mode decision" {
            val start = boardWithRedSpellOnStack("Fixture Counter", artifactOnBattlefield = false)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)

            // A plain counter goes straight to its targets — the stage stays invisible for every card
            // that has no modes, which is what keeps the framework additive.
            engine.advance(start, castDecision(window, "Fixture Counter")).pending<DecisionRequest.ChooseTargets>()
        }

        // ---- The two Blast templates, measured -------------------------------------------------------

        "ADR-005/CR 115.1: a target-restricted Blast is not enumerated with no object of its colour" {
            // Nothing red anywhere: no red spell on the stack, and the only permanent is a blue land.
            // *Both* modes therefore have no legal target, so the cast is absent entirely (CR 601.2c).
            val state = colourlessBoard(listOf("Fixture Restricted Blast", "Fixture Conditional Blast"))

            enumeratedCasts(pausedRequestOf(state)) shouldContainExactly listOf("Fixture Conditional Blast")
        }

        "ADR-005/CR 608.2c: an effect-conditional Blast IS enumerated against a white spell" {
            // The enumeration-completeness case, and the packet's central claim. A white spell is on the
            // stack; the conditional Blast's counter mode targets *any* spell, so casting it is legal and
            // must be offered — it simply will not do anything. The restricted Blast, whose targeting
            // line names red, is correctly absent.
            val state = boardWithWhiteSpellOnStack()

            enumeratedCasts(pausedRequestOf(state)) shouldContainExactly listOf("Fixture Conditional Blast")

            // Asserting the *card* is offered is not enough, and the difference matters: this board also
            // holds a permanent, so the destroy mode alone would keep the card enumerated even if the
            // counter mode had been wrongly given a colour restriction. The claim under test is
            // specifically that the **counter mode** is choosable against a white spell, so pin the mode
            // and the target it offers.
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val afterCast = engine.advance(state, castDecision(window, "Fixture Conditional Blast"))
            afterCast.pending<DecisionRequest.ChooseModes>().options.map { it.modeIndex } shouldContainExactly
                listOf(0, 1)

            val whiteSpellTargets =
                targetsAfterChoosingMode(engine, state, "Fixture Conditional Blast", printedMode = 0)
            whiteSpellTargets.options shouldContainExactly
                listOf(
                    dev.mtgplay.core.state.Target
                        .SpellOnStack(soleStackedSpellId(state)),
                )
        }

        "CR 608.2c: the effect-conditional Blast resolves against a white spell and does nothing to it" {
            // Cast it, target the white spell, let it resolve. The target survives — not because the
            // spell fizzled (it did not: the target was legal throughout) but because the condition in
            // its effect was false. The log says so: SpellResolved, never SpellFizzled.
            val start = boardWithWhiteSpellOnStack()
            val after = castModalSpell(engine, start, "Fixture Conditional Blast", printedMode = 0)

            after.events.filterIsInstance<GameEvent.SpellCountered>().shouldBeEmpty()
            after.events.filterIsInstance<GameEvent.SpellFizzled>().shouldBeEmpty()
            after.events
                .filterIsInstance<GameEvent.SpellResolved>()
                .map { it.card } shouldContainExactly listOf(CardRef("Fixture Conditional Blast"))
            // And the white spell is still sitting on the stack, untouched.
            after.sharedZones.stack.shouldNotBeNull()
            spellNamesOnStack(after) shouldContainExactly listOf("Fixture Prayer")
        }

        "CR 701.5a: the same effect-conditional Blast DOES counter a red spell — the condition, not the target" {
            // The controlled comparison: identical card, identical mode, identical cast sequence, and the
            // only thing changed is the colour of the spell it points at.
            val start = boardWithRedSpellOnStack("Fixture Conditional Blast", artifactOnBattlefield = false)
            val after = castModalSpell(engine, start, "Fixture Conditional Blast", printedMode = 0)

            after.events
                .filterIsInstance<GameEvent.SpellCountered>()
                .shouldNotBeNull()
                .size shouldBe 1
            spellNamesOnStack(after).shouldBeEmpty()
        }

        "CR 601.2b: only choosable modes are offered, so one dead mode does not block the other" {
            // A red permanent is on the battlefield but no red spell is on the stack. The restricted
            // Blast's counter mode has no legal target; its destroy mode does. Exactly one option — and
            // it is the *destroy* mode, named by its printed index 1, not renumbered to 0.
            val state = redPermanentOnlyBoard()
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val afterCast = engine.advance(state, castDecision(window, "Fixture Restricted Blast"))

            val request = afterCast.pending<DecisionRequest.ChooseModes>()
            request.options.map { it.modeIndex } shouldContainExactly listOf(1)
            request.options.map { it.text } shouldContainExactly listOf("Destroy target red permanent.")
        }

        "CR 700.2: an option's printed index survives the filtering, so a log names the same mode always" {
            // The property the previous test's `listOf(1)` depends on, stated on its own: the option list
            // is filtered, never renumbered. Answering option 0 here records printed mode 1.
            val state = redPermanentOnlyBoard()
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val afterCast = engine.advance(state, castDecision(window, "Fixture Restricted Blast"))
            val request = afterCast.pending<DecisionRequest.ChooseModes>()

            val afterMode = engine.advance(afterCast.pausedState, Decision.MultiSelect(request.id, listOf(0)))
            afterMode.pausedState.pendingCast
                .shouldNotBeNull()
                .chosenModes shouldContainExactly listOf(1)
        }

        // ---- The gathering record --------------------------------------------------------------------

        "CR 601.2b/601.2c: a mode that targets nothing settles the cast's targets rather than asking" {
            // The one branch only a targetless mode reaches: choosing it must settle `chosenTargets`
            // empty, because a `ChooseTargets` request with no options is not representable (ADR-005).
            val start = boardWithRedSpellOnStack("Fixture Charm", artifactOnBattlefield = false)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
            val afterCast = engine.advance(start, castDecision(window, "Fixture Charm"))
            val request = afterCast.pending<DecisionRequest.ChooseModes>()

            val nothingHappens = request.options.single { it.modeIndex == 1 }
            val afterMode =
                engine.advance(
                    afterCast.pausedState,
                    Decision.MultiSelect(request.id, listOf(request.options.indexOf(nothingHappens))),
                )
            // Straight past the targets stage to the payment plan (CR 601.2g).
            afterMode.pending<DecisionRequest.ChoosePaymentPlan>()
            afterMode.pausedState.pendingCast
                .shouldNotBeNull()
                .chosenTargets
                .shouldNotBeNull()
                .shouldBeEmpty()
        }

        "ADR-005: a modal card cannot be asked what it targets without a mode, and says so loudly" {
            // The safety property of `ModalSpell`, from the engine's side: any future call site that
            // reaches for `definition.targetSpec` on a modal card fails rather than silently reading it
            // as an untargeted spell.
            shouldThrowAny { fixtureRestrictedBlast.targetSpec }
            shouldThrowAny { fixtureRestrictedBlast.resolution }
        }

        "CR 700.2: a cast record naming a mode the card does not print fails loudly" {
            // An arity or index the gathering could never produce is an engine defect, not a rules case.
            // The fixture prints "Choose one —", so a missing mode, a second mode, and an index past
            // the printed list are all outside its own ModeChoice.
            shouldThrowAny { chosenSpellModes(fixtureRestrictedBlast, listOf(7)) }
            shouldThrowAny { chosenSpellModes(fixtureRestrictedBlast, listOf()) }
            shouldThrowAny { chosenSpellModes(fixtureRestrictedBlast, listOf(0, 1)) }
        }
    })

/** The names of the spells currently on [state]'s stack, bottom-up. */
private fun spellNamesOnStack(state: GameState): List<String> =
    state.sharedZones.stack
        .filterIsInstance<dev.mtgplay.core.state.StackEntry.Spell>()
        .map { it.obj.card.name }
