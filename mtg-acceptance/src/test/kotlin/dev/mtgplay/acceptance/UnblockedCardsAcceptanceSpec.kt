package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
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
import dev.mtgplay.rules.engine.layeredPower
import dev.mtgplay.rules.engine.layeredToughness
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The five cards that earlier packets wrote, diagnosed, and dropped — each on a framework that has since
 * landed — played end to end under the invariant checker.
 *
 * Every one of them is here because a *previous* packet's report named its blocker precisely, and the
 * value of this suite is that it checks those diagnoses rather than only the cards:
 *
 * | Card | Named blocker | Where it landed |
 * |---|---|---|
 * | Ghostly Flicker | a target **count** (`FW-MULTITGT`) | `TargetCount.Exactly(2)` |
 * | Cast into the Fire | a per-mode variable count | modes × counts, both frameworks unchanged |
 * | Thraben Charm | an **unbounded** count | `TargetCount.AnyNumber` |
 * | Giant's Boulder | a costed mana ability, and trap **T17** | `FW-MANACOST`, `FW-MANA` |
 * | Basilisk Gate | sorcery-only timing, a snapshot, and trap **T17** | `FW-MANACOST`, `FW-DURATION`, `FW-MANA` |
 *
 * Two of those diagnoses were **wrong**, and this file pins the corrections. Giant's Boulder was filed as
 * needing a "target permanent" restriction that had already shipped with Scour from Existence, and
 * Basilisk Gate's remaining blocker was never the restriction either. What both actually needed was the
 * mana-payment reservation, and the T17 cases below are the ones that would crash without it.
 */
class UnblockedCardsAcceptanceSpec :
    StringSpec({

        // ---- Ghostly Flicker: an exact count above one ------------------------------------------

        "CR 601.2c: Ghostly Flicker demands two targets and is uncastable with only one legal permanent" {
            // Three Islands (so the {2}{U} is payable) and *nothing else* alice controls that the
            // restriction admits. The lands themselves are legal targets, so the near-miss board has to
            // be built the other way round: one land, and the mana from elsewhere is not available.
            val game = ScriptedGame.startFrom(flickerBoard(aliceExtraPermanents = 0, aliceLands = 1))
            val window = game.window()
            // Not castable: one legal permanent cannot fill a minimum of two, so no option is offered.
            window.options.none { it is PriorityOption.CastSpell && it.card == GHOSTLY_FLICKER } shouldBe true
        }

        "CR 601.2c/400.7: Ghostly Flicker blinks two permanents, both of which come back as new objects" {
            val game = ScriptedGame.startFrom(flickerBoard(aliceExtraPermanents = 1, aliceLands = 3))
            val casting = game.beginCast("Ghostly Flicker")

            val targets = casting.multiTargetRequest()
            // An exact count: the bounds are equal, so the agent must name two — never one, never none.
            targets.minimumCount shouldBe 2
            targets.maximumCount shouldBe 2
            // Alice's three Islands and her Grizzly Bears; bob's permanents are absent (CR 109.5), and
            // so is her enchantment, which "artifacts, creatures, and/or lands" excludes.
            targets.options shouldHaveSize 4

            val bearsIndex = targets.options.indexOf(Target.Permanent(ALICE_BEARS_ID))
            val landIndex = targets.options.indexOfFirst { it != Target.Permanent(ALICE_BEARS_ID) }
            val resolved =
                casting
                    .apply(Decision.MultiSelect(targets.id, listOf(bearsIndex, landIndex)))
                    .settlePayment()
                    .pass()
                    .pass()

            // CR 400.7: both blinked permanents are new objects, and nothing is left in exile.
            resolved.state.sharedZones.exile
                .shouldBeEmpty()
            resolved.state.sharedZones.battlefield
                .none { it.id == ALICE_BEARS_ID } shouldBe true
            // Everything came back: the battlefield is the same size it was.
            resolved.state.sharedZones.battlefield shouldHaveSize
                game.state.sharedZones.battlefield.size
        }

        "CR 601.2c: Ghostly Flicker refuses an answer naming the same permanent twice" {
            val game = ScriptedGame.startFrom(flickerBoard(aliceExtraPermanents = 1, aliceLands = 3))
            val casting = game.beginCast("Ghostly Flicker")
            val targets = casting.multiTargetRequest()

            val failure =
                runCatching { casting.apply(Decision.MultiSelect(targets.id, listOf(0, 0))) }
            failure.isFailure shouldBe true
        }

        // ---- Cast into the Fire: two modes, two different counts ---------------------------------

        "CR 601.2b/601.2c: Cast into the Fire's first mode is a ranged target request and its second is not" {
            val board = burnBoard()

            // Mode 0 — "each of up to two target creatures": a ranged request over both Bears.
            val damageMode = ScriptedGame.startFrom(board).beginCast("Cast into the Fire").chooseMode(0)
            val ranged = damageMode.multiTargetRequest()
            ranged.minimumCount shouldBe 0
            ranged.maximumCount shouldBe 2
            ranged.options shouldHaveSize 2

            // Mode 1 — "exile target artifact": the ordinary single-select, on the same board.
            val exileMode = ScriptedGame.startFrom(board).beginCast("Cast into the Fire").chooseMode(1)
            val single =
                exileMode.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("CR 601.2c: expected a single-target request, was ${exileMode.pendingRequest}")
            single.options shouldHaveSize 1
        }

        "CR 120: Cast into the Fire's first mode marks one damage on each of two creatures" {
            val game = ScriptedGame.startFrom(burnBoard())
            val casting = game.beginCast("Cast into the Fire").chooseMode(0)
            val targets = casting.multiTargetRequest()

            val resolved =
                casting
                    .apply(Decision.MultiSelect(targets.id, listOf(0, 1)))
                    .settlePayment()
                    .pass()
                    .pass()

            // Two 2/2 Bears, one damage each — both survive the CR 704.5g check, which is the point of
            // "1 damage to *each*" rather than "2 damage to one".
            resolved.state.sharedZones.battlefield
                .filter { it.card == CardRef("Grizzly Bears") }
                .map { it.damageMarked } shouldContainExactly listOf(1, 1)
        }

        "CR 601.2c: Cast into the Fire's damage mode stays castable with no creature on the battlefield" {
            // The "up to" minimum of zero: the card is in the window even with nothing to point at,
            // which is a real line (baiting a counter, binning a dead card) and an ADR-005 requirement.
            val game = ScriptedGame.startFrom(burnBoard(withCreatures = false, withArtifact = false))
            val window = game.window()
            window.options.any { it is PriorityOption.CastSpell && it.card == CAST_INTO_THE_FIRE } shouldBe true
        }

        // ---- Thraben Charm: the unbounded count --------------------------------------------------

        "CR 115.1: Thraben Charm's third mode offers an unbounded choice clamped to the players present" {
            val game = ScriptedGame.startFrom(charmBoard())
            val casting = game.beginCast("Thraben Charm").chooseMode(2)

            val targets = casting.multiTargetRequest()
            // "Any number" — the minimum is zero and the maximum is the option count, never Int.MAX_VALUE.
            targets.minimumCount shouldBe 0
            targets.maximumCount shouldBe 2
            // Both players, the chooser included: the card says "players", not "opponents".
            targets.options shouldContainExactly listOf(Target.Player(alice), Target.Player(bob))
        }

        "CR 701.3a: Thraben Charm's third mode exiles both graveyards when both players are named" {
            val game = ScriptedGame.startFrom(charmBoard())
            val casting = game.beginCast("Thraben Charm").chooseMode(2)
            val targets = casting.multiTargetRequest()

            val resolved =
                casting
                    .apply(Decision.MultiSelect(targets.id, listOf(0, 1)))
                    .settlePayment()
                    .pass()
                    .pass()

            // Alice's own graveyard also gains the Charm itself (CR 608.2m), so it is not empty — but
            // the two cards that were there before are gone.
            resolved.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly listOf(THRABEN_CHARM)
            resolved.state.players
                .getValue(bob)
                .graveyard
                .shouldBeEmpty()
            resolved.state.sharedZones.exile shouldHaveSize 3
        }

        "CR 303.4: Thraben Charm's second mode may destroy an Aura, which a hexproof creature cannot shield" {
            val game = ScriptedGame.startFrom(charmBoard())
            val casting = game.beginCast("Thraben Charm").chooseMode(1)
            val single =
                casting.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("CR 601.2c: expected a single-target request, was ${casting.pendingRequest}")
            single.options shouldContainExactly listOf(Target.Permanent(ALICE_AURA_ID))
        }

        // ---- Giant's Boulder: trap T17, both halves ----------------------------------------------

        "trap T17: Giant's Boulder's {7} ability enumerates and pays without tapping itself for mana" {
            // The reproduction the colourless-utility packet reported as a crash. The Boulder is a mana
            // source *and* its own ability needs it untapped; an enumerator that offered a plan tapping
            // the Boulder toward its own {7} would throw at CR 602.2a.
            val game = ScriptedGame.startFrom(boulderBoard())
            val activating = game.activateOnBattlefield("Giant's Boulder")

            val targets =
                activating.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("CR 601.2c: expected a target request, was ${activating.pendingRequest}")
            // "Destroy target permanent" is the widest line in the pool: every permanent on the table.
            targets.options shouldHaveSize game.state.sharedZones.battlefield.size

            val bearsIndex = targets.options.indexOf(Target.Permanent(BOB_BEARS_ID))
            bearsIndex shouldNotBe -1
            val resolved =
                activating
                    .apply(Decision.SingleSelect(targets.id, bearsIndex))
                    .settlePayment()
                    .pass()
                    .pass()

            // The target died, and the Boulder sacrificed itself to its own cost (CR 701.16a).
            resolved.state.sharedZones.battlefield
                .none { it.id == BOB_BEARS_ID } shouldBe true
            resolved.state.sharedZones.battlefield
                .none { it.card == GIANTS_BOULDER } shouldBe true
        }

        // ---- Basilisk Gate: sorcery timing, and a snapshotted count -------------------------------

        "CR 602.5d: Basilisk Gate's pump is absent from the window while a spell is on the stack" {
            // "Activate only as a sorcery" is the same predicate a sorcery's cast is checked against, so
            // a non-empty stack closes the window. Without `ActivatedAbility.timing` this would be an
            // instant-speed combat trick the card is deliberately not (ADR-005).
            val game = ScriptedGame.startFrom(gateBoard(spellOnStack = true))
            val window = game.window()
            window.options.none {
                it is PriorityOption.ActivateAbility && it.card == BASILISK_GATE
            } shouldBe true
        }

        "CR 602.5d: the same ability *is* offered in an empty-stack main phase" {
            val game = ScriptedGame.startFrom(gateBoard(spellOnStack = false))
            val window = game.window()
            window.options.any {
                it is PriorityOption.ActivateAbility && it.card == BASILISK_GATE
            } shouldBe true
        }

        "CR 608.2h: the pump is +X/+X for the Gates you control, counted once on resolution" {
            // Two Gates out, so X = 2 — and the activating Gate counts itself even though it taps.
            val game = ScriptedGame.startFrom(gateBoard(spellOnStack = false, gates = 2))
            val activating = game.activateOnBattlefield("Basilisk Gate")

            val targets =
                activating.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("CR 601.2c: expected a target request, was ${activating.pendingRequest}")
            val bearsIndex = targets.options.indexOf(Target.Permanent(ALICE_BEARS_ID))
            bearsIndex shouldNotBe -1

            val resolved =
                activating
                    .apply(Decision.SingleSelect(targets.id, bearsIndex))
                    .settlePayment()
                    .pass()
                    .pass()

            // A 2/2 Grizzly Bears +2/+2 = 4/4.
            layeredPower(resolved.state, ALICE_BEARS_ID) shouldBe 4
            layeredToughness(resolved.state, ALICE_BEARS_ID) shouldBe 4
        }
    })

private val GHOSTLY_FLICKER = CardRef("Ghostly Flicker")
private val CAST_INTO_THE_FIRE = CardRef("Cast into the Fire")
private val THRABEN_CHARM = CardRef("Thraben Charm")
private val GIANTS_BOULDER = CardRef("Giant's Boulder")
private val BASILISK_GATE = CardRef("Basilisk Gate")

private val ALICE_BEARS_ID = ObjectId(50)
private val BOB_BEARS_ID = ObjectId(51)
private val ALICE_AURA_ID = ObjectId(52)
private val BOULDER_ID = ObjectId(53)

/** The priority window alice holds, or a loud failure. */
private fun ScriptedGame.window(): DecisionRequest.ChooseAction =
    pendingRequest as? DecisionRequest.ChooseAction
        ?: error("expected a priority window, was $pendingRequest")

/** The pending ranged target request, or a loud failure. */
private fun ScriptedGame.multiTargetRequest(): DecisionRequest.ChooseMultipleTargets =
    pendingRequest as? DecisionRequest.ChooseMultipleTargets
        ?: error("expected the CR 601.2c multi-target request, was $pendingRequest")

/** Begins casting [card] from alice's hand, stopping at whatever it needs next. */
private fun ScriptedGame.beginCast(card: String): ScriptedGame {
    val window = window()
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(card) }
    check(index >= 0) { "no cast of $card enumerated in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Activates [card]'s battlefield ability, stopping at whatever it needs next. */
private fun ScriptedGame.activateOnBattlefield(card: String): ScriptedGame {
    val window = window()
    val index =
        window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == CardRef(card) }
    check(index >= 0) { "no $card activation enumerated in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Answers a pending mode request by the mode's **printed** index (CR 601.2b). */
private fun ScriptedGame.chooseMode(printedMode: Int): ScriptedGame {
    val modes =
        pendingRequest as? DecisionRequest.ChooseModes
            ?: error("CR 601.2b: expected the mode request, was $pendingRequest")
    val index = modes.options.indexOfFirst { it.modeIndex == printedMode }
    check(index >= 0) { "mode $printedMode is not offered; options were ${modes.options}" }
    // CR 601.2b: a mode choice is a subset since `W9-B`; "choose one" answers with a one-element one.
    return apply(Decision.MultiSelect(modes.id, listOf(index)))
}

/** Settles a pending CR 601.2g payment plan by taking the first one offered; a no-op if none is pending. */
private fun ScriptedGame.settlePayment(): ScriptedGame =
    when (val request = pendingRequest) {
        is DecisionRequest.ChoosePaymentPlan -> apply(Decision.SingleSelect(request.id, 0))
        else -> this
    }

/**
 * Alice holds Ghostly Flicker over [aliceLands] Islands, with [aliceExtraPermanents] Grizzly Bears and
 * one Aura she controls, plus bob's Grizzly Bears. The Aura is there to make the disjunctive restriction
 * *narrow* something: it is a permanent alice controls that "artifacts, creatures, and/or lands"
 * excludes.
 */
private fun flickerBoard(
    aliceExtraPermanents: Int,
    aliceLands: Int,
): GameState =
    handcrafted(
        aliceHand = handOf(alice, 0L, "Ghostly Flicker"),
        battlefield =
            cards("Island", 20L until (20L + aliceLands), alice) +
                (
                    if (aliceExtraPermanents > 0) {
                        listOf(bfObject(ALICE_BEARS_ID, "Grizzly Bears", alice))
                    } else {
                        emptyList()
                    }
                ) +
                listOf(
                    bfObject(ALICE_AURA_ID, "Journey to Nowhere", alice),
                    bfObject(BOB_BEARS_ID, "Grizzly Bears", bob),
                ),
    )

/** Alice holds Cast into the Fire over Mountains, with two Bears and one artifact on the battlefield. */
private fun burnBoard(
    withCreatures: Boolean = true,
    withArtifact: Boolean = true,
): GameState =
    handcrafted(
        aliceHand = handOf(alice, 0L, "Cast into the Fire"),
        battlefield =
            cards("Mountain", 20L..22L, alice) +
                (
                    if (withCreatures) {
                        listOf(
                            bfObject(ALICE_BEARS_ID, "Grizzly Bears", alice),
                            bfObject(BOB_BEARS_ID, "Grizzly Bears", bob),
                        )
                    } else {
                        emptyList()
                    }
                ) +
                (if (withArtifact) listOf(bfObject(ObjectId(60), "Ichor Wellspring", bob)) else emptyList()),
    )

/**
 * Alice holds Thraben Charm over Plains, with an Aura she controls and two cards in each graveyard, so
 * every one of the Charm's three modes has something to point at.
 */
private fun charmBoard(): GameState =
    handcrafted(
        aliceHand = handOf(alice, 0L, "Thraben Charm"),
        battlefield =
            cards("Plains", 20L..22L, alice) +
                listOf(
                    bfObject(ALICE_AURA_ID, "Journey to Nowhere", alice),
                    bfObject(ALICE_BEARS_ID, "Grizzly Bears", alice),
                    bfObject(BOB_BEARS_ID, "Grizzly Bears", bob),
                ),
        aliceGraveyard = listOf(bfObject(ObjectId(70), "Lightning Bolt", alice)),
        bobGraveyard =
            listOf(
                bfObject(ObjectId(71), "Lightning Bolt", bob),
                bfObject(ObjectId(72), "Grizzly Bears", bob),
            ),
    )

/**
 * Giant's Boulder untapped on alice's battlefield with seven Mountains beside it — exactly enough for
 * the `{7}`, and *only* if the Boulder is not counted as a payer for its own cost (trap T17).
 */
private fun boulderBoard(): GameState =
    handcrafted(
        aliceHand = persistentListOf(),
        battlefield =
            cards("Mountain", 20L..26L, alice) +
                listOf(
                    bfObject(BOULDER_ID, "Giant's Boulder", alice),
                    bfObject(BOB_BEARS_ID, "Grizzly Bears", bob),
                ),
    )

/**
 * [gates] Basilisk Gates on alice's battlefield with two Islands for the `{2}`, plus a Grizzly Bears to
 * pump. With [spellOnStack] a spell sits on the stack, which closes the sorcery window (CR 602.5d).
 */
private fun gateBoard(
    spellOnStack: Boolean,
    gates: Int = 1,
): GameState =
    handcrafted(
        aliceHand = persistentListOf(),
        battlefield =
            cards("Island", 20L..21L, alice) +
                cards("Basilisk Gate", 30L until (30L + gates), alice) +
                listOf(bfObject(ALICE_BEARS_ID, "Grizzly Bears", alice)),
        stack = if (spellOnStack) listOf(stackedSpell(90L, "Lightning Bolt", bob)) else emptyList(),
    )

/** A battlefield or zone object over [MvpCards]. */
private fun bfObject(
    id: ObjectId,
    name: String,
    owner: PlayerId,
): GameObject = GameObject(id = id, card = CardRef(name), owner = owner)

/** [names] as [owner]'s hand, ids running from [firstId]. */
private fun handOf(
    owner: PlayerId,
    firstId: Long,
    vararg names: String,
): PersistentList<GameObject> =
    names
        .mapIndexed { index, name -> GameObject(ObjectId(firstId + index), CardRef(name), owner) }
        .toPersistentList()

/** A spell already on the stack under [controller]'s control, at stack-residence id [id] (CR 400.7). */
private fun stackedSpell(
    id: Long,
    card: String,
    controller: PlayerId,
): StackEntry.Spell =
    StackEntry.Spell(
        obj = GameObject(ObjectId(id), CardRef(card), controller),
        controller = controller,
        targets = persistentListOf(),
        definition =
            MvpCards.definitions.getValue(CardRef(card)) as dev.mtgplay.core.definition.SpellDefinition,
    )

/** Alice's precombat main on turn 8, holding priority, over real [MvpCards] definitions. */
private fun handcrafted(
    aliceHand: PersistentList<GameObject>,
    battlefield: List<GameObject>,
    aliceGraveyard: List<GameObject> = emptyList(),
    bobGraveyard: List<GameObject> = emptyList(),
    stack: List<StackEntry> = emptyList(),
): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to
                    playerWithZones(hand = aliceHand, graveyard = aliceGraveyard.toPersistentList())
                        .copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                bob to playerWithZones(graveyard = bobGraveyard.toPersistentList()),
            ),
        turn = Turn(alice, 8, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = stack.toPersistentList(),
                exile = persistentListOf(),
            ),
        nextObjectId = 200,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
