package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `W9-G`'s two alternate castings driven end-to-end through [ScriptedGame], which invariant-checks every
 * transition: **prototype** (CR 702.160, CR 718) on Boulderbranch Golem and **cascade** (CR 702.85) on
 * Maelstrom Colossus — the two cards docs/gauntlet-deferred-ten.md named as the top of Monster Tron's
 * curve and dropped.
 *
 * Each printed clause gets a test that would fail if the clause were quietly missing, which is the whole
 * point of encoding them rather than approximating:
 * - *"with different mana cost, **color**, and **size**"* — the prototyped Golem is a `{3}{G}` **3/3**,
 *   and its own "gain life equal to its power" reads **3** rather than 6. A prototype encoded as an
 *   alternative cost alone would pass every other test and fail these two.
 * - *"exile cards … **until** you exile a nonland card that costs less"* — the dig walks past lands.
 * - *"You may cast it **without paying its mana cost**"* — a `{R}` spell is cast off an empty mana pool.
 * - *"Put the exiled cards on the bottom **in a random order**"* — the cards return, the exile zone
 *   empties, and the order comes from the match PRNG (ADR-006), so the same seed replays it.
 */
class AlternateCastingAcceptanceSpec :
    StringSpec({

        // ---- Prototype (CR 702.160, CR 718) --------------------------------------------------------

        "CR 718.3: both casts of a prototype card are enumerated — the printed one and the prototyped one" {
            // Seven Forests: the seat can afford {7} *and* {3}{G}, so ADR-005 requires both options.
            val game = ScriptedGame.startFrom(golemBoard(forests = PRINTED_COST_FORESTS))
            val window = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            val golemOptions =
                window.options.filterIsInstance<PriorityOption.CastSpell>().filter {
                    it.card == CardRef(BOULDERBRANCH_GOLEM)
                }
            golemOptions shouldHaveSize 2
            golemOptions.map { it.permission is CastingPermission.Prototype } shouldContainExactlyInAnyOrder
                listOf(false, true)
        }

        "ADR-005: on four Forests only the prototyped cast is offered — the printed {7} is unaffordable" {
            // The other direction of ADR-005, and the one that crashes rather than merely hides a line:
            // an enumerated cast whose cost cannot be paid dead-ends mid-pipeline.
            val game = ScriptedGame.startFrom(golemBoard(forests = PROTOTYPE_COST_FORESTS))
            val window = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            val golemOptions =
                window.options.filterIsInstance<PriorityOption.CastSpell>().filter {
                    it.card == CardRef(BOULDERBRANCH_GOLEM)
                }
            golemOptions shouldHaveSize 1
            (golemOptions.single().permission is CastingPermission.Prototype) shouldBe true
        }

        "CR 718.3b: a prototyped Boulderbranch Golem is a 3/3 and gains its controller 3 life" {
            // Four Forests is {3}{G} exactly — the printed {7} is unaffordable, so only the prototyped
            // cast is on offer, which is the line Monster Tron actually takes on turn four.
            val game = ScriptedGame.startFrom(golemBoard(forests = PROTOTYPE_COST_FORESTS))
            castGolem(game, prototyped = true)
            game.driveUntil {
                game.state.players
                    .getValue(alice)
                    .life != STARTING_LIFE
            }

            val golem = golemOn(game.state)
            golem.prototyped shouldBe true
            layeredPower(game.state, golem.id) shouldBe PROTOTYPE_SIZE
            layeredToughness(game.state, golem.id) shouldBe PROTOTYPE_SIZE
            // "You gain life equal to its power" — 3, read off the prototyped body (CR 718.3b).
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + PROTOTYPE_SIZE
        }

        "CR 718.3b: the same card cast for its printed {7} is a 6/5 and gains 6 life" {
            val game = ScriptedGame.startFrom(golemBoard(forests = PRINTED_COST_FORESTS))
            castGolem(game, prototyped = false)
            game.driveUntil {
                game.state.players
                    .getValue(alice)
                    .life != STARTING_LIFE
            }

            val golem = golemOn(game.state)
            golem.prototyped shouldBe false
            layeredPower(game.state, golem.id) shouldBe PRINTED_POWER
            layeredToughness(game.state, golem.id) shouldBe PRINTED_TOUGHNESS
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + PRINTED_POWER
        }

        // ---- Cascade (CR 702.85) -------------------------------------------------------------------

        "CR 702.85a: cascade digs past lands, casts the first cheaper nonland card free, and bottoms the rest" {
            val game = cascadeGame()
            castColossus(game)
            // The cascade trigger resolves, digs, and offers the Bolt it turned up.
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseYesNo }
            val offer = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()
            offer.card shouldBe CardRef(CASCADE_HIT)
            // CR 702.85a: everything down to and including the hit was exiled — the two Forests it
            // walked past and the Bolt itself.
            game.state.sharedZones.exile
                .map { it.card } shouldBe
                listOf(CardRef("Forest"), CardRef("Forest"), CardRef(CASCADE_HIT))
            game.apply(Decision.SingleSelect(offer.id, DecisionRequest.ChooseYesNo.ACCEPT))

            // The free cast runs the real CR 601 pipeline from exile: choose a target, then pay {0}.
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            game.apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Player(bob))))
            payIfAsked(game)
            game.driveUntil {
                game.state.players
                    .getValue(bob)
                    .life != STARTING_LIFE
            }

            // The Bolt was cast for nothing — every Forest is still untapped bar the eight the Colossus
            // itself spent, which is the whole board — and bob took 3.
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - CASCADE_HIT_DAMAGE
            // CR 702.85a: the cards that were *not* cast went back; the Bolt is in the graveyard.
            game.state.sharedZones.exile
                .shouldBeEmpty()
            game.state.players
                .getValue(alice)
                .library
                .takeLast(CASCADE_LANDS_WALKED)
                .map { it.card } shouldBe List(CASCADE_LANDS_WALKED) { CardRef("Forest") }
            game.state.events.filterIsInstance<GameEvent.CardsPutOnBottomInRandomOrder>() shouldHaveSize 1
        }

        "CR 702.85a: a declined cascade bottoms every exiled card, including the one it offered" {
            val game = cascadeGame()
            castColossus(game)
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseYesNo }
            val offer = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()
            game.apply(Decision.SingleSelect(offer.id, DecisionRequest.ChooseYesNo.DECLINE))

            // Nothing was cast, so all three exiled cards are back in the library and bob is untouched.
            game.state.sharedZones.exile
                .shouldBeEmpty()
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE
            game.state.players
                .getValue(alice)
                .library
                .takeLast(CASCADE_EXILED_TOTAL)
                .map { it.card } shouldContainExactlyInAnyOrder
                listOf(CardRef("Forest"), CardRef("Forest"), CardRef(CASCADE_HIT))
            game.state.players
                .getValue(alice)
                .library shouldHaveSize CASCADE_LIBRARY_SIZE
        }

        "ADR-006: the random bottom order is seeded — the same seed reproduces it, and it is drawn from the PRNG" {
            val orders = listOf(CASCADE_SEED, CASCADE_SEED).map { declinedBottomOrder(it) }
            orders[0] shouldBe orders[1]
            // The draw consumed match entropy: the generator moved (ADR-006 — there is no other source).
            val before = cascadeState(CASCADE_SEED).rng
            val game = cascadeGame(CASCADE_SEED)
            castColossus(game)
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseYesNo }
            val offer = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()
            game.apply(Decision.SingleSelect(offer.id, DecisionRequest.ChooseYesNo.DECLINE))
            (game.state.rng == before) shouldBe false
        }
    })

// ---- the cards under test ------------------------------------------------------------------------------

private const val BOULDERBRANCH_GOLEM: String = "Boulderbranch Golem"
private const val MAELSTROM_COLOSSUS: String = "Maelstrom Colossus"

/** The nonland card cascade turns up on the pinned library: `{R}`, mana value 1, well under the `{8}`. */
private const val CASCADE_HIT: String = "Lightning Bolt"

private const val PROTOTYPE_SIZE: Int = 3
private const val PRINTED_POWER: Int = 6
private const val PRINTED_TOUGHNESS: Int = 5

/** Forests for the prototyped `{3}{G}` cast — four mana, one of them green. */
private const val PROTOTYPE_COST_FORESTS: Int = 4

/** Forests for the printed `{7}` cast. */
private const val PRINTED_COST_FORESTS: Int = 7

/** Forests for the `{8}` Colossus. */
private const val CASCADE_COST_FORESTS: Int = 8

private const val CASCADE_HIT_DAMAGE: Int = 3

/** The lands cascade walks past on the pinned library before it meets the Bolt. */
private const val CASCADE_LANDS_WALKED: Int = 2

/** Every card the pinned cascade exiles: the two lands plus the hit. */
private const val CASCADE_EXILED_TOTAL: Int = 3

/** The pinned library's size — unchanged by a declined cascade, which puts everything back. */
private const val CASCADE_LIBRARY_SIZE: Int = 5

private const val CASCADE_SEED: Long = 0xC45CADE

/** The turn the pinned boards resume on — late enough that nothing is summoning sick. */
private const val BOARD_TURN: Int = 8

// ---- boards --------------------------------------------------------------------------------------------

private fun settled(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice).copy(summoningSick = false)

/**
 * Alice on [forests] untapped Forests with a Boulderbranch Golem in hand, holding priority in her own
 * precombat main phase. Four Forests offer only the prototyped cast; seven offer both.
 */
private fun golemBoard(forests: Int): GameState =
    aliceBoard(
        battlefield = List(forests) { settled(it.toLong(), "Forest") },
        hand = listOf(settled(HAND_ID, BOULDERBRANCH_GOLEM)),
        library = listOf(settled(LIBRARY_BASE, "Forest")),
        seed = 0,
    )

/**
 * Alice on eight Forests with a Maelstrom Colossus in hand and a **pinned** library: two Forests on top,
 * then a Lightning Bolt, then two more Forests. Cascade must walk past the two lands (CR 702.85a's
 * "nonland card") and stop at the Bolt, whose mana value 1 is under the Colossus's 8.
 */
private fun cascadeState(seed: Long): GameState =
    aliceBoard(
        battlefield = List(CASCADE_COST_FORESTS) { settled(it.toLong(), "Forest") },
        hand = listOf(settled(HAND_ID, MAELSTROM_COLOSSUS)),
        library =
            listOf(
                settled(LIBRARY_BASE, "Forest"),
                settled(LIBRARY_BASE + 1, "Forest"),
                settled(LIBRARY_BASE + 2, CASCADE_HIT),
                settled(LIBRARY_BASE + 3, "Forest"),
                settled(LIBRARY_BASE + 4, "Forest"),
            ),
        seed = seed,
    )

private fun cascadeGame(seed: Long = CASCADE_SEED): ScriptedGame = ScriptedGame.startFrom(cascadeState(seed))

private const val HAND_ID: Long = 50
private const val LIBRARY_BASE: Long = 60

/** A one-sided board with alice holding priority in her own precombat main phase (CR 117.1a). */
private fun aliceBoard(
    battlefield: List<GameObject>,
    hand: List<GameObject>,
    library: List<GameObject>,
    seed: Long,
): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = library.toPersistentList(),
                        hand = hand.toPersistentList(),
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(GameObject(ObjectId(999), CardRef("Mountain"), bob)),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.NONE,
                    ),
            ),
        turn = Turn(alice, BOARD_TURN, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(battlefield.toPersistentList(), persistentListOf(), persistentListOf()),
        nextObjectId = 1000,
        rng = Rng(seed),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )

// ---- driving helpers ------------------------------------------------------------------------------------

/** The Boulderbranch Golem on the battlefield. */
private fun golemOn(state: GameState): GameObject =
    state.sharedZones.battlefield.single { it.card == CardRef(BOULDERBRANCH_GOLEM) }

/** Casts the Golem, choosing the prototyped option or the printed one, and pays the first offered plan. */
private fun castGolem(
    game: ScriptedGame,
    prototyped: Boolean,
) {
    val window = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell &&
                it.card == CardRef(BOULDERBRANCH_GOLEM) &&
                (it.permission is CastingPermission.Prototype) == prototyped
        }
    check(index >= 0) { "no ${if (prototyped) "prototyped" else "printed"} cast offered: ${window.options}" }
    game.apply(Decision.SingleSelect(window.id, index))
    payIfAsked(game)
}

/** Casts the Colossus for its printed `{8}`. */
private fun castColossus(game: ScriptedGame) {
    val window = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
    val index =
        window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(MAELSTROM_COLOSSUS) }
    check(index >= 0) { "no cast offered for $MAELSTROM_COLOSSUS: ${window.options}" }
    game.apply(Decision.SingleSelect(window.id, index))
    payIfAsked(game)
}

/**
 * Answers a [DecisionRequest.ChoosePaymentPlan] with its first plan when one is pending. A `{0}` cost
 * still surfaces one — the single empty plan — so the free cast goes through the same door as any other.
 */
private fun payIfAsked(game: ScriptedGame) {
    val request = game.pendingRequest
    if (request is DecisionRequest.ChoosePaymentPlan) {
        game.apply(Decision.SingleSelect(request.id, 0))
    }
}

/** The order a declined cascade put its exiled cards back in, for a given [seed]. */
private fun declinedBottomOrder(seed: Long): List<CardRef> {
    val game = cascadeGame(seed)
    castColossus(game)
    game.driveUntil { game.pendingRequest is DecisionRequest.ChooseYesNo }
    val offer = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()
    game.apply(Decision.SingleSelect(offer.id, DecisionRequest.ChooseYesNo.DECLINE))
    return game.state.players
        .getValue(alice)
        .library
        .takeLast(CASCADE_EXILED_TOTAL)
        .map { it.card }
}

private const val MAX_DRIVE_STEPS: Int = 40

private fun ScriptedGame.driveUntil(predicate: () -> Boolean): ScriptedGame {
    var steps = 0
    while (!predicate() && !isOver && steps < MAX_DRIVE_STEPS) {
        passOrOrder()
        steps++
    }
    check(predicate()) { "the drive predicate was not satisfied within $MAX_DRIVE_STEPS steps" }
    return this
}

private fun ScriptedGame.passOrOrder(): ScriptedGame =
    when (val request = pendingRequest) {
        is DecisionRequest.ChooseAction -> {
            val pass = request.options.indexOfFirst { it is PriorityOption.Pass }
            apply(Decision.SingleSelect(request.id, pass))
        }
        is DecisionRequest.OrderTriggers -> apply(Decision.MultiSelect(request.id, request.options.indices.toList()))
        else -> error("the drive helper only passes priority and orders triggers, but met: $request")
    }
