package dev.mtgplay.pauper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** The decklist text-format parser (P6.1): sections, counts, comments, and loud malformation failures. */
class DeckListParserSpec :
    StringSpec({
        "the format parses name, sections, counts, and comments" {
            val text =
                """
                # a comment
                Name: Sample

                Main
                4 Lightning Bolt
                18 Mountain

                Sideboard
                2 Pyroblast
                """.trimIndent()
            val deck = DeckListParser.parse(text)
            deck.name shouldBe "Sample"
            deck.main shouldBe listOf(DeckEntry(4, "Lightning Bolt"), DeckEntry(18, "Mountain"))
            deck.sideboard shouldBe listOf(DeckEntry(2, "Pyroblast"))
        }

        "a card name with spaces and an apostrophe parses whole" {
            val deck = DeckListParser.parse("1 Sentinel's Eyes")
            deck.main shouldBe listOf(DeckEntry(1, "Sentinel's Eyes"))
        }

        "entries before any header default to the mainboard" {
            val deck = DeckListParser.parse("4 Mountain")
            deck.main shouldBe listOf(DeckEntry(4, "Mountain"))
        }

        "a non-integer count fails loudly with the line number" {
            val failure = shouldThrow<IllegalStateException> { DeckListParser.parse("Main\nx Mountain") }
            failure.message shouldContain "line 2"
        }

        "a line with no card name fails loudly" {
            shouldThrow<IllegalArgumentException> { DeckListParser.parse("4") }
        }

        "the two MVP decklists parse to the documented totals" {
            MvpDecks.monoRedMadness.mainCount shouldBe 60
            MvpDecks.monoRedMadness.sideboardCount shouldBe 15
            MvpDecks.gwBogles.mainCount shouldBe 60
            MvpDecks.gwBogles.sideboardCount shouldBe 15
        }
    })
