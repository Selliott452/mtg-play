package dev.mtgplay.core.random

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe

private const val SAMPLE_COUNT: Int = 2000
private const val MAX_BOUND: Int = 1000

/**
 * Known-answer tests pinning the frozen PRNG contract (ADR-006). The algorithm is splitmix64
 * exactly as in Sebastiano Vigna's public-domain reference (`prng.di.unimi.it/splitmix64.c`);
 * the seed-0 vector below is that reference implementation's widely published output. If one
 * of these assertions fails the algorithm changed, and every recorded replay corpus is
 * invalid: fix the code, never the vector.
 */
class RngSpec :
    StringSpec({
        "splitmix64 known-answer vector: seed 0 produces the published first eight outputs" {
            val expected =
                listOf(
                    0xE220A8397B1DCDAFuL,
                    0x6E789E6AA1B965F4uL,
                    0x06C45D188009454FuL,
                    0xF88BB8A8724C81ECuL,
                    0x1B39896A51A8749BuL,
                    0x53CB9F0C747EA2EAuL,
                    0x2C829ABE1F4532E1uL,
                    0xC584133AC916AB3CuL,
                )
            var rng = Rng(0)
            expected.forEach { want ->
                val (value, next) = rng.nextLong()
                value.toULong() shouldBe want
                rng = next
            }
        }

        "splitmix64 known-answer vector: seed 42 (cross-checked against an independent implementation)" {
            val expected =
                listOf(
                    0xBDD732262FEB6E95uL,
                    0x28EFE333B266F103uL,
                    0x47526757130F9F52uL,
                    0x581CE1FF0E4AE394uL,
                )
            var rng = Rng(42)
            expected.forEach { want ->
                val (value, next) = rng.nextLong()
                value.toULong() shouldBe want
                rng = next
            }
        }

        "drawing is pure: the same Rng value always yields the same draw (ADR-002)" {
            val rng = Rng(123)
            rng.nextLong() shouldBe rng.nextLong()
            rng.nextInt(10) shouldBe rng.nextInt(10)
        }

        "nextInt stays in [0, bound) for many seeds and bounds" {
            // Seeds and bounds are drawn deterministically from an independent generator: the
            // seeded PRNG is the only sanctioned source of randomness (ADR-006), so tests draw
            // from it rather than from kotlin.random, which the ForbiddenImport rule bans.
            var generator = Rng(0x5EEDL)
            repeat(SAMPLE_COUNT) {
                val (seed, afterSeed) = generator.nextLong()
                val (boundLessOne, afterBound) = afterSeed.nextInt(MAX_BOUND)
                generator = afterBound
                val bound = boundLessOne + 1
                val (value, _) = Rng(seed).nextInt(bound)
                value shouldBeInRange (0 until bound)
            }
        }

        "nextInt(1) always draws 0" {
            var generator = Rng(0xB0FFL)
            repeat(SAMPLE_COUNT) {
                val (seed, next) = generator.nextLong()
                generator = next
                Rng(seed).nextInt(1).first shouldBe 0
            }
        }

        "nextInt rejects a non-positive bound" {
            shouldThrow<IllegalArgumentException> { Rng(0).nextInt(0) }
            shouldThrow<IllegalArgumentException> { Rng(0).nextInt(-3) }
        }

        "nextInt known-answer: seed 7, bound 6 produces the pinned sequence" {
            var rng = Rng(7)
            val drawn =
                buildList {
                    repeat(12) {
                        val (value, next) = rng.nextInt(6)
                        add(value)
                        rng = next
                    }
                }
            drawn shouldBe listOf(3, 0, 0, 3, 4, 3, 4, 0, 5, 5, 1, 4)
        }
    })
