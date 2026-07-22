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
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * The CR 608.2b fizzle, reachable end-to-end from P3.2 (superseding the P2.x verdict).
 *
 * **Historical note.** The P2.1/P2.3 packets proved a fizzle was *unreachable* end-to-end: with a
 * players-only target pool, a spell's only target could become illegal only by the player leaving
 * the game — which in a two-player game *is* the game ending (CR 104.2a), so the resolution that
 * would fizzle never begins. The fizzle branch was pinned only at unit level. P3.2 makes creatures
 * targetable (CR 115.4) and mortal (CR 704.5g), so a targeted creature can die mid-stack while the
 * spell that targets it still waits below it — and the fizzle happens for real. That P2.x proof is
 * now superseded; this spec drives the genuine article.
 */
class FizzleVerdictAcceptanceSpec :
    StringSpec({

        "CR 608.2b: a Bolt fizzles when a response Bolt kills its only target before it resolves" {
            // alice Bolts bob's Grizzly Bears; bob responds by Bolting the same creature. LIFO makes
            // bob's Bolt resolve first and kill the 2/2 (CR 704.5g); alice's Bolt then finds its only
            // target gone and fizzles (CR 608.2b) — no damage, straight to alice's graveyard.
            val bearId = ObjectId(100)
            val game = ScriptedGame.startFrom(fizzleStartState(bearId))

            castBoltAtCreature(game, bearId) // alice's Bolt targets the Bears
            game.pass() // alice keeps priority after casting, then passes to bob (CR 117.3b/d)
            castBoltAtCreature(game, bearId) // bob responds, targeting the same Bears
            game.pass() // bob passes
            game.pass() // alice passes: all passed, bob's (top) Bolt resolves and kills the Bears
            // The Bears are dead now; the remaining priority round resolves alice's stranded Bolt.
            game.pass()
            game.pass()

            val events = game.state.events
            // The Bears died to bob's Bolt (CR 704.5g), and exactly one Bolt fizzled (alice's).
            events.filterIsInstance<GameEvent.CreatureDied>().map { it.card } shouldContainExactly
                listOf(CardRef("Grizzly Bears"))
            val fizzles = events.filterIsInstance<GameEvent.SpellFizzled>()
            fizzles shouldHaveSize 1
            fizzles.single().card shouldBe CardRef("Lightning Bolt")

            // No player lost life: both Bolts were aimed at the creature, and the fizzled one did
            // nothing at all (CR 608.2b — none of its instructions are performed).
            game.state.players.values
                .map { it.life } shouldContainExactly listOf(STARTING_LIFE, STARTING_LIFE)
            // The dead Bears and bob's own resolved Bolt sit in bob's graveyard (CR 608.2m); alice's
            // fizzled Bolt sits in hers, having done nothing.
            game.state.players
                .getValue(bob)
                .graveyard
                .map { it.card } shouldContainExactlyInAnyOrder
                listOf(CardRef("Grizzly Bears"), CardRef("Lightning Bolt"))
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly listOf(CardRef("Lightning Bolt"))
            game.state.sharedZones.stack shouldHaveSize 0
        }
    })

/**
 * A paused state for the fizzle duel: alice's precombat main, both seats at 20 life holding a
 * Lightning Bolt with one untapped Mountain, and bob's Grizzly Bears (id [bearId]) on the
 * battlefield — the shared target of both Bolts. Real [MvpCards] definitions throughout.
 */
private fun fizzleStartState(bearId: ObjectId): GameState {
    val aliceBolt = GameObject(ObjectId(1), CardRef("Lightning Bolt"), alice)
    val aliceMountain = GameObject(ObjectId(2), CardRef("Mountain"), alice)
    val bobBolt = GameObject(ObjectId(3), CardRef("Lightning Bolt"), bob)
    val bobMountain = GameObject(ObjectId(4), CardRef("Mountain"), bob)
    val bears = GameObject(bearId, CardRef("Grizzly Bears"), bob, summoningSick = false)

    val alicePlayer =
        playerWithZones(hand = persistentListOf(aliceBolt))
            .copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY)
    val bobPlayer = playerWithZones(hand = persistentListOf(bobBolt))

    return GameState(
        players = persistentMapOf(alice to alicePlayer, bob to bobPlayer),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(aliceMountain, bobMountain, bears),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 101,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}

// Casts the pending seat's Lightning Bolt at the battlefield creature [creatureId]: the cast
// option, the Target.Permanent choice (CR 601.2c), then the sole payment plan (CR 601.2g).
private fun castBoltAtCreature(
    game: ScriptedGame,
    creatureId: ObjectId,
) {
    val window =
        game.pendingRequest as? DecisionRequest.ChooseAction
            ?: error("casting requires a priority window, was ${game.pendingRequest}")
    val castIndex =
        window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef("Lightning Bolt") }
    check(castIndex >= 0) { "no Lightning Bolt cast enumerated in ${window.options}" }
    game.apply(Decision.SingleSelect(window.id, castIndex))

    val targets =
        game.pendingRequest as? DecisionRequest.ChooseTargets
            ?: error("expected the CR 601.2c targets request, was ${game.pendingRequest}")
    val targetIndex = targets.options.indexOf(Target.Permanent(creatureId))
    check(targetIndex >= 0) { "creature $creatureId is not among the legal targets ${targets.options}" }
    game.apply(Decision.SingleSelect(targets.id, targetIndex))

    val payment =
        game.pendingRequest as? DecisionRequest.ChoosePaymentPlan
            ?: error("expected the CR 601.2g payment request, was ${game.pendingRequest}")
    game.apply(Decision.SingleSelect(payment.id, 0))
}
