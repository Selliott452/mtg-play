package dev.mtgplay.rules

import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.rules.engine.MAX_LIBRARY_ARRANGEMENTS
import dev.mtgplay.rules.engine.arrangementsFor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
                val options =
                    arrangementsFor(LibraryLookMode.Scry(maxOf(poolSize, 1)), poolSize, matching = emptyList())
                options.size shouldBe expected
                options.distinct().size shouldBe expected
                options.all { it.isTotalOver(poolSize) } shouldBe true
            }
        }

        "CR 701.17a: every scry outcome is reachable — each ordered (bottom, top) split appears exactly once" {
            val options = arrangementsFor(LibraryLookMode.Scry(3), 3, matching = emptyList())
            val outcomes = options.map { it.toBottom to it.toTop }.toSet()
            // 4! distinct (bottom-order, top-order) pairs whose concatenation covers 0..2.
            outcomes.size shouldBe options.size
            outcomes.contains(emptyList<Int>() to listOf(2, 0, 1)) shouldBe true
            outcomes.contains(listOf(2, 0, 1) to emptyList<Int>()) shouldBe true
            outcomes.contains(listOf(1) to listOf(2, 0)) shouldBe true
        }

        "CR 701.14a: a reorder-the-top clause admits exactly N! orderings, all on top" {
            val options = arrangementsFor(LibraryLookMode.ReorderTop(3), 3, matching = emptyList())
            options.size shouldBe 6
            options.all { it.toHand.isEmpty() && it.toBottom.isEmpty() } shouldBe true
            options.map { it.toTop }.distinct().size shouldBe 6
        }

        "ADR-005: a mandatory keep enumerates exactly one kept card per arrangement, never zero" {
            val options = arrangementsFor(LibraryLookMode.OneToHandRestToBottom(4), 4, matching = emptyList())
            options.size shouldBe 24
            options.all { it.toHand.size == 1 && it.toTop.isEmpty() } shouldBe true
            // Each of the four cards is keepable, so no card is silently excluded from the hand.
            options.map { it.toHand.single() }.toSet() shouldBe setOf(0, 1, 2, 3)
        }

        "CR 701: a mandatory keep over an empty pool keeps nothing rather than enumerating nothing" {
            val options = arrangementsFor(LibraryLookMode.OneToHandRestToBottom(4), 0, matching = emptyList())
            options.size shouldBe 1
            options.single().isTotalOver(0) shouldBe true
        }

        "CR 701.14a: a hand-to-top clause enumerates the ordered k-selections and keeps the residue in hand" {
            val options = arrangementsFor(LibraryLookMode.HandToTop(2), 4, matching = emptyList())
            // P(4, 2) = 12 ordered placements.
            options.size shouldBe 12
            options.all { it.toTop.size == 2 && it.toBottom.isEmpty() } shouldBe true
            // The residue is canonical — hand order — so no two options differ only in an unobservable order.
            options.all { it.toHand == it.toHand.sorted() } shouldBe true
        }

        "CR 701: a hand-to-top clause places as many as the hand holds when it is short" {
            val options = arrangementsFor(LibraryLookMode.HandToTop(2), 1, matching = emptyList())
            options.size shouldBe 1
            options.single().toTop shouldBe listOf(0)
        }

        "ADR-005/ADR-006: the enumeration is a pure function of the mode and the pool size" {
            val first = arrangementsFor(LibraryLookMode.Scry(3), 3, matching = emptyList())
            val second = arrangementsFor(LibraryLookMode.Scry(3), 3, matching = emptyList())
            first shouldBe second
        }

        "CR 701.17a: an arrangement space over the engine's budget fails loudly rather than truncating" {
            // A scry 6 would be 7! = 5040 outcomes; the budget is checked before the walk allocates anything.
            val failure =
                shouldThrow<IllegalStateException> {
                    arrangementsFor(LibraryLookMode.Scry(6), 6, matching = emptyList())
                }
            failure.message.orEmpty() shouldContain MAX_LIBRARY_ARRANGEMENTS.toString()
        }

        "CR 701.17a: the budget admits the deepest look in the gauntlet" {
            arrangementsFor(LibraryLookMode.Scry(5), 5, matching = emptyList()).size shouldBe MAX_LIBRARY_ARRANGEMENTS
        }

        "CR 701.16a: a filtered keep may take only a matching card, and declining is enumerated" {
            // Ancient Stirrings' shape: look at five, keep at most one — with only positions 1 and 3 matching.
            val options = arrangementsFor(stirringsMode(5), 5, matching = listOf(1, 3))
            // 1 * 5! (keep nothing) + 2 * 4! (keep one of the two matches) = 168.
            options.size shouldBe 168
            options.all { it.isTotalOver(5) && it.toTop.isEmpty() } shouldBe true
            // No non-matching card is ever offered to the hand, and no arrangement keeps more than one.
            options.flatMap { it.toHand }.toSet() shouldBe setOf(1, 3)
            options.all { it.toHand.size <= 1 } shouldBe true
            // Declining is legal and, by the ascending-subset order, is the very first index.
            options.first().toHand.shouldBeEmpty()
        }

        "CR 701.16a: a filtered keep over a pool with no matching card enumerates only the decline orderings" {
            val options = arrangementsFor(stirringsMode(3), 3, matching = emptyList())
            // Nothing may be kept, so the whole space is the 3! orders of putting everything on the bottom.
            options.size shouldBe 6
            options.all { it.toHand.isEmpty() && it.toTop.isEmpty() } shouldBe true
        }

        "CR 701.16a: an any-number keep enumerates every subset of the matching cards" {
            // Lead the Stampede's shape: look at five, keep any number of matches — all five matching.
            val options = arrangementsFor(stampedeMode(5), 5, matching = listOf(0, 1, 2, 3, 4))
            // sum over k of C(5, k) * (5 - k)! = 120 + 120 + 60 + 20 + 5 + 1 = 326.
            options.size shouldBe 326
            options.distinct().size shouldBe 326
            options.all { it.isTotalOver(5) && it.toTop.isEmpty() } shouldBe true
            // Every subset size from none to all five is reachable — the "any number" the card prints.
            options.map { it.toHand.size }.toSet() shouldBe setOf(0, 1, 2, 3, 4, 5)
            // The kept cards are listed in pool order, so two options never differ only in hand order.
            options.all { it.toHand == it.toHand.sorted() } shouldBe true
        }

        "ADR-005: a filtered keep enumerated against the wrong pool positions fails loudly" {
            val failure =
                shouldThrow<IllegalArgumentException> {
                    arrangementsFor(stampedeMode(3), 3, matching = listOf(1, 5))
                }
            failure.message.orEmpty() shouldContain "matching indices"
        }
    })

/** Ancient Stirrings' mode at depth [count]: look, then keep **at most one** colorless card. */
private fun stirringsMode(count: Int) =
    LibraryLookMode.RevealMatchingToHandRestToBottom(
        count = count,
        toHand = RevealedCardFilter.COLORLESS_CARD,
        maxToHand = 1,
    )

/** Lead the Stampede's mode at depth [count]: look, then keep **any number** of creature cards. */
private fun stampedeMode(count: Int) =
    LibraryLookMode.RevealMatchingToHandRestToBottom(
        count = count,
        toHand = RevealedCardFilter.CREATURE_CARD,
        maxToHand = count,
    )
