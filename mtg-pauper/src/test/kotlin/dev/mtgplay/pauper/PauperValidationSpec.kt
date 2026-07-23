package dev.mtgplay.pauper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Pauper deck-construction validation (P6.1, CR 100.2a): each rule in isolation, the all-violations
 * report, and the two real decklists validating legal.
 */
class PauperValidationSpec :
    StringSpec({
        val loader = DeckLoader(MvpCardPool.catalog)

        "CR 100.2a: a mainboard below 60 cards is rejected" {
            val report = PauperValidator.validate(loadedDeck(main = listOf(snapshotCard("Mountain", 59))))
            report.violations shouldContainExactly listOf(DeckViolation.MainDeckTooSmall(59, 60))
        }

        "CR 100.2a: any number of basic lands is allowed" {
            val report = PauperValidator.validate(loadedDeck(main = listOf(snapshotCard("Mountain", 60))))
            report.isLegal.shouldBeTrue()
        }

        "CR 100.2a: a fifth copy of a non-basic card is rejected" {
            val report =
                PauperValidator.validate(
                    loadedDeck(main = listOf(snapshotCard("Lightning Bolt", 5), snapshotCard("Mountain", 55))),
                )
            report.violations shouldContainExactly listOf(DeckViolation.TooManyCopies("Lightning Bolt", 5, 4))
        }

        "CR 100.2a: copies are counted across main and sideboard together" {
            val report =
                PauperValidator.validate(
                    loadedDeck(
                        main = listOf(snapshotCard("Lightning Bolt", 3), snapshotCard("Mountain", 57)),
                        sideboard = listOf(snapshotCard("Lightning Bolt", 2)),
                    ),
                )
            report.violations shouldContainExactly listOf(DeckViolation.TooManyCopies("Lightning Bolt", 5, 4))
        }

        "CR 100.2: a card not legal in Pauper is rejected" {
            val report =
                PauperValidator.validate(
                    loadedDeck(
                        main = listOf(fabricatedCard("Contraband", 4, Legality.BANNED), snapshotCard("Mountain", 56)),
                    ),
                )
            report.violations shouldContainExactly listOf(DeckViolation.IllegalCard("Contraband", Legality.BANNED))
        }

        "a sideboard above 15 cards is rejected" {
            val report =
                PauperValidator.validate(
                    loadedDeck(
                        main = listOf(snapshotCard("Mountain", 60)),
                        sideboard =
                            listOf(
                                snapshotCard("Pyroblast", 4),
                                snapshotCard("Relic of Progenitus", 3),
                                snapshotCard("Forest", 9),
                            ),
                    ),
                )
            report.violations shouldContainExactly listOf(DeckViolation.SideboardTooLarge(16, 15))
        }

        "every violation is reported, not just the first" {
            val report =
                PauperValidator.validate(
                    loadedDeck(
                        main = listOf(fabricatedCard("Contraband", 5, Legality.NOT_LEGAL)),
                        sideboard = listOf(snapshotCard("Mountain", 16)),
                    ),
                )
            // Mainboard too small (5), sideboard too large (16), an illegal card, and >4 copies of it.
            report.violations shouldContainExactlyInAnyOrder
                listOf(
                    DeckViolation.MainDeckTooSmall(5, 60),
                    DeckViolation.SideboardTooLarge(16, 15),
                    DeckViolation.IllegalCard("Contraband", Legality.NOT_LEGAL),
                    DeckViolation.TooManyCopies("Contraband", 5, 4),
                )
        }

        "both MVP decklists validate as legal Pauper decks" {
            MvpDecks.all.forEach { deck ->
                val report = PauperValidator.validate(loader.load(deck))
                report.isLegal.shouldBeTrue()
                report.violations shouldBe emptyList()
            }
        }

        "the Mono-Red mainboard is exactly 60 and its sideboard exactly 15" {
            val loaded = loader.load(MvpDecks.monoRedMadness)
            loaded.mainCount shouldBe 60
            loaded.sideboardCount shouldBe 15
        }

        "an over-limit non-basic land is still copy-limited (only basics are exempt)" {
            // Ash Barrens is a non-basic Land; five copies exceed the limit.
            val report =
                PauperValidator.validate(
                    loadedDeck(main = listOf(snapshotCard("Ash Barrens", 5), snapshotCard("Mountain", 55))),
                )
            report.violations shouldContainExactly listOf(DeckViolation.TooManyCopies("Ash Barrens", 5, 4))
            snapshotMeta("Ash Barrens").isBasic.shouldBeFalse()
        }
    })
