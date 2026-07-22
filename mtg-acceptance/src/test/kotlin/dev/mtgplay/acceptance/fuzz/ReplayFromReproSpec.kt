package dev.mtgplay.acceptance.fuzz

import dev.mtgplay.acceptance.alice
import dev.mtgplay.acceptance.bob
import dev.mtgplay.acceptance.mountainConfig
import dev.mtgplay.acceptance.replay.Fingerprint
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.GameEngine
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequestId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.toPersistentMap
import java.nio.file.Files

/**
 * Failure-repro persistence and replay (P3.3, deliverable 3): a fuzz failure is persisted to a
 * self-contained file whose path and inline summary appear in the test failure message, and a
 * persisted repro replays to the *same* failure. Proven with a deliberately-corrupted engine, never
 * by weakening the real one; the real engine's own corpora (the smoke specs) prove the no-failure
 * side by never producing a repro.
 */
class ReplayFromReproSpec :
    StringSpec({

        "a FuzzRepro round-trips through its text form unchanged" {
            val repro =
                FuzzRepro(
                    seed = 42,
                    libraries =
                        mapOf(
                            alice to listOf(CardRef("Mountain"), CardRef("Lightning Bolt")),
                            bob to listOf(CardRef("Forest"), CardRef("Grizzly Bears")),
                        ),
                    startingPlayer = alice,
                    startingHandSize = 7,
                    decisions =
                        listOf(
                            Decision.SingleSelect(DecisionRequestId(alice, 0), 2),
                            Decision.MultiSelect(DecisionRequestId(bob, 1), listOf(0, 3)),
                            Decision.MultiSelect(DecisionRequestId(alice, 2), emptyList()),
                        ),
                    failureType = "InvariantViolationException",
                    failureDetail = "first line of detail\nsecond line of detail",
                    fingerprint = Fingerprint("deadbeef"),
                    probeOptionLabel = "action[1]=PlayLand",
                )
            FuzzRepro.parse(repro.render()) shouldBe repro
        }

        "the harness persists a repro and names it in the failure message, and the repro replays to the same failure" {
            val tempDir = Files.createTempDirectory("fuzz-roundtrip")
            val corpus =
                FuzzCorpus(
                    name = "roundtrip",
                    seeds = listOf(0L),
                    configForSeed = { seed -> mountainConfig(seed = seed) },
                    caps = FuzzCorpus.Caps(turnCap = 200),
                    // The failure under test is the corrupt-state invariant violation on respond,
                    // so probing is switched off to keep the scenario about that alone.
                    probePolicy = ProbePolicy.Never,
                )

            val failure =
                shouldThrow<FuzzFailure> {
                    FuzzHarness.run(corpus, engine = corruptingEngine(), failureDir = tempDir)
                }

            // The repro was persisted, and both the path and an inline summary are in the message.
            Files.exists(failure.reproPath).shouldBeTrue()
            failure.message.orEmpty() shouldContain failure.reproPath.toAbsolutePath().toString()
            failure.message.orEmpty() shouldContain "failure: InvariantViolationException"
            failure.message.orEmpty() shouldContain "fingerprint:"

            // The persisted file replays — against the same corrupting engine — to the same failure.
            val outcome = ReplayFromRepro.replay(failure.reproPath, MvpCards.definitions, corruptingEngine())
            outcome.reproduced.shouldBeTrue()
            outcome.reproducedFailureType shouldBe "InvariantViolationException"
            outcome.reproducedFingerprint shouldBe outcome.expectedFingerprint
        }

        "a repro that replays clean (no failure reproduced) reports not-reproduced" {
            // A repro pointing at the real engine and an empty decision log never fails on replay,
            // so the round-trip correctly reports the mismatch rather than a false positive.
            val repro =
                FuzzRepro(
                    seed = 1,
                    libraries = mountainConfig(seed = 1).libraries,
                    startingPlayer = alice,
                    startingHandSize = 7,
                    decisions = emptyList(),
                    failureType = "InvariantViolationException",
                    failureDetail = "a failure that will not recur on a clean engine",
                    fingerprint = Fingerprint("not-the-real-one"),
                    probeOptionLabel = null,
                )
            val outcome = ReplayFromRepro.replay(repro, MvpCards.definitions, DefaultGameEngine())
            outcome.reproduced shouldBe false
            outcome.reproducedFingerprint shouldBe null
        }
    })

/**
 * A [GameEngine] whose every [advance] corrupts the state into two simultaneous priority holders
 * (CR 117.1a violation) — the deliberate defect that drives the repro round-trip. Its [start] is the
 * real engine, so a game can begin from a valid first state before the corruption strikes.
 */
private fun corruptingEngine(): GameEngine =
    object : GameEngine {
        private val real = DefaultGameEngine()

        override fun start(config: MatchConfig): AdvanceResult = real.start(config)

        override fun advance(
            state: GameState,
            decision: Decision,
        ): AdvanceResult {
            val bothHold =
                state.players
                    .mapValues { (_, player) -> player.copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY) }
                    .toPersistentMap()
            val anyRequest = (real.start(mountainConfig()) as AdvanceResult.NeedsDecision).request
            return AdvanceResult.NeedsDecision(state.copy(players = bothHold), anyRequest)
        }
    }
