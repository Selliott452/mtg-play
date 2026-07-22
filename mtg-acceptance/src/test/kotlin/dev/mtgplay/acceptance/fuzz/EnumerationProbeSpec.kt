package dev.mtgplay.acceptance.fuzz

import dev.mtgplay.acceptance.alice
import dev.mtgplay.acceptance.bob
import dev.mtgplay.acceptance.mountains
import dev.mtgplay.acceptance.playerWithZones
import dev.mtgplay.acceptance.twoPlayerState
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.GameEngine
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The enumeration-completeness probe (P3.3, deliverable 2): that it maps each enumerated option to a
 * legal decision, that probing a clean engine passes, and that a phantom option — one the engine
 * enumerated but then refuses — is caught loudly as a [ProbeFailure] (ADR-005, PLAN.md §7).
 */
class EnumerationProbeSpec :
    StringSpec({

        val id = DecisionRequestId(alice, 0)

        "candidatesFor maps a single-select priority window to one decision per option (ADR-005)" {
            val window =
                DecisionRequest.ChooseAction(
                    id,
                    options =
                        listOf(
                            PriorityOption.Pass,
                            PriorityOption.PlayLand(ObjectId(1), CardRef("Mountain")),
                            PriorityOption.CastSpell(ObjectId(2), CardRef("Lightning Bolt")),
                        ),
                )
            val candidates = EnumerationProbe.candidatesFor(window)
            candidates shouldHaveSize 3
            candidates.map { it.decision } shouldBe
                listOf(
                    Decision.SingleSelect(id, 0),
                    Decision.SingleSelect(id, 1),
                    Decision.SingleSelect(id, 2),
                )
        }

        "candidatesFor builds a correctly-sized discard including each card (CR 514.1)" {
            val discard =
                DecisionRequest.ChooseDiscards(
                    id,
                    options =
                        listOf(
                            DecisionRequest.ChooseDiscards.Option(ObjectId(1), CardRef("Mountain")),
                            DecisionRequest.ChooseDiscards.Option(ObjectId(2), CardRef("Mountain")),
                            DecisionRequest.ChooseDiscards.Option(ObjectId(3), CardRef("Mountain")),
                        ),
                    count = 2,
                )
            val candidates = EnumerationProbe.candidatesFor(discard)
            candidates shouldHaveSize 3
            // Each candidate is a size-2 selection whose first index is the card it targets.
            candidates.forEachIndexed { index, candidate ->
                val decision = candidate.decision.shouldBeInstanceOf<Decision.MultiSelect>()
                decision.indices shouldHaveSize 2
                decision.indices.first() shouldBe index
                decision.indices.distinct() shouldHaveSize 2
            }
        }

        "candidatesFor probes declare-attackers as the empty declaration plus each singleton (CR 508.1)" {
            val declare =
                DecisionRequest.DeclareAttackers(
                    id,
                    options =
                        listOf(
                            DecisionRequest.DeclareAttackers.Option(ObjectId(1), CardRef("Grizzly Bears"), bob),
                            DecisionRequest.DeclareAttackers.Option(ObjectId(2), CardRef("Grizzly Bears"), bob),
                        ),
                )
            val candidates = EnumerationProbe.candidatesFor(declare)
            candidates.map { it.decision } shouldBe
                listOf(
                    Decision.MultiSelect(id, emptyList()),
                    Decision.MultiSelect(id, listOf(0)),
                    Decision.MultiSelect(id, listOf(1)),
                )
        }

        "candidatesFor probes a blocker order as one identity permutation of all options (CR 509.2)" {
            val order =
                DecisionRequest.OrderBlockers(
                    id,
                    attacker = ObjectId(9),
                    options =
                        listOf(
                            DecisionRequest.OrderBlockers.Option(ObjectId(1), CardRef("Grizzly Bears")),
                            DecisionRequest.OrderBlockers.Option(ObjectId(2), CardRef("Grizzly Bears")),
                        ),
                )
            val candidates = EnumerationProbe.candidatesFor(order)
            candidates shouldHaveSize 1
            candidates.single().decision shouldBe Decision.MultiSelect(id, listOf(0, 1))
        }

        "probe passes and returns the option count when the engine advances every option cleanly" {
            val window =
                DecisionRequest.ChooseAction(
                    id,
                    options = listOf(PriorityOption.Pass, PriorityOption.PlayLand(ObjectId(1), CardRef("Mountain"))),
                )
            val probed = EnumerationProbe.probe(alwaysAdvances(window), sampleState(), window)
            probed shouldBe 2
        }

        "probe raises a ProbeFailure naming the phantom option when the engine refuses one (ADR-005)" {
            val window =
                DecisionRequest.ChooseAction(
                    id,
                    options =
                        listOf(
                            PriorityOption.Pass,
                            PriorityOption.PlayLand(ObjectId(1), CardRef("Mountain")),
                            PriorityOption.PlayLand(ObjectId(2), CardRef("Mountain")),
                        ),
                )
            // The fake engine rejects index 1 — a phantom option the enumeration should not have offered.
            val engine = refusesIndex(window, refused = 1)
            val failure = shouldThrow<ProbeFailure> { EnumerationProbe.probe(engine, sampleState(), window) }
            failure.optionLabel shouldContain "[1]"
            failure.probedDecision shouldBe Decision.SingleSelect(id, 1)
        }
    })

// A minimal valid paused state to forward to the fake engines; its content is irrelevant to the
// probe, which only cares whether advancing throws.
private fun sampleState(): GameState =
    twoPlayerState(
        turn = Turn(alice, 1, TurnPhase.PRECOMBAT_MAIN, null),
        aliceState = playerWithZones(library = mountains(0L..5L, alice)),
        bobState = playerWithZones(library = mountains(10L..15L, bob)),
        nextObjectId = 100,
    )

// A fake engine that advances every decision to the same benign pause — never throws.
private fun alwaysAdvances(request: DecisionRequest): GameEngine =
    object : GameEngine {
        override fun start(config: MatchConfig): AdvanceResult = error("unused")

        override fun advance(
            state: GameState,
            decision: Decision,
        ): AdvanceResult = AdvanceResult.NeedsDecision(state, request)
    }

// A fake engine that throws for exactly one single-select index — a phantom enumerated option.
private fun refusesIndex(
    request: DecisionRequest,
    refused: Int,
): GameEngine =
    object : GameEngine {
        override fun start(config: MatchConfig): AdvanceResult = error("unused")

        override fun advance(
            state: GameState,
            decision: Decision,
        ): AdvanceResult {
            require(!(decision is Decision.SingleSelect && decision.index == refused)) {
                "phantom option $refused is not actually playable"
            }
            return AdvanceResult.NeedsDecision(state, request)
        }
    }
