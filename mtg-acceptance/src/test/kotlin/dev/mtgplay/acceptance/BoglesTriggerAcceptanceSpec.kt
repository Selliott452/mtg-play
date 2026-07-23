package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.replay.ReplayHarness
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.cards.warriorToken
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.engine.layeredCharacteristics
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The four real Bogles triggered halves (CR 603) exercised end-to-end through the real engine
 * pipeline and driven by [ScriptedGame], which invariant-checks **every** transition (P5.1
 * deliverable 9): Cartouche's enters-the-battlefield token that then fights, Armadillo Cloak's
 * damage-triggered lifegain to the Aura's controller, Rancor's full die→fall-off→return chain,
 * Abundant Growth's enters-the-battlefield draw, plus APNAP ordering, the OrderTriggers decision,
 * responding to a trigger on the stack, and CR 704.5d token cessation. Every state is a valid engine
 * input by construction (ADR-004).
 */
class BoglesTriggerAcceptanceSpec :
    StringSpec({

        "CR 603.6a: Abundant Growth's enters-the-battlefield trigger draws a card on resolution" {
            // Alice casts Abundant Growth on her Forest; on ETB she draws the top of her library.
            val game =
                gameFrom(
                    alice =
                        Board(
                            hand = listOf(obj(10, "Abundant Growth")),
                            battlefield = listOf(obj(0, "Forest")),
                            library = listOf(obj(20, "Plains")),
                        ),
                )
            game.castAuraOn("Abundant Growth", ObjectId(0))
            game.driveUntil { game.state.events.any { it is GameEvent.CardDrawn && it.player == alice } }
            // The ETB trigger resolved and drew the top card into alice's hand (CR 120.1).
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContain CardRef("Plains")
        }

        "CR 603.6a, CR 111.4: Cartouche of Solidarity's ETB creates a 1/1 vigilant Warrior token" {
            val game =
                gameFrom(
                    alice =
                        Board(
                            hand = listOf(obj(10, "Cartouche of Solidarity")),
                            battlefield = listOf(obj(0, "Plains"), notSick(obj(1, "Grizzly Bears"))),
                        ),
                )
            game.castAuraOn("Cartouche of Solidarity", ObjectId(1))
            game.driveUntil { game.state.events.any { it is GameEvent.TokenCreated } }

            val token =
                game.state.sharedZones.battlefield
                    .single { isToken(game.state, it) }
            token.card shouldBe CardRef("Warrior")
            token.owner shouldBe alice
            val characteristics = layeredCharacteristics(game.state, token.id)
            characteristics.power shouldBe 1
            characteristics.toughness shouldBe 1
            characteristics.keywords shouldContain Keyword.VIGILANCE
        }

        "CR 603.2: the Warrior token fights — it deals combat damage as a real creature" {
            // A Warrior token already on the battlefield (created a prior turn, no longer summoning
            // sick) attacks the defender for its 1 power.
            val game =
                gameFrom(
                    alice = Board(battlefield = listOf(notSick(GameObject(ObjectId(1), CardRef("Warrior"), alice)))),
                    definitions = withWarrior(),
                )
            game.marchToCombatAndAttack(listOf(ObjectId(1)))
            game.driveUntil {
                game.state.players
                    .getValue(bob)
                    .life < STARTING_LIFE
            }
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 1
        }

        "CR 603.2: Armadillo Cloak's enchanted creature deals combat damage; its controller gains that life" {
            // Alice's Grizzly Bears wears Armadillo Cloak (+2/+2 -> 4/4); it attacks unblocked for 4,
            // and alice (the Aura's controller) gains 4 life.
            val game =
                gameFrom(
                    alice =
                        Board(
                            battlefield =
                                listOf(
                                    notSick(obj(1, "Grizzly Bears")),
                                    enchant(2, "Armadillo Cloak", 1),
                                ),
                        ),
                )
            game.marchToCombatAndAttack(listOf(ObjectId(1)))
            game.driveUntil {
                game.state.players
                    .getValue(alice)
                    .life > STARTING_LIFE
            }
            // Alice gained 4 (CR 118.9 "that much"); bob took 4.
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + 4
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 4
        }

        "CR 704.5m -> CR 603.6b: a Rancor'd creature dies, Rancor falls off, then returns to its owner's hand" {
            // Bob holds priority; he Bolts alice's Rancor-enchanted Bears (2/2 -> 4/2), which is lethal.
            val game =
                gameFrom(
                    alice =
                        Board(
                            battlefield =
                                listOf(
                                    obj(1, "Grizzly Bears"),
                                    enchant(2, "Rancor", 1),
                                ),
                        ),
                    bob = Board(hand = listOf(obj(10, "Lightning Bolt")), battlefield = listOf(obj(11, "Mountain"))),
                    holder = bob,
                )
            game.castTargeting("Lightning Bolt", Target.Permanent(ObjectId(1)))
            // The Bolt resolves (Bears takes 3, lethal), Bears dies (CR 704.5g), Rancor falls off
            // (CR 704.5m), and Rancor's trigger returns it to alice's hand (CR 603.6b).
            game.driveUntil {
                game.state.players
                    .getValue(alice)
                    .hand
                    .any { it.card == CardRef("Rancor") }
            }
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContain CardRef("Rancor")
            // The full chain narrated: the creature died, the Aura fell off, and it returned.
            game.state.events.filterIsInstance<GameEvent.CreatureDied>() shouldHaveSize 1
            game.state.events.filterIsInstance<GameEvent.AuraFellOff>() shouldHaveSize 1
            game.state.events
                .filterIsInstance<GameEvent.CardReturnedToHand>()
                .single()
                .card shouldBe CardRef("Rancor")
        }

        "CR 603.3b: two of one player's simultaneous triggers surface an OrderTriggers decision" {
            // Alice attacks with two Armadillo-Cloaked Bears; both deal damage, so she controls two
            // simultaneous lifegain triggers and must order them.
            val game =
                gameFrom(
                    alice =
                        Board(
                            battlefield =
                                listOf(
                                    notSick(obj(1, "Grizzly Bears")),
                                    enchant(2, "Armadillo Cloak", 1),
                                    notSick(obj(3, "Grizzly Bears")),
                                    enchant(4, "Armadillo Cloak", 3),
                                ),
                        ),
                )
            game.marchToCombatAndAttack(listOf(ObjectId(1), ObjectId(3)))
            // Combat damage fires both triggers; the next decision is alice ordering them (CR 603.3b).
            game.driveUntil { game.pendingRequest is DecisionRequest.OrderTriggers }
            val order = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.OrderTriggers>()
            order.options shouldHaveSize 2
            order.seat shouldBe alice
            // Order them and finish; both resolve, gaining 4 + 4 = 8 life total.
            game.apply(Decision.MultiSelect(order.id, listOf(1, 0)))
            game.driveUntil {
                game.state.players
                    .getValue(alice)
                    .life >= STARTING_LIFE + 8
            }
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + 8
        }

        "CR 603.3, CR 608.1: a triggered ability on the stack can be responded to (LIFO)" {
            // Alice casts Abundant Growth; it resolves and its ETB draw trigger goes on the stack.
            // While that trigger waits, bob responds with a Lightning Bolt to alice's face — the Bolt
            // resolves first (CR 608.1 LIFO), then the trigger's draw.
            val game =
                gameFrom(
                    alice =
                        Board(
                            hand = listOf(obj(10, "Abundant Growth")),
                            battlefield = listOf(obj(0, "Forest")),
                            library = listOf(obj(20, "Plains")),
                        ),
                    bob = Board(hand = listOf(obj(11, "Lightning Bolt")), battlefield = listOf(obj(12, "Mountain"))),
                )
            game.castAuraOn("Abundant Growth", ObjectId(0))
            // Drive until the draw trigger is on the stack and bob holds his response window.
            game.driveUntil {
                game.state.sharedZones.stack
                    .any { it is StackEntry.Ability } &&
                    game.state.players
                        .getValue(bob)
                        .priorityStatus == PriorityStatus.HOLDS_PRIORITY
            }
            game.castTargeting("Lightning Bolt", Target.Player(alice))
            game.driveUntil {
                game.state.events.any { it is GameEvent.CardDrawn && it.player == alice } &&
                    game.state.players
                        .getValue(alice)
                        .life < STARTING_LIFE
            }
            // Both happened: the Bolt dealt 3 to alice and the trigger drew her a card.
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE - 3
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContain CardRef("Plains")
        }

        "CR 704.5d: a Warrior token that dies ceases to exist rather than staying in the graveyard" {
            // Bob Bolts alice's 1/1 Warrior token; it dies (CR 704.5g) and then ceases to exist.
            val game =
                gameFrom(
                    alice = Board(battlefield = listOf(notSick(GameObject(ObjectId(1), CardRef("Warrior"), alice)))),
                    bob = Board(hand = listOf(obj(10, "Lightning Bolt")), battlefield = listOf(obj(11, "Mountain"))),
                    holder = bob,
                    definitions = withWarrior(),
                )
            game.castTargeting("Lightning Bolt", Target.Permanent(ObjectId(1)))
            game.driveUntil { game.state.events.any { it is GameEvent.TokenCeasedToExist } }
            // The token is gone everywhere — battlefield and graveyard alike (CR 704.5d).
            game.state.sharedZones.battlefield
                .none { it.card == CardRef("Warrior") } shouldBe true
            game.state.players.values
                .all { p -> p.graveyard.none { it.card == CardRef("Warrior") } } shouldBe true
        }

        "ADR-006: a full random aura game that fired triggers replays exactly (fingerprint + event log)" {
            // Find a random-legal aura game whose event log shows a trigger was put on the stack, so the
            // replay covers ability stack entries and the pending-trigger fingerprint state.
            val seed =
                (0L..REPLAY_SEED_SEARCH).firstOrNull { candidate ->
                    playAuraGame(candidate).state.events.any { it is GameEvent.TriggeredAbilityPutOnStack }
                } ?: error("no trigger-firing aura game found within the search range")
            val original = playAuraGame(seed)
            original.state.events.any { it is GameEvent.TriggeredAbilityPutOnStack } shouldBe true
            // The same (config, decisions) reproduces the game exactly on both axes (ADR-006).
            ReplayHarness.verifyReproduces(boglesAuraConfig(seed), original).reproduced shouldBe true
        }
    })

// ---- driving helpers over ScriptedGame (invariant-checked every transition) -----------------------

private fun isToken(
    state: GameState,
    obj: GameObject,
): Boolean = state.definitions[obj.card] is TokenDefinition

private fun ScriptedGame.action(): DecisionRequest.ChooseAction =
    pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()

/** Casts an Aura [name] onto the permanent [target], paying its first plan (CR 601.2). */
private fun ScriptedGame.castAuraOn(
    name: String,
    target: ObjectId,
): ScriptedGame {
    castOption(name)
    val targets = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
    val index = targets.options.indexOf(Target.Permanent(target))
    check(index >= 0) { "no legal enchant target $target for $name in ${targets.options}" }
    apply(Decision.SingleSelect(targets.id, index))
    return payFirstPlan()
}

/** Casts a targeted spell [name] at [target], paying its first plan (CR 601.2). */
private fun ScriptedGame.castTargeting(
    name: String,
    target: Target,
): ScriptedGame {
    castOption(name)
    val targets = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
    val index = targets.options.indexOf(target)
    check(index >= 0) { "no legal target $target for $name in ${targets.options}" }
    apply(Decision.SingleSelect(targets.id, index))
    return payFirstPlan()
}

private fun ScriptedGame.castOption(name: String): ScriptedGame {
    val window = action()
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(name) }
    check(index >= 0) { "no CastSpell option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

private fun ScriptedGame.payFirstPlan(): ScriptedGame {
    val payment = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
    return apply(Decision.SingleSelect(payment.id, 0))
}

/** Passes priority, ordering any simultaneous triggers in the deterministic identity permutation. */
private fun ScriptedGame.passOrOrder(): ScriptedGame =
    when (val request = pendingRequest) {
        is DecisionRequest.ChooseAction -> apply(passDecision(request))
        is DecisionRequest.OrderTriggers -> apply(Decision.MultiSelect(request.id, request.options.indices.toList()))
        is DecisionRequest.DeclareAttackers -> apply(Decision.MultiSelect(request.id, emptyList()))
        is DecisionRequest.DeclareBlockers -> apply(Decision.MultiSelect(request.id, emptyList()))
        else -> error("passOrOrder cannot answer $request")
    }

/** Advances (passing / declining combat / ordering triggers) until [predicate] holds. */
private fun ScriptedGame.driveUntil(predicate: () -> Boolean): ScriptedGame {
    var steps = 0
    while (!predicate() && !isOver && steps < MAX_DRIVE_STEPS) {
        passOrOrder()
        steps++
    }
    check(predicate()) { "the drive predicate was not satisfied within $MAX_DRIVE_STEPS steps" }
    return this
}

/** Passes to the declare-attackers step and declares [attackers] (CR 508.1). */
private fun ScriptedGame.marchToCombatAndAttack(attackers: List<ObjectId>): ScriptedGame {
    driveUntil { pendingRequest is DecisionRequest.DeclareAttackers }
    val declare = pendingRequest.shouldBeInstanceOf<DecisionRequest.DeclareAttackers>()
    val indices = attackers.map { id -> declare.options.indexOfFirst { it.attacker == id } }
    check(indices.all { it >= 0 }) { "an attacker in $attackers is not eligible in ${declare.options}" }
    apply(Decision.MultiSelect(declare.id, indices))
    return this
}

private fun passDecision(request: DecisionRequest.ChooseAction): Decision.SingleSelect {
    val index = request.options.indexOfFirst { it is PriorityOption.Pass }
    check(index >= 0) { "CR 117.3d: passing must always be enumerated" }
    return Decision.SingleSelect(request.id, index)
}

/** A full random-legal Bogles-aura game (with all four trigger cards) played to completion. */
private fun playAuraGame(seed: Long): ScriptedGame =
    ScriptedGame
        .start(boglesAuraConfig(seed))
        .playToCompletion(RandomLegalResponder(seed), turnCap = REAL_CARD_TURN_CAP)

private const val MAX_DRIVE_STEPS: Int = 200
private const val REPLAY_SEED_SEARCH: Long = 12

// ---- state construction ---------------------------------------------------------------------------

/** One seat's hand, battlefield, and library objects, for constructing a scenario board. */
private data class Board(
    val hand: List<GameObject> = emptyList(),
    val battlefield: List<GameObject> = emptyList(),
    val library: List<GameObject> = emptyList(),
)

/** The real-card registry plus the Warrior token definition (for tests that place a token directly). */
private fun withWarrior(): Map<CardRef, CardDefinition> = MvpCards.definitions + (CardRef("Warrior") to warriorToken)

/** A battlefield/hand object [id] of card [name] (owner assigned by [gameFrom]). */
private fun obj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice)

/** Marks a battlefield creature as no longer summoning sick (controlled since the turn began). */
private fun notSick(obj: GameObject): GameObject = obj.copy(summoningSick = false)

/** An Aura [name] with id [id] owned by alice, attached to the battlefield object [on] (CR 303.4). */
private fun enchant(
    id: Long,
    name: String,
    on: Long,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice, attachedTo = ObjectId(on))

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004): [holder] holds priority
 * on the given [alice] and [bob] boards, real [MvpCards] definitions, turn belongs to alice. Every
 * transition is invariant-checked by the driver.
 */
private fun gameFrom(
    alice: Board = Board(),
    bob: Board = Board(),
    holder: PlayerId = dev.mtgplay.acceptance.alice,
    definitions: Map<CardRef, CardDefinition> = MvpCards.definitions,
): ScriptedGame {
    val aliceSeat = dev.mtgplay.acceptance.alice
    val bobSeat = dev.mtgplay.acceptance.bob
    val bobHand = bob.hand.map { it.copy(owner = bobSeat) }
    val bobField = bob.battlefield.map { it.copy(owner = bobSeat) }
    val everyId = (alice.hand + alice.battlefield + alice.library + bobHand + bobField).map { it.id.value }
    val nextId = (everyId.maxOrNull() ?: -1L) + 1

    fun seat(
        seat: PlayerId,
        board: Board,
        library: List<GameObject>,
    ) = PlayerState(
        life = STARTING_LIFE,
        library = library.toPersistentList(),
        hand = (if (seat == bobSeat) bobHand else board.hand).toPersistentList(),
        graveyard = persistentListOf(),
        priorityStatus = if (seat == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
    )

    val state =
        GameState(
            players =
                persistentMapOf(
                    aliceSeat to seat(aliceSeat, alice, alice.library),
                    bobSeat to seat(bobSeat, bob, bob.library),
                ),
            turn = Turn(aliceSeat, 3, TurnPhase.PRECOMBAT_MAIN, null),
            sharedZones =
                SharedZones(
                    battlefield = (alice.battlefield + bobField).toPersistentList(),
                    stack = persistentListOf(),
                    exile = persistentListOf(),
                ),
            nextObjectId = nextId,
            rng = Rng(0),
            events = persistentListOf(),
            definitions = definitions.toPersistentMap(),
        )
    return ScriptedGame.startFrom(state)
}
