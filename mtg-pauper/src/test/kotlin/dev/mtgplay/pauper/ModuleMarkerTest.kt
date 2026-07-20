package dev.mtgplay.pauper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Smoke test proving the Kotest + JUnit Platform wiring executes in the `mtg-pauper` module.
 */
class ModuleMarkerTest :
    StringSpec({
        "mtg-pauper module marker is wired into the build" {
            ModuleMarker.MODULE_NAME shouldBe "mtg-pauper"
        }
    })
