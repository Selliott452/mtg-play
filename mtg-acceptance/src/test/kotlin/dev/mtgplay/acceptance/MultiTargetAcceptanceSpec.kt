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
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.engine.layeredPower
import dev.mtgplay.rules.engine.layeredToughness
import dev.mtgplay.rules.viewFor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `FW-MULTITGT` end to end on real cards (docs/design/multi-target.md §8), across both halves of the
 * packet.
 *
 * The **multi-target** half is **Faerie Macabre** ("Discard this card: Exile up to two target cards
 * from graveyards") and **Blood Fountain** ("`{3}{B}`, `{T}`, Sacrifice this artifact: Return up to two
 * target creature cards from your graveyard to your hand"). Between them they exercise every branch the
 * count opens: taking the maximum, taking fewer, taking **none**, and being refused an illegal
 * combination. Faerie Macabre also carries the packet's sharpest case — two targets in *different*
 * players' graveyards, so CR 601.2c's same-object rule cannot be a positional check.
 *
 * The **control-restricted** half is **Tamiyo's Safekeeping** ("target permanent you control") and
 * **Brinebarrow Intruder** ("target creature an opponent controls"), the two cards the packet's new
 * [dev.mtgplay.core.definition.PermanentRestriction] members land. They are here rather than in a
 * separate spec because what they demonstrate is the same property the multi-target work leans on: an
 * option list that differs by *who is deciding*, on a board neither seat's view of which differs.
 *
 * Every game runs under the invariant checker.
 */
class MultiTargetAcceptanceSpec :
    StringSpec({

        "CR 601.2c/701.3a: Faerie Macabre exiles two target cards from two different graveyards" {
            val game = ScriptedGame.startFrom(multiTargetStartState())
            val activating = game.activateFromHand("Faerie Macabre")

            // CR 602.2b, following CR 601.2c: targets are chosen before the cost is paid — so the Faerie
            // is still in hand and is not among its own options.
            val targets = activating.multiTargetRequest()
            targets.seat shouldBe alice
            targets.card shouldBe CardRef("Faerie Macabre")
            // "Up to two" (CR 115.1) with five legal cards: nought, one, or two.
            targets.minimumCount shouldBe 0
            targets.maximumCount shouldBe 2

            // "Cards from graveyards" is the widest line in the pool: both seats, every printed type.
            val aliceBolt = activating.state.graveyardCard("Lightning Bolt", alice).id
            val bobBears = activating.state.graveyardCard("Grizzly Bears", bob).id
            targets.options shouldContainExactly
                listOf(
                    Target.CardInGraveyard(aliceBolt),
                    Target.CardInGraveyard(activating.state.graveyardCard("Island", alice).id),
                    Target.CardInGraveyard(activating.state.graveyardCard("Grizzly Bears", alice).id),
                    Target.CardInGraveyard(activating.state.graveyardCard("Lightning Bolt", bob).id),
                    Target.CardInGraveyard(bobBears),
                )

            // One target from each graveyard — the case a positional same-object check would miss.
            val chosen =
                listOf(
                    targets.options.indexOf(Target.CardInGraveyard(aliceBolt)),
                    targets.options.indexOf(Target.CardInGraveyard(bobBears)),
                )
            val resolved = activating.apply(Decision.MultiSelect(targets.id, chosen)).pass().pass()

            // CR 400.7: both cards left their graveyards for exile as new objects.
            resolved.state.players
                .getValue(alice)
                .graveyard
                // CR 701.8: the Faerie itself is here, put into the graveyard by the ability's *cost* —
                // after targets were chosen, which is why it was never one of its own options.
                .map { it.card } shouldContainExactly
                listOf(CardRef("Island"), CardRef("Grizzly Bears"), CardRef("Faerie Macabre"))
            resolved.state.players
                .getValue(bob)
                .graveyard
                .map { it.card } shouldContainExactly listOf(CardRef("Lightning Bolt"))
            // The two exiled cards, plus the Faerie the cost discarded... which went to the *graveyard*.
            resolved.state.sharedZones.exile
                .map { it.card } shouldContainExactly
                listOf(CardRef("Lightning Bolt"), CardRef("Grizzly Bears"))
            // CR 701.8: "Discard this card" is a cost, so the Faerie itself is in alice's graveyard —
            // it arrived *after* targets were chosen, which is why it was never one of them.
            resolved.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldNotContain CardRef("Faerie Macabre")
        }

        "CR 608.2b: Faerie Macabre may decline both targets, and the ability still resolves" {
            val game = ScriptedGame.startFrom(multiTargetStartState())
            val activating = game.activateFromHand("Faerie Macabre")
            val targets = activating.multiTargetRequest()

            // The empty answer is legal because the minimum is zero (CR 115.1).
            val resolved = activating.apply(Decision.MultiSelect(targets.id, emptyList())).pass().pass()

            // Nothing was exiled — and, crucially, the ability *resolved* rather than fizzling: an
            // "up to N" object with no targets has no illegal target (CR 608.2b).
            resolved.state.sharedZones.exile
                .shouldBeEmpty()
            resolved.state.players
                .getValue(alice)
                .graveyard
                .map { it.card }
                .size shouldBe THREE_PLUS_THE_DISCARDED_FAERIE
        }

        "CR 601.2c: the same card can't be chosen twice for one instance of the word 'target'" {
            val game = ScriptedGame.startFrom(multiTargetStartState())
            val activating = game.activateFromHand("Faerie Macabre")
            val targets = activating.multiTargetRequest()

            // The whole point of ADR-005: an agent cannot express an illegal combination, and trying is
            // a loud failure rather than a silently deduplicated exile of one card.
            shouldThrow<IllegalArgumentException> {
                activating.apply(Decision.MultiSelect(targets.id, listOf(0, 0)))
            }
            // Three targets is likewise refused, from the other bound.
            shouldThrow<IllegalArgumentException> {
                activating.apply(Decision.MultiSelect(targets.id, listOf(0, 1, 2)))
            }
        }

        "CR 602.2b/400.7: Blood Fountain returns two target creature cards from its controller's graveyard" {
            val game = ScriptedGame.startFrom(multiTargetStartState(bloodFountainOnBattlefield = true))
            val activating = game.activateOnBattlefield("Blood Fountain")

            val targets = activating.multiTargetRequest()
            targets.minimumCount shouldBe 0
            targets.maximumCount shouldBe 1
            // "Creature cards from *your* graveyard": alice's Bears alone. Her Bolt and her Island are
            // declined by the restriction, and both of bob's cards by the scope — so the maximum the
            // request offers is clamped from the printed two down to the one card on the board.
            targets.options shouldContainExactly
                listOf(Target.CardInGraveyard(activating.state.graveyardCard("Grizzly Bears", alice).id))

            val paying = activating.apply(Decision.MultiSelect(targets.id, listOf(0)))
            val payment =
                paying.pendingRequest as? DecisionRequest.ChoosePaymentPlan
                    ?: error("expected the CR 602.2b payment request, was ${paying.pendingRequest}")
            val resolved = paying.apply(Decision.SingleSelect(payment.id, 0)).pass().pass()

            resolved.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly
                listOf(
                    CardRef("Faerie Macabre"),
                    CardRef("Tamiyo's Safekeeping"),
                    CardRef("Brinebarrow Intruder"),
                    CardRef("Grizzly Bears"),
                )
            // The Fountain paid its own sacrifice cost, so it is in the graveyard beside what it left.
            resolved.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly
                listOf(CardRef("Lightning Bolt"), CardRef("Island"), CardRef("Blood Fountain"))
        }

        "CR 108.4/611.2: Tamiyo's Safekeeping targets only its caster's own permanents" {
            val game = ScriptedGame.startFrom(multiTargetStartState())
            val casting = game.castTargeting("Tamiyo's Safekeeping")

            // A one-target line, so the single-select request kind is unchanged by this framework.
            val targets =
                casting.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("expected the CR 601.2c targets request, was ${casting.pendingRequest}")
            // Every permanent alice controls — her lands and her Bears — and none of bob's.
            targets.options shouldContainExactly
                alicePermanentIds(casting.state).map { Target.Permanent(it) }
            targets.options shouldNotContain Target.Permanent(BOB_BEARS_ID)

            val chosen = targets.options.indexOf(Target.Permanent(ALICE_BEARS_ID))
            val paying = casting.apply(Decision.SingleSelect(targets.id, chosen))
            val payment =
                paying.pendingRequest as? DecisionRequest.ChoosePaymentPlan
                    ?: error("expected the CR 601.2g payment request, was ${paying.pendingRequest}")
            val resolved = paying.apply(Decision.SingleSelect(payment.id, 0)).pass().pass()

            // CR 613.3 layer 6: one effect granting both keywords, with one timestamp.
            val effect = resolved.state.timedEffects.single()
            effect.affected shouldBe ALICE_BEARS_ID
            effect.modification.grantedKeywords.size shouldBe 2
            resolved.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + TAMIYO_LIFEGAIN
        }

        "CR 102.1/613.3: Brinebarrow Intruder's trigger shrinks a creature an opponent controls" {
            val game = ScriptedGame.startFrom(multiTargetStartState())
            // CR 702.8a: flash — the creature is castable at instant speed, which is what makes it a trick.
            val resolving = game.castFromHand("Brinebarrow Intruder").pass().pass()

            // CR 603.3d: the enters trigger chooses its target as it is put on the stack.
            val targets =
                resolving.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("expected the CR 603.3d targets request, was ${resolving.pendingRequest}")
            // Only bob's creature: alice's own Bears is excluded by the restriction, and the Intruder
            // that just entered is alice's too.
            targets.options shouldContainExactly listOf(Target.Permanent(BOB_BEARS_ID))

            val resolved = resolving.apply(Decision.SingleSelect(targets.id, 0)).pass().pass()

            // "-2/-0": power drops, toughness does not, so the 2/2 survives and deals nothing.
            layeredPower(resolved.state, BOB_BEARS_ID) shouldBe 0
            layeredToughness(resolved.state, BOB_BEARS_ID) shouldBe 2
            // ADR-007: a running continuous effect is public, so both seats see it unfiltered.
            listOf(alice, bob).forEach { seat ->
                viewFor(resolved.state, seat).timedEffects shouldContainExactly resolved.state.timedEffects
            }
        }
    })

/** The life Tamiyo's Safekeeping's controller gains (CR 119.3), restated so the test owns its number. */
private const val TAMIYO_LIFEGAIN: Int = 2

/** alice's three graveyard cards plus the Faerie her ability's cost discarded (CR 701.8). */
private const val THREE_PLUS_THE_DISCARDED_FAERIE: Int = 4

private val ALICE_BEARS_ID = ObjectId(40)
private val BOB_BEARS_ID = ObjectId(41)
private val BLOOD_FOUNTAIN_ID = ObjectId(42)

/** The pending multi-target request, or a loud failure naming what was actually pending. */
private fun ScriptedGame.multiTargetRequest(): DecisionRequest.ChooseMultipleTargets =
    pendingRequest as? DecisionRequest.ChooseMultipleTargets
        ?: error("expected the CR 601.2c multi-target request, was $pendingRequest")

/** The priority window alice holds, or a loud failure. */
private fun ScriptedGame.window(): DecisionRequest.ChooseAction =
    pendingRequest as? DecisionRequest.ChooseAction
        ?: error("expected a priority window, was $pendingRequest")

/** Activates [name]'s hand-scoped ability (CR 113.6c), stopping at its target request. */
private fun ScriptedGame.activateFromHand(name: String): ScriptedGame = activateOnBattlefield(name)

/** Activates [name]'s activated ability, stopping at the first choice it needs. */
private fun ScriptedGame.activateOnBattlefield(name: String): ScriptedGame {
    val window = window()
    val index =
        window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == CardRef(name) }
    check(index >= 0) { "no $name activation enumerated in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Begins casting [name] from alice's hand, stopping at its CR 601.2c target request. */
private fun ScriptedGame.castTargeting(name: String): ScriptedGame {
    val window = window()
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(name) }
    check(index >= 0) { "no $name cast enumerated in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Casts [name] from alice's hand and settles its payment plan, returning the paid-but-unresolved game. */
private fun ScriptedGame.castFromHand(name: String): ScriptedGame {
    val casting = castTargeting(name)
    val payment =
        casting.pendingRequest as? DecisionRequest.ChoosePaymentPlan
            ?: error("expected the CR 601.2g payment request, was ${casting.pendingRequest}")
    return casting.apply(Decision.SingleSelect(payment.id, 0))
}

/** The graveyard object with card [name] owned by [owner]; both seats hold same-named cards here. */
private fun GameState.graveyardCard(
    name: String,
    owner: PlayerId,
): GameObject =
    players
        .getValue(owner)
        .graveyard
        .first { it.card == CardRef(name) }

/** Every battlefield object alice owns, in battlefield order — the "you control" enumeration's answer. */
private fun alicePermanentIds(state: GameState): List<ObjectId> =
    state.sharedZones.battlefield
        .filter { it.owner == alice }
        .map { it.id }

/**
 * The shared board: alice's precombat main on turn 8, holding Faerie Macabre, Tamiyo's Safekeeping and
 * Brinebarrow Intruder with enough untapped lands for any of them, a Grizzly Bears each on the
 * battlefield, and a deliberately mixed pair of graveyards — alice has an instant, a land and a creature
 * card, bob an instant and a creature card. Every restriction and scope the four cards print therefore
 * has both a match and a near-miss to decline. Real [MvpCards] definitions throughout.
 *
 * With [bloodFountainOnBattlefield] the Fountain is already on the battlefield untapped, so its
 * activated ability is reachable without first casting it and waiting out its enters trigger.
 */
private fun multiTargetStartState(bloodFountainOnBattlefield: Boolean = false): GameState {
    val hand =
        persistentListOf(
            GameObject(ObjectId(1), CardRef("Faerie Macabre"), alice),
            GameObject(ObjectId(2), CardRef("Tamiyo's Safekeeping"), alice),
            GameObject(ObjectId(3), CardRef("Brinebarrow Intruder"), alice),
        )
    // Islands fund Brinebarrow Intruder's {U} and Blood Fountain's {3}{B}; the Forest Tamiyo's {G}.
    val aliceLands =
        cards("Island", 4L..8L, alice) + cards("Forest", 9L..9L, alice) + cards("Swamp", 10L..10L, alice)
    val bobLand = GameObject(ObjectId(11), CardRef("Island"), bob)

    val aliceGraveyard =
        listOf(CardRef("Lightning Bolt"), CardRef("Island"), CardRef("Grizzly Bears"))
            .mapIndexed { index, card -> GameObject(ObjectId(20L + index), card, alice) }
            .toPersistentList()
    val bobGraveyard =
        listOf(CardRef("Lightning Bolt"), CardRef("Grizzly Bears"))
            .mapIndexed { index, card -> GameObject(ObjectId(30L + index), card, bob) }
            .toPersistentList()

    val battlefield =
        aliceLands + bobLand +
            listOf(
                GameObject(ALICE_BEARS_ID, CardRef("Grizzly Bears"), alice),
                GameObject(BOB_BEARS_ID, CardRef("Grizzly Bears"), bob),
            ) +
            if (bloodFountainOnBattlefield) {
                listOf(GameObject(BLOOD_FOUNTAIN_ID, CardRef("Blood Fountain"), alice))
            } else {
                emptyList()
            }

    return GameState(
        players =
            persistentMapOf(
                alice to
                    playerWithZones(hand = hand, graveyard = aliceGraveyard)
                        .copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                bob to playerWithZones(graveyard = bobGraveyard),
            ),
        turn = Turn(alice, 8, TurnPhase.PRECOMBAT_MAIN, null),
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
