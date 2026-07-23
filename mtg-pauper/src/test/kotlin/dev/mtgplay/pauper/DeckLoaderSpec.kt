package dev.mtgplay.pauper

import dev.mtgplay.core.identity.CardRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/** The deck loader (P6.1): name resolution to metadata + [CardRef], and loud, complete unknown-name reporting. */
class DeckLoaderSpec :
    StringSpec({
        val loader = DeckLoader(testCatalog)

        "a decklist of known cards resolves every entry to its metadata and CardRef" {
            val list =
                DeckList(
                    name = "burn",
                    main = listOf(DeckEntry(4, "Lightning Bolt"), DeckEntry(56, "Mountain")),
                    sideboard = emptyList(),
                )
            val loaded = loader.load(list)
            loaded.mainCount shouldBe 60
            loaded.main.first().ref shouldBe CardRef("Lightning Bolt")
            loaded.main
                .first()
                .metadata.oracleId shouldBe "4457ed35-7c10-48c8-9776-456485fdf070"
            loaded.mainLibrary().size shouldBe 60
            loaded.mainLibrary().count { it == CardRef("Lightning Bolt") } shouldBe 4
        }

        "both MVP decklists load without error" {
            val loader2 = DeckLoader(MvpCardPool.catalog)
            MvpDecks.all.forEach { deck ->
                val loaded = loader2.load(deck)
                loaded.mainCount shouldBe 60
            }
        }

        "an unknown card name fails loudly, listing every unresolved name at once" {
            val list =
                DeckList(
                    name = "typos",
                    main = listOf(DeckEntry(4, "Lightnin Bolt"), DeckEntry(4, "Montain"), DeckEntry(52, "Mountain")),
                    sideboard = listOf(DeckEntry(1, "Montain")),
                )
            val failure = shouldThrow<UnknownCardsException> { loader.load(list) }
            // Distinct, first-appearance order — the whole set surfaces in one pass.
            failure.names shouldContainExactly listOf("Lightnin Bolt", "Montain")
        }

        "resolution preserves both boards" {
            val list =
                DeckList(
                    name = "split",
                    main = listOf(DeckEntry(60, "Mountain")),
                    sideboard = listOf(DeckEntry(4, "Pyroblast")),
                )
            val loaded = loader.load(list)
            loaded.sideboard.map { it.ref } shouldContainExactlyInAnyOrder listOf(CardRef("Pyroblast"))
        }
    })
