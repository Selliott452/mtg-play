package dev.mtgplay.pauper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe

/**
 * Definition coverage (P6.1): the current mainboard definition gaps for the two MVP decks — the
 * exact set P6.2 must shrink. These assertions are the interface to P6.2: encoding a gap card must
 * update the pinned set here consciously (deliverable 4).
 */
class DefinitionCoverageSpec :
    StringSpec({
        val loader = DeckLoader(MvpCardPool.catalog)

        "P6.2 checklist — Mono-Red Madness mainboard definition gaps (pinned)" {
            val report = DefinitionCoverage.check(loader.load(MvpDecks.monoRedMadness))
            report.missingNames shouldBe
                listOf(
                    "Faithless Looting",
                    "Fiery Temper",
                    "Fireblast",
                    "Grab the Prize",
                    "Guttersnipe",
                    "Highway Robbery",
                    "Lava Dart",
                    "Melded Moxite",
                    "Sneaky Snacker",
                    "Voldaren Epicure",
                )
            report.isPlayable.shouldBeFalse()
        }

        "P6.2 checklist — GW Bogles mainboard definition gaps (pinned)" {
            val report = DefinitionCoverage.check(loader.load(MvpDecks.gwBogles))
            report.missingNames shouldBe
                listOf(
                    "Ash Barrens",
                    "Malevolent Rumble",
                    "Utopia Sprawl",
                )
            report.isPlayable.shouldBeFalse()
        }

        "coverage is distinct from legality: the gap cards are all Pauper-legal" {
            val loaded = loader.load(MvpDecks.gwBogles)
            val coverage = DefinitionCoverage.check(loaded)
            val legality = PauperValidator.validate(loaded)
            // A card can be legal yet unplayable: the deck validates legal even with definition gaps.
            legality.isLegal shouldBe true
            coverage.isPlayable shouldBe false
        }
    })
