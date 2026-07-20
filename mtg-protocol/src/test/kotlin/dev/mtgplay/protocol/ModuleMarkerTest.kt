package dev.mtgplay.protocol

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Smoke test proving the Kotest + JUnit Platform wiring executes in the `mtg-protocol` module.
 */
class ModuleMarkerTest :
    StringSpec({
        "mtg-protocol module marker is wired into the build" {
            ModuleMarker.MODULE_NAME shouldBe "mtg-protocol"
        }
    })
