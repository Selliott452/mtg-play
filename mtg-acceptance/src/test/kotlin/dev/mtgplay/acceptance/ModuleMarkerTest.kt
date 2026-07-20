package dev.mtgplay.acceptance

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Smoke test proving the Kotest + JUnit Platform wiring executes in the `mtg-acceptance` module.
 */
class ModuleMarkerTest :
    StringSpec({
        "mtg-acceptance module marker is wired into the build" {
            ModuleMarker.MODULE_NAME shouldBe "mtg-acceptance"
        }
    })
