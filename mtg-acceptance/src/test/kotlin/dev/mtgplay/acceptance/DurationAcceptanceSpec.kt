package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.EffectDuration
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
import dev.mtgplay.rules.engine.layeredPower
import dev.mtgplay.rules.engine.layeredToughness
import dev.mtgplay.rules.viewFor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `FW-DURATION` end to end on a real card (docs/design/duration.md §9.6): **Timberwatch Elf**, whose
 * `{T}` ability gives target creature +X/+X until end of turn, X being the number of Elves on the
 * battlefield.
 *
 * Two games, and the second is the one that matters. The first plays the ability through the real
 * pipeline — enumeration, the CR 602.2b target choice, the stack, resolution, and the CR 514.2
 * wear-off — proving the store fills and empties on schedule. The second is the CR 514.2
 * **simultaneity** case: a creature that is alive only because of the pump takes damage that is
 * sublethal to its pumped toughness, and must survive the cleanup step, because the pump ending and
 * the damage being removed happen at the same moment. Sequencing those two would kill it, and no
 * test that predates this framework would notice.
 *
 * Both games run under the invariant checker, which now includes `TIMED_EFFECT_SANITY`.
 */
class DurationAcceptanceSpec :
    StringSpec({

        "CR 602.2b/611.2/514.2: Timberwatch Elf pumps a creature for the turn, and the pump expires at cleanup" {
            // alice controls two Elves (a Timberwatch Elf and an Elvish Mystic) and a Grizzly Bears.
            val game = ScriptedGame.startFrom(timberwatchStartState())

            val window = game.priorityWindow()
            val activationIndex =
                window.options.indexOfFirst {
                    it is PriorityOption.ActivateAbility && it.card == CardRef("Timberwatch Elf")
                }
            check(activationIndex >= 0) { "no Timberwatch Elf activation enumerated in ${window.options}" }
            val activating = game.apply(Decision.SingleSelect(window.id, activationIndex))

            // CR 602.2b: targets are chosen as part of activating, before any cost is paid.
            val targets =
                activating.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("expected the CR 602.2b targets request, was ${activating.pendingRequest}")
            targets.seat shouldBe alice
            // "Target creature" (CR 115.1b) offers every creature on the battlefield, either player's.
            targets.options shouldContainExactly
                listOf(
                    Target.Permanent(TIMBERWATCH_ID),
                    Target.Permanent(MYSTIC_ID),
                    Target.Permanent(BEARS_ID),
                )
            val chosen = targets.options.indexOf(Target.Permanent(BEARS_ID))
            val onStack = activating.apply(Decision.SingleSelect(targets.id, chosen))

            // The Elf is tapped for the cost (CR 602.2a) and nothing is in the store yet: the effect is
            // created by the *resolution*, not by the activation.
            onStack.state.sharedZones.battlefield
                .single { it.id == TIMBERWATCH_ID }
                .tapped shouldBe true
            onStack.state.timedEffects.shouldBeEmpty()

            // Both players pass; the ability resolves (CR 608.2) and creates the effect (CR 611.2).
            val resolved = onStack.pass().pass()
            val effect = resolved.state.timedEffects.single()
            effect.affected shouldBe BEARS_ID
            effect.duration shouldBe EffectDuration.UntilEndOfTurn
            effect.createdOnTurn shouldBe resolved.state.turn.number
            // Two Elves on the battlefield, so X = 2 (CR 608.2h) — the Bears is a 4/4.
            effect.modification.powerMod shouldBe TWO_ELVES
            layeredPower(resolved.state, BEARS_ID) shouldBe 2 + TWO_ELVES
            layeredToughness(resolved.state, BEARS_ID) shouldBe 2 + TWO_ELVES

            // ADR-007: a running continuous effect is public, so both seats see it unfiltered.
            listOf(alice, bob).forEach { seat ->
                viewFor(resolved.state, seat).timedEffects shouldContainExactly listOf(effect)
            }

            // CR 514.2: the cleanup step ends it. By alice's next turn the store is empty and the Bears
            // is back to its printed 2/2, with no explicit recompute anywhere.
            val nextTurn = resolved.passUntil { it.turn.number > START_TURN }
            nextTurn.state.timedEffects.shouldBeEmpty()
            layeredPower(nextTurn.state, BEARS_ID) shouldBe 2
            layeredToughness(nextTurn.state, BEARS_ID) shouldBe 2
        }

        "CR 514.2: a creature alive only because of the pump survives cleanup, damage removal being simultaneous" {
            // Same board, plus a Lightning Bolt in alice's hand and a Mountain to cast it with.
            val game = ScriptedGame.startFrom(timberwatchStartState(withBolt = true))

            // Pump the Bears to a 4/4 first.
            val window = game.priorityWindow()
            val activationIndex =
                window.options.indexOfFirst {
                    it is PriorityOption.ActivateAbility && it.card == CardRef("Timberwatch Elf")
                }
            val targets =
                game.apply(Decision.SingleSelect(window.id, activationIndex)).pendingRequest
                    as DecisionRequest.ChooseTargets
            val pumped =
                game
                    .apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Permanent(BEARS_ID))))
                    .pass()
                    .pass()
            layeredToughness(pumped.state, BEARS_ID) shouldBe 2 + TWO_ELVES

            // Bolt it for 3: sublethal against the pumped toughness of 4, lethal against the printed 2.
            val boltWindow = pumped.priorityWindow()
            val boltIndex =
                boltWindow.options.indexOfFirst {
                    it is PriorityOption.CastSpell && it.card == CardRef("Lightning Bolt")
                }
            check(boltIndex >= 0) { "no Lightning Bolt cast enumerated in ${boltWindow.options}" }
            val casting = pumped.apply(Decision.SingleSelect(boltWindow.id, boltIndex))
            val boltTargets =
                casting.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("expected the CR 601.2c targets request, was ${casting.pendingRequest}")
            val aimed =
                casting.apply(
                    Decision.SingleSelect(boltTargets.id, boltTargets.options.indexOf(Target.Permanent(BEARS_ID))),
                )
            val payment =
                aimed.pendingRequest as? DecisionRequest.ChoosePaymentPlan
                    ?: error("expected the CR 601.2g payment request, was ${aimed.pendingRequest}")
            val burned = aimed.apply(Decision.SingleSelect(payment.id, 0)).pass().pass()

            // CR 704.5g: 3 marked damage against a layered toughness of 4 is sublethal — it lives.
            val damaged =
                burned.state.sharedZones.battlefield
                    .single { it.id == BEARS_ID }
            damaged.damageMarked shouldBe BOLT_DAMAGE
            layeredToughness(burned.state, BEARS_ID) shouldBe 2 + TWO_ELVES

            // The headline. Cleanup removes the marked damage and ends the pump *simultaneously*
            // (CR 514.2). Sequencing the wear-off first would leave a 2/2 carrying 3 damage and kill it.
            val nextTurn = burned.passUntil { it.turn.number > START_TURN }
            nextTurn.state.timedEffects.shouldBeEmpty()
            val survivor =
                nextTurn.state.sharedZones.battlefield
                    .single { it.id == BEARS_ID }
            survivor.card shouldBe CardRef("Grizzly Bears")
            survivor.damageMarked shouldBe 0
            layeredToughness(nextTurn.state, BEARS_ID) shouldBe 2
        }
    })

/** The turn both games start on. */
private const val START_TURN: Int = 8

/** Elves on the battlefield in [timberwatchStartState] — the snapshotted X (CR 608.2h). */
private const val TWO_ELVES: Int = 2

/** Lightning Bolt's damage (CR 120.3d) — sublethal to the pumped Bears, lethal to the printed one. */
private const val BOLT_DAMAGE: Int = 3

private val TIMBERWATCH_ID = ObjectId(1)
private val MYSTIC_ID = ObjectId(2)
private val BEARS_ID = ObjectId(3)

/** The open priority window, or a loud failure naming what the game is actually paused at. */
private fun ScriptedGame.priorityWindow(): DecisionRequest.ChooseAction =
    pendingRequest as? DecisionRequest.ChooseAction
        ?: error("expected a priority window, was $pendingRequest")

/**
 * A paused state for the demonstrations: alice's precombat main on turn [START_TURN], with an
 * untapped Timberwatch Elf, an Elvish Mystic, and a Grizzly Bears on the battlefield — none summoning
 * sick, so the CR 302.6 gate does not block the `{T}` ability — plus a Mountain, and (when
 * [withBolt]) a Lightning Bolt in hand. Real [MvpCards] definitions throughout.
 */
private fun timberwatchStartState(withBolt: Boolean = false): GameState {
    val battlefield =
        persistentListOf(
            GameObject(TIMBERWATCH_ID, CardRef("Timberwatch Elf"), alice, summoningSick = false),
            GameObject(MYSTIC_ID, CardRef("Elvish Mystic"), alice, summoningSick = false),
            GameObject(BEARS_ID, CardRef("Grizzly Bears"), alice, summoningSick = false),
            GameObject(ObjectId(4), CardRef("Mountain"), alice),
            GameObject(ObjectId(5), CardRef("Forest"), bob),
        )
    val hand =
        if (withBolt) {
            persistentListOf(GameObject(ObjectId(6), CardRef("Lightning Bolt"), alice))
        } else {
            persistentListOf()
        }

    return GameState(
        players =
            persistentMapOf(
                alice to
                    playerWithZones(hand = hand)
                        .copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                bob to playerWithZones(),
            ),
        turn = Turn(alice, START_TURN, TurnPhase.PRECOMBAT_MAIN, null),
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
}
