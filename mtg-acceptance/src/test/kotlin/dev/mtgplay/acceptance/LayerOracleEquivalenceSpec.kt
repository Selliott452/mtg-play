package dev.mtgplay.acceptance

import dev.mtgplay.rules.engine.layeredCharacteristics
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/** Random boards to compare — well above the packet's ≥ 500 floor, still a sub-second run. */
private const val EQUIVALENCE_BOARDS = 600

/**
 * The layer engine's equivalence to the brute-force oracle (P4.3 deliverable 3, docs/design/layer-system.md
 * §8): over [EQUIVALENCE_BOARDS] random boards, the engine's [layeredCharacteristics] set-equals the
 * independent [oracleCharacteristics] for **every** battlefield object — P/T, keywords, and granted mana
 * abilities. Both directions are proved by construction: a missing or phantom contribution on either
 * side is a mismatch. The two computations share no CR 613 classification code (the oracle re-derives
 * attachment and the additive sum/union itself); a dynamic magnitude is read live against the same state
 * on both sides (CR 613.3c), which is card data, not layer logic.
 */
class LayerOracleEquivalenceSpec :
    StringSpec({

        "CR 613 and §8: layeredCharacteristics set-equals the brute-force oracle over random boards" {
            var objectsChecked = 0
            var aurasSeen = 0
            var dynamicAuras = 0
            var multiAuraHosts = 0
            for (seed in 0L until EQUIVALENCE_BOARDS) {
                val state = randomBoard(seed)
                val battlefield = state.sharedZones.battlefield
                aurasSeen += battlefield.count { it.attachedTo != null }
                dynamicAuras +=
                    battlefield.count { it.card.name == "Ethereal Armor" || it.card.name == "Ancestral Mask" }
                multiAuraHosts += battlefield.count { host -> battlefield.count { it.attachedTo == host.id } >= 2 }
                for (obj in battlefield) {
                    objectsChecked += 1
                    val layered = layeredCharacteristics(state, obj.id)
                    val oracle = oracleCharacteristics(state, obj.id)
                    withClue("seed=$seed id=${obj.id.value} card=${obj.card.name}") {
                        layered.power shouldBe oracle.power
                        layered.toughness shouldBe oracle.toughness
                        layered.keywords.toSet() shouldBe oracle.keywords
                        layered.manaAbilities.toSet() shouldBe oracle.manaAbilities.toSet()
                    }
                }
            }
            // The corpus is meaningful, not a heap of bare creatures: Auras were stacked, including the
            // two dynamic-magnitude cards and hosts carrying several Auras at once.
            aurasSeen shouldBeGreaterThan EQUIVALENCE_BOARDS
            dynamicAuras shouldBeGreaterThan 0
            multiAuraHosts shouldBeGreaterThan 0
            println(
                "LAYER EQUIVALENCE: boards=$EQUIVALENCE_BOARDS objects=$objectsChecked aurasSeen=$aurasSeen " +
                    "dynamicAuras=$dynamicAuras multiAuraHosts=$multiAuraHosts",
            )
        }
    })
