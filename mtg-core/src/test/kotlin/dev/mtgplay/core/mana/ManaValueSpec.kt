package dev.mtgplay.core.mana

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Mana value per CR 203.3, pinned on the cost shapes the packet spec names explicitly.
 */
class ManaValueSpec :
    StringSpec({
        "CR 203.3: Slippery Bogle's hybrid cost {G/U} has mana value 1" {
            ManaCost.parse("{G/U}").manaValue shouldBe 1
        }

        "CR 203.3: Gut Shot's Phyrexian cost {R/P} has mana value 1" {
            ManaCost.parse("{R/P}").manaValue shouldBe 1
        }

        "CR 203.3: Fireblast's cost {4}{R}{R} has mana value 6" {
            ManaCost.parse("{4}{R}{R}").manaValue shouldBe 6
        }

        "CR 203.3: the colorless symbol {C} contributes 1, not 0" {
            ManaCost.parse("{C}").manaValue shouldBe 1
        }

        "CR 203.3: a generic symbol {N} contributes exactly N" {
            (0..30).forEach { n ->
                ManaCost.parse("{$n}").manaValue shouldBe n
            }
        }
    })
