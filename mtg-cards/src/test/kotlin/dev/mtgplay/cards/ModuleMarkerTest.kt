package dev.mtgplay.cards

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Smoke test proving the Kotest + JUnit Platform wiring executes in the `mtg-cards` module.
 */
class ModuleMarkerTest :
    StringSpec({
        "mtg-cards module marker is wired into the build" {
            ModuleMarker.MODULE_NAME shouldBe "mtg-cards"
        }
    })
