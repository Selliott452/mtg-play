package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.applyIndefinitely
import dev.mtgplay.rules.effect.applyUntilEndOfTurn
import dev.mtgplay.rules.effect.putCounters
import dev.mtgplay.rules.engine.Chooser
import dev.mtgplay.rules.engine.cleanupRemoveDamageAndEndEffects
import dev.mtgplay.rules.engine.hasSubtype
import dev.mtgplay.rules.engine.layeredCharacteristics
import dev.mtgplay.rules.engine.legalTargets
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * CR 613 **layer 4** (type changing) and **sublayer 7b** (P/T setting), the two stages `FW-TYPECHANGE`
 * populated, exercised entirely against fixture objects — `mtg-rules` names no card (ADR-003).
 *
 * The shape under test throughout is Kenku Artificer's without its name: a noncreature artifact
 * ("Fixture Anvil", which has no printed P/T box at all) that a resolution-generated continuous effect
 * turns into a 0/0 creature with a new subtype, alongside three `+1/+1` counters. Four properties matter
 * and each has its own test: the type line is **unioned** not replaced (CR 205.1b), sublayer 7b runs
 * **before** 7c so the counters land on the set value rather than beside it (CR 613.4b/4c), the effect
 * has **no duration** and survives the CR 514.2 cleanup (CR 611.2b), and every read that asks whether a
 * permanent is a creature now asks the **layer engine** rather than the printed card.
 */
class TypeChangeLayerSpec :
    StringSpec({

        "CR 613.1d: an added card type is unioned onto the printed type line, never substituted" {
            val animated = animate(anvilState())
            val layered = layeredCharacteristics(animated, ANVIL)
            // CR 205.1b: "becomes an artifact creature" keeps the artifact type. Substituting instead of
            // unioning would silently switch off every affinity count on the board.
            layered.cardTypes shouldBe persistentSetOf(CardType.ARTIFACT, CardType.CREATURE)
            layered.subtypes shouldBe persistentSetOf(SERVANT)
            hasSubtype(animated, ANVIL, SERVANT) shouldBe true
        }

        "CR 613.4b: sublayer 7b creates a P/T box on an object whose printed card has none" {
            // Before: a plain artifact has no power and no toughness at all (CR 208.1).
            val before = layeredCharacteristics(anvilState(), ANVIL)
            before.power.shouldBeNull()
            before.toughness.shouldBeNull()
            // After: layer 4 makes it a creature and 7b gives it numbers. 7b is the *only* stage allowed
            // to invent a P/T box; every other one refuses an object without one.
            val after = layeredCharacteristics(animate(anvilState(), counters = 0), ANVIL)
            after.power shouldBe 0
            after.toughness shouldBe 0
        }

        "CR 613.4b before 613.4c: a 0/0 set plus three +1/+1 counters is a 3/3" {
            val layered = layeredCharacteristics(animate(anvilState()), ANVIL)
            // The whole card turns on this ordering. Fold the set into 7c and the two contributions
            // commute, leaving a 0/0 that the CR 704.5f state-based action destroys on sight.
            layered.power shouldBe 3
            layered.toughness shouldBe 3
        }

        "CR 613.7: two set-P/T effects on one object are resolved by timestamp, latest wins" {
            // The one implemented stage whose within-layer order is observable: 7b overwrites rather
            // than adding, so the later timestamp is the surviving value (CR 613.7a).
            val first = setPowerToughness(animate(anvilState(), counters = 0), power = 5, toughness = 5)
            val second = setPowerToughness(first, power = 1, toughness = 1)
            layeredCharacteristics(second, ANVIL).power shouldBe 1
            layeredCharacteristics(second, ANVIL).toughness shouldBe 1
        }

        "CR 613.1f: a keyword granted by the same effect rides in layer 6, not layer 4" {
            layeredCharacteristics(animate(anvilState()), ANVIL).keywords shouldBe
                persistentSetOf(Keyword.FLYING)
        }

        "CR 611.2b: an indefinite effect survives the CR 514.2 cleanup that ends every timed one" {
            val animated = animate(anvilState())
            val alsoPumped =
                applyUntilEndOfTurn(
                    animated,
                    affected = ANVIL,
                    modification = ContinuousModification(powerMod = 2, toughnessMod = 2),
                    sourceCard = SOURCE,
                )
            alsoPumped.timedEffects.map { it.duration } shouldContainExactly
                listOf(EffectDuration.Indefinite, EffectDuration.UntilEndOfTurn)
            // CR 514.2 ends the pump and leaves the type change standing. Getting this backwards is the
            // failure that leaves no trace: the artifact stops being a creature between turns, and its
            // counters go inert rather than illegal.
            val afterCleanup = cleanupRemoveDamageAndEndEffects(alsoPumped)
            afterCleanup.timedEffects.map { it.duration } shouldContainExactly listOf(EffectDuration.Indefinite)
            layeredCharacteristics(afterCleanup, ANVIL).power shouldBe 3
        }

        "CR 613.4c / CR 122.1a: P/T counters on an object with no P/T box fail loudly" {
            // Counters placed *without* the paired type change — the half-applied resolution the guard
            // exists to catch. Silently ignoring them would look like a merely smaller creature.
            val countersOnly = putCounters(anvilState(), ANVIL, Counter.PLUS_ONE_PLUS_ONE, THREE)
            val error = shouldThrow<IllegalArgumentException> { layeredCharacteristics(countersOnly, ANVIL) }
            error.message.shouldBeInstanceOf<String>() shouldContain "122.1a"
        }

        "CR 115.1b: 'noncreature artifact' reads the layered type line, so an animated one is excluded" {
            val spec =
                TargetSpec.TargetPermanent(PermanentRestriction.NONCREATURE_ARTIFACT, TargetCount.UpTo(1))
            legalTargets(anvilState(), spec, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.Permanent(ANVIL))
            // Reading printed types here would offer the artifact a second time and then mis-resolve.
            legalTargets(animate(anvilState()), spec, alice, Chooser.Nobody).shouldBeEmpty()
        }

        "CR 302: the animated artifact is a creature to 'target creature' too, and to combat" {
            val spec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
            legalTargets(anvilState(), spec, alice, Chooser.Nobody).shouldBeEmpty()
            legalTargets(animate(anvilState()), spec, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.Permanent(ANVIL))
        }
    })

/** The battlefield artifact every test here animates. */
private val ANVIL = ObjectId(0)

/** The subtype the fixture effect grants (CR 205.3m); a creature type, like every printed one. */
private val SERVANT = Subtype("Golem")

/** The printed identity recorded on the fixture effect (CR 113.7c). */
private val SOURCE = CardRef("Fixture Anvil")

/** How many `+1/+1` counters the fixture places, matching the shape under test. */
private const val THREE: Int = 3

/** A board with one noncreature artifact Alice controls, and nothing else. */
private fun anvilState(): GameState = auraState(listOf(bfObject(ANVIL.value, "Fixture Anvil")))

/**
 * The shape under test: [counters] `+1/+1` counters and the durationless layer-4/7b/6 change that makes
 * the Anvil a 0/0 Golem artifact creature with flying — both in one call, as one resolution would.
 */
private fun animate(
    state: GameState,
    counters: Int = THREE,
): GameState {
    val withCounters =
        if (counters == 0) state else putCounters(state, ANVIL, Counter.PLUS_ONE_PLUS_ONE, counters)
    return applyIndefinitely(
        withCounters,
        affected = ANVIL,
        modification =
            ContinuousModification(
                grantedKeywords = persistentSetOf(Keyword.FLYING),
                addedCardTypes = persistentSetOf(CardType.CREATURE),
                addedSubtypes = persistentSetOf(SERVANT),
                setPower = 0,
                setToughness = 0,
            ),
        sourceCard = SOURCE,
    )
}

/** A second, later sublayer-7b effect on the Anvil — the CR 613.7 within-layer ordering case. */
private fun setPowerToughness(
    state: GameState,
    power: Int,
    toughness: Int,
): GameState =
    applyIndefinitely(
        state,
        affected = ANVIL,
        modification = ContinuousModification(setPower = power, setToughness = toughness),
        sourceCard = SOURCE,
    )
