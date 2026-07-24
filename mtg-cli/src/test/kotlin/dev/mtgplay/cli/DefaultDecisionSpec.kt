package dev.mtgplay.cli

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The safe default a blank line takes (P6.4 deliverable 3): the passive, always-legal answer per
 * request kind - pass, keep, decline, declare nothing, discard the lowest. A default is legal by
 * construction, so pressing Enter never dead-ends.
 */
class DefaultDecisionSpec :
    StringSpec({
        val rid = DecisionRequestId(viewerSeat, 0)

        "CR 117.3d: the default for a priority window is pass, wherever pass sits" {
            val request =
                DecisionRequest.ChooseAction(
                    rid,
                    listOf(PriorityOption.PlayLand(ObjectId(5), CardRef("Mountain")), PriorityOption.Pass),
                )
            defaultDecision(request) shouldBe Decision.SingleSelect(rid, 1)
        }

        "CR 601.3b: the default for a 'you may' yes/no is to decline" {
            val request = DecisionRequest.ChooseYesNo(rid, "You may", ObjectId(5), CardRef("Fiery Temper"))
            defaultDecision(request) shouldBe Decision.SingleSelect(rid, DecisionRequest.ChooseYesNo.DECLINE)
        }

        "CR 514.1: the default cleanup discard is the first N (lowest-index) cards" {
            val request =
                DecisionRequest.ChooseDiscards(
                    rid,
                    listOf(
                        DecisionRequest.ChooseDiscards.Option(ObjectId(4), CardRef("Lightning Bolt")),
                        DecisionRequest.ChooseDiscards.Option(ObjectId(5), CardRef("Mountain")),
                        DecisionRequest.ChooseDiscards.Option(ObjectId(6), CardRef("Fireblast")),
                    ),
                    count = 2,
                )
            defaultDecision(request) shouldBe Decision.MultiSelect(rid, listOf(0, 1))
        }

        "CR 103.4: the default mulligan decision is to keep" {
            defaultDecision(DecisionRequest.ChooseMulligan(rid, mulligansTaken = 0)) shouldBe
                Decision.SingleSelect(rid, DecisionRequest.ChooseMulligan.KEEP)
        }

        "CR 508.1: the default declare-attackers is to attack with nobody" {
            val request =
                DecisionRequest.DeclareAttackers(
                    rid,
                    listOf(
                        DecisionRequest.DeclareAttackers.Option(ObjectId(1), CardRef("Grizzly Bears"), opponentSeat),
                    ),
                )
            defaultDecision(request) shouldBe Decision.MultiSelect(rid, emptyList())
        }

        "CR 701.18: the default 'choose one or opt out' is the trailing opt-out index" {
            val request =
                DecisionRequest.ChooseFromLibrary(
                    rid,
                    listOf(DecisionRequest.ChooseFromLibrary.Option(ObjectId(9), CardRef("Forest"))),
                )
            defaultDecision(request) shouldBe Decision.SingleSelect(rid, request.findNoneIndex)
        }
    })
