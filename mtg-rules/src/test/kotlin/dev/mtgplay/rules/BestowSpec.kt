package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.CounterAmount
import dev.mtgplay.core.definition.EntersWithCounters
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.StaticCondition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.engine.layeredCharacteristics
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * Bestow (CR 702.103) and the CR 614.1c enters-with-counters replacement, against fixtures
 * (`mtg-rules` names no card, ADR-003). `W10-C`.
 *
 * The fixture is Nyxborn Hydra's shape without being it: a `{X}{G}` enchantment creature 0/1 with
 * bestow `{X}{G}{G}`, "enters with X `+1/+1` counters", and the two static abilities — the layer-4 "an
 * Aura enchantment and not a creature while attached to a creature" and the layer-6/7c grant to the
 * enchanted creature.
 *
 * The properties under test are the four that make bestow a mechanic rather than an expensive Aura:
 *
 * 1. **One card, two spells.** The same object in hand offers two casts, and they target differently.
 * 2. **The bestowed permanent is not a creature.** If layer 4 only *added* the Aura subtype, this would
 *    be a creature sitting in the combat enumeration — an enumerated-but-illegal action (ADR-005).
 * 3. **The counters are part of entering.** They are there at the first read, not after a trigger.
 * 4. **It becomes a creature when the host leaves, and does not go to a graveyard.** That is the whole
 *    card, and it is the one property a CR 704.5m implementation would silently get wrong.
 */
class BestowSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 702.103b: one card in hand offers two casts, and only the bestow one targets" {
            val state = bestowBoard()
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val casts =
                window.options.filterIsInstance<PriorityOption.CastSpell>().filter { it.card == BESTOWER }
            casts.size shouldBe 2
            casts.count { it.permission == null } shouldBe 1
            casts.count { it.permission is CastingPermission.Bestow } shouldBe 1
        }

        "CR 601.2c: the bestow cast asks for a creature to enchant; the ordinary cast asks for nothing" {
            val bestow = engine.advance(bestowBoard(), castVia(bestowBoard(), bestowed = true))
            bestow.pending<DecisionRequest.ChooseTargets>().options shouldBe
                listOf(Target.Permanent(HOST_ID))

            // The same card cast for its printed cost is a creature spell: CR 601.2c has nothing to ask,
            // so the next pause is the X announcement.
            val plain = engine.advance(bestowBoard(), castVia(bestowBoard(), bestowed = false))
            plain.pending<DecisionRequest.ChooseXValue>().cardObjectId shouldBe BESTOWER_ID
        }

        "CR 702.103a + CR 614.1c: a bestowed permanent is an Aura, not a creature, and carries its X counters" {
            val resolved = castBestowed(engine, announcedX = 2)
            val bestowed = resolved.sharedZones.battlefield.single { it.card == BESTOWER }
            // CR 303.4f: it entered attached to the creature it targeted.
            bestowed.attachedTo shouldBe HOST_ID
            // CR 614.1c: the counters are part of the entering event, so they are already here.
            bestowed.counterCount(Counter.PLUS_ONE_PLUS_ONE) shouldBe 2
            val layered = layeredCharacteristics(resolved, bestowed.id)
            // CR 613.1d: the Aura subtype was added and the creature type *removed*.
            layered.cardTypes shouldBe persistentSetOf(CardType.ENCHANTMENT)
            layered.subtypes.contains(Subtype("Aura")) shouldBe true
        }

        "CR 613.3c: the enchanted creature gets +1/+1 for each counter on the Aura, and its two keywords" {
            val resolved = castBestowed(engine, announcedX = 2)
            val host = layeredCharacteristics(resolved, HOST_ID)
            // A 2/2 host plus two +1/+1 counters *on the Aura* is a 4/4 — the counters are not its own.
            host.power shouldBe HOST_POWER + 2
            host.toughness shouldBe HOST_POWER + 2
            host.keywords.contains(Keyword.REACH) shouldBe true
            host.keywords.contains(Keyword.TRAMPLE) shouldBe true
        }

        "CR 702.103c: when the host leaves, the permanent unattaches, becomes a creature, and does not die" {
            val resolved = castBestowed(engine, announcedX = 2)
            // The host leaves the battlefield the bluntest way there is; what matters is that the
            // state-based actions then run with a dangling attachment.
            val hostGone = removeHost(resolved)
            val checked = engine.advance(hostGone, passDecisionFor(hostGone, alice)).pausedState

            val survivor = checked.sharedZones.battlefield.single { it.card == BESTOWER }
            // CR 702.103c: unattached and **still on the battlefield** — not in a graveyard (CR 704.5m).
            survivor.attachedTo shouldBe null
            checked.players
                .getValue(alice)
                .graveyard
                .none { it.card == BESTOWER } shouldBe true
            // CR 604.3: the type-changing ability's condition has failed, so it is a creature again —
            // with the counters it entered carrying, which is the whole point of the card.
            val layered = layeredCharacteristics(checked, survivor.id)
            layered.cardTypes shouldBe persistentSetOf(CardType.ENCHANTMENT, CardType.CREATURE)
            layered.power shouldBe BESTOWER_PRINTED_POWER + 2
            layered.toughness shouldBe BESTOWER_PRINTED_TOUGHNESS + 2
        }

        "CR 614.1c: the same clause on an ordinary cast makes a plain creature with the same counters" {
            val resolved = castOrdinary(engine, announcedX = 2)
            val creature = resolved.sharedZones.battlefield.single { it.card == BESTOWER }
            creature.attachedTo shouldBe null
            creature.counterCount(Counter.PLUS_ONE_PLUS_ONE) shouldBe 2
            val layered = layeredCharacteristics(resolved, creature.id)
            layered.cardTypes shouldBe persistentSetOf(CardType.ENCHANTMENT, CardType.CREATURE)
            layered.power shouldBe BESTOWER_PRINTED_POWER + 2
            // Its own printed keywords, and nothing granted to anybody: the Aura line's affected set is
            // empty for a permanent attached to nothing.
            layered.keywords shouldBe persistentSetOf(Keyword.REACH, Keyword.TRAMPLE)
            layeredCharacteristics(resolved, HOST_ID).power shouldBe HOST_POWER
        }

        "CR 614.1c: X announced as zero enters with no counters at all" {
            val resolved = castOrdinary(engine, announcedX = 0)
            val creature = resolved.sharedZones.battlefield.single { it.card == BESTOWER }
            creature.counters.isEmpty() shouldBe true
            layeredCharacteristics(resolved, creature.id).power shouldBe BESTOWER_PRINTED_POWER
        }
    })

private const val FIXTURE_BESTOWER = "Fixture Bestower"

private const val FIXTURE_HOST = "Fixture Bestow Host"

private const val BESTOWER_PRINTED_POWER = 0

private const val BESTOWER_PRINTED_TOUGHNESS = 1

private const val HOST_POWER = 2

/** Four Forests: enough for the bestow cost `{X}{G}{G}` at X = 2. */
private const val FORESTS = 4

private val BESTOWER = CardRef(FIXTURE_BESTOWER)

/**
 * The ids [fixtureState] allocates, per seat and in its own order: battlefield first (the host, then the
 * four Forests), then the library, then the hand.
 */
private val HOST_ID = ObjectId(0)

private val BESTOWER_ID = ObjectId((FORESTS + 1).toLong())

/** The priority decision that begins a cast of the fixture, via bestow or via its printed cost. */
private fun castVia(
    state: GameState,
    bestowed: Boolean,
): Decision.SingleSelect {
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell &&
                it.card == BESTOWER &&
                (it.permission is CastingPermission.Bestow) == bestowed
        }
    check(index >= 0) { "no ${if (bestowed) "bestow" else "ordinary"} cast of $FIXTURE_BESTOWER" }
    return Decision.SingleSelect(window.id, index)
}

/** Casts the fixture for its bestow cost at [announcedX], enchanting the host, and resolves it. */
private fun castBestowed(
    engine: DefaultGameEngine,
    announcedX: Int,
): GameState = castAndResolve(engine, bestowed = true, announcedX = announcedX)

/** Casts the fixture for its printed cost at [announcedX] and resolves it. */
private fun castOrdinary(
    engine: DefaultGameEngine,
    announcedX: Int,
): GameState = castAndResolve(engine, bestowed = false, announcedX = announcedX)

/**
 * Drives one whole cast of the fixture to resolution, answering each stage as it comes: the one legal
 * target when there is one (CR 601.2c), the requested value of X (CR 601.2b), the first payment plan
 * (CR 601.2g), then passes until the stack is empty (CR 117.4).
 */
private fun castAndResolve(
    engine: DefaultGameEngine,
    bestowed: Boolean,
    announcedX: Int,
): GameState {
    val board = bestowBoard()
    var current = engine.advance(board, castVia(board, bestowed))
    while (true) {
        val paused = current.pausedState
        when (val request = awaitedRequestOf(current)) {
            is DecisionRequest.ChooseTargets ->
                current = engine.advance(paused, Decision.SingleSelect(request.id, 0))
            is DecisionRequest.ChooseXValue ->
                current =
                    engine.advance(
                        paused,
                        Decision.SingleSelect(request.id, request.values.indexOf(announcedX)),
                    )
            is DecisionRequest.ChoosePaymentPlan ->
                current = engine.advance(paused, planDecision(request))
            is DecisionRequest.ChooseAction -> {
                if (paused.sharedZones.stack.isEmpty()) return paused
                current = engine.advance(paused, passDecision(request))
            }
            else -> error("unexpected request while casting the bestow fixture: $request")
        }
    }
}

/**
 * The request a paused [result] is waiting on, untyped. Deliberately **not** named `pendingRequestOf`,
 * for the reason `OptionalCostTestSupport.requestAwaiting` records: a same-package declaration of that
 * name shadows the published nullable accessor for every spec in this source set.
 */
private fun awaitedRequestOf(result: AdvanceResult): DecisionRequest = (result as AdvanceResult.NeedsDecision).request

/** [state] with the enchanted host simply gone from the battlefield (CR 400.7 is not the point here). */
private fun removeHost(state: GameState): GameState =
    state.copy(
        sharedZones =
            state.sharedZones.copy(
                battlefield = state.sharedZones.battlefield.removingAll { it.id == HOST_ID },
            ),
    )

/** Alice mid-priority with the fixture in hand, a 2/2 host, and four Forests. */
private fun bestowBoard(): GameState =
    fixtureState(
        aliceSetup =
            SeatSetup(
                hand = listOf(FIXTURE_BESTOWER),
                battlefield = listOf(FIXTURE_HOST) + List(FORESTS) { "Fixture Forest" },
                library = emptyList(),
            ),
        bobSetup = SeatSetup(library = emptyList()),
        definitions = fixtureDefinitions + bestowFixtures,
    )

/** A dynamic magnitude of one per `+1/+1` counter on the effect's own source (CR 613.3c, CR 122.1a). */
private val perCounterOnSource: Magnitude =
    Magnitude.Dynamic { state, source ->
        state.sharedZones.battlefield
            .firstOrNull { it.id == source }
            ?.counterCount(Counter.PLUS_ONE_PLUS_ONE)
            ?: 0
    }

private val bestowFixtures: Map<CardRef, CardDefinition> =
    mapOf(
        BESTOWER to bestowerDefinition(),
        CardRef(FIXTURE_HOST) to
            object : CardDefinition {
                override val characteristics =
                    PrintedCharacteristics(
                        name = FIXTURE_HOST,
                        manaCost = null,
                        supertypes = persistentSetOf(),
                        cardTypes = persistentSetOf(CardType.CREATURE),
                        subtypes = persistentSetOf(),
                        powerToughness = PrintedPowerToughness(HOST_POWER, HOST_POWER),
                    )
            },
    )

private fun bestowerDefinition(): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = FIXTURE_BESTOWER,
                manaCost = ManaCost.parse("{X}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT, CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness =
                    PrintedPowerToughness(BESTOWER_PRINTED_POWER, BESTOWER_PRINTED_TOUGHNESS),
                keywords = persistentSetOf(Keyword.REACH, Keyword.TRAMPLE),
            )

        override val castingPermissions =
            persistentListOf<CastingPermission>(CastingPermission.Bestow(ManaCost.parse("{X}{G}{G}")))

        override val entersWithCounters =
            EntersWithCounters(Counter.PLUS_ONE_PLUS_ONE, CounterAmount.AnnouncedX)

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }

        override val staticContinuousEffects =
            persistentListOf(
                StaticContinuousEffect(
                    affects = AffectedSet.Self,
                    condition = StaticCondition.AttachedToCreature,
                    addedSubtypes = persistentSetOf(Subtype("Aura")),
                    removedCardTypes = persistentSetOf(CardType.CREATURE),
                ),
                StaticContinuousEffect(
                    affects = AffectedSet.Enchanted,
                    grantedKeywords = persistentSetOf(Keyword.REACH, Keyword.TRAMPLE),
                    powerMod = perCounterOnSource,
                    toughnessMod = perCounterOnSource,
                ),
            )
    }
