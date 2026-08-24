package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
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
import dev.mtgplay.rules.StackEntryView
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.viewFor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `FW-ABILTGT` end to end on a real card (docs/design/targeted-abilities.md §7): **Lotleth Giant**,
 * whose enters-the-battlefield trigger deals 1 damage to target opponent for each creature card in its
 * controller's graveyard.
 *
 * It is the only gauntlet card the framework blocks that composes to *this framework plus nothing
 * else*, so the game below exercises exactly the new machinery: the creature spell resolves, its
 * trigger fires, the engine pauses at CR 603.3d for a target choice the pool has never had before, the
 * chosen target rides on the stack entry, and the resolution reads it.
 *
 * The whole game runs under the invariant checker, which now includes `ABILITY_TARGET_SANITY`.
 */
class TargetedTriggerAcceptanceSpec :
    StringSpec({

        "CR 603.3d/608.2c: Lotleth Giant's trigger targets an opponent as it is put on the stack" {
            // alice casts Lotleth Giant with three creature cards and one Bolt in her graveyard.
            val game = ScriptedGame.startFrom(lotlethStartState())

            val window =
                game.pendingRequest as? DecisionRequest.ChooseAction
                    ?: error("expected a priority window, was ${game.pendingRequest}")
            val castIndex =
                window.options.indexOfFirst {
                    it is PriorityOption.CastSpell && it.card == CardRef("Lotleth Giant")
                }
            check(castIndex >= 0) { "no Lotleth Giant cast enumerated in ${window.options}" }
            val casting = game.apply(Decision.SingleSelect(window.id, castIndex))

            // The *spell* targets nothing (CR 302.1), so the cast goes straight to its payment plan.
            val payment =
                casting.pendingRequest as? DecisionRequest.ChoosePaymentPlan
                    ?: error("expected the CR 601.2g payment request, was ${casting.pendingRequest}")
            val paid = casting.apply(Decision.SingleSelect(payment.id, 0))

            // Both players pass; the creature spell resolves and enters the battlefield (CR 608.3),
            // firing its enters-the-battlefield trigger (CR 603.6a).
            val resolving = paid.pass().pass()

            // CR 603.3d: the engine pauses *as the ability is put on the stack* — not at a priority
            // window, and before the ability reaches the stack.
            val targets =
                resolving.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("expected the CR 603.3d targets request, was ${resolving.pendingRequest}")
            targets.seat shouldBe alice
            targets.card shouldBe CardRef("Lotleth Giant")
            // CR 115.1a/102.1: "target opponent" offers bob and not alice, even though alice decides.
            targets.options shouldContainExactly listOf(Target.Player(bob))
            resolving.state.sharedZones.stack
                .shouldBeEmpty()
            resolving.state.pendingTriggers shouldHaveSize 1
            resolving.state.pendingTriggerTargets.shouldNotBeNull()

            val onStack = resolving.apply(Decision.SingleSelect(targets.id, 0))
            // The choice rides on the stack entry (CR 601.2c), and the placement record is closed.
            val entry =
                onStack.state.sharedZones.stack
                    .single() as StackEntry.Ability
            entry.targets shouldContainExactly listOf(Target.Player(bob))
            onStack.state.pendingTriggerTargets shouldBe null

            // ADR-007: the target is public, so *both* seats see it on the stack entry view.
            listOf(alice, bob).forEach { seat ->
                val view = viewFor(onStack.state, seat).stack.single()
                (view as StackEntryView.TriggeredAbilityOnStack).targets shouldContainExactly
                    listOf(Target.Player(bob))
            }

            // Both pass; the ability resolves (CR 608.2b re-check passes — bob is still in the game).
            val resolved = onStack.pass().pass()
            // Three creature cards in alice's graveyard, one damage each (CR 120.3a).
            resolved.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - GRAVEYARD_CREATURES
            resolved.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE
            resolved.state.sharedZones.stack
                .shouldBeEmpty()
            // CR 113.7a: the ability ceased to exist — no card moved for it, and no fizzle happened.
            resolved.state.events
                .filterIsInstance<GameEvent.AbilityFizzled>()
                .shouldBeEmpty()
            resolved.state.events
                .filterIsInstance<GameEvent.TriggeredAbilityResolved>() shouldHaveSize 1
            // And the Giant itself is a 6/5 on the battlefield.
            resolved.state.sharedZones.battlefield
                .map { it.card }
                .filter { it == CardRef("Lotleth Giant") } shouldContainExactly listOf(CardRef("Lotleth Giant"))
        }
    })

/** How many creature cards sit in alice's graveyard in [lotlethStartState] — the trigger's damage. */
private const val GRAVEYARD_CREATURES: Int = 3

/**
 * A paused state for the demonstration: alice's precombat main on turn 8, holding Lotleth Giant with
 * seven untapped Swamps, and three creature cards plus one Lightning Bolt already in her graveyard.
 * Real [MvpCards] definitions throughout.
 */
private fun lotlethStartState(): GameState {
    val giant = GameObject(ObjectId(1), CardRef("Lotleth Giant"), alice)
    val aliceSwamps = cards("Swamp", 2L..8L, alice)
    val bobSwamp = GameObject(ObjectId(9), CardRef("Swamp"), bob)
    val aliceGraveyard =
        listOf(
            CardRef("Grizzly Bears"),
            CardRef("Hill Giant"),
            CardRef("Lightning Bolt"),
            CardRef("Wind Drake"),
        ).mapIndexed { index, card -> GameObject(ObjectId(10L + index), card, alice) }
            .toPersistentList()

    return GameState(
        players =
            persistentMapOf(
                alice to
                    playerWithZones(hand = persistentListOf(giant), graveyard = aliceGraveyard)
                        .copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                bob to playerWithZones(),
            ),
        turn = Turn(alice, 8, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = (aliceSwamps + bobSwamp).toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
