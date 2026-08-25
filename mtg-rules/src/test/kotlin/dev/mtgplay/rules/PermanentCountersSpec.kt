package dev.mtgplay.rules

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.effect.putCounters
import dev.mtgplay.rules.engine.SbaOutcome
import dev.mtgplay.rules.engine.effectiveKeywords
import dev.mtgplay.rules.engine.layeredCharacteristics
import dev.mtgplay.rules.engine.layeredPower
import dev.mtgplay.rules.engine.layeredToughness
import dev.mtgplay.rules.engine.performStateBasedActions
import dev.mtgplay.rules.engine.player
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.toPersistentList

/*
 * Counters on permanents (CR 122), from the layer the rules put them in to the state-based actions
 * that read them.
 *
 * The counter model is a multiset keyed by kind: CR 122.1 says counters have no characteristics and
 * that counters with the same description are interchangeable, so a kind and a count is exactly the
 * information the rules can distinguish. `+X/+Y` counters carry signed components (CR 122.1a covers
 * `+1/+1`, `-1/-1` and `-0/-1` with one sentence), and keyword counters carry a keyword (CR 122.1b).
 */
class PermanentCountersSpec :
    StringSpec({

        /** Alice's [name] on the battlefield with [counters], paused at her declare-attackers step. */
        fun withCounters(
            name: String,
            counters: Map<Counter, Int>,
        ): GameState = attackStep(aliceField = listOf(Combatant(name, counters = counters)))

        val subject = ObjectId(0)

        // --- CR 613.4c: sublayer 7c, where the rule actually puts P/T counters ---

        "CR 613.4c / CR 122.1a: +1/+1 counters add to power and toughness in sublayer 7c" {
            val state = withCounters("Bear", mapOf(Counter.PLUS_ONE_PLUS_ONE to 2))
            layeredPower(state, subject) shouldBe 4
            layeredToughness(state, subject) shouldBe 4
        }

        "CR 613.4c / CR 122.1a: -1/-1 counters subtract from power and toughness" {
            val state = withCounters("Ogre", mapOf(Counter.MINUS_ONE_MINUS_ONE to 2))
            layeredPower(state, subject) shouldBe 1
            layeredToughness(state, subject) shouldBe 1
        }

        "CR 122.1a: a -0/-1 counter reduces toughness only" {
            // The Wall of Roots counter. No gauntlet card places one yet, but the kind is one
            // instance of the same CR 122.1a rule and must behave as the rule says.
            val state = withCounters("Wall", mapOf(Counter.MINUS_ZERO_MINUS_ONE to 3))
            layeredPower(state, subject) shouldBe 0
            layeredToughness(state, subject) shouldBe 1
        }

        "CR 613.4c: counters and Aura P/T modifiers both apply in sublayer 7c and commute" {
            // The rule names both in one breath ("effects and counters that modify power and/or
            // toughness"), so a Rancor's +2/+0 and a +1/+1 counter simply sum.
            val base = withCounters("Bear", mapOf(Counter.PLUS_ONE_PLUS_ONE to 1))
            val state = base.withAuraAttached("Fixture Rancor", subject)
            layeredPower(state, subject) shouldBe 5
            layeredToughness(state, subject) shouldBe 3
        }

        // --- CR 613.1f / CR 122.1b: keyword counters are layer 6 ---

        "CR 122.1b / CR 613.1f: a keyword counter grants its keyword in layer 6" {
            val state = withCounters("Bear", mapOf(Counter.KeywordCounter(Keyword.LIFELINK) to 1))
            effectiveKeywords(state, subject) shouldBe setOf(Keyword.LIFELINK)
        }

        "CR 122.1b: a keyword counter unions with printed keywords rather than replacing them" {
            val state = withCounters("Flyer", mapOf(Counter.KeywordCounter(Keyword.LIFELINK) to 1))
            effectiveKeywords(state, subject) shouldBe setOf(Keyword.FLYING, Keyword.LIFELINK)
        }

        "CR 702.15b: two lifelink counters are redundant, not cumulative" {
            // Keywords are a set; CR 702's "multiple instances are redundant" is free.
            val state = withCounters("Bear", mapOf(Counter.KeywordCounter(Keyword.LIFELINK) to 2))
            effectiveKeywords(state, subject) shouldBe setOf(Keyword.LIFELINK)
        }

        "CR 122.1b: a keyword counter grants no power or toughness" {
            val state = withCounters("Bear", mapOf(Counter.KeywordCounter(Keyword.LIFELINK) to 1))
            layeredPower(state, subject) shouldBe 2
            layeredToughness(state, subject) shouldBe 2
        }

        "CR 122.1b: a counter can only be a keyword the rule's list names" {
            // Defender is not on CR 122.1b's closed list, so minting the counter fails loudly rather
            // than creating a marker no rule sanctions.
            val error =
                shouldThrow<IllegalArgumentException> { Counter.KeywordCounter(Keyword.DEFENDER) }
            error.message.orEmpty() shouldContain "122.1b"
        }

        // --- CR 704.5f: toughness 0 or less ---

        "CR 704.5f: -1/-1 counters that bring toughness to 0 put the creature into its owner's graveyard" {
            val state = withCounters("Bear", mapOf(Counter.MINUS_ONE_MINUS_ONE to 2))
            val after = state.afterStateBasedActions()
            after.sharedZones.battlefield.map { it.card.name } shouldBe emptyList()
            after.player(alice).graveyard.map { it.card.name } shouldContainExactly listOf("Bear")
        }

        "CR 704.5f: a creature whose counters leave positive toughness survives" {
            val state = withCounters("Ogre", mapOf(Counter.MINUS_ONE_MINUS_ONE to 2))
            state
                .afterStateBasedActions()
                .sharedZones.battlefield
                .map { it.card.name } shouldContainExactly
                listOf("Ogre")
        }

        "CR 122.2: a creature that dies with counters is reborn in the graveyard without them" {
            // Counters are not retained across a zone change; they are not removed, they cease to
            // exist (CR 122.2), and the CR 400.7 rebirth is where that becomes observable.
            val state =
                withCounters(
                    "Bear",
                    mapOf(Counter.MINUS_ONE_MINUS_ONE to 2, Counter.KeywordCounter(Keyword.LIFELINK) to 1),
                )
            state
                .afterStateBasedActions()
                .player(alice)
                .graveyard
                .single()
                .counters
                .shouldBeEmpty()
        }

        // --- CR 704.5q: annihilation ---

        "CR 704.5q: equal numbers of +1/+1 and -1/-1 counters annihilate" {
            val state =
                withCounters("Bear", mapOf(Counter.PLUS_ONE_PLUS_ONE to 2, Counter.MINUS_ONE_MINUS_ONE to 2))
            val after = state.afterStateBasedActions()
            after.sharedZones.battlefield
                .single()
                .counters
                .shouldBeEmpty()
            layeredPower(after, subject) shouldBe 2
        }

        "CR 704.5q: N is the smaller count, so the surplus stays" {
            val state =
                withCounters("Bear", mapOf(Counter.PLUS_ONE_PLUS_ONE to 3, Counter.MINUS_ONE_MINUS_ONE to 1))
            val after = state.afterStateBasedActions()
            after.sharedZones.battlefield
                .single()
                .counters shouldBe mapOf(Counter.PLUS_ONE_PLUS_ONE to 2)
            layeredPower(after, subject) shouldBe 4
            layeredToughness(after, subject) shouldBe 4
        }

        "CR 704.5q: annihilation is exactly power/toughness-neutral" {
            // Removing N of each changes power by -N + N and toughness by -N + N, which is why
            // annihilation can never make a creature die that would have lived, or the reverse, and
            // why its ordering against CR 704.5f inside one batch is unobservable.
            val before =
                withCounters("Bear", mapOf(Counter.PLUS_ONE_PLUS_ONE to 4, Counter.MINUS_ONE_MINUS_ONE to 3))
            val after = before.afterStateBasedActions()
            layeredPower(after, subject) shouldBe layeredPower(before, subject)
            layeredToughness(after, subject) shouldBe layeredToughness(before, subject)
        }

        "CR 704.5q: a -0/-1 counter is a different kind and never annihilates a +1/+1" {
            // CR 704.5q names +1/+1 and -1/-1 specifically. Wall of Roots' counter is neither.
            val state =
                withCounters("Wall", mapOf(Counter.PLUS_ONE_PLUS_ONE to 1, Counter.MINUS_ZERO_MINUS_ONE to 1))
            state
                .afterStateBasedActions()
                .sharedZones.battlefield
                .single()
                .counters shouldBe
                mapOf(Counter.PLUS_ONE_PLUS_ONE to 1, Counter.MINUS_ZERO_MINUS_ONE to 1)
        }

        "CR 704.5q and CR 704.5f in one batch: a creature that both annihilates and dies, dies" {
            // 2/2 with two +1/+1 and four -1/-1: net -2/-2, so toughness 0 (CR 704.5f) *and* a pair to
            // annihilate (CR 704.5q). The batch performs both; the creature is gone either way.
            val state =
                withCounters("Bear", mapOf(Counter.PLUS_ONE_PLUS_ONE to 2, Counter.MINUS_ONE_MINUS_ONE to 4))
            layeredToughness(state, subject) shouldBe 0
            val after = state.afterStateBasedActions()
            after.sharedZones.battlefield shouldBe emptyList()
            after.player(alice).graveyard.map { it.card.name } shouldContainExactly listOf("Bear")
        }

        // --- The effect primitive (CR 122.1) ---

        "CR 122.1: putCounters is additive and emits one event per call" {
            val state = attackStep(aliceField = listOf(Combatant("Bear")))
            val once = putCounters(state, subject, Counter.PLUS_ONE_PLUS_ONE)
            val after = putCounters(once, subject, Counter.PLUS_ONE_PLUS_ONE, 2)
            after.sharedZones.battlefield
                .single()
                .counterCount(Counter.PLUS_ONE_PLUS_ONE) shouldBe 3
            after.events.filterIsInstance<GameEvent.CountersPlaced>() shouldHaveSize 2
        }

        "CR 122.1: putCounters preserves battlefield order" {
            // Battlefield order is the CR 613.7 timestamp spine; a counter change must not reorder it.
            val state =
                attackStep(aliceField = listOf(Combatant("Bear"), Combatant("Ogre"), Combatant("Giant")))
            putCounters(state, ObjectId(1), Counter.PLUS_ONE_PLUS_ONE)
                .sharedZones
                .battlefield
                .map { it.card.name } shouldContainExactly listOf("Bear", "Ogre", "Giant")
        }

        "CR 613.4c: P/T counters on an object with no power or toughness fail loudly" {
            // A `+1/+1` counter on a noncreature permanent is legal Magic and inert until the
            // permanent becomes a creature — which needs CR 613 layers 4 and 7b, neither implemented.
            // Refusing beats silently ignoring the counters (docs/design/layer-system.md §1).
            val base = attackStep(aliceField = listOf(Combatant("Bear")))
            val aura = base.withAuraAttached("Hex Aura", subject)
            val auraId =
                aura.sharedZones.battlefield
                    .first { it.card.name == "Hex Aura" }
                    .id
            val withCounter = putCounters(aura, auraId, Counter.PLUS_ONE_PLUS_ONE)
            val error =
                shouldThrow<IllegalArgumentException> { layeredCharacteristics(withCounter, auraId) }
            error.message.orEmpty() shouldContain "613.4c"
        }
    })

/** An id well clear of the handcrafted field's allocations, for an Aura appended after the fact. */
private const val COUNTER_AURA_ID: Long = 90

/** This state with the Aura fixture [auraName] on the battlefield, attached to [attachedTo]. */
private fun GameState.withAuraAttached(
    auraName: String,
    attachedTo: ObjectId,
): GameState {
    val aura =
        GameObject(
            id = ObjectId(COUNTER_AURA_ID),
            card = CardRef(auraName),
            owner = alice,
            attachedTo = attachedTo,
            summoningSick = false,
        )
    return copy(
        sharedZones = sharedZones.copy(battlefield = (sharedZones.battlefield + aura).toPersistentList()),
        nextObjectId = maxOf(nextObjectId, COUNTER_AURA_ID + 1),
    )
}

/**
 * This state after the CR 704.3 repeat-until-quiet state-based-action loop. Fails loudly if a player
 * lost — no counter scenario here is meant to end the game.
 */
private fun GameState.afterStateBasedActions(): GameState =
    when (val outcome = performStateBasedActions(this)) {
        is SbaOutcome.Continued -> outcome.state
        is SbaOutcome.Loss -> error("CR 704.5: unexpected player loss in a counter scenario: ${outcome.result}")
    }
