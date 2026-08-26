package dev.mtgplay.rules

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.applyUntilYourNextTurn
import dev.mtgplay.rules.effect.goad
import dev.mtgplay.rules.engine.cleanupRemoveDamageAndEndEffects
import dev.mtgplay.rules.engine.effectiveKeywords
import dev.mtgplay.rules.engine.endUntilYourNextTurnEffects
import dev.mtgplay.rules.engine.pendingCombatDecision
import dev.mtgplay.rules.engine.validateDecision
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * **Goad** (CR 701.38a) as an attack requirement (CR 508.1d), and the **"until your next turn"**
 * duration both it and Throne of the Dead Three's hexproof grant run on (CR 611.2) — `W11`.
 * Fixture objects only; `mtg-rules` names no card (ADR-003).
 *
 * The two properties the framework turns on are the first and the last: a goaded creature that is able
 * to attack **must** be declared, so the declaration stops being a free subset; and the duration is
 * ended by the *untap step* of the goading player's next turn, not by any cleanup — a distinction the
 * CR 514.2 wear-off is deliberately unable to make.
 */
class GoadSpec :
    StringSpec({

        "CR 701.38a: goading records who goaded and when, and narrates it" {
            val goaded = goad(combatState(bob), ObjectId(0), alice)
            val creature = goaded.sharedZones.battlefield.single { it.id == ObjectId(0) }
            creature.goadedBy shouldBe alice
            creature.goadedOnTurn shouldBe goaded.turn.number
            goaded.events.last() shouldBe GameEvent.CreatureGoaded(ObjectId(0), CardRef("Ent"), alice)
        }

        "CR 508.1d: a goaded creature that can attack is published as a required attacker" {
            val request = attackRequest(goad(combatState(bob), ObjectId(0), alice))
            request.options.map { it.attacker } shouldContainExactly listOf(ObjectId(0), ObjectId(1))
            request.required.map { it.attacker } shouldContainExactly listOf(ObjectId(0))
            request.required.single().goadedBy shouldBe alice
            // The index a seat must include, resolved once by the request rather than by each seat.
            request.requiredIndices shouldContainExactly listOf(0)
        }

        "CR 701.38a: 'if able' is the eligibility list — a tapped goaded creature requires nothing" {
            val request = attackRequest(goad(combatState(bob, firstIsTapped = true), ObjectId(0), alice))
            // Not on offer at all (CR 508.1a), so not required either — one predicate, not two.
            request.options.map { it.attacker } shouldContainExactly listOf(ObjectId(1))
            request.required.shouldBeEmpty()
        }

        "CR 508.1d: a declaration that leaves a goaded creature at home is refused" {
            val request = attackRequest(goad(combatState(bob), ObjectId(0), alice))
            // Attacking with the *other* creature only — a legal subset before goad, illegal now.
            val omitted = Decision.MultiSelect(request.id, listOf(1))
            shouldThrow<IllegalArgumentException> { validateDecision(request, omitted) }
                .message
                .orEmpty() shouldContain "attacks each combat if able"
            // Attacking with nothing is refused for the same reason.
            shouldThrow<IllegalArgumentException> {
                validateDecision(request, Decision.MultiSelect(request.id, emptyList()))
            }
            // Including it is legal, alone or with the other.
            validateDecision(request, Decision.MultiSelect(request.id, listOf(0)))
            validateDecision(request, Decision.MultiSelect(request.id, listOf(0, 1)))
        }

        "CR 508.1: with nothing goaded the declaration is still a free subset, empty included" {
            val request = attackRequest(combatState(bob))
            request.required.shouldBeEmpty()
            validateDecision(request, Decision.MultiSelect(request.id, emptyList()))
        }

        "CR 611.2: goad survives the goading player's own turn and the opponent's, and ends on the next" {
            // Alice goads on her own turn 3. Her next turn is 5.
            val goaded = goad(combatState(bob, turnNumber = 3, active = alice), ObjectId(0), alice)
            goadedBy(endUntilYourNextTurnEffects(goaded)) shouldBe alice
            goadedBy(endUntilYourNextTurnEffects(atUntapOf(goaded, bob, 4))) shouldBe alice
            goadedBy(endUntilYourNextTurnEffects(atUntapOf(goaded, alice, 5))).shouldBeNull()
        }

        "CR 611.2: goaded on the opponent's turn, goad ends at the goading player's very next untap" {
            // Alice goads during bob's turn 4; her next turn is 5.
            val goaded = goad(combatState(bob, turnNumber = 4, active = bob), ObjectId(0), alice)
            goadedBy(endUntilYourNextTurnEffects(goaded)) shouldBe alice
            goadedBy(endUntilYourNextTurnEffects(atUntapOf(goaded, alice, 5))).shouldBeNull()
        }

        "CR 514.2: the cleanup step does not end an until-your-next-turn effect" {
            val granted = hexproofUntilAlicesNextTurn(combatState(bob, turnNumber = 3, active = alice))
            granted.timedEffects.single().duration shouldBe EffectDuration.UntilYourNextTurn(alice)
            effectiveKeywords(granted, ObjectId(0)) shouldContain Keyword.HEXPROOF
            // The turn it was created on ends, and the grant is untouched — the whole reason the
            // duration could not be encoded as UntilEndOfTurn.
            val cleaned = cleanupRemoveDamageAndEndEffects(granted)
            cleaned.timedEffects.single().duration shouldBe EffectDuration.UntilYourNextTurn(alice)
        }

        "CR 611.2: an until-your-next-turn keyword grant is ended by that turn's untap, not the one before" {
            val granted = hexproofUntilAlicesNextTurn(combatState(bob, turnNumber = 3, active = alice))
            // Bob's turn 4 begins: still hexproof, which is the turn the card is protecting it through.
            val opponentsTurn = endUntilYourNextTurnEffects(atUntapOf(granted, bob, 4))
            effectiveKeywords(opponentsTurn, ObjectId(0)) shouldContain Keyword.HEXPROOF
            // Alice's turn 5 begins: the grant is gone before anything in that turn happens.
            val ownTurn = endUntilYourNextTurnEffects(atUntapOf(granted, alice, 5))
            ownTurn.timedEffects.shouldBeEmpty()
            effectiveKeywords(ownTurn, ObjectId(0)) shouldNotContain Keyword.HEXPROOF
        }

        "CR 400.7: goad ends with the object — the marker is not carried by a copy with a new id" {
            val goaded = goad(combatState(bob), ObjectId(0), alice)
            val creature = goaded.sharedZones.battlefield.single { it.id == ObjectId(0) }
            // The fresh object a zone move makes (CR 400.7) is constructed from the card, not copied.
            GameObject(ObjectId(99), creature.card, creature.owner).goadedBy.shouldBeNull()
        }
    })

/** [state]'s creature 0's goading player, or `null` when it is not goaded. */
private fun goadedBy(state: GameState) = state.sharedZones.battlefield.single { it.id == ObjectId(0) }.goadedBy

/** A fixture "gains hexproof until alice's next turn" on creature 0 (CR 611.2, CR 702.11b). */
private fun hexproofUntilAlicesNextTurn(state: GameState): GameState =
    applyUntilYourNextTurn(
        state = state,
        affected = ObjectId(0),
        player = alice,
        modification = ContinuousModification(grantedKeywords = persistentSetOf(Keyword.HEXPROOF)),
        sourceCard = CardRef("Fixture Throne"),
    )

/**
 * A declare-attackers board: two of [attackerOwner]'s creatures, both able to attack, in that seat's
 * declare-attackers step with combat not yet engaged.
 */
private fun combatState(
    attackerOwner: PlayerId,
    turnNumber: Int = 3,
    active: PlayerId = attackerOwner,
    firstIsTapped: Boolean = false,
): GameState =
    auraState(
        battlefield =
            listOf(
                bfObject(0, "Ent", owner = attackerOwner).copy(summoningSick = false, tapped = firstIsTapped),
                bfObject(1, "Toad", owner = attackerOwner).copy(summoningSick = false),
            ),
        turn = Turn(active, turnNumber, TurnPhase.COMBAT, TurnStep.DECLARE_ATTACKERS),
    )

/** [state] moved to the untap step of [active]'s turn [number] — where the duration sweep runs. */
private fun atUntapOf(
    state: GameState,
    active: PlayerId,
    number: Int,
): GameState = state.copy(turn = Turn(active, number, TurnPhase.BEGINNING, TurnStep.UNTAP))

/** The declare-attackers request [state] is paused at (CR 508.1). */
private fun attackRequest(state: GameState): DecisionRequest.DeclareAttackers =
    pendingCombatDecision(state).shouldBeInstanceOf<DecisionRequest.DeclareAttackers>()
