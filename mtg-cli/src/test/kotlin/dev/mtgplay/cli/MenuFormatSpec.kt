package dev.mtgplay.cli

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.ManaActivation
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.decision.SourceClassKey
import dev.mtgplay.rules.decision.SymbolPayment
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

/**
 * The numbered decision menus (P6.4 deliverable 2). Each request kind renders meaningful labels -
 * card names where ids appear, a cast's source/permission, a payment plan's symbol assignments,
 * combatants' effective P/T - rather than bare indices (the P6.3 corpus brief).
 */
class MenuFormatSpec :
    StringSpec({
        val view = midGameView()
        val rid = DecisionRequestId(viewerSeat, 0)

        fun menu(request: DecisionRequest): String = renderMenu(view, request).joinToString("\n")

        "CR 117: a priority window numbers pass and each cast/play with its meaning" {
            val request =
                DecisionRequest.ChooseAction(
                    rid,
                    listOf(
                        PriorityOption.Pass,
                        PriorityOption.CastSpell(ObjectId(4), CardRef("Lightning Bolt")),
                        PriorityOption.PlayLand(ObjectId(5), CardRef("Mountain")),
                    ),
                )
            val text = menu(request)
            text shouldContain "1) Pass"
            text shouldContain "Cast Lightning Bolt"
            text shouldContain "Play land Mountain"
            text shouldContain "[Enter] = pass"
        }

        "CR 601.2g-h: a payment menu renders each plan's activations and what they pay" {
            val plan =
                PaymentPlan(
                    listOf(ManaActivation(SourceClassKey(CardRef("Mountain"), listOf(ManaType.RED)), ManaType.RED)),
                    listOf(SymbolPayment.WithMana(ManaType.RED)),
                )
            val request = DecisionRequest.ChoosePaymentPlan(rid, ObjectId(4), CardRef("Lightning Bolt"), listOf(plan))
            val text = menu(request)
            text shouldContain "tap Mountain for {R}"
            text shouldContain "pay {R}"
        }

        "CR 508.1: a declare-attackers menu shows each attacker's effective P/T and its defender" {
            val request =
                DecisionRequest.DeclareAttackers(
                    rid,
                    listOf(
                        DecisionRequest.DeclareAttackers.Option(ObjectId(1), CardRef("Grizzly Bears"), opponentSeat),
                    ),
                )
            val text = menu(request)
            text shouldContain "Grizzly Bears 4/2"
            text shouldContain "attacks GW Bogles"
        }

        "CR 601.3b: a yes/no menu offers decline then accept, and hints n/y" {
            val request =
                DecisionRequest.ChooseYesNo(
                    rid,
                    "You may cast Fiery Temper for its madness cost",
                    ObjectId(5),
                    CardRef("Fiery Temper"),
                )
            val text = menu(request)
            text shouldContain "No (decline)"
            text shouldContain "Yes"
            text shouldContain "n/y"
        }

        "CR 514.1: a discard menu names each card and states the exact count" {
            val request =
                DecisionRequest.ChooseDiscards(
                    rid,
                    listOf(
                        DecisionRequest.ChooseDiscards.Option(ObjectId(4), CardRef("Lightning Bolt")),
                        DecisionRequest.ChooseDiscards.Option(ObjectId(5), CardRef("Mountain")),
                    ),
                    count = 1,
                )
            val text = menu(request)
            text shouldContain "Discard exactly 1"
            text shouldContain "Lightning Bolt"
            text shouldContain "the first 1"
        }

        "CR 701.18: a library-search menu offers each match plus a trailing find-none" {
            val request =
                DecisionRequest.ChooseFromLibrary(
                    rid,
                    listOf(DecisionRequest.ChooseFromLibrary.Option(ObjectId(9), CardRef("Forest"))),
                )
            val text = menu(request)
            text shouldContain "Forest"
            text shouldContain "(find none)"
        }

        "CR 603.3b: an order-triggers menu names each trigger and asks for a full order" {
            val request =
                DecisionRequest.OrderTriggers(
                    rid,
                    listOf(
                        DecisionRequest.OrderTriggers.Option(CardRef("Guttersnipe"), "deal 2 damage"),
                        DecisionRequest.OrderTriggers.Option(CardRef("Voldaren Epicure"), "create a Blood token"),
                    ),
                )
            val text = menu(request)
            text shouldContain "Guttersnipe: deal 2 damage"
            text shouldContain "Voldaren Epicure: create a Blood token"
            text shouldContain "in the order you want"
        }

        "CR 614.12: a colour choice numbers the offered colours" {
            val request =
                DecisionRequest.ChooseColor(
                    rid,
                    ObjectId(7),
                    CardRef("Utopia Sprawl"),
                    listOf(Color.GREEN, Color.WHITE),
                )
            val text = menu(request)
            text shouldContain "green"
            text shouldContain "white"
        }

        "CR 103.4: a mulligan menu offers keep and mulligan" {
            val text = menu(DecisionRequest.ChooseMulligan(rid, mulligansTaken = 1))
            text shouldContain "Keep this hand"
            text shouldContain "Mulligan"
        }

        "CR 702.19e: a trample menu offers each assignable amount to the player" {
            val request =
                DecisionRequest.AssignTrampleDamage(
                    rid,
                    ObjectId(1),
                    CardRef("Grizzly Bears"),
                    opponentSeat,
                    listOf(0, 1, 2),
                )
            val text = menu(request)
            text shouldContain "0 to the player"
            text shouldContain "2 to the player"
        }
    })
