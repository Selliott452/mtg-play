package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.Responder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.MatchResult
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * Real-card combat that kills a player (CR 704.5a via CR 510.1c): two ready Grizzly Bears swing
 * into a defenceless opponent on 4 life, and the 4 unblocked combat damage ends the game. The
 * ending carries the life-total loss reason, exactly as a Bolt kill would (CR 704.5a is the same
 * state-based action however the life was lost).
 */
class CombatLethalityAcceptanceSpec :
    StringSpec({

        "CR 510.1c and CR 704.5a: unblocked combat damage takes a player to 0 and ends the game" {
            val game =
                ScriptedGame
                    .startFrom(combatKillStartState())
                    .playToCompletion(ALL_OUT_ATTACK, turnCap = 4)

            game.result shouldBe MatchResult(winner = alice, loser = bob, reason = LossReason.LIFE_TOTAL_ZERO_OR_LESS)
            game.state.players
                .getValue(bob)
                .life shouldBeLessThanOrEqual 0
            // The lethal blow was combat damage to the player, not a spell (no Bolt in this game).
            game.state.events.filterIsInstance<GameEvent.DamageDealt>().any {
                it.recipient == Target.Player(bob)
            } shouldBe true
        }
    })

/**
 * A paused state at alice's declare-attackers turn-based action (CR 508.1): alice controls two
 * ready Grizzly Bears (2/2, not summoning sick, untapped), bob is on 4 life with no creatures to
 * block, and both hands are empty so the only actions are the attack and passes. Real [MvpCards]
 * definitions.
 */
private fun combatKillStartState(): GameState {
    val bearA = GameObject(ObjectId(0), CardRef("Grizzly Bears"), alice, summoningSick = false)
    val bearB = GameObject(ObjectId(1), CardRef("Grizzly Bears"), alice, summoningSick = false)
    // A couple of inert library cards so an incidental draw never decks a seat out mid-scenario.
    val aliceLibrary = persistentListOf(GameObject(ObjectId(2), CardRef("Forest"), alice))
    val bobLibrary = persistentListOf(GameObject(ObjectId(3), CardRef("Forest"), bob))

    val alicePlayer = playerWithZones(library = aliceLibrary)
    val bobPlayer = playerWithZones(life = 4, library = bobLibrary)

    return GameState(
        players = persistentMapOf(alice to alicePlayer, bob to bobPlayer),
        turn = Turn(alice, 3, TurnPhase.COMBAT, TurnStep.DECLARE_ATTACKERS),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(bearA, bearB),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 4,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}

// Attacks with every eligible creature, blocks with none, and passes every priority window — the
// deterministic "swing for the win" policy this scripted kill needs. The casting, target, payment,
// and blocker-order requests are all unreachable in this creatureless-hand, no-block scenario.
private val ALL_OUT_ATTACK: Responder =
    Responder { request, _ ->
        when (request) {
            is DecisionRequest.ChooseAction -> {
                val pass = request.options.indexOfFirst { it is PriorityOption.Pass }
                check(pass >= 0) { "CR 117.3d: passing must always be enumerated, options were ${request.options}" }
                Decision.SingleSelect(request.id, pass)
            }
            is DecisionRequest.DeclareAttackers ->
                Decision.MultiSelect(request.id, request.options.indices.toList())
            is DecisionRequest.DeclareBlockers -> Decision.MultiSelect(request.id, emptyList())
            is DecisionRequest.ChooseDiscards ->
                Decision.MultiSelect(request.id, (0 until request.count).toList())
            is DecisionRequest.ChooseModes ->
                error("the all-out-attack policy never casts, but a mode request surfaced: $request")
            is DecisionRequest.ChooseTargets ->
                error("the all-out-attack policy never casts, but a targets request surfaced: $request")
            is DecisionRequest.ChoosePaymentPlan ->
                error("the all-out-attack policy never casts, but a payment request surfaced: $request")
            is DecisionRequest.ChooseXValue ->
                error("the all-out-attack policy never casts, but an X announcement surfaced: $request")
            is DecisionRequest.OrderBlockers ->
                error("the all-out-attack policy never blocks, but a blocker-order request surfaced: $request")
            is DecisionRequest.AssignTrampleDamage ->
                error("the all-out-attack policy has no tramplers, but a trample-assignment request surfaced: $request")
            is DecisionRequest.OrderTriggers ->
                error("the all-out-attack policy fires no triggers, but a trigger-order request surfaced: $request")
            is DecisionRequest.ChooseYesNo ->
                error("the all-out-attack policy casts no madness cards, but a yes/no request surfaced: $request")
            is DecisionRequest.SizedSelection ->
                error("the all-out-attack policy pays no cost selections, but one surfaced: $request")
            // CR 601.2b/701.60a: this policy never casts, so it never collects evidence either.
            is DecisionRequest.SummedSelection ->
                error("the all-out-attack policy collects no evidence, but one surfaced: $request")
            // CR 601.2c: this creatureless-hand scenario reaches no target choice of either arity.
            is DecisionRequest.RangedSelection ->
                error("the all-out-attack policy casts no multi-target spell, but one surfaced: $request")
            is DecisionRequest.ChooseReplacement ->
                error("the all-out-attack policy discards no two-replacement cards, but one surfaced: $request")
            is DecisionRequest.ChooseColor ->
                error("the all-out-attack policy casts no colour-choosing permanents, but one surfaced: $request")
            is DecisionRequest.ChooseFromRevealed ->
                error("the all-out-attack policy resolves no reveal effects, but one surfaced: $request")
            is DecisionRequest.ChooseCostMode ->
                error("the all-out-attack policy resolves no cost-then-draw spells, but one surfaced: $request")
            is DecisionRequest.ChooseFromLibrary ->
                error("the all-out-attack policy activates no library searches, but one surfaced: $request")
            is DecisionRequest.ChooseLibraryArrangement ->
                error("the all-out-attack policy resolves no library looks, but one surfaced: $request")
            is DecisionRequest.ChooseCounterPayment ->
                error("the all-out-attack policy casts no counters, but one surfaced: $request")
            is DecisionRequest.ChooseRevealedHandCard ->
                error("the all-out-attack policy casts no hand-reveal spells, but one surfaced: $request")
            is DecisionRequest.ChooseTapOrUntap ->
                error("the all-out-attack policy resolves no tap-or-untap clause, but one surfaced: $request")
            is DecisionRequest.ChooseOptionalManaPayment ->
                error("the all-out-attack policy plays no pay-then-draw permanents, but one surfaced: $request")
            is DecisionRequest.ChooseGraveyardCardToExile ->
                error("the all-out-attack policy activates no graveyard-exile abilities, but one surfaced: $request")
            is DecisionRequest.ChooseLibraryPosition ->
                error("the all-out-attack policy casts no library-placement spells, but one surfaced: $request")
            is DecisionRequest.ChooseExploreDestination ->
                error("the all-out-attack policy activates no exploring abilities, but one surfaced: $request")
            is DecisionRequest.ChooseRevealedCardType ->
                error("the all-out-attack policy casts no type-choosing reveal spells, but one surfaced: $request")
            is DecisionRequest.MulliganRequest ->
                error("the all-out-attack policy runs mulligan-free games, but a mulligan request surfaced: $request")
        }
    }
