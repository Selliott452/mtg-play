package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.LIGHTNING_BOLT_DAMAGE
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
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
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `FW-COUNTER` end to end on real cards (docs/design/countering-spells.md §12): the counter war, the
 * CR 701.5/CR 608.2b verdict split, and Force Spike's CR 118.3a payment — all played through the whole
 * engine, so enumeration (ADR-005), the cast pipeline, and resolution are exercised together rather
 * than a resolution being handed a handcrafted stack.
 *
 * Every game runs under the invariant checker, which now includes the unless-pay pause's sanity in
 * `PENDING_RESOLUTION_SANITY`.
 *
 * `FizzleVerdictAcceptanceSpec` is the deliberate sibling of this file and is untouched: if countering
 * had been merged into the fizzle path, that spec's `SpellFizzled` counts would have changed meaning.
 */
class CounteringAcceptanceSpec :
    StringSpec({

        "CR 701.5a: a counter war — the countered counter's effect never runs and the Bolt resolves" {
            // alice Bolts bob; bob answers with Counterspell; alice counters bob's Counterspell.
            val opening = castTargetingPlayer(ScriptedGame.startFrom(counterWarState()), "Lightning Bolt", bob)
            val boltId = opening.second
            var game = opening.first.pass()

            // bob's Counterspell may target exactly the one spell on the stack — and CR 115.1 offers
            // it as a *spell* target, a kind of target this pool has never had before.
            val bobCounter = castCounter(game, "Counterspell", Target.SpellOnStack(boltId))
            game = bobCounter.first
            game = game.pass()

            // alice's Counterspell sees **both** spells on the stack, and never itself: the card is
            // still in her hand while she chooses, and CR 601.2c re-validates against the same set.
            val aliceCast = beginCast(game, "Counterspell")
            val targets = aliceCast.pendingRequest as? DecisionRequest.ChooseTargets ?: error("expected targets")
            targets.options shouldContainExactly
                listOf(Target.SpellOnStack(boltId), Target.SpellOnStack(bobCounter.second))
            game = payFor(aliceCast.apply(Decision.SingleSelect(targets.id, 1)))

            // Everyone passes: alice's Counterspell resolves and counters bob's.
            game = game.pass().pass()
            val countered =
                game.state.events
                    .filterIsInstance<GameEvent.SpellCountered>()
                    .single()
            countered.objectId shouldBe bobCounter.second
            countered.controller shouldBe bob
            // bob's Counterspell is in **bob's** graveyard (CR 701.5a, owner's), never alice's.
            game.state.players
                .getValue(bob)
                .graveyard
                .map { it.card } shouldContainExactly listOf(CardRef("Counterspell"))
            // Its own effect never ran: the Bolt is still on the stack.
            game.state.sharedZones.stack shouldHaveSize 1

            // Everyone passes again: the Bolt resolves and bob takes 3.
            game = game.pass().pass()
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - LIGHTNING_BOLT_DAMAGE
            // The verdicts stayed separate throughout: one counter, no fizzle.
            game.state.events.filterIsInstance<GameEvent.SpellCountered>() shouldHaveSize 1
            game.state.events
                .filterIsInstance<GameEvent.SpellFizzled>()
                .shouldBeEmpty()
        }

        "CR 118.3a: Force Spike's target's controller pays, saving the spell; the Spike resolves regardless" {
            val opening = castTargetingPlayer(ScriptedGame.startFrom(counterWarState()), "Lightning Bolt", bob)
            var game = opening.first.pass()
            game = castCounter(game, "Force Spike", Target.SpellOnStack(opening.second)).first
            game = game.pass().pass()

            // The pause belongs to **alice** — the Bolt's controller — not to bob, who cast the Spike.
            val payment =
                game.pendingRequest as? DecisionRequest.ChooseCounterPayment
                    ?: error("expected the CR 118.3a unless-pay request, was ${game.pendingRequest}")
            payment.seat shouldBe alice
            payment.card shouldBe CardRef("Lightning Bolt")
            payment.cost.render() shouldBe "{1}"
            payment.options.first() shouldBe DecisionRequest.ChooseCounterPayment.Option.Decline

            // Alice pays from an untapped Mountain: the Bolt survives and the Spike resolves doing nothing.
            game = game.apply(Decision.SingleSelect(payment.id, 1))
            game.state.events
                .filterIsInstance<GameEvent.SpellCountered>()
                .shouldBeEmpty()
            game.state.events
                .filterIsInstance<GameEvent.SpellResolved>()
                .map { it.card } shouldContainExactly
                listOf(CardRef("Force Spike"))
            game.state.sharedZones.stack
                .filterIsInstance<StackEntry.Spell>()
                .map { it.obj.card } shouldContainExactly listOf(CardRef("Lightning Bolt"))

            game = game.pass().pass()
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - LIGHTNING_BOLT_DAMAGE
        }

        "CR 118.3a: declining Force Spike's payment counters the spell, and no damage is ever dealt" {
            val opening = castTargetingPlayer(ScriptedGame.startFrom(counterWarState()), "Lightning Bolt", bob)
            var game = opening.first.pass()
            game = castCounter(game, "Force Spike", Target.SpellOnStack(opening.second)).first
            game = game.pass().pass()

            val payment =
                game.pendingRequest as? DecisionRequest.ChooseCounterPayment
                    ?: error("expected the CR 118.3a unless-pay request, was ${game.pendingRequest}")
            game = game.apply(Decision.SingleSelect(payment.id, 0))

            game.state.events
                .filterIsInstance<GameEvent.SpellCountered>()
                .map { it.card } shouldContainExactly
                listOf(CardRef("Lightning Bolt"))
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE
            game.state.sharedZones.stack
                .shouldBeEmpty()
        }

        "ADR-005: Spell Pierce is absent from the window with only a creature spell on the stack" {
            // "Counter target noncreature spell": with nothing but Grizzly Bears on the stack there is
            // no legal target, so the cast is excluded from enumeration rather than allowed to
            // dead-end mid-pipeline (CR 601.2c). Counterspell, unrestricted, is offered on the same board.
            var game = ScriptedGame.startFrom(pierceState())
            game = beginCast(game, "Grizzly Bears").let { payFor(it) }
            game = game.pass()

            val window =
                game.pendingRequest as? DecisionRequest.ChooseAction
                    ?: error("expected bob's priority window, was ${game.pendingRequest}")
            val casts = window.options.filterIsInstance<PriorityOption.CastSpell>().map { it.card.name }
            casts shouldContainExactly listOf("Counterspell")
        }
    })

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

/** Answers the pending payment request with its first plan (CR 601.2g). */
private fun payFor(game: ScriptedGame): ScriptedGame {
    val payment =
        game.pendingRequest as? DecisionRequest.ChoosePaymentPlan
            ?: error("expected the CR 601.2g payment request, was ${game.pendingRequest}")
    return game.apply(Decision.SingleSelect(payment.id, 0))
}

/** Casts [card] at the player [seat]; returns the game and the new spell's stack-residence id. */
private fun castTargetingPlayer(
    game: ScriptedGame,
    card: String,
    seat: dev.mtgplay.core.identity.PlayerId,
): Pair<ScriptedGame, ObjectId> {
    val casting = beginCast(game, card)
    val targets = casting.pendingRequest as? DecisionRequest.ChooseTargets ?: error("expected targets")
    val index = targets.options.indexOf(Target.Player(seat))
    check(index >= 0) { "no player target for $seat in ${targets.options}" }
    val paid = payFor(casting.apply(Decision.SingleSelect(targets.id, index)))
    return paid to topSpellId(paid)
}

/** Casts the counter [card] at [target]; returns the game and the counter's own stack-residence id. */
private fun castCounter(
    game: ScriptedGame,
    card: String,
    target: Target.SpellOnStack,
): Pair<ScriptedGame, ObjectId> {
    val casting = beginCast(game, card)
    val targets = casting.pendingRequest as? DecisionRequest.ChooseTargets ?: error("expected targets")
    val index = targets.options.indexOf(target)
    check(index >= 0) { "no $target in ${targets.options}" }
    val paid = payFor(casting.apply(Decision.SingleSelect(targets.id, index)))
    return paid to topSpellId(paid)
}

/** The stack-residence id (CR 400.7) of the topmost spell. */
private fun topSpellId(game: ScriptedGame): ObjectId =
    (
        game.state.sharedZones.stack
            .last() as StackEntry.Spell
    ).obj.id

/**
 * A precombat main phase deep enough for the war: alice holds Lightning Bolt, Counterspell, and Force
 * Spike over four Mountains and two Islands; bob holds Counterspell and Force Spike over three Islands.
 * Both seats therefore have spare untapped mana, which is what makes the CR 118.3a payment reachable.
 */
private fun counterWarState(): GameState {
    val aliceHand =
        listOf("Lightning Bolt", "Counterspell", "Force Spike")
            .mapIndexed { index, card -> GameObject(ObjectId(index.toLong()), CardRef(card), alice) }
            .toPersistentList()
    val bobHand =
        listOf("Counterspell", "Force Spike")
            .mapIndexed { index, card -> GameObject(ObjectId(10L + index), CardRef(card), bob) }
            .toPersistentList()
    val aliceLands = cards("Mountain", 20L..23L, alice) + cards("Island", 24L..25L, alice)
    val bobLands = cards("Island", 30L..32L, bob)
    return handcraftedMain(aliceHand, bobHand, aliceLands + bobLands)
}

/** alice holds Grizzly Bears over Forests; bob holds Counterspell and Spell Pierce over Islands. */
private fun pierceState(): GameState {
    val aliceHand = persistentListOf(GameObject(ObjectId(0), CardRef("Grizzly Bears"), alice))
    val bobHand =
        listOf("Counterspell", "Spell Pierce")
            .mapIndexed { index, card -> GameObject(ObjectId(10L + index), CardRef(card), bob) }
            .toPersistentList()
    val lands = cards("Forest", 20L..22L, alice) + cards("Island", 30L..32L, bob)
    return handcraftedMain(aliceHand, bobHand, lands)
}

/** A turn-8 precombat main with alice holding priority and the real [MvpCards] registry. */
private fun handcraftedMain(
    aliceHand: kotlinx.collections.immutable.PersistentList<GameObject>,
    bobHand: kotlinx.collections.immutable.PersistentList<GameObject>,
    battlefield: List<GameObject>,
): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to
                    playerWithZones(hand = aliceHand).copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                bob to playerWithZones(hand = bobHand),
            ),
        turn = Turn(alice, 8, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
