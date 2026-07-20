package dev.mtgplay.rules

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Smoke test proving the Kotest + JUnit Platform wiring executes in the `mtg-rules` module.
 */
class ModuleMarkerTest :
    StringSpec({
        "mtg-rules module marker is wired into the build" {
            ModuleMarker.MODULE_NAME shouldBe "mtg-rules"
        }
    })
