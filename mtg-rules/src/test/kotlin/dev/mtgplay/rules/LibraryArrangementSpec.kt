package dev.mtgplay.rules

import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.rules.engine.MAX_LIBRARY_ARRANGEMENTS
import dev.mtgplay.rules.engine.arrangementsFor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The arrangement enumeration itself (CR 701.14a, CR 701.17a): completeness, the closed-form counts, the
 * deterministic order, and the budget. ADR-005 makes this the definition of legality, so these are the
 * properties everything downstream rests on (docs/design/library-look.md §4).
 */
class LibraryArrangementSpec :
    StringSpec({
        "CR 701.17a: scry N admits exactly (N + 1)! arrangements, all distinct and all total" {
            listOf(0 to 1, 1 to 2, 2 to 6, 3 to 24, 4 to 120).forEach { (poolSize, expected) ->
                val options = arrangementsFor(LibraryLookMode.Scry(maxOf(poolSize, 1)), poolSize)
                options.size shouldBe expected
                options.distinct().size shouldBe expected
                options.all { it.isTotalOver(poolSize) } shouldBe true
            }
        }

        "CR 701.17a: every scry outcome is reachable — each ordered (bottom, top) split appears exactly once" {
            val options = arrangementsFor(LibraryLookMode.Scry(3), 3)
            val outcomes = options.map { it.toBottom to it.toTop }.toSet()
            // 4! distinct (bottom-order, top-order) pairs whose concatenation covers 0..2.
            outcomes.size shouldBe options.size
            outcomes.contains(emptyList<Int>() to listOf(2, 0, 1)) shouldBe true
            outcomes.contains(listOf(2, 0, 1) to emptyList<Int>()) shouldBe true
            outcomes.contains(listOf(1) to listOf(2, 0)) shouldBe true
        }

        "CR 701.14a: a reorder-the-top clause admits exactly N! orderings, all on top" {
            val options = arrangementsFor(LibraryLookMode.ReorderTop(3), 3)
            options.size shouldBe 6
            options.all { it.toHand.isEmpty() && it.toBottom.isEmpty() } shouldBe true
            options.map { it.toTop }.distinct().size shouldBe 6
        }

        "ADR-005: a mandatory keep enumerates exactly one kept card per arrangement, never zero" {
            val options = arrangementsFor(LibraryLookMode.OneToHandRestToBottom(4), 4)
            options.size shouldBe 24
            options.all { it.toHand.size == 1 && it.toTop.isEmpty() } shouldBe true
            // Each of the four cards is keepable, so no card is silently excluded from the hand.
            options.map { it.toHand.single() }.toSet() shouldBe setOf(0, 1, 2, 3)
        }

        "CR 701: a mandatory keep over an empty pool keeps nothing rather than enumerating nothing" {
            val options = arrangementsFor(LibraryLookMode.OneToHandRestToBottom(4), 0)
            options.size shouldBe 1
            options.single().isTotalOver(0) shouldBe true
        }

        "CR 701.14a: a hand-to-top clause enumerates the ordered k-selections and keeps the residue in hand" {
            val options = arrangementsFor(LibraryLookMode.HandToTop(2), 4)
            // P(4, 2) = 12 ordered placements.
            options.size shouldBe 12
            options.all { it.toTop.size == 2 && it.toBottom.isEmpty() } shouldBe true
            // The residue is canonical — hand order — so no two options differ only in an unobservable order.
            options.all { it.toHand == it.toHand.sorted() } shouldBe true
        }

        "CR 701: a hand-to-top clause places as many as the hand holds when it is short" {
            val options = arrangementsFor(LibraryLookMode.HandToTop(2), 1)
            options.size shouldBe 1
            options.single().toTop shouldBe listOf(0)
        }

        "ADR-005/ADR-006: the enumeration is a pure function of the mode and the pool size" {
            val first = arrangementsFor(LibraryLookMode.Scry(3), 3)
            val second = arrangementsFor(LibraryLookMode.Scry(3), 3)
            first shouldBe second
        }

        "CR 701.17a: an arrangement space over the engine's budget fails loudly rather than truncating" {
            // A scry 6 would be 7! = 5040 outcomes; the budget is checked before the walk allocates anything.
            val failure = shouldThrow<IllegalStateException> { arrangementsFor(LibraryLookMode.Scry(6), 6) }
            failure.message.orEmpty() shouldContain MAX_LIBRARY_ARRANGEMENTS.toString()
        }

        "CR 701.17a: the budget admits the deepest look in the gauntlet" {
            arrangementsFor(LibraryLookMode.Scry(5), 5).size shouldBe MAX_LIBRARY_ARRANGEMENTS
        }
    })
