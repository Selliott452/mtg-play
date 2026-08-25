package dev.mtgplay.rules

import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.engine.player
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * `FW-OPTCOST` (kicker, CR 702.33) and `FW-ALTCOST` (a conditional, hand-revealing alternative cost,
 * CR 118.9) at the rules level with fixtures (`mtg-rules` names no real card).
 *
 * The three things these frameworks add that nothing else in the engine had: an **optional additional**
 * cost, **linked information** that survives the spell becoming a permanent (CR 702.33f), and a casting
 * permission gated on **game state** rather than on where the card sits.
 */
class OptionalCostSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        // ---- The kicker announcement (CR 601.2b, CR 702.33a) ----------------------------------------

        "CR 702.33a: a card with kicker surfaces a yes/no announcement before the payment plan" {
            val paused = beginCast("Fixture Whacker", List(2) { "Fixture Mountain" }, engine)
            val request = pausedRequestOf<DecisionRequest.ChooseYesNo>(paused)
            request.card shouldBe CardRef("Fixture Whacker")
            request.prompt shouldBe "Pay the kicker cost {R} for Fixture Whacker?"
        }

        "CR 601.2f: declining the kicker pays the printed cost; accepting adds the kicker's own symbols" {
            val paused = beginCast("Fixture Whacker", List(2) { "Fixture Mountain" }, engine)
            val request = pausedRequestOf<DecisionRequest.ChooseYesNo>(paused)

            val declined =
                engine.advance(
                    paused,
                    Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.DECLINE),
                )
            declined.pending<DecisionRequest.ChoosePaymentPlan>().cost shouldBe ManaCost.parse("{R}")

            // CR 118.7: a kicker is a whole cost concatenated on, not an amount added — {R} kicked is
            // {R}{R} and demands two red, where summing mana values would have produced a payable-by-
            // anything {2}.
            val accepted = engine.advance(paused, Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.ACCEPT))
            accepted.pending<DecisionRequest.ChoosePaymentPlan>().cost shouldBe ManaCost.parse("{R}{R}")
        }

        "ADR-005: the kicker announcement is not surfaced when the kicked cost is unaffordable" {
            // One Mountain pays {R} but not {R}{R}, so "yes" would dead-end. The announcement is skipped
            // and the cast settles unkicked — the same treatment a vacuous target choice already gets.
            val paused = beginCast("Fixture Whacker", listOf("Fixture Mountain"), engine)
            pausedRequestOf<DecisionRequest.ChoosePaymentPlan>(paused).cost shouldBe ManaCost.parse("{R}")
        }

        "ADR-005: an unaffordable kicker never makes the cast itself disappear" {
            // The cast is still enumerated — declining is always legal, because a kicker only ever makes
            // a cost larger.
            val state = optionalCostState("Fixture Whacker", listOf("Fixture Mountain"))
            enumeratedCasts(pausedRequestOf<DecisionRequest.ChooseAction>(state))
                .shouldContainExactly(listOf("Fixture Whacker"))
        }

        // ---- Linked information (CR 702.33f) -------------------------------------------------------

        "CR 702.33f: the answer is fixed on the cast record as the spell goes on the stack" {
            val kicked = castWhacker(kick = true, lands = List(2) { "Fixture Mountain" }, engine)
            (kicked.sharedZones.stack.single() as StackEntry.Spell).kicked shouldBe true

            val unkicked = castWhacker(kick = false, lands = List(2) { "Fixture Mountain" }, engine)
            (unkicked.sharedZones.stack.single() as StackEntry.Spell).kicked shouldBe false
        }

        "CR 702.33f: the answer crosses the zone change onto the permanent, which is a new object" {
            val resolved =
                resolveTopOfStack(
                    castWhacker(kick = true, List(2) { "Fixture Mountain" }, engine),
                ).pausedState
            val permanent = resolved.sharedZones.battlefield.single { it.card == CardRef("Fixture Whacker") }
            permanent.kickedWhenCast shouldBe true
        }

        "CR 702.33f: an unkicked permanent carries no marker" {
            val resolved =
                resolveTopOfStack(castWhacker(kick = false, List(2) { "Fixture Mountain" }, engine)).pausedState
            resolved.sharedZones.battlefield
                .single { it.card == CardRef("Fixture Whacker") }
                .kickedWhenCast shouldBe false
        }

        // ---- The intervening-if, both checks (CR 603.4) ---------------------------------------------

        "CR 603.4: a kicked permanent's intervening-if holds, so the ability goes on the stack" {
            // Resolving the creature spell grants a priority round, which is when CR 603.3b puts a fired
            // trigger on the stack - so the ability is observable there rather than in the queue.
            val resolved =
                resolveTopOfStack(
                    castWhacker(kick = true, List(2) { "Fixture Mountain" }, engine),
                ).pausedState
            resolved.sharedZones.stack.filterIsInstance<StackEntry.Ability>() shouldHaveSize 1
        }

        "CR 603.4: an unkicked permanent's ability does not trigger at all — nothing reaches the stack" {
            // The first of CR 603.4's two checks, and the one that is *only* observable in the action
            // space: putting the test inside the effect instead would queue the trigger, order it, and
            // open a priority round the rules do not permit. The final board would look identical.
            val resolved =
                resolveTopOfStack(castWhacker(kick = false, List(2) { "Fixture Mountain" }, engine)).pausedState
            resolved.pendingTriggers.shouldBeEmpty()
            resolved.sharedZones.stack
                .filterIsInstance<StackEntry.Ability>()
                .shouldBeEmpty()
        }

        "CR 603.4: the trigger's effect runs only for the kicked cast" {
            val kicked = driveTriggerToResolution(kick = true, engine)
            kicked.player(alice).life shouldBe STARTING_LIFE - FIXTURE_WHACKER_TRIGGER_DAMAGE

            val unkicked = driveTriggerToResolution(kick = false, engine)
            unkicked.player(alice).life shouldBe STARTING_LIFE
        }

        // ---- The conditional, hand-revealing alternative cost (CR 118.9, CR 701.16a) ----------------

        "CR 118.9: with no land in hand, both the printed cast and the free alternative are enumerated" {
            // Two genuinely different positions — one spends mana, the other spends information — so both
            // are options, exactly as Fireblast's two casts are.
            val state = optionalCostState("Fixture Grant", List(2) { "Fixture Forest" })
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val grants =
                window.options
                    .filterIsInstance<PriorityOption.CastSpell>()
                    .filter { it.card == CardRef("Fixture Grant") }
            grants shouldHaveSize 2
            grants.count { it.permission == null } shouldBe 1
            grants.count { it.permission is CastingPermission.AlternativeCost } shouldBe 1
        }

        "CR 118.9: a land card in hand falsifies the condition, and the alternative cast disappears" {
            // The first permission in this engine whose legality can flip without the card moving.
            val state =
                optionalCostState("Fixture Grant", List(2) { "Fixture Forest" }, extraHand = listOf("Fixture Mountain"))
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            window.options
                .filterIsInstance<PriorityOption.CastSpell>()
                .filter { it.card == CardRef("Fixture Grant") }
                .map { it.permission }
                .shouldContainExactly(listOf(null))
        }

        "CR 118.9: the condition still gates the alternative when the printed cost is unpayable" {
            // No mana at all: the printed cast is gone, the free one remains, so a seat holding no land
            // can still cast it off an empty board. That asymmetry is the card's whole point.
            val state = optionalCostState("Fixture Grant", lands = emptyList())
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val grants =
                window.options
                    .filterIsInstance<PriorityOption.CastSpell>()
                    .filter { it.card == CardRef("Fixture Grant") }
            grants shouldHaveSize 1
            grants.single().permission.shouldBeInstanceOf<CastingPermission.AlternativeCost>()
        }

        "CR 701.16a: paying the alternative cost reveals the caster's hand to every player" {
            val cast = castGrantForFree(engine, extraHand = listOf("Fixture Surge", "Fixture Bolt-X"))
            val reveal = cast.events.filterIsInstance<GameEvent.CardsRevealed>().single()
            reveal.player shouldBe alice
            // CR 601.2a: the spell left the hand for the stack several stages earlier, so what is
            // revealed is the hand as it stands while the cost is paid — Fixture Grant is not in it.
            reveal.cards shouldContainExactly listOf(CardRef("Fixture Surge"), CardRef("Fixture Bolt-X"))
        }

        "CR 701.16a: an empty hand is a legal thing to reveal — the cost can never fail" {
            val cast = castGrantForFree(engine, extraHand = emptyList())
            cast.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .single()
                .cards
                .shouldBeEmpty()
        }

        "CR 118.9: the free cast spends no mana, and the spell reaches the stack" {
            val cast = castGrantForFree(engine, extraHand = emptyList())
            cast.player(alice).manaPool.shouldBeEmpty()
            cast.sharedZones.stack
                .single()
                .shouldBeInstanceOf<StackEntry.Spell>()
                .obj.card shouldBe CardRef("Fixture Grant")
        }
    })

/** Casts "Fixture Whacker" with the kicker announcement answered [kick], paying the first plan. */
private fun castWhacker(
    kick: Boolean,
    lands: List<String>,
    engine: GameEngine,
): dev.mtgplay.core.state.GameState {
    val paused = beginCast("Fixture Whacker", lands, engine)
    val announcement = pausedRequestOf<DecisionRequest.ChooseYesNo>(paused)
    val index = if (kick) DecisionRequest.ChooseYesNo.ACCEPT else DecisionRequest.ChooseYesNo.DECLINE
    val answered = engine.advance(paused, Decision.SingleSelect(announcement.id, index)).pausedState
    val plan = pausedRequestOf<DecisionRequest.ChoosePaymentPlan>(answered)
    return engine.advance(answered, planDecision(plan)).pausedState
}

/**
 * Resolves a Whacker cast and then resolves whatever the entry left behind, so the *effect* of the
 * CR 603.4 trigger is observable rather than only its presence on the queue.
 */
private fun driveTriggerToResolution(
    kick: Boolean,
    engine: GameEngine,
): dev.mtgplay.core.state.GameState {
    val entered = resolveTopOfStack(castWhacker(kick, List(2) { "Fixture Mountain" }, engine)).pausedState
    // An unkicked Whacker put nothing on the stack (CR 603.4), so there is nothing to resolve and the
    // board after entry *is* the answer.
    if (entered.sharedZones.stack.none { it is StackEntry.Ability }) return entered
    return resolveTopOfStack(entered).pausedState
}

/** Casts "Fixture Grant" via its free, hand-revealing alternative cost. */
private fun castGrantForFree(
    engine: GameEngine,
    extraHand: List<String>,
): dev.mtgplay.core.state.GameState {
    val state = optionalCostState("Fixture Grant", lands = emptyList(), extraHand = extraHand)
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell &&
                it.card == CardRef("Fixture Grant") &&
                it.permission is CastingPermission.AlternativeCost
        }
    check(index >= 0) { "the free alternative cast was not enumerated: ${window.options}" }
    val gathering = engine.advance(state, Decision.SingleSelect(window.id, index)).pausedState
    val plan = pausedRequestOf<DecisionRequest.ChoosePaymentPlan>(gathering)
    return engine.advance(gathering, planDecision(plan)).pausedState
}
