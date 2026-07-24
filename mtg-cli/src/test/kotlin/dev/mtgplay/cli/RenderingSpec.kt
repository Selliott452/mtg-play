package dev.mtgplay.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The per-seat board renderer (P6.4 deliverable 1). A hand-built mid-game state renders names,
 * effective P/T, keywords, zones, and the turn header correctly, and honours the hidden-information
 * boundary at render time (ADR-007, deferred to Phase 7): the opponent's hand is a count only.
 */
class RenderingSpec :
    StringSpec({
        val transcript = renderView(midGameView()).joinToString("\n")

        "CR 613 sublayer 7c: an enchanted creature renders its effective (layered) power/toughness" {
            // Grizzly Bears 2/2 + Rancor (+2/+0) = 4/2.
            transcript shouldContain "Grizzly Bears 4/2"
        }

        "CR 613 layer 6: an effective keyword granted by an Aura renders on the creature" {
            transcript shouldContain "trample"
        }

        "CR 303.4: an Aura renders its type and what it is attached to" {
            transcript shouldContain "Rancor (Aura)"
            transcript shouldContain "attached to Grizzly Bears#1"
        }

        "CR 110.5b: a tapped permanent renders its tapped status" {
            transcript shouldContain "Mountain (Land)"
            transcript shouldContain "tapped"
        }

        "ADR-007 render-time discipline: the opponent's hand is a count only, never its contents" {
            transcript shouldContain "Hand: 1 (hidden)"
            transcript shouldNotContain "Slippery Bogle"
        }

        "CR 402: the viewer's own hand renders its contents" {
            transcript shouldContain "Hand: 1 (listed below)"
            transcript shouldContain "Lightning Bolt"
        }

        "CR 404: a graveyard is public and renders its contents" {
            transcript shouldContain "Fiery Temper"
        }

        "CR 119.1: both players' life totals render" {
            transcript shouldContain "Life 18"
            transcript shouldContain "Life 20"
        }

        "CR 500.1: the turn header renders the turn number, active player, and phase" {
            transcript shouldContain "Turn 3"
            transcript shouldContain "precombat main"
        }

        "CR 405: an empty stack renders as empty" {
            transcript shouldContain "Stack: (empty)"
        }

        "the viewer is marked, and both deck names appear" {
            transcript shouldContain "Mono-Red Madness (you)"
            transcript shouldContain "GW Bogles"
        }

        "the opponent's library is a count, never card order" {
            renderView(midGameView()).any { it.contains("Library: 25") } shouldBe true
        }
    })
