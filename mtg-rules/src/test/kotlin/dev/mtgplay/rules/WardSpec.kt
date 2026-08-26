package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CounterUnlessPaid
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.definition.Ward
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.detectWardTriggers
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * Ward (CR 702.21a) end to end, on fixture definitions — `mtg-rules` names no card (ADR-003), so nothing
 * here mentions Tolarian Terror.
 *
 * The tests are grouped by the four halves of the printed reminder text, because each is a place a
 * narrower implementation would have looked right: *becomes the target*, *a spell **or ability***, *an
 * **opponent** controls*, and *counter **it** unless **that player** pays*.
 */
class WardSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        // ---- "becomes the target of a spell or ability an opponent controls" ------------------------

        "CR 702.21a: an opponent's spell targeting a warded permanent fires its ward trigger" {
            val state = wardBoard()
            val fired = detectWardTriggers(state, listOf(Target.Permanent(WARDED_ID)), alice, SPELL_ID)

            val trigger = fired.pendingTriggers.single()
            // The trigger belongs to the ward permanent's controller, not to the player who targeted.
            trigger.controller shouldBe bob
            trigger.sourceId shouldBe WARDED_ID
            trigger.ability.condition shouldBe TriggerCondition.BecameTargetOfOpponentsSpellOrAbility
            // "counter *it*" — the object that did the targeting, captured as linked information because
            // ward's own trigger targets nothing and so could not name it as a target (CR 603.10).
            trigger.targetedBy shouldBe SPELL_ID
            trigger.ability.counterUnlessPaid
                ?.cost shouldBe WARD_COST
        }

        "CR 702.21a: 'an opponent controls' — a permanent's own controller targeting it fires nothing" {
            // Bob may enchant, pump or save his own warded creature without paying his own tax.
            val state = wardBoard()
            detectWardTriggers(state, listOf(Target.Permanent(WARDED_ID)), bob, SPELL_ID)
                .pendingTriggers
                .shouldBeEmpty()
        }

        "CR 702.21a: a permanent without ward fires nothing, and neither does a non-permanent target" {
            val state = wardBoard()
            detectWardTriggers(state, listOf(Target.Permanent(PLAIN_ID)), alice, SPELL_ID)
                .pendingTriggers
                .shouldBeEmpty()
            detectWardTriggers(state, listOf(Target.Player(bob)), alice, SPELL_ID)
                .pendingTriggers
                .shouldBeEmpty()
        }

        "CR 702.21b: a permanent targeted twice by one object fires ward twice" {
            val state = wardBoard()
            val fired =
                detectWardTriggers(
                    state,
                    listOf(Target.Permanent(WARDED_ID), Target.Permanent(WARDED_ID)),
                    alice,
                    SPELL_ID,
                )
            fired.pendingTriggers shouldHaveSize 2
        }

        // ---- "counter it unless that player pays" -----------------------------------------------

        "CR 702.21a: the decider is the targeting object's controller, not ward's" {
            val request = wardPause().pending<DecisionRequest.ChooseCounterPayment>()

            // Alice cast the removal at bob's warded creature; alice is the one asked to pay.
            request.seat shouldBe alice
            request.card shouldBe CardRef(REMOVAL)
            request.cost shouldBe WARD_COST
            request.options.first() shouldBe DecisionRequest.ChooseCounterPayment.Option.Decline
        }

        "CR 701.5a: declining counters the targeting spell, and the ward trigger resolves normally" {
            val paused = wardPause()
            val request = paused.pending<DecisionRequest.ChooseCounterPayment>()
            val done = engine.advance(paused.pausedState, Decision.SingleSelect(request.id, DECLINE)).pausedState

            done.events.filterIsInstance<GameEvent.SpellCountered>().map { it.card } shouldContainExactly
                listOf(CardRef(REMOVAL))
            // CR 113.7a: ward's own ability ceased to exist, as a *resolved* ability rather than a
            // countered one — countering is what it did, not what happened to it.
            done.events.filterIsInstance<GameEvent.TriggeredAbilityResolved>() shouldHaveSize 1
            done.sharedZones.stack.shouldBeEmpty()
            done.pendingCounterPayment shouldBe null
        }

        "CR 702.21a: paying leaves the spell on the stack fully intact — ward is a tax, not protection" {
            val paused = wardPause()
            val request = paused.pending<DecisionRequest.ChooseCounterPayment>()
            request.options shouldHaveSize 2

            val done = engine.advance(paused.pausedState, Decision.SingleSelect(request.id, 1)).pausedState

            done.events.filterIsInstance<GameEvent.SpellCountered>().shouldBeEmpty()
            done.sharedZones.stack
                .single()
                .shouldBeInstanceOf<StackEntry.Spell>()
                .obj.card shouldBe CardRef(REMOVAL)
            // The payment really happened: alice's two sources are tapped for it.
            done.sharedZones.battlefield.count { it.owner == alice && it.tapped } shouldBe 2
        }

        "CR 702.21a: a decider who cannot pay gets a decline-only request and loses the spell" {
            val paused = wardPause(aliceSources = 0)
            val request = paused.pending<DecisionRequest.ChooseCounterPayment>()
            request.options shouldContainExactly listOf(DecisionRequest.ChooseCounterPayment.Option.Decline)

            val done = engine.advance(paused.pausedState, Decision.SingleSelect(request.id, DECLINE)).pausedState
            done.events.filterIsInstance<GameEvent.SpellCountered>() shouldHaveSize 1
        }

        "CR 702.21a: a ward trigger whose victim has left the stack counters nothing and asks nobody" {
            // Ward names its victim as *linked information*, not as a target, so nothing re-checks it
            // under CR 608.2b — the targeting spell may perfectly legally have resolved or been countered
            // in the meantime. This is the reachable case a target-shaped design would have crashed on.
            val done =
                resolveTopOfStack(
                    wardBoard(stack = listOf(wardTriggerEntry(targetedBy = ObjectId(4242)))),
                ).pausedState

            done.pendingCounterPayment shouldBe null
            done.events.filterIsInstance<GameEvent.SpellCountered>().shouldBeEmpty()
            done.events.filterIsInstance<GameEvent.AbilityCountered>().shouldBeEmpty()
            done.events.filterIsInstance<GameEvent.TriggeredAbilityResolved>() shouldHaveSize 1
        }

        // ---- "a spell **or ability**" ---------------------------------------------------------------

        "CR 113.7a: ward counters an *ability*, which ceases to exist rather than going to a graveyard" {
            val paused = wardPause(victim = VictimKind.ACTIVATED_ABILITY)
            val request = paused.pending<DecisionRequest.ChooseCounterPayment>()
            // An ability is not a card, so the seat is told which permanent's ability is at stake.
            request.card shouldBe CardRef(TAPPER)

            val done = engine.advance(paused.pausedState, Decision.SingleSelect(request.id, DECLINE)).pausedState

            done.events
                .filterIsInstance<GameEvent.AbilityCountered>()
                .single()
                .sourceCard shouldBe CardRef(TAPPER)
            // CR 113.7a: nothing moved anywhere. No card entered a graveyard, and no SpellCountered.
            done.events.filterIsInstance<GameEvent.SpellCountered>().shouldBeEmpty()
            done.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            done.sharedZones.stack.shouldBeEmpty()
        }

        "CR 113.7a: a countered triggered ability leaves the stack and no card moves" {
            val paused = wardPause(victim = VictimKind.TRIGGERED_ABILITY)
            val request = paused.pending<DecisionRequest.ChooseCounterPayment>()
            val done = engine.advance(paused.pausedState, Decision.SingleSelect(request.id, DECLINE)).pausedState

            done.events
                .filterIsInstance<GameEvent.AbilityCountered>()
                .single()
                .sourceCard shouldBe CardRef(WATCHER)
            done.sharedZones.stack.shouldBeEmpty()
            done.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
        }

        "CR 111.1: an ability with no stack identity is never matched by a counter looking for one" {
            // An entry built without an identity models "this object cannot be named" in the type rather
            // than as a sentinel id, so a ward trigger looking for an id it no longer finds counters
            // nothing — instead of removing whichever unidentified ability happens to be on the stack.
            val done =
                resolveTopOfStack(
                    wardBoard(
                        stack = listOf(unidentifiedAbility(), wardTriggerEntry(targetedBy = ObjectId(4242))),
                    ),
                ).pausedState

            done.events.filterIsInstance<GameEvent.AbilityCountered>().shouldBeEmpty()
            done.sharedZones.stack shouldHaveSize 1
        }
    })

private const val WARDED = "Fixture Warded Beast"
private const val PLAIN = "Fixture Plain Beast"
private const val REMOVAL = "Fixture Removal"
private const val TAPPER = "Fixture Tapper"
private const val WATCHER = "Fixture Watcher"
private const val SOURCE = "Fixture Ward Source"
private const val DECLINE = 0

private val WARD_COST: ManaCost = ManaCost.parse("{2}")

private val WARDED_ID = ObjectId(0)
private val PLAIN_ID = ObjectId(1)
private val SPELL_ID = ObjectId(50)
private val VICTIM_ID = ObjectId(50)
private val WARD_TRIGGER_ID = ObjectId(60)

private val noOp = ResolutionEffect { state, _ -> state }

/** Which kind of stack object the ward trigger is pointed at — the "spell **or ability**" split. */
private enum class VictimKind { SPELL, ACTIVATED_ABILITY, TRIGGERED_ABILITY }

/**
 * A resolving ward trigger on top of the stack with its victim below it, paused on the CR 702.21a
 * payment. [aliceSources] untapped `{C}` sources decide whether alice can afford the `{2}`.
 */
private fun wardPause(
    victim: VictimKind = VictimKind.SPELL,
    aliceSources: Int = 2,
): AdvanceResult =
    resolveTopOfStack(
        wardBoard(
            aliceSources = aliceSources,
            stack = listOf(victimEntry(victim), wardTriggerEntry(targetedBy = VICTIM_ID)),
        ),
    )

private fun victimEntry(kind: VictimKind): StackEntry =
    when (kind) {
        VictimKind.SPELL ->
            StackEntry.Spell(
                obj = GameObject(VICTIM_ID, CardRef(REMOVAL), alice),
                controller = alice,
                targets = persistentListOf(Target.Permanent(WARDED_ID)),
                definition = removalFixture,
            )
        VictimKind.ACTIVATED_ABILITY ->
            StackEntry.ActivatedAbilityOnStack(
                sourceId = ObjectId(2),
                sourceCard = CardRef(TAPPER),
                controller = alice,
                ability = ActivatedAbility(cost = persistentListOf(AbilityCost.TapSelf), effect = noOp),
                targets = persistentListOf(Target.Permanent(WARDED_ID)),
                entryId = VICTIM_ID,
            )
        VictimKind.TRIGGERED_ABILITY ->
            StackEntry.Ability(
                trigger =
                    PendingTrigger(
                        sourceId = ObjectId(2),
                        sourceCard = CardRef(WATCHER),
                        controller = alice,
                        ability = TriggeredAbility(TriggerCondition.EnteredBattlefieldSelf, noOp),
                    ),
                targets = persistentListOf(Target.Permanent(WARDED_ID)),
                entryId = VICTIM_ID,
            )
    }

/** Bob's ward trigger on the stack, pointed at [targetedBy]. */
private fun wardTriggerEntry(targetedBy: ObjectId): StackEntry.Ability =
    StackEntry.Ability(
        trigger =
            PendingTrigger(
                sourceId = WARDED_ID,
                sourceCard = CardRef(WARDED),
                controller = bob,
                ability =
                    TriggeredAbility(
                        condition = TriggerCondition.BecameTargetOfOpponentsSpellOrAbility,
                        effect = noOp,
                        counterUnlessPaid = CounterUnlessPaid(WARD_COST),
                    ),
                targetedBy = targetedBy,
            ),
        entryId = WARD_TRIGGER_ID,
    )

/** An ability built by hand with no stack identity — the sentinel case. */
private fun unidentifiedAbility(): StackEntry.Ability =
    StackEntry.Ability(
        trigger =
            PendingTrigger(
                sourceId = ObjectId(2),
                sourceCard = CardRef(WATCHER),
                controller = alice,
                ability = TriggeredAbility(TriggerCondition.EnteredBattlefieldSelf, noOp),
            ),
    )

/**
 * Bob's warded 2/2 and an unwarded one on the battlefield, plus [aliceSources] untapped colourless
 * sources for alice, with [stack] already on the stack and nobody holding priority (CR 608.1).
 */
private fun wardBoard(
    aliceSources: Int = 0,
    stack: List<StackEntry> = emptyList(),
): GameState {
    val battlefield =
        buildList {
            add(GameObject(WARDED_ID, CardRef(WARDED), bob))
            add(GameObject(PLAIN_ID, CardRef(PLAIN), bob))
            repeat(aliceSources) { add(GameObject(ObjectId(10L + it), CardRef(SOURCE), alice)) }
        }
    return GameState(
        players = persistentMapOf(alice to wardSeat(), bob to wardSeat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = stack.toPersistentList(),
                exile = persistentListOf(),
            ),
        nextObjectId = 1000,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = wardDefinitions.toPersistentMap(),
    )
}

private fun wardSeat(): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
        priorityStatus = PriorityStatus.NONE,
    )

/** `{1}{R}` instant: "destroy target creature" — the removal ward taxes. Its effect is inert here. */
private val removalFixture: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = REMOVAL,
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        override val resolution = noOp
    }

private fun beastFixture(
    name: String,
    ward: Ward?,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(2, 2),
            )
        override val ward = ward
    }

private val wardDefinitions: Map<CardRef, CardDefinition> =
    listOf(
        beastFixture(WARDED, Ward(WARD_COST)),
        beastFixture(PLAIN, ward = null),
        removalFixture,
        wardSourceFixture(),
    ).associateBy { CardRef(it.characteristics.name) }

/** A land that taps for `{C}`, so the CR 702.21a payment has something to be paid with. */
private fun wardSourceFixture(): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = SOURCE,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities =
            persistentListOf(
                ManaAbility(persistentListOf(ManaType.COLORLESS)),
            )
    }
