package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.card.Keyword
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
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.engine.layeredCharacteristics
import dev.mtgplay.rules.engine.layeredPower
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The keyword tail end to end on real cards, through the real pipeline, under the invariant checker.
 *
 * Two games, each chosen because a unit test on the seam could pass while the game is still wrong:
 *
 * 1. **Toxin Analysis** is cast for its real cost, resolves, grants deathtouch and lifelink until end
 *    of turn, and creates its Clue — and then the granted deathtouch actually kills a creature it
 *    could not otherwise scratch (CR 704.5h). The keyword travels from a card's resolution, through
 *    the CR 611.2 store, through CR 613 layer 6, into a damage event, into a state-based action; a
 *    break anywhere on that path is invisible to a seam test.
 * 2. **Goblin Tomb Raider** attacks the turn it arrives because an artifact is on the battlefield
 *    (CR 604.3, CR 702.10b) — the conditional static ability observed where it actually matters,
 *    which under ADR-005 is whether the attack option is *enumerated at all*.
 */
class KeywordTailAcceptanceSpec :
    StringSpec({

        "CR 702.2b/704.5h: a creature given deathtouch by Toxin Analysis destroys a creature it cannot outdamage" {
            val game = ScriptedGame.startFrom(toxinStartState())

            // Cast Toxin Analysis for {B}, targeting alice's own 1/1.
            val window = game.priorityWindow()
            val castIndex =
                window.options.indexOfFirst {
                    it is PriorityOption.CastSpell && it.card == CardRef("Toxin Analysis")
                }
            check(castIndex >= 0) { "no Toxin Analysis cast enumerated in ${window.options}" }
            val casting = game.apply(Decision.SingleSelect(window.id, castIndex))

            // CR 601.2c: "target creature" offers every creature on the battlefield, either player's.
            val targets =
                casting.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("expected the CR 601.2c targets request, was ${casting.pendingRequest}")
            val chosen = targets.options.indexOf(Target.Permanent(RUNT_ID))
            check(chosen >= 0) { "alice's own creature was not offered as a target: ${targets.options}" }

            val targeted = casting.apply(Decision.SingleSelect(targets.id, chosen))
            // CR 601.2h: the mana cost is paid after targets are chosen; the lone Swamp gives one plan.
            val payment =
                targeted.pendingRequest as? DecisionRequest.ChoosePaymentPlan
                    ?: error("expected the CR 601.2h payment request, was ${targeted.pendingRequest}")
            val resolved =
                targeted
                    .apply(Decision.SingleSelect(payment.id, 0))
                    .pass()
                    .pass()

            // CR 613.1f layer 6: both granted keywords are live on the creature.
            val keywords = layeredCharacteristics(resolved.state, RUNT_ID).keywords
            (Keyword.DEATHTOUCH in keywords) shouldBe true
            (Keyword.LIFELINK in keywords) shouldBe true
            // CR 701.50a: Investigate created a Clue token, which the brief did not mention at all.
            resolved.state.sharedZones.battlefield
                .count { it.card == CardRef("Clue") } shouldBe 1

            // Attack with the 1/1 into bob's 3/3 Hill Giant. It deals 1 damage, which is nowhere near
            // lethal to toughness 3 — and CR 704.5h destroys the Giant anyway.
            val attacking = resolved.toDeclareAttackers()
            val attackers =
                attacking.pendingRequest as? DecisionRequest.DeclareAttackers
                    ?: error("expected declare-attackers, was ${attacking.pendingRequest}")
            val runt = attackers.options.indexOfFirst { it.attacker == RUNT_ID }
            check(runt >= 0) { "the pumped creature was not offered as an attacker" }
            val declared = attacking.apply(Decision.MultiSelect(attackers.id, listOf(runt)))

            val blockers =
                declared.pass().pass().pendingRequest as? DecisionRequest.DeclareBlockers
                    ?: error("expected declare-blockers, was ${declared.pendingRequest}")
            val block = blockers.options.indexOfFirst { it.blocker == GIANT_ID }
            check(block >= 0) { "bob's Giant was not offered as a blocker" }
            val afterDamage = declared.apply(Decision.MultiSelect(blockers.id, listOf(block))).pass().pass()

            // CR 704.5h: the 3/3 is destroyed by 1 point of deathtouch damage.
            afterDamage.state.sharedZones.battlefield
                .filter { it.card == CardRef("Hill Giant") }
                .shouldBeEmpty()
            // CR 702.15: the same damage was lifelinked, so alice gained exactly 1.
            afterDamage.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + 1
        }

        "CR 604.3/702.10b: Goblin Tomb Raider attacks the turn it arrives, but only while you control an artifact" {
            // With an artifact land on the battlefield the conditional static grants haste, so the
            // summoning-sick Raider *is* enumerated as an attacker — and it is a 2/2, not a 1/2.
            val withArtifact = ScriptedGame.startFrom(raiderStartState(withArtifact = true))
            layeredPower(withArtifact.state, RAIDER_ID) shouldBe 2

            val attackers =
                withArtifact.toDeclareAttackers().pendingRequest as? DecisionRequest.DeclareAttackers
                    ?: error("expected declare-attackers with an artifact out")
            attackers.options.map { it.card } shouldContainExactly listOf(CardRef("Goblin Tomb Raider"))
        }

        "CR 604.3: without an artifact the same summoning-sick Raider is a 1/2 and is offered no attack at all" {
            // The control, and the reason the condition is not cosmetic: with no artifact there is no
            // haste, so CR 302.6 bars the attack and combat never engages — the declare-attackers
            // decision is not surfaced at all (ADR-005), rather than being surfaced and then refused.
            val bare = ScriptedGame.startFrom(raiderStartState(withArtifact = false))
            layeredPower(bare.state, RAIDER_ID) shouldBe 1
            (Keyword.HASTE in layeredCharacteristics(bare.state, RAIDER_ID).keywords) shouldBe false

            val atCombat = bare.toDeclareAttackers()
            (atCombat.pendingRequest is DecisionRequest.DeclareAttackers) shouldBe false
        }
    })

/**
 * Passes priority until the game reaches the declare-attackers step (CR 508.1). The predicate is
 * checked before each response, so the game stops *at* the step — whether that pause is the
 * declare-attackers decision or, with no eligible attacker, the plain priority window it degenerates
 * to (ADR-005). Telling those two apart is exactly what the conditional-haste tests assert.
 */
private fun ScriptedGame.toDeclareAttackers(): ScriptedGame = passUntil { it.turn.step == TurnStep.DECLARE_ATTACKERS }

/** The pending priority window; fails loudly if the game is paused on anything else. */
private fun ScriptedGame.priorityWindow(): DecisionRequest.ChooseAction =
    pendingRequest as? DecisionRequest.ChooseAction
        ?: error("expected a priority window, was $pendingRequest")

private val RUNT_ID = ObjectId(1)
private val GIANT_ID = ObjectId(2)
private val RAIDER_ID = ObjectId(1)
private const val START_TURN = 3

/**
 * alice holds Toxin Analysis and a Swamp to cast it with, controls a Standing Troops 1/1 body, and
 * faces bob's Hill Giant 3/3 — a creature the 1/1 cannot kill by damage under any circumstance, which
 * is what makes the destruction a CR 704.5h result rather than a CR 704.5g one.
 */
private fun toxinStartState(): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to
                    playerWithZones(
                        hand = persistentListOf(GameObject(ObjectId(3), CardRef("Toxin Analysis"), alice)),
                    ).copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                bob to playerWithZones(),
            ),
        turn = Turn(alice, START_TURN, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield =
                    persistentListOf(
                        GameObject(ObjectId(0), CardRef("Swamp"), alice),
                        GameObject(RUNT_ID, CardRef("Standing Troops"), alice, summoningSick = false),
                        GameObject(GIANT_ID, CardRef("Hill Giant"), bob, summoningSick = false),
                    ).toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )

/**
 * alice controls a freshly arrived (summoning-sick) Goblin Tomb Raider, and — when [withArtifact] —
 * a Great Furnace, which is an artifact land and therefore switches the condition on.
 */
private fun raiderStartState(withArtifact: Boolean): GameState {
    val battlefield =
        buildList {
            add(GameObject(ObjectId(0), CardRef("Mountain"), alice))
            add(GameObject(RAIDER_ID, CardRef("Goblin Tomb Raider"), alice, summoningSick = true))
            if (withArtifact) add(GameObject(ObjectId(2), CardRef("Great Furnace"), alice))
        }
    return GameState(
        players =
            persistentMapOf(
                alice to playerWithZones().copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
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
