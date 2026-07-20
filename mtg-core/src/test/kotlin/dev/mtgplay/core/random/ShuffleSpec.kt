package dev.mtgplay.core.random

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

private const val SAMPLE_COUNT: Int = 500
private const val MAX_LIST_SIZE: Int = 64

// Test inputs are drawn from the seeded PRNG itself: it is the only sanctioned source of
// randomness (ADR-006), so tests draw from it rather than from kotlin.random, which the
// ForbiddenImport rule bans. Generation is therefore deterministic and reproducible.
private fun Rng.randomIntList(): Pair<PersistentList<Int>, Rng> {
    val (size, afterSize) = nextInt(MAX_LIST_SIZE + 1)
    var current = afterSize
    val elements =
        buildList {
            repeat(size) {
                val (value, next) = current.nextLong()
                add(value.toInt())
                current = next
            }
        }
    return elements.toPersistentList() to current
}

/**
 * The deterministic Fisher–Yates shuffle (CR 103.1's shuffle, ADR-006): permutation
 * properties, seed determinism, and known-answer cases pinning the frozen draw order
 * (cross-checked against an independent implementation of the same contract).
 */
class ShuffleSpec :
    StringSpec({
        "shuffling preserves the multiset of elements" {
            var generator = Rng(0x5EEDL)
            repeat(SAMPLE_COUNT) {
                val (list, afterList) = generator.randomIntList()
                val (seed, afterSeed) = afterList.nextLong()
                generator = afterSeed
                val (shuffled, _) = list.shuffled(Rng(seed))
                shuffled.sorted() shouldBe list.sorted()
            }
        }

        "the same seed produces the same order (ADR-006)" {
            var generator = Rng(0xB0FFL)
            repeat(SAMPLE_COUNT) {
                val (list, afterList) = generator.randomIntList()
                val (seed, afterSeed) = afterList.nextLong()
                generator = afterSeed
                val first = list.shuffled(Rng(seed))
                val second = list.shuffled(Rng(seed))
                first shouldBe second
            }
        }

        "shuffle known-answer: seed 42 over 0..7 pins the permutation and the successor state" {
            val (shuffled, rng) = (0..7).toPersistentList().shuffled(Rng(42))
            shuffled shouldBe listOf(3, 1, 6, 2, 4, 0, 7, 5)
            rng.state shouldBe 0x538454127B0964BDuL.toLong()
        }

        "shuffle known-answer: seed 12345 over 0..9 pins the permutation" {
            val (shuffled, _) = (0..9).toPersistentList().shuffled(Rng(12345))
            shuffled shouldBe listOf(8, 6, 7, 2, 1, 3, 9, 5, 0, 4)
        }

        "lists of fewer than two elements come back unchanged with the generator undrawn" {
            persistentListOf<Int>().shuffled(Rng(9)) shouldBe (persistentListOf<Int>() to Rng(9))
            persistentListOf(1).shuffled(Rng(9)) shouldBe (persistentListOf(1) to Rng(9))
        }
    })
