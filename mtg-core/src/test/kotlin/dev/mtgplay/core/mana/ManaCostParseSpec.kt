package dev.mtgplay.core.mana

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.persistentListOf

/**
 * Example-based parsing and rendering of Scryfall brace syntax, including the loud rejection
 * of everything outside the MVP mana model (CR 107.4; docs/decklists.md).
 */
class ManaCostParseSpec :
    StringSpec({
        "parses {1}{G}{G} into a generic symbol followed by two green symbols" {
            ManaCost.parse("{1}{G}{G}").symbols shouldBe
                persistentListOf(
                    ManaSymbol.Generic(1),
                    ManaSymbol.Colored(Color.GREEN),
                    ManaSymbol.Colored(Color.GREEN),
                )
        }

        "CR 107.4c: {C} parses as the colorless symbol, distinct from generic {1}" {
            ManaCost.parse("{C}").symbols shouldBe persistentListOf(ManaSymbol.Colorless)
            ManaCost.parse("{C}") shouldNotBe ManaCost.parse("{1}")
        }

        "{0} is a legal cost of one generic symbol" {
            ManaCost.parse("{0}").symbols shouldBe persistentListOf(ManaSymbol.Generic(0))
        }

        "multi-digit generic symbols parse as one symbol: {10}" {
            ManaCost.parse("{10}").symbols shouldBe persistentListOf(ManaSymbol.Generic(10))
        }

        "hybrid printed order is preserved: {G/U} and {U/G} are distinct" {
            ManaCost.parse("{G/U}").symbols shouldBe
                persistentListOf(ManaSymbol.Hybrid(Color.GREEN, Color.BLUE))
            ManaCost.parse("{G/U}") shouldNotBe ManaCost.parse("{U/G}")
        }

        "Phyrexian symbols parse to their color: {R/P}" {
            ManaCost.parse("{R/P}").symbols shouldBe persistentListOf(ManaSymbol.Phyrexian(Color.RED))
        }

        "{X} is rejected loudly: deliberately outside the MVP mana model (architect decision, P1.1)" {
            shouldThrow<IllegalArgumentException> { ManaCost.parse("{X}") }
        }

        "snow {S} and monocolored hybrid {2/W} are rejected loudly" {
            shouldThrow<IllegalArgumentException> { ManaCost.parse("{S}") }
            shouldThrow<IllegalArgumentException> { ManaCost.parse("{2/W}") }
        }

        "malformed text is rejected: missing and unterminated braces" {
            shouldThrow<IllegalArgumentException> { ManaCost.parse("R") }
            shouldThrow<IllegalArgumentException> { ManaCost.parse("{R") }
        }

        "the empty string is rejected: a card with no mana cost is the absence of a ManaCost" {
            shouldThrow<IllegalArgumentException> { ManaCost.parse("") }
        }

        "a ManaCost value requires at least one symbol" {
            shouldThrow<IllegalArgumentException> { ManaCost(persistentListOf()) }
        }

        "a generic symbol rejects a negative amount" {
            shouldThrow<IllegalArgumentException> { ManaSymbol.Generic(-1) }
        }

        "a hybrid symbol rejects identical components" {
            shouldThrow<IllegalArgumentException> { ManaSymbol.Hybrid(Color.GREEN, Color.GREEN) }
        }
    })
