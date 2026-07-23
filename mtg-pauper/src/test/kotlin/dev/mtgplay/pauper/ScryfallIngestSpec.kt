package dev.mtgplay.pauper

import dev.mtgplay.core.mana.Color
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Ingestion of the staged Scryfall snapshot (P6.1): a faithful round-trip of every field, correct
 * derived facts (Basic supertype, colors, legality), and loud failure on malformed data.
 */
class ScryfallIngestSpec :
    StringSpec({
        val catalog = MvpCardPool.catalog

        "the snapshot ingests every staged card" {
            // The staged snapshot carries the 43 MVP-pool cards (packet fixture).
            catalog.cards.size shouldBe EXPECTED_SNAPSHOT_CARD_COUNT
        }

        "the attribution string is carried through for the CC BY 4.0 obligation (ADR-003)" {
            catalog.attribution shouldContain "Scryfall"
            catalog.attribution shouldContain "CC BY 4.0"
        }

        "P6.1: a creature card round-trips every field faithfully" {
            val guttersnipe = catalog.metadataFor("Guttersnipe") ?: error("Guttersnipe missing from the snapshot")
            guttersnipe.manaCost shouldBe "{2}{R}"
            guttersnipe.typeLine shouldBe "Creature — Goblin Shaman"
            guttersnipe.power shouldBe "2"
            guttersnipe.toughness shouldBe "2"
            guttersnipe.colors shouldBe setOf(Color.RED)
            guttersnipe.pauperLegality shouldBe Legality.LEGAL
            guttersnipe.oracleId shouldBe "c6bdaf76-6a03-4695-9c4b-f040e73435af"
            guttersnipe.oracleText shouldContain "instant or sorcery"
        }

        "CR 208: a non-creature card has null power and toughness" {
            val bolt = catalog.metadataFor("Lightning Bolt") ?: error("Lightning Bolt missing")
            bolt.power.shouldBeNull()
            bolt.toughness.shouldBeNull()
        }

        "CR 205.4: a basic land is recognised as Basic, and only it among the lands" {
            val mountain = catalog.metadataFor("Mountain") ?: error("Mountain missing")
            val ashBarrens = catalog.metadataFor("Ash Barrens") ?: error("Ash Barrens missing")
            mountain.isBasic shouldBe true
            mountain.manaCost shouldBe ""
            mountain.colors shouldBe emptySet()
            // Ash Barrens is a non-basic land (type line "Land"), so it is not copy-limit-exempt.
            ashBarrens.isBasic shouldBe false
        }

        "CR 202.2: a hybrid-cost card keeps its raw cost string and both colors" {
            val bogle = catalog.metadataFor("Slippery Bogle") ?: error("Slippery Bogle missing")
            bogle.manaCost shouldBe "{G/U}"
            bogle.colors shouldContainExactly setOf(Color.GREEN, Color.BLUE)
        }

        "malformed data fails loudly: a missing required field is rejected" {
            val malformed = """{ "cards": [ { "name": "Broken" } ], "source": "x" }"""
            val failure = shouldThrow<IllegalStateException> { ScryfallIngest.parse(malformed) }
            failure.message shouldContain "Broken"
        }

        "malformed data fails loudly: an unrecognised legality token is rejected" {
            val bad = shouldThrow<IllegalStateException> { Legality.ofScryfall("someday_maybe") }
            bad.message shouldContain "someday_maybe"
        }
    })

/** The card count of the architect-staged snapshot (P6.1 fixture). */
private const val EXPECTED_SNAPSHOT_CARD_COUNT = 43
