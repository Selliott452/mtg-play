package dev.mtgplay.acceptance

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.random.shuffled
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.LayeredCharacteristics
import dev.mtgplay.rules.engine.layeredCharacteristics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.toPersistentList

private const val PERMUTATION_BOARDS = 300
private const val PERMUTATIONS_PER_BOARD = 3
private const val ADDITIVITY_BOARDS = 300
private const val PURITY_BOARDS = 200
private const val PURITY_RECOMPUTES = 4

/**
 * The five CR 613 properties the layer engine must satisfy (P4.3 deliverable 4, docs/design/layer-system.md
 * §8), each guarding a decision the design note fixed: **order-independence** (the property that justifies
 * sourcing timestamps from ObjectId-entry order and building no timestamp field, §3), **layer isolation**
 * (grants and modifiers live in disjoint layers), **additivity** (layer 7c is a sum), **purity**
 * (compute-on-read keeps no cache, §5), and the **loud gate** (an effect in an unpopulated layer throws,
 * never silently drops, §1). The numeric multi-Aura scenarios of §8 (two Ethereal Armors; Ethereal Armor +
 * Ancestral Mask) are pinned in `mtg-cards` AurasSpec; these are the general laws over random boards.
 */
class LayerPropertiesSpec :
    StringSpec({

        "CR 613.7 and §3: layered characteristics are invariant under any Aura timestamp (ObjectId) permutation" {
            // The order-independence property, and the reason the §3 deferred-timestamp decision is
            // correct rather than merely convenient. Timestamps are sourced from ObjectId-entry order
            // with no dedicated field; the apply loop still sorts by them (the 613.7 spine is real). That
            // is only *safe* because every within-layer MVP interaction commutes — additive layer-7c
            // modifiers and set-union layer-6 grants — so timestamp order is unobservable. Reassigning
            // which id (hence timestamp) each Aura holds must leave every host's characteristics
            // identical. A regression that let a non-commuting effect kind in (ability removal, set-P/T,
            // a dependency) would surface here as a mismatch.
            var permutedBoards = 0
            for (seed in 0L until PERMUTATION_BOARDS) {
                val board = randomBoard(seed)
                val auraIds = auraIdsInEntryOrder(board)
                if (auraIds.size < 2) continue
                permutedBoards += 1
                val baseline = hostCharacteristics(board)
                // A reversal is a guaranteed non-identity permutation for the distinct increasing ids.
                withClue("seed=$seed permutation=reversed") {
                    hostCharacteristics(withAuraTimestamps(board, auraIds.reversed())) shouldBe baseline
                }
                // Plus uniformly random permutations via the frozen shuffle (ADR-006).
                var rng = Rng(seed)
                repeat(PERMUTATIONS_PER_BOARD) {
                    val (shuffled, next) = auraIds.toPersistentList().shuffled(rng)
                    rng = next
                    withClue("seed=$seed permutation=shuffled") {
                        hostCharacteristics(withAuraTimestamps(board, shuffled)) shouldBe baseline
                    }
                }
            }
            // Non-vacuous: the corpus contained boards with an observable (>= 2 Aura) permutation.
            permutedBoards shouldBeGreaterThan 0
        }

        "CR 613 layer isolation: a layer-6 keyword grant never moves P/T (grants and modifiers are disjoint layers)" {
            // Grizzly Bears (printed 2/2) enchanted ONLY by keyword-grant-only Auras: the grants union
            // into the keyword set (layer 6); P/T (layer 7c) is untouched, staying the printed 2/2.
            val bears = GameObject(ObjectId(0), CardRef("Grizzly Bears"), alice, summoningSick = false)
            val trample = GameObject(ObjectId(1), CardRef(TRAMPLE_SIGIL), alice, attachedTo = ObjectId(0))
            val vigilance = GameObject(ObjectId(2), CardRef(VIGILANCE_SIGIL), alice, attachedTo = ObjectId(0))
            val state = layerBoard(listOf(bears, trample, vigilance), syntheticLayerDefinitions)
            val layered = layeredCharacteristics(state, ObjectId(0))
            layered.power shouldBe 2
            layered.toughness shouldBe 2
            layered.keywords.shouldContainExactlyInAnyOrder(Keyword.TRAMPLE, Keyword.VIGILANCE)
        }

        "CR 613 layer isolation: a layer-7c P/T modifier never grants a keyword" {
            // Wind Drake (printed 2/2, flying) enchanted only by Ancestral Mask (+2/+2 per OTHER
            // enchantment, no keyword). Two Masks each see one other enchantment -> +4/+4 -> 6/6, but the
            // keyword set stays exactly the printed {flying}: a modifier adds no ability.
            val drake = GameObject(ObjectId(0), CardRef("Wind Drake"), alice, summoningSick = false)
            val maskA = GameObject(ObjectId(1), CardRef("Ancestral Mask"), alice, attachedTo = ObjectId(0))
            val maskB = GameObject(ObjectId(2), CardRef("Ancestral Mask"), alice, attachedTo = ObjectId(0))
            val layered = layeredCharacteristics(layerBoard(listOf(drake, maskA, maskB)), ObjectId(0))
            layered.power shouldBe 6
            layered.toughness shouldBe 6
            layered.keywords.shouldContainExactlyInAnyOrder(Keyword.FLYING)
        }

        "CR 613 sublayer 7c additivity: layered P/T equals the printed base plus the sum of every modifier" {
            for (seed in 0L until ADDITIVITY_BOARDS) {
                val state = randomBoard(seed)
                val battlefield = state.sharedZones.battlefield
                for (creature in battlefield.filter { isCreatureOnBattlefield(state, it) }) {
                    val printed =
                        state.definitions
                            .getValue(creature.card)
                            .characteristics.powerToughness
                            ?: error("CR 208.1: a creature host has a printed P/T box")
                    val auras = battlefield.filter { it.attachedTo == creature.id }
                    val powerMods = auras.sumOf { modifierSum(state, it, isPower = true) }
                    val toughnessMods = auras.sumOf { modifierSum(state, it, isPower = false) }
                    val layered = layeredCharacteristics(state, creature.id)
                    withClue("seed=$seed id=${creature.id.value} card=${creature.card.name}") {
                        layered.power shouldBe printed.power + powerMods
                        layered.toughness shouldBe printed.toughness + toughnessMods
                    }
                }
            }
        }

        "CR 613.3c and §5 compute-on-read: recomputing on the same state is identical across repeated calls" {
            // Compute-on-read stores no computed characteristic (§5); repeated reads of one state must
            // return equal results, which also proves no hidden mutable memo drifts between calls.
            for (seed in 0L until PURITY_BOARDS) {
                val state = randomBoard(seed)
                for (obj in state.sharedZones.battlefield) {
                    val first = layeredCharacteristics(state, obj.id)
                    repeat(PURITY_RECOMPUTES) {
                        withClue("seed=$seed id=${obj.id.value}") {
                            layeredCharacteristics(state, obj.id) shouldBe first
                        }
                    }
                }
            }
        }

        "CR 613 §1 loud gate: an effect that classifies into no populated layer throws, never silently drops" {
            // Grizzly Bears enchanted by an EMPTY-effect Aura (no layer-6 grant, no layer-7c modifier).
            // The engine's requireImplementedKind gate must fail loudly through the public accessor; a
            // silent drop that returned the base 2/2 is the forbidden outcome (§1).
            val bears = GameObject(ObjectId(0), CardRef("Grizzly Bears"), alice, summoningSick = false)
            val hollow = GameObject(ObjectId(1), CardRef(HOLLOW_AURA), alice, attachedTo = ObjectId(0))
            val state = layerBoard(listOf(bears, hollow), syntheticLayerDefinitions)
            val error = shouldThrow<IllegalArgumentException> { layeredCharacteristics(state, ObjectId(0)) }
            error.message.shouldBeInstanceOf<String>() shouldContain "613"
        }
    })

/** The layered characteristics of every host (non-Aura battlefield object), keyed by its stable id. */
private fun hostCharacteristics(state: GameState): Map<ObjectId, LayeredCharacteristics> =
    state.sharedZones.battlefield
        .filter { it.attachedTo == null }
        .associate { it.id to layeredCharacteristics(state, it.id) }

/** Whether the battlefield object [obj] is a creature by its printed type (CR 302.1). */
private fun isCreatureOnBattlefield(
    state: GameState,
    obj: GameObject,
): Boolean =
    state.definitions[obj.card]
        ?.characteristics
        ?.cardTypes
        ?.contains(CardType.CREATURE) == true

/** The sum of one [aura]'s power (or toughness) modifiers, each magnitude read live against [state] (CR 613.3c). */
private fun modifierSum(
    state: GameState,
    aura: GameObject,
    isPower: Boolean,
): Int {
    val effects: List<StaticContinuousEffect> = state.definitions[aura.card]?.staticContinuousEffects ?: emptyList()
    return effects.sumOf { effect ->
        evaluateMagnitudeNaively(if (isPower) effect.powerMod else effect.toughnessMod, state, aura.id)
    }
}
