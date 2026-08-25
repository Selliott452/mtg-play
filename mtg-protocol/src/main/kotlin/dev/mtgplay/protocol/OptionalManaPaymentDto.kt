package dev.mtgplay.protocol

import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The wire mapping of an optional pay-then-draw clause's answer (CR 601.3b) — Nihil Spellbomb.
 *
 * The *payload* is [CounterPaymentOptionDto], shared with a counter's unless-pay: both answers are
 * "decline" or "pay by this plan", and spelling that twice is how two encodings of a payment plan drift
 * apart. Only the mapping functions live here, in their own file, because PaymentPlanDto.kt sits at
 * detekt's per-file function budget.
 */

/** An optional pay-then-draw option to its wire form (CR 601.3b). */
fun DecisionRequest.ChooseOptionalManaPayment.Option.toDto(): CounterPaymentOptionDto =
    when (this) {
        DecisionRequest.ChooseOptionalManaPayment.Option.Decline -> CounterPaymentOptionDto.Decline
        is DecisionRequest.ChooseOptionalManaPayment.Option.Pay -> CounterPaymentOptionDto.Pay(plan.toDto())
    }

/** An optional pay-then-draw option back to the engine value (CR 601.3b). */
fun CounterPaymentOptionDto.toOptionalManaPaymentOption(): DecisionRequest.ChooseOptionalManaPayment.Option =
    when (this) {
        CounterPaymentOptionDto.Decline -> DecisionRequest.ChooseOptionalManaPayment.Option.Decline
        is CounterPaymentOptionDto.Pay -> DecisionRequest.ChooseOptionalManaPayment.Option.Pay(plan.toDomain())
    }
