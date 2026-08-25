package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.event.GameEvent
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
import dev.mtgplay.rules.StackEntryView
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.viewFor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `FW-ZONETGT` end to end on real cards (docs/design/graveyard-targeting.md §7): **Archaeomancer**,
 * whose enters-the-battlefield trigger returns a targeted instant or sorcery card from *your* graveyard
 * to your hand, and **Pulse of Murasa**, an instant that returns a targeted creature or land card from
 * *a* graveyard to its owner's hand and gains 6 life.
 *
 * Between them they exercise both axes the new spec carries — the [dev.mtgplay.core.definition.GraveyardCardRestriction]
 * noun and the [dev.mtgplay.core.definition.GraveyardScope] possessive — on both the trigger path
 * (CR 603.3d) and the cast path (CR 601.2c), which are the two sites a target choice can be made.
 *
 * The third case is the ADR-007 pin. It asserts **both halves together**: that the option list a seat is
 * offered names only cards in a *public* zone (CR 400.2 — a graveyard), and that both seats' `SeatView`
 * card tables already describe every card that list names. If the enumeration were ever widened to a
 * hidden zone the first half fails; if `visibleCardRefs` ever stopped feeding both graveyards into
 * `SeatView.cards` the second does. Neither half is meaningful without the other, which is why they are
 * one test.
 *
 * The whole game runs under the invariant checker.
 */
class GraveyardTargetingAcceptanceSpec :
    StringSpec({

        "CR 603.3d/404: Archaeomancer's trigger targets an instant or sorcery in its controller's graveyard" {
            val game = ScriptedGame.startFrom(graveyardStartState())
            val paid = game.castFromHand("Archaeomancer")

            // Both pass; the creature spell resolves, enters, and fires its ETB trigger (CR 603.6a).
            val resolving = paid.pass().pass()

            // CR 603.3d: the engine pauses as the ability is put on the stack, for a target choice whose
            // options are cards in a graveyard — a zone the pool has never targeted before.
            val targets =
                resolving.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("expected the CR 603.3d targets request, was ${resolving.pendingRequest}")
            targets.seat shouldBe alice
            targets.card shouldBe CardRef("Archaeomancer")

            // GraveyardScope.YOURS: only alice's own graveyard, and only its instant/sorcery cards. Her
            // Grizzly Bears is declined by the restriction; bob's Lightning Bolt by the scope.
            targets.options shouldContainExactly
                listOf(
                    Target.CardInGraveyard(resolving.state.graveyardCard("Lightning Bolt", alice).id),
                    Target.CardInGraveyard(resolving.state.graveyardCard("Faithless Looting", alice).id),
                )
            targets.options shouldNotContain
                Target.CardInGraveyard(resolving.state.graveyardCard("Grizzly Bears", alice).id)
            targets.options shouldNotContain
                Target.CardInGraveyard(resolving.state.graveyardCard("Lightning Bolt", bob).id)

            val onStack = resolving.apply(Decision.SingleSelect(targets.id, 0))
            val boltId = onStack.state.graveyardCard("Lightning Bolt", alice).id
            val entry =
                onStack.state.sharedZones.stack
                    .single() as StackEntry.Ability
            entry.targets shouldContainExactly listOf(Target.CardInGraveyard(boltId))

            // ADR-007: a graveyard is a public zone (CR 400.2), so *both* seats see the chosen target on
            // the stack entry view — the same disclosure a spell-on-stack target gets.
            listOf(alice, bob).forEach { seat ->
                val view = viewFor(onStack.state, seat).stack.single()
                (view as StackEntryView.TriggeredAbilityOnStack).targets shouldContainExactly
                    listOf(Target.CardInGraveyard(boltId))
            }

            val resolved = onStack.pass().pass()
            // CR 400.7: the Bolt left the graveyard for the hand as a *new* object, joining the
            // still-uncast Pulse of Murasa there.
            resolved.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly
                listOf(CardRef("Pulse of Murasa"), CardRef("Lightning Bolt"))
            resolved.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly
                listOf(CardRef("Faithless Looting"), CardRef("Grizzly Bears"))
            resolved.state.events
                .filterIsInstance<GameEvent.AbilityFizzled>()
                .shouldBeEmpty()
        }

        "CR 601.2c/404: Pulse of Murasa targets a creature or land card in *either* graveyard" {
            val game = ScriptedGame.startFrom(graveyardStartState())

            val window =
                game.pendingRequest as? DecisionRequest.ChooseAction
                    ?: error("expected a priority window, was ${game.pendingRequest}")
            val castIndex =
                window.options.indexOfFirst {
                    it is PriorityOption.CastSpell && it.card == CardRef("Pulse of Murasa")
                }
            check(castIndex >= 0) { "no Pulse of Murasa cast enumerated in ${window.options}" }
            val casting = game.apply(Decision.SingleSelect(window.id, castIndex))

            // CR 601.2c: the *spell* targets, so the target choice comes before the payment plan.
            val targets =
                casting.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("expected the CR 601.2c targets request, was ${casting.pendingRequest}")

            // GraveyardScope.ANY: alice's creature card *and* bob's, in turn order; the instants and the
            // sorcery in both graveyards are declined by CREATURE_OR_LAND.
            targets.options shouldContainExactly
                listOf(
                    Target.CardInGraveyard(casting.state.graveyardCard("Grizzly Bears", alice).id),
                    Target.CardInGraveyard(casting.state.graveyardCard("Hill Giant", bob).id),
                )

            // Choose *bob's* Hill Giant: "to its owner's hand" means bob's hand, not the caster's.
            val chosen = casting.apply(Decision.SingleSelect(targets.id, 1))
            val payment =
                chosen.pendingRequest as? DecisionRequest.ChoosePaymentPlan
                    ?: error("expected the CR 601.2g payment request, was ${chosen.pendingRequest}")
            val resolved =
                chosen
                    .apply(Decision.SingleSelect(payment.id, 0))
                    .pass()
                    .pass()

            resolved.state.players
                .getValue(bob)
                .hand
                .map { it.card } shouldContainExactly listOf(CardRef("Hill Giant"))
            resolved.state.players
                .getValue(alice)
                .hand
                .map { it.card }
                .shouldNotContain(CardRef("Hill Giant"))
            // CR 119.3: and the *caster* gains the 6 life, whoever owned the card.
            resolved.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + PULSE_LIFEGAIN
            resolved.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE
        }

        "ADR-005/ADR-007: every graveyard-card option is public, and both seats' card tables name it" {
            val game = ScriptedGame.startFrom(graveyardStartState())
            val casting = game.castTargeting("Pulse of Murasa")
            val targets =
                casting.pendingRequest as? DecisionRequest.ChooseTargets
                    ?: error("expected the CR 601.2c targets request, was ${casting.pendingRequest}")
            val state = casting.state

            // Half one — ADR-005: every option names an object in a *graveyard*, and no option names an
            // object in a library or a hand. CR 400.2 is the whole reason the list needs no filtering, so
            // the property is stated over the zone rather than over the target type.
            val hiddenZoneIds =
                state.players.values
                    .flatMap { it.library + it.hand }
                    .mapTo(mutableSetOf()) { it.id }
            val graveyardIds =
                state.players.values
                    .flatMap { it.graveyard }
                    .associateBy { it.id }
            targets.options shouldHaveSize 2
            targets.options.forEach { option ->
                val card = option as Target.CardInGraveyard
                graveyardIds.containsKey(card.id) shouldBe true
                hiddenZoneIds shouldNotContain card.id
            }

            // Half two — ADR-007: because every option is public, *both* seats' SeatView card tables
            // already describe it. The non-deciding seat is the load-bearing one: it never receives the
            // request, but it must still be able to name what the deciding seat was offered, or the two
            // halves have drifted.
            listOf(alice, bob).forEach { seat ->
                val cards = viewFor(state, seat).cards
                targets.options.forEach { option ->
                    val ref = graveyardIds.getValue((option as Target.CardInGraveyard).id).card
                    cards.containsKey(ref) shouldBe true
                }
            }
            // And the deciding seat's own request really is withheld from the other seat, so the pin
            // above is about the *card identities* being public, not about the request leaking.
            viewFor(state, bob).pendingDecision.shouldNotBeNull()
        }
    })

/** The life Pulse of Murasa's controller gains (CR 119.3), mirrored here so the test states its own number. */
private const val PULSE_LIFEGAIN: Int = 6

/** Casts [name] from alice's hand and settles its payment plan, returning the paid-but-unresolved game. */
private fun ScriptedGame.castFromHand(name: String): ScriptedGame {
    val window =
        pendingRequest as? DecisionRequest.ChooseAction
            ?: error("expected a priority window, was $pendingRequest")
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(name) }
    check(index >= 0) { "no $name cast enumerated in ${window.options}" }
    val casting = apply(Decision.SingleSelect(window.id, index))
    val payment =
        casting.pendingRequest as? DecisionRequest.ChoosePaymentPlan
            ?: error("expected the CR 601.2g payment request, was ${casting.pendingRequest}")
    return casting.apply(Decision.SingleSelect(payment.id, 0))
}

/** Begins casting [name] from alice's hand, stopping at its CR 601.2c target request. */
private fun ScriptedGame.castTargeting(name: String): ScriptedGame {
    val window =
        pendingRequest as? DecisionRequest.ChooseAction
            ?: error("expected a priority window, was $pendingRequest")
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(name) }
    check(index >= 0) { "no $name cast enumerated in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
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

/**
 * A paused state for the demonstrations: alice's precombat main on turn 8, holding Archaeomancer and
 * Pulse of Murasa with enough untapped lands for either, and a deliberately mixed pair of graveyards —
 * alice has an instant, a sorcery, and a creature card; bob has an instant and a creature card. Every
 * restriction/scope pairing the two cards print therefore has both a match and a near-miss to decline.
 * Real [MvpCards] definitions throughout.
 */
private fun graveyardStartState(): GameState {
    val hand =
        persistentListOf(
            GameObject(ObjectId(1), CardRef("Archaeomancer"), alice),
            GameObject(ObjectId(2), CardRef("Pulse of Murasa"), alice),
        )
    // Islands fund Archaeomancer's {2}{U}{U}; the Forest and two Islands fund Pulse of Murasa's {2}{G}.
    val aliceLands = cards("Island", 3L..7L, alice) + cards("Forest", 8L..8L, alice)
    val bobLand = GameObject(ObjectId(9), CardRef("Island"), bob)

    val aliceGraveyard =
        listOf(
            CardRef("Lightning Bolt"),
            CardRef("Faithless Looting"),
            CardRef("Grizzly Bears"),
        ).mapIndexed { index, card -> GameObject(ObjectId(10L + index), card, alice) }
            .toPersistentList()
    val bobGraveyard =
        listOf(
            CardRef("Lightning Bolt"),
            CardRef("Hill Giant"),
        ).mapIndexed { index, card -> GameObject(ObjectId(20L + index), card, bob) }
            .toPersistentList()

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
                battlefield = (aliceLands + bobLand).toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
