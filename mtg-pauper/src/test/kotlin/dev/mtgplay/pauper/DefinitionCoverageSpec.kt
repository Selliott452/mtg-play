package dev.mtgplay.pauper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Definition coverage (P6.2b): both MVP mainboards are now fully playable — every mainboard card
 * resolves to a rules [dev.mtgplay.core.definition.CardDefinition] in [MvpCards]. P6.2b drove the
 * P6.1/P6.2 gap lists to empty; these assertions pin that the gap is closed (deliverable 3).
 */
class DefinitionCoverageSpec :
    StringSpec({
        val loader = DeckLoader(MvpCardPool.catalog)

        "P6.2b: the Mono-Red Madness mainboard has no definition gaps" {
            val report = DefinitionCoverage.check(loader.load(MvpDecks.monoRedMadness))
            report.missingNames.shouldBeEmpty()
            report.isPlayable.shouldBeTrue()
        }

        "P6.2b: the GW Bogles mainboard has no definition gaps" {
            val report = DefinitionCoverage.check(loader.load(MvpDecks.gwBogles))
            report.missingNames.shouldBeEmpty()
            report.isPlayable.shouldBeTrue()
        }

        "coverage is distinct from legality: a legal deck against an empty registry is unplayable" {
            val loaded = loader.load(MvpDecks.gwBogles)
            // The distinction still holds structurally: measured against an empty registry, every
            // mainboard card is "missing" though the deck validates perfectly legal.
            val coverage = DefinitionCoverage.check(loaded, definitions = emptyMap())
            val legality = PauperValidator.validate(loaded)
            legality.isLegal shouldBe true
            coverage.isPlayable shouldBe false
        }
    })
