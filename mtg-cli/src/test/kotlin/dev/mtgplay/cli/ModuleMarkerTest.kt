package dev.mtgplay.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Smoke test proving the Kotest + JUnit Platform wiring executes in the `mtg-cli` module.
 */
class ModuleMarkerTest :
    StringSpec({
        "mtg-cli module marker is wired into the build" {
            ModuleMarker.MODULE_NAME shouldBe "mtg-cli"
        }
    })
