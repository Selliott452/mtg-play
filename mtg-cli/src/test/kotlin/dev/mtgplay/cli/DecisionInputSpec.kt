package dev.mtgplay.cli

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Parsing typed input into a [Decision] (P6.4 deliverable 2/3). Menu positions are one-based; invalid
 * input (out of range, wrong arity, duplicated, non-numeric) returns `null` so the driver re-prompts
 * rather than the engine throwing.
 */
class DecisionInputSpec :
    StringSpec({
        val rid = DecisionRequestId(viewerSeat, 0)

        val priority =
            DecisionRequest.ChooseAction(
                rid,
                listOf(PriorityOption.Pass, PriorityOption.PlayLand(ObjectId(5), CardRef("Mountain"))),
            )

        "a one-based single-select maps position k to engine index k-1" {
            parseDecision(priority, "1") shouldBe Decision.SingleSelect(rid, 0)
            parseDecision(priority, "2") shouldBe Decision.SingleSelect(rid, 1)
        }

        "an out-of-range or non-numeric single-select is rejected (re-prompt)" {
            parseDecision(priority, "3").shouldBeNull()
            parseDecision(priority, "abc").shouldBeNull()
        }

        val discards =
            DecisionRequest.ChooseDiscards(
                rid,
                listOf(
                    DecisionRequest.ChooseDiscards.Option(ObjectId(4), CardRef("Lightning Bolt")),
                    DecisionRequest.ChooseDiscards.Option(ObjectId(5), CardRef("Mountain")),
                    DecisionRequest.ChooseDiscards.Option(ObjectId(6), CardRef("Fireblast")),
                ),
                count = 2,
            )

        "a fixed-size selection parses a comma-separated list to zero-based indices" {
            parseDecision(discards, "1,3") shouldBe Decision.MultiSelect(rid, listOf(0, 2))
        }

        "a fixed-size selection rejects the wrong arity, a duplicate, or an out-of-range index" {
            parseDecision(discards, "1").shouldBeNull()
            parseDecision(discards, "1,1").shouldBeNull()
            parseDecision(discards, "1,9").shouldBeNull()
        }

        val attackers =
            DecisionRequest.DeclareAttackers(
                rid,
                listOf(
                    DecisionRequest.DeclareAttackers.Option(ObjectId(1), CardRef("Grizzly Bears"), opponentSeat),
                    DecisionRequest.DeclareAttackers.Option(ObjectId(2), CardRef("Hill Giant"), opponentSeat),
                ),
            )

        "an any-size subset (attackers) parses any distinct in-range set" {
            parseDecision(attackers, "2") shouldBe Decision.MultiSelect(rid, listOf(1))
            parseDecision(attackers, "1,2") shouldBe Decision.MultiSelect(rid, listOf(0, 1))
        }

        val order =
            DecisionRequest.OrderTriggers(
                rid,
                listOf(
                    DecisionRequest.OrderTriggers.Option(CardRef("Guttersnipe"), "a"),
                    DecisionRequest.OrderTriggers.Option(CardRef("Voldaren Epicure"), "b"),
                ),
            )

        "a permutation must list every option exactly once" {
            parseDecision(order, "2,1") shouldBe Decision.MultiSelect(rid, listOf(1, 0))
            parseDecision(order, "1").shouldBeNull()
            parseDecision(order, "1,1").shouldBeNull()
        }

        val yesNo = DecisionRequest.ChooseYesNo(rid, "You may", ObjectId(5), CardRef("Fiery Temper"))

        "a yes/no accepts words or numbers, and rejects anything else" {
            parseDecision(yesNo, "y") shouldBe Decision.SingleSelect(rid, DecisionRequest.ChooseYesNo.ACCEPT)
            parseDecision(yesNo, "n") shouldBe Decision.SingleSelect(rid, DecisionRequest.ChooseYesNo.DECLINE)
            parseDecision(yesNo, "2") shouldBe Decision.SingleSelect(rid, DecisionRequest.ChooseYesNo.ACCEPT)
            parseDecision(yesNo, "1") shouldBe Decision.SingleSelect(rid, DecisionRequest.ChooseYesNo.DECLINE)
            parseDecision(yesNo, "maybe").shouldBeNull()
        }

        "CR 509.1a: a block declaration rejects an answer that uses one blocker twice" {
            val blockers =
                DecisionRequest.DeclareBlockers(
                    rid,
                    listOf(
                        DecisionRequest.DeclareBlockers.Option(
                            ObjectId(1),
                            CardRef("Bear"),
                            ObjectId(10),
                            CardRef("A"),
                        ),
                        DecisionRequest.DeclareBlockers.Option(
                            ObjectId(1),
                            CardRef("Bear"),
                            ObjectId(11),
                            CardRef("B"),
                        ),
                    ),
                )
            parseDecision(blockers, "1") shouldBe Decision.MultiSelect(rid, listOf(0))
            parseDecision(blockers, "1,2").shouldBeNull()
        }
    })
