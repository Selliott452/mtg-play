package dev.mtgplay.server

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private const val MATCH_SEED: Long = 0x7A2B
private const val SEED_A: Long = 11
private const val SEED_B: Long = 29

/**
 * The fast, always-on face of the day-one acceptance (ADR-008): a real CIO server on an ephemeral
 * localhost port and TWO [dev.mtgplay.server.client.ReferenceClient]s — each its own real socket — play
 * the MVP match (Mono-Red Madness vs GW Bogles) to a `GameOver` and agree on the winner. Same wire code
 * path as the two-process acceptance's clients, without the per-JVM startup cost. Skips-with-flag if the
 * sandbox forbids binding a localhost socket.
 */
class TwoClientRealSocketSpec :
    StringSpec({
        "ADR-008: two ReferenceClients over real localhost sockets finish the MVP match and agree on the outcome" {
            if (!canBindLocalhost()) {
                println("[P7.3 two-client real-socket] SKIPPED: the sandbox forbids binding a localhost socket")
            } else {
                val (runA, runB) = playTwoClientRealSocketMatch(MATCH_SEED, SEED_A, SEED_B)

                // Both seats reached the same real result over two independent sockets.
                runA.result shouldBe runB.result
                // Every envelope each seat received carried the protocol version (ADR-008: schema-skew is loud).
                runA.allEnvelopesVersioned shouldBe true
                runB.allEnvelopesVersioned shouldBe true
            }
        }
    })
