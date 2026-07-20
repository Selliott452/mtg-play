package dev.mtgplay.core.mana

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Color derivation from mana costs per CR 202.2, pinned on the pool's interesting cases.
 */
class ColorDerivationSpec :
    StringSpec({
        "CR 202.2: Slippery Bogle ({G/U}) is green AND blue — hybrid contributes both colors" {
            ManaCost.parse("{G/U}").colors shouldBe setOf(Color.GREEN, Color.BLUE)
        }

        "CR 202.2: Gut Shot ({R/P}) is red — a Phyrexian symbol contributes its color" {
            ManaCost.parse("{R/P}").colors shouldBe setOf(Color.RED)
        }

        "CR 202.2: Fireblast ({4}{R}{R}) is red — generic symbols contribute no color" {
            ManaCost.parse("{4}{R}{R}").colors shouldBe setOf(Color.RED)
        }

        "CR 202.2: Armadillo Cloak ({1}{G}{W}) is green and white" {
            ManaCost.parse("{1}{G}{W}").colors shouldBe setOf(Color.WHITE, Color.GREEN)
        }

        "CR 202.2: a {C}-only cost contributes no color — colorless is not a color (CR 105.4)" {
            ManaCost.parse("{C}").colors shouldBe emptySet()
        }

        "CR 105.1: colors come back in canonical WUBRG order regardless of printed order" {
            ManaCost.parse("{G}{W}{U}").colors.toList() shouldBe
                listOf(Color.WHITE, Color.BLUE, Color.GREEN)
        }
    })
