package dev.mtgplay.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Smoke test proving the Kotest + JUnit Platform wiring executes in the `mtg-core` module.
 */
class ModuleMarkerTest :
    StringSpec({
        "mtg-core module marker is wired into the build" {
            ModuleMarker.MODULE_NAME shouldBe "mtg-core"
        }
    })
