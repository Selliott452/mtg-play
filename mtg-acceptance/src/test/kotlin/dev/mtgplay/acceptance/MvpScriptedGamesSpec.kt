package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.MatchResult
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The two scripted representative games of the MVP matchup (P6.3, deliverable 2): each deck's signature win,
 * played end-to-end through [ScriptedGame] (which invariant-checks every transition, PLAN.md §2.3) as a pure
 * decision script — deterministic, so each is a faithful replay record (ADR-006).
 *
 * **Construction approach (flagged).** Both games are driven from a handcrafted mid-game board via
 * [ScriptedGame.startFrom] over the real [MvpCards] definitions, not from a turn-1 shuffle. A full random
 * shuffle of two 60-card libraries cannot be steered to a specific multi-turn line for *both* seats, so a
 * curated pause is the honest way to pin a named signature play (the sanctioned ADR-004 resumability path the
 * acceptance suite uses throughout, e.g. [CombatLethalityAcceptanceSpec], [CastFromElsewhereAcceptanceSpec]).
 * Every card action from that pause on is a real engine transition; nothing is stubbed. The boards stage the
 * exact stated line and the scripts assert the headline beats, the named mechanisms firing (reusing the
 * corpus' [mechanismsIn] detectors), the killing blow, and the final [MatchResult].
 */
class MvpScriptedGamesSpec :
    StringSpec({

        "GW Bogles curve-out: a hexproof one-drop grown by stacked Auras tramples through a chump for the win" {
            // Bob (Bogles) is mid-curve: a Slippery Bogle (hexproof) survived, a Utopia Sprawl already ramps a
            // Forest (green chosen), and Ethereal Armor + Rancor are in hand to stack onto the Bogle. Alice
            // (Madness) is at 5 with a lone Voldaren Epicure (1/1) to chump. Bob's turn, precombat main.
            val bogle = ObjectId(0)
            val sprawlForest = ObjectId(1)
            val chump = ObjectId(20)
            val sprawl =
                GameObject(
                    ObjectId(2),
                    CardRef("Utopia Sprawl"),
                    bob,
                    attachedTo = sprawlForest,
                    chosenColor = Color.GREEN,
                )
            val bobBattlefield =
                listOf(
                    GameObject(bogle, CardRef("Slippery Bogle"), bob, summoningSick = false),
                    GameObject(sprawlForest, CardRef("Forest"), bob, summoningSick = false),
                    sprawl,
                    GameObject(ObjectId(3), CardRef("Plains"), bob, summoningSick = false),
                )
            val bobHand =
                listOf(
                    GameObject(ObjectId(4), CardRef("Ethereal Armor"), bob),
                    GameObject(ObjectId(5), CardRef("Rancor"), bob),
                )
            val epicure = GameObject(chump, CardRef("Voldaren Epicure"), alice, summoningSick = false)
            val game =
                handcraftedGame(
                    activePlayer = bob,
                    active =
                        Seat(
                            battlefield = bobBattlefield,
                            hand = bobHand,
                            library = listOf(GameObject(ObjectId(6), CardRef("Forest"), bob)),
                        ),
                    defender =
                        Seat(
                            life = 5,
                            battlefield = listOf(epicure),
                            library = listOf(GameObject(ObjectId(21), CardRef("Mountain"), alice)),
                        ),
                )

            // Auras stack onto the hexproof one-drop: Ethereal Armor (+1/+1 per enchantment you control, first
            // strike) then Rancor (+2/+0, trample). Rancor's {G} taps the Sprawl-enchanted Forest, firing the
            // Sprawl triggered-mana bonus (CR 605.1b). CR 613 layers make the Bogle 6/4: base 1/1, +3/+3 from
            // Ethereal Armor (Sprawl + Armor + Rancor = 3 enchantments), +2/+0 from Rancor.
            game.castAuraOn("Ethereal Armor", bogle).resolveStack()
            game.castAuraOn("Rancor", bogle).resolveStack()
            game.state.sharedZones.battlefield
                .single { it.card == CardRef("Ethereal Armor") }
                .attachedTo shouldBe bogle
            game.state.sharedZones.battlefield
                .single { it.card == CardRef("Rancor") }
                .attachedTo shouldBe bogle

            // Attack with the Bogle; alice chump-blocks with the 1/1 Epicure; the trample excess (6 power − 1
            // lethal to the blocker = 5) is assigned to alice (CR 702.19e), and first strike (CR 702.7) deals
            // it before the blocker can swing back.
            game.driveUntil { game.pendingRequest is DecisionRequest.DeclareAttackers }
            game.declareAttackers(bogle)
            game.driveUntil { game.pendingRequest is DecisionRequest.DeclareBlockers }
            game.declareBlock(blocker = chump, attacker = bogle)
            game.driveUntil { game.pendingRequest is DecisionRequest.AssignTrampleDamage }
            val trample = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.AssignTrampleDamage>()
            trample.options shouldBe (0..5).toList()
            game.apply(Decision.SingleSelect(trample.id, TRAMPLE_TO_FACE))
            game.driveUntil { game.isOver }

            // Headline beats: Sprawl ramped, the Bogle trampled exactly 5 to the face, the chump died, and
            // Bogles won by taking alice to 0 (CR 704.5a).
            (Mechanism.SPRAWL_TRIGGERED_MANA in mechanismsIn(game)).shouldBeTrue()
            (Mechanism.TRAMPLE_ASSIGNMENT in mechanismsIn(game)).shouldBeTrue()
            game.state.events
                .filterIsInstance<GameEvent.DamageDealt>()
                .last { it.recipient == Target.Player(alice) }
                .amount shouldBe TRAMPLE_TO_FACE
            // The trample went *through* a real chump: the 1/1 Epicure was declared as a blocker (its lethal
            // was committed to it, freeing only the excess for the face). The chump's own CreatureDied is not
            // asserted — the game-ending SBA (alice at 0) is simultaneous (CR 704.3) and ends the game first.
            game.state.events
                .filterIsInstance<GameEvent.BlockersDeclared>()
                .any { declared -> declared.blocks.any { it.blocker == chump } }
                .shouldBeTrue()
            game.state.players
                .getValue(alice)
                .life shouldBeLessThanOrEqual 0
            game.result shouldBe MatchResult(winner = bob, loser = alice, reason = LossReason.LIFE_TOTAL_ZERO_OR_LESS)
        }

        "Mono-Red Madness burn race: Looting pitches Fiery Temper, Guttersnipe converts, Fireblast finishes" {
            // Alice (Madness) has Guttersnipe online and four Mountains; her hand is Faithless Looting, Fiery
            // Temper (to pitch), Fireblast (the finisher), and a spare. Bob (Bogles) is at 13, defenceless.
            // Alice's turn, precombat main.
            val guttersnipe = ObjectId(0)
            val looting = ObjectId(10)
            val fieryTemper = ObjectId(11)
            val fireblast = ObjectId(12)
            val spare = ObjectId(13)
            val game =
                handcraftedGame(
                    activePlayer = alice,
                    active =
                        Seat(
                            battlefield =
                                listOf(
                                    GameObject(guttersnipe, CardRef("Guttersnipe"), alice, summoningSick = false),
                                    GameObject(ObjectId(1), CardRef("Mountain"), alice, summoningSick = false),
                                    GameObject(ObjectId(2), CardRef("Mountain"), alice, summoningSick = false),
                                    GameObject(ObjectId(3), CardRef("Mountain"), alice, summoningSick = false),
                                    GameObject(ObjectId(4), CardRef("Mountain"), alice, summoningSick = false),
                                ),
                            hand =
                                listOf(
                                    GameObject(looting, CardRef("Faithless Looting"), alice),
                                    GameObject(fieryTemper, CardRef("Fiery Temper"), alice),
                                    GameObject(fireblast, CardRef("Fireblast"), alice),
                                    GameObject(spare, CardRef("Mountain"), alice),
                                ),
                            library =
                                listOf(
                                    GameObject(ObjectId(30), CardRef("Mountain"), alice),
                                    GameObject(ObjectId(31), CardRef("Forest"), alice),
                                ),
                        ),
                    defender = Seat(life = 13, library = listOf(GameObject(ObjectId(40), CardRef("Forest"), bob))),
                )

            // Faithless Looting ({R}) — Guttersnipe pings bob for 2 (CR 603.2e) — resolves: draw two, then
            // discard two; pitch Fiery Temper (madness → exile, CR 702.35a) and the spare.
            game.castNormally("Faithless Looting").paySole()
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseResolutionDiscards }
            val discard = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseResolutionDiscards>()
            val temperIndex = discard.options.indexOfFirst { it.card == CardRef("Fiery Temper") }
            val spareIndex =
                discard.options.indexOfFirst { it.card != CardRef("Fiery Temper") && it.card != CardRef("Fireblast") }
            game.apply(Decision.MultiSelect(discard.id, listOf(temperIndex, spareIndex)))

            // The reflexive madness trigger (CR 702.35b) offers the {R} cast; accept and bolt bob for 3 —
            // Guttersnipe pings again (Fiery Temper is an instant). Bob: 13 → 11 → 9 → 6.
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseYesNo }
            val yesNo = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()
            game.apply(Decision.SingleSelect(yesNo.id, DecisionRequest.ChooseYesNo.ACCEPT))
            game.chooseTargetPlayer(bob)
            game.paySole()
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.players
                .getValue(bob)
                .life shouldBe 6

            // Fireblast for its alternative cost (CR 118.9): sacrifice two Mountains rather than pay {4}{R}{R}.
            // Guttersnipe pings for 2 (bob → 4), then Fireblast's 4 finishes (bob → 0, CR 704.5a).
            game.castViaAlternativeCost("Fireblast")
            game.chooseTargetPlayer(bob)
            val sacrifices = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseSacrifices>()
            sacrifices.count shouldBe 2
            game.apply(Decision.MultiSelect(sacrifices.id, listOf(0, 1)))
            game.paySole()
            game.driveUntil { game.isOver }

            // Headline beats: madness cast happened, Guttersnipe fired on all three casts, two Mountains were
            // sacrificed to Fireblast, Fireblast's 4 was the killing blow, and Madness won (CR 704.5a).
            val mechanisms = mechanismsIn(game)
            (Mechanism.MADNESS_CAST in mechanisms).shouldBeTrue()
            (Mechanism.GUTTERSNIPE_TRIGGER in mechanisms).shouldBeTrue()
            game.state.events
                .filterIsInstance<GameEvent.TriggeredAbilityPutOnStack>()
                .count { it.sourceCard == CardRef("Guttersnipe") } shouldBeGreaterThanOrEqual 3
            game.state.events
                .filterIsInstance<GameEvent.PermanentSacrificed>()
                .count { it.card == CardRef("Mountain") } shouldBe 2
            game.state.events
                .filterIsInstance<GameEvent.DamageDealt>()
                .last { it.recipient == Target.Player(bob) }
                .amount shouldBe FIREBLAST_KILL_DAMAGE
            game.state.players
                .getValue(bob)
                .life shouldBeLessThanOrEqual 0
            game.result shouldBe MatchResult(winner = alice, loser = bob, reason = LossReason.LIFE_TOTAL_ZERO_OR_LESS)
        }
    })

/** The trample excess assigned to the defending player in the Bogles game: 6 power − 1 lethal to the chump. */
private const val TRAMPLE_TO_FACE: Int = 5

/** Fireblast's damage — the killing blow in the Madness game (CR 120.3a). */
private const val FIREBLAST_KILL_DAMAGE: Int = 4

// ---- handcrafted-state construction ---------------------------------------------------------------------

/** One seat's starting zones for a handcrafted scripted game. */
private data class Seat(
    val life: Int = STARTING_LIFE,
    val battlefield: List<GameObject> = emptyList(),
    val hand: List<GameObject> = emptyList(),
    val library: List<GameObject> = emptyList(),
    val graveyard: List<GameObject> = emptyList(),
)

/**
 * A [ScriptedGame] resumed (ADR-004) from a precombat-main state of [activePlayer]'s turn over the real
 * [MvpCards] definitions: [active] holds priority with the given zones, [defender] is the opponent. Every
 * transition from here is invariant-checked. The turn number is a fixed mid-game 4 (no summoning-sickness or
 * land-drop bearing on either scripted line).
 */
private fun handcraftedGame(
    activePlayer: PlayerId,
    active: Seat,
    defender: Seat,
): ScriptedGame {
    val defenderSeat = if (activePlayer == alice) bob else alice
    val bySeat = mapOf(activePlayer to active, defenderSeat to defender)
    val allObjects =
        bySeat.values.flatMap { it.battlefield + it.hand + it.library + it.graveyard }
    val nextId = (allObjects.maxOfOrNull { it.id.value } ?: -1L) + 1

    fun playerState(
        seat: PlayerId,
        setup: Seat,
    ): PlayerState =
        PlayerState(
            life = setup.life,
            library = setup.library.toPersistentList(),
            hand = setup.hand.toPersistentList(),
            graveyard = setup.graveyard.toPersistentList(),
            priorityStatus = if (seat == activePlayer) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
        )

    val state =
        GameState(
            players =
                persistentMapOf(
                    alice to playerState(alice, bySeat.getValue(alice)),
                    bob to playerState(bob, bySeat.getValue(bob)),
                ),
            turn = Turn(activePlayer, MID_GAME_TURN, TurnPhase.PRECOMBAT_MAIN, null),
            sharedZones =
                SharedZones(
                    battlefield = bySeat.values.flatMap { it.battlefield }.toPersistentList(),
                    stack = persistentListOf(),
                    exile = persistentListOf(),
                ),
            nextObjectId = nextId,
            rng = Rng(0),
            events = persistentListOf(),
            definitions = MvpCards.definitions.toPersistentMap(),
        )
    return ScriptedGame.startFrom(state)
}

/** A mid-game turn number: past summoning sickness, no land-drop or turn-count bearing on either line. */
private const val MID_GAME_TURN: Int = 4

// ---- driving helpers over ScriptedGame (invariant-checked every transition) -----------------------------

private fun ScriptedGame.action(): DecisionRequest.ChooseAction =
    pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()

/** Selects the normal-cast (from hand, printed cost) option for [name]. */
private fun ScriptedGame.castNormally(name: String): ScriptedGame {
    val window = action()
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell &&
                it.card == CardRef(name) &&
                it.source == CastSource.HAND &&
                it.permission == null
        }
    check(index >= 0) { "no normal cast for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Selects the alternative-cost cast option for [name] (Fireblast's sacrifice-two-Mountains line). */
private fun ScriptedGame.castViaAlternativeCost(name: String): ScriptedGame {
    val window = action()
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell &&
                it.card == CardRef(name) &&
                it.permission is CastingPermission.AlternativeCost
        }
    check(index >= 0) { "no alternative-cost cast for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Casts the Aura [name] from hand onto the permanent [target], answering its enchant target and sole plan. */
private fun ScriptedGame.castAuraOn(
    name: String,
    target: ObjectId,
): ScriptedGame {
    castNormally(name)
    val targets = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
    val index = targets.options.indexOf(Target.Permanent(target))
    check(index >= 0) { "$target is not an enchant target for $name in ${targets.options}" }
    apply(Decision.SingleSelect(targets.id, index))
    return paySole()
}

/** Answers the pending target request with the player [target]. */
private fun ScriptedGame.chooseTargetPlayer(target: PlayerId): ScriptedGame {
    val targets = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
    val index = targets.options.indexOf(Target.Player(target))
    check(index >= 0) { "player $target is not a legal target in ${targets.options}" }
    return apply(Decision.SingleSelect(targets.id, index))
}

/** Answers the pending payment request with the sole (index-0) plan. */
private fun ScriptedGame.paySole(): ScriptedGame {
    val payment = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
    return apply(Decision.SingleSelect(payment.id, 0))
}

/** Declares [attacker] as the sole attacker at the pending declare-attackers step. */
private fun ScriptedGame.declareAttackers(attacker: ObjectId): ScriptedGame {
    val request = pendingRequest.shouldBeInstanceOf<DecisionRequest.DeclareAttackers>()
    val index = request.options.indexOfFirst { it.attacker == attacker }
    check(index >= 0) { "$attacker is not an eligible attacker in ${request.options}" }
    return apply(Decision.MultiSelect(request.id, listOf(index)))
}

/** Declares [blocker] blocking [attacker] as the sole block at the pending declare-blockers step. */
private fun ScriptedGame.declareBlock(
    blocker: ObjectId,
    attacker: ObjectId,
): ScriptedGame {
    val request = pendingRequest.shouldBeInstanceOf<DecisionRequest.DeclareBlockers>()
    val index = request.options.indexOfFirst { it.blocker == blocker && it.attacker == attacker }
    check(index >= 0) { "$blocker blocking $attacker is not a legal block in ${request.options}" }
    return apply(Decision.MultiSelect(request.id, listOf(index)))
}

/**
 * Advances by passing every priority window and putting single-controller trigger batches on the stack in
 * enumeration order until [predicate] holds — the "resolve the stack, reach the next scripted decision"
 * walker. Fails loudly if it reaches a request it does not auto-answer before [predicate] (so a mis-scripted
 * combat or cast step surfaces immediately) or exceeds the step guard.
 */
private fun ScriptedGame.driveUntil(predicate: () -> Boolean): ScriptedGame {
    var steps = 0
    while (!predicate() && !isOver) {
        check(steps < MAX_DRIVE_STEPS) { "driveUntil did not reach its predicate within $MAX_DRIVE_STEPS steps" }
        when (val request = pendingRequest) {
            is DecisionRequest.ChooseAction -> {
                val pass = request.options.indexOfFirst { it is PriorityOption.Pass }
                check(pass >= 0) { "CR 117.3d: passing must always be enumerated" }
                apply(Decision.SingleSelect(request.id, pass))
            }
            is DecisionRequest.OrderTriggers ->
                apply(Decision.MultiSelect(request.id, request.options.indices.toList()))
            else -> error("driveUntil cannot auto-answer $request; the script must handle it")
        }
        steps++
    }
    return this
}

/** Resolves the stack to empty (both players pass, triggers ordered), leaving the active player at priority. */
private fun ScriptedGame.resolveStack(): ScriptedGame = driveUntil { state.sharedZones.stack.isEmpty() }

private const val MAX_DRIVE_STEPS: Int = 200
