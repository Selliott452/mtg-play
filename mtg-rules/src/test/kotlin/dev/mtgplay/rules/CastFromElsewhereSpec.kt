package dev.mtgplay.rules

import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
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
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.engine.discardApplyingReplacements
import dev.mtgplay.rules.engine.player
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P5.2 cast-from-elsewhere and replacement frameworks (CR 614/616, CR 702.34/35/139) exercised at
 * the rules level with fixtures (`mtg-rules` names no real card): madness's discard→exile replacement
 * and reflexive cast, flashback's graveyard cast and leave-stack exile (resolution and fizzle), escape's
 * graveyard cast with the exile-two additional cost, and the CR 616.1 ordering choice — plus enumeration
 * completeness in both directions.
 */
class CastFromElsewhereSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        // ---- Madness replacement (CR 702.35a, CR 614.5) --------------------------------------------

        "CR 702.35a: a discarded madness card is exiled instead, marked, with a reflexive trigger queued" {
            val state = elsewhereState(aliceHand = listOf("Fixture Fiery Temper"))
            val fieryId = handIdOf(state, alice, "Fixture Fiery Temper")
            val after = discardApplyingReplacements(state, alice, fieryId)

            // CR 702.35a: exiled instead of discarded — nothing reached the graveyard.
            after.player(alice).hand.shouldBeEmpty()
            after.player(alice).graveyard.shouldBeEmpty()
            // CR 614.5: applied exactly once — a single marked exile object.
            after.sharedZones.exile shouldHaveSize 1
            val exiled = after.sharedZones.exile.single()
            exiled.card shouldBe CardRef("Fixture Fiery Temper")
            exiled.awaitingMadness shouldBe true
            // CR 702.35b: the reflexive may-cast ability fired, functioning from exile.
            val trigger = after.pendingTriggers.single()
            trigger.ability.condition shouldBe TriggerCondition.MadnessCast
            trigger.ability.zoneScope shouldBe TriggerZoneScope.Exile
            trigger.subject shouldBe exiled.id
            after.events.filterIsInstance<GameEvent.CardExiledByMadness>() shouldHaveSize 1
        }

        // ---- Flashback (CR 702.34) -----------------------------------------------------------------

        "CR 702.34e: a flashback spell cast from the graveyard is exiled instead of graveyarded on resolution" {
            // Alice's Flashback Bolt sits in her graveyard; she has {2}{R} of Mountains to flash it back.
            val state =
                elsewhereState(
                    aliceGraveyard = listOf("Fixture Flashback Bolt"),
                    aliceBattlefield = listOf("Fixture Mountain", "Fixture Mountain", "Fixture Mountain"),
                )
            var result = engine.advance(state, castFrom(state, "Fixture Flashback Bolt", CastSource.GRAVEYARD))
            result = engine.advance(result.pausedState, targetDecision(result.pending(), bob))
            result = engine.advance(result.pausedState, planDecision(result.pending()))
            result = passUntilResolved(engine, result)

            // Bob took 3, and the flashback card went to EXILE, not to a graveyard (CR 702.34e).
            result.pausedState.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - FIXTURE_FLASHBACK_LIFE_LOSS
            result.pausedState
                .player(alice)
                .graveyard
                .shouldBeEmpty()
            result.pausedState.sharedZones.exile
                .map { it.card } shouldContainExactly
                listOf(CardRef("Fixture Flashback Bolt"))
            result.pausedState.events.filterIsInstance<GameEvent.SpellExiledInsteadOfGraveyard>() shouldHaveSize 1
        }

        "CR 702.34e / CR 608.2b: a flashback spell that fizzles is exiled instead of graveyarded" {
            // A flashback bolt on the stack targeting a permanent that no longer exists fizzles (CR 608.2b);
            // the leave-stack replacement still sends it to exile (CR 702.34e).
            val gone = ObjectId(500)
            val boltObject = GameObject(ObjectId(80), CardRef("Fixture Flashback Bolt"), alice)
            val entry =
                StackEntry.Spell(
                    obj = boltObject,
                    controller = alice,
                    targets = persistentListOf(Target.Permanent(gone)),
                    definition = fixtureFlashbackBolt,
                    castVia = CastingPermission.Flashback(ManaCost.parse("{2}{R}")),
                )
            val state = stackState(entry, nextObjectId = 200)
            val resolved = resolveTopOfStack(state).pausedState

            resolved.sharedZones.stack.shouldBeEmpty()
            resolved.player(alice).graveyard.shouldBeEmpty()
            resolved.sharedZones.exile.map { it.card } shouldContainExactly
                listOf(CardRef("Fixture Flashback Bolt"))
            resolved.events.filterIsInstance<GameEvent.SpellFizzled>() shouldHaveSize 1
            resolved.events.filterIsInstance<GameEvent.SpellExiledInsteadOfGraveyard>() shouldHaveSize 1
        }

        // ---- Escape (CR 702.139) -------------------------------------------------------------------

        "CR 702.139a: escape casts from the graveyard, exiling two other cards as an additional cost" {
            // Alice's Escape Bolt plus two other cards sit in her graveyard; she has {R} to escape it.
            val state =
                elsewhereState(
                    aliceGraveyard = listOf("Fixture Escape Bolt", "Fixture Bolt", "Fixture Comet"),
                    aliceBattlefield = listOf("Fixture Mountain"),
                )
            var result = engine.advance(state, castFrom(state, "Fixture Escape Bolt", CastSource.GRAVEYARD))
            result = engine.advance(result.pausedState, targetDecision(result.pending(), bob))
            // The additional-cost selection: exile the two other graveyard cards.
            val exileRequest = result.pending<DecisionRequest.ChooseCardsToExile>()
            exileRequest.count shouldBe 2
            exileRequest.options.map { it.card } shouldContainExactly
                listOf(CardRef("Fixture Bolt"), CardRef("Fixture Comet"))
            result = engine.advance(result.pausedState, Decision.MultiSelect(exileRequest.id, listOf(0, 1)))
            result = engine.advance(result.pausedState, planDecision(result.pending()))
            result = passUntilResolved(engine, result)

            // Bob took 3; the two additional-cost cards and the resolved bolt are all in exile now.
            result.pausedState.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - FIXTURE_FLASHBACK_LIFE_LOSS
            result.pausedState.sharedZones.exile
                .map { it.card.name }
                .sorted() shouldBe
                listOf("Fixture Bolt", "Fixture Comet").sorted()
            // The escaped spell, having no leave-stack replacement, went to the graveyard as normal.
            result.pausedState
                .player(alice)
                .graveyard
                .map { it.card } shouldContainExactly
                listOf(CardRef("Fixture Escape Bolt"))
            result.pausedState.events.filterIsInstance<GameEvent.CardsExiledForCost>() shouldHaveSize 1
        }

        // ---- Enumeration completeness, both directions (ADR-005) -----------------------------------

        "ADR-005: a flashback card is enumerated from the graveyard and, separately, cast normally from hand" {
            val state =
                elsewhereState(
                    aliceHand = listOf("Fixture Flashback Bolt"),
                    aliceGraveyard = listOf("Fixture Flashback Bolt"),
                    aliceBattlefield = listOf("Fixture Mountain", "Fixture Mountain", "Fixture Mountain"),
                )
            val options = action(state).options.filterIsInstance<PriorityOption.CastSpell>()
            // Exactly two casts of this card: the normal hand cast (no permission) and the graveyard
            // flashback cast, in hand-then-graveyard enumeration order.
            val boltCasts = options.filter { it.card == CardRef("Fixture Flashback Bolt") }
            boltCasts.map { it.source } shouldContainExactly listOf(CastSource.HAND, CastSource.GRAVEYARD)
            boltCasts.first().permission shouldBe null
            (boltCasts.last().permission is CastingPermission.Flashback) shouldBe true
        }

        "ADR-005: escape is not enumerated when the graveyard lacks the two other cards to exile" {
            // Escape Bolt is alone in the graveyard (no two others to exile), so the escape cast is absent.
            val state =
                elsewhereState(
                    aliceGraveyard = listOf("Fixture Escape Bolt"),
                    aliceBattlefield = listOf("Fixture Mountain"),
                )
            action(state)
                .options
                .filterIsInstance<PriorityOption.CastSpell>()
                .none { it.permission is CastingPermission.Escape } shouldBe true
        }

        "ADR-005: a madness card in exile is never enumerated as a priority-window cast" {
            // The madness card sits in exile awaiting its reflexive trigger; it is not offered at priority.
            val state =
                elsewhereState(
                    aliceExile = listOf("Fixture Fiery Temper"),
                    aliceBattlefield = listOf("Fixture Mountain", "Fixture Mountain"),
                )
            action(state).options.filterIsInstance<PriorityOption.CastSpell>().shouldBeEmpty()
        }

        // ---- CR 616.1 replacement ordering (fixtures) ----------------------------------------------

        "CR 616.1: a discard with two applicable replacements surfaces an ordering choice, applied once" {
            // Alice discards a card carrying two discard→exile replacements at cleanup; she must choose
            // which applies first (CR 616.1), and applying one exiles it (CR 614.5 leaves nothing more).
            val state = cleanupState(aliceHand = listOf("Fixture Double Madness") + List(7) { "Filler" })
            val discardRequest = pausedRequestOf<DecisionRequest.ChooseDiscards>(state)
            val doubleIndex = discardRequest.options.indexOfFirst { it.card == CardRef("Fixture Double Madness") }
            val afterDiscard = engine.advance(state, Decision.MultiSelect(discardRequest.id, listOf(doubleIndex)))

            // The CR 616.1 choice is surfaced with both applicable replacements.
            val choice = afterDiscard.pending<DecisionRequest.ChooseReplacement>()
            choice.options shouldHaveSize 2
            choice.seat shouldBe alice
            // Pick the first; the card is exiled exactly once.
            val applied = engine.advance(afterDiscard.pausedState, Decision.SingleSelect(choice.id, 0))
            applied.pausedState.sharedZones.exile
                .map { it.card } shouldContainExactly
                listOf(CardRef("Fixture Double Madness"))
            applied.pausedState
                .player(alice)
                .hand
                .none { it.card == CardRef("Fixture Double Madness") } shouldBe true
        }
    })

// ---- state builders and decision helpers -------------------------------------------------------------

private val registry = (fixtureDefinitions + castFromElsewhereFixtures).toPersistentMap()

/** Alice's priority window in [state], as a [DecisionRequest.ChooseAction] (ADR-004). */
private fun action(state: GameState): DecisionRequest.ChooseAction = pausedRequestOf(state)

/** The id of the hand object of [name] owned by [seat]. */
private fun handIdOf(
    state: GameState,
    seat: PlayerId,
    name: String,
): ObjectId =
    state
        .player(seat)
        .hand
        .first { it.card == CardRef(name) }
        .id

/** The decision that begins casting [card] from [source] in [state]'s priority window. */
private fun castFrom(
    state: GameState,
    card: String,
    source: CastSource,
): Decision.SingleSelect {
    val request = action(state)
    val index =
        request.options.indexOfFirst {
            it is PriorityOption.CastSpell && it.card == CardRef(card) && it.source == source
        }
    check(index >= 0) { "no $source CastSpell option for $card in ${request.options}" }
    return Decision.SingleSelect(request.id, index)
}

/** Passes both players until the top of the stack resolves and alice again holds priority. */
private fun passUntilResolved(
    engine: DefaultGameEngine,
    from: AdvanceResult,
): AdvanceResult {
    var result = from
    repeat(2) { result = engine.advance(result.pausedState, passDecision(result.pending())) }
    return result
}

/** Alice's four private/shared zones, by card name, for a handcrafted state. */
private data class Zones(
    val hand: List<String> = emptyList(),
    val graveyard: List<String> = emptyList(),
    val battlefield: List<String> = emptyList(),
    val exile: List<String> = emptyList(),
)

/**
 * A handcrafted precombat-main state with [holder] (alice by default) holding priority, the P5.2 fixture
 * registry, and the given zones. Ids are allocated sequentially across battlefield, hand, graveyard,
 * and exile.
 */
private fun elsewhereState(
    aliceHand: List<String> = emptyList(),
    aliceGraveyard: List<String> = emptyList(),
    aliceBattlefield: List<String> = emptyList(),
    aliceExile: List<String> = emptyList(),
    holder: PlayerId = alice,
): GameState =
    buildState(
        Zones(aliceHand, aliceGraveyard, aliceBattlefield, aliceExile),
        Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        holder,
    )

/** A handcrafted cleanup state (no priority holder) with alice's over-full hand. */
private fun cleanupState(aliceHand: List<String>): GameState =
    buildState(Zones(hand = aliceHand), Turn(alice, 3, TurnPhase.ENDING, TurnStep.CLEANUP), null)

/** A handcrafted state with a single spell already on the stack (all players having passed). */
private fun stackState(
    entry: StackEntry.Spell,
    nextObjectId: Long,
): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to seat(alice, null),
                bob to seat(bob, null),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(entry), persistentListOf()),
        nextObjectId = nextObjectId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = registry,
    )

private fun buildState(
    zones: Zones,
    turn: Turn,
    holder: PlayerId?,
): GameState {
    var nextId = 0L

    fun objects(names: List<String>) =
        names.map { GameObject(ObjectId(nextId), CardRef(it), alice).also { _ -> nextId += 1 } }.toPersistentList()

    val field = objects(zones.battlefield)
    val hand = objects(zones.hand)
    val grave = objects(zones.graveyard)
    val exile = objects(zones.exile)
    return GameState(
        players =
            persistentMapOf(
                alice to seat(alice, holder).copy(hand = hand, graveyard = grave),
                bob to seat(bob, holder),
            ),
        turn = turn,
        sharedZones = SharedZones(field, persistentListOf(), exile),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = registry,
    )
}

private fun seat(
    seat: PlayerId,
    holder: PlayerId?,
): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
        priorityStatus = if (seat == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
    )
