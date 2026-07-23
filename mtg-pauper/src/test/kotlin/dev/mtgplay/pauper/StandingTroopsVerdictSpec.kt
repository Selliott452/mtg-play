package dev.mtgplay.pauper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The Standing Troops verdict (P6.1 open question). The staged snapshot carries the authoritative
 * legality; whatever it says is pinned here. It is not in either MVP decklist — it exists in the
 * pool only as a vigilance test-creature ([dev.mtgplay.cards.MvpCards]) — so this only fixes the
 * legality of that roster note.
 *
 * Verdict: the snapshot records Standing Troops as **Pauper-legal**, so the engine's test-creature
 * roster note stands: it is a legal common.
 */
class StandingTroopsVerdictSpec :
    StringSpec({
        "Standing Troops is Pauper-legal per the authoritative snapshot" {
            val standingTroops = MvpCardPool.catalog.metadataFor("Standing Troops") ?: error("Standing Troops missing")
            standingTroops.pauperLegality shouldBe Legality.LEGAL
        }
    })
