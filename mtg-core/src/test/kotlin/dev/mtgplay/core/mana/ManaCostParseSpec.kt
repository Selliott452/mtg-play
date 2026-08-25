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

        // The assertion this spec used to make -- that {X} is rejected -- was the P1.1 architect
        // decision that no MVP card had a variable cost. `FW-X` reverses it: the symbol is real now,
        // so the spec asserts what it *is* rather than that it is absent.
        "CR 107.3: {X} parses to the variable symbol, and round-trips" {
            ManaCost.parse("{X}{G}").symbols shouldBe
                persistentListOf(ManaSymbol.X, ManaSymbol.Colored(Color.GREEN))
            ManaCost.parse("{X}{G}").render() shouldBe "{X}{G}"
        }

        "CR 202.3b: an unannounced {X} contributes nothing to mana value off the stack" {
            ManaCost.parse("{X}{R}").manaValue shouldBe 1
            ManaCost.parse("{X}").manaValue shouldBe 0
        }

        "CR 202.2: {X} contributes no colour -- {X}{R} is red because of its {R}" {
            ManaCost.parse("{X}{R}").colors shouldBe setOf(Color.RED)
        }

        "CR 601.2b: substituting an announced value turns {X} into that much generic mana" {
            ManaCost.parse("{X}{G}").substitutingX(3) shouldBe ManaCost.parse("{3}{G}")
            // A zeroed X is dropped, exactly as a zeroed generic is after a cost reduction: {0}{G} and
            // {G} pay identically, and keeping the dead symbol would split one position into two
            // renderings on the wire and in the CLI menu.
            ManaCost.parse("{X}{G}").substitutingX(0) shouldBe ManaCost.parse("{G}")
            // ...unless it is the whole cost, because a ManaCost is never an empty symbol list.
            ManaCost.parse("{X}").substitutingX(0) shouldBe ManaCost.parse("{0}")
        }

        "CR 107.3: every instance of X in one cost takes the same announced value" {
            ManaCost.parse("{X}{X}{R}").substitutingX(2) shouldBe ManaCost.parse("{2}{2}{R}")
        }

        "CR 601.2b: a negative announced value is rejected loudly" {
            shouldThrow<IllegalArgumentException> { ManaCost.parse("{X}{G}").substitutingX(-1) }
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
