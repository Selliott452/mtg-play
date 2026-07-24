package dev.mtgplay.cli

import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.rules.decision.ManaSourceChoice
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SymbolPayment

/*
 * How one payment plan (CR 601.2g-h) reads as text: its per-symbol assignments, so the player can
 * tell two plans apart by which sources each spends (docs/design/mana-payment.md). The corpus brief
 * asks payment menus to render their symbol assignments rather than a bare index.
 */

/** A payment plan's label: each cost symbol's payment, in printed order; "free ({0})" for an empty plan. */
fun paymentPlanLabel(plan: PaymentPlan): String {
    if (plan.payments.isEmpty()) return "free ({0})"
    return plan.payments.joinToString(", ") { symbolPaymentLabel(it) }
}

/** One symbol's payment: a mana of a type from a named source, or a Phyrexian symbol's 2 life (CR 107.4). */
private fun symbolPaymentLabel(payment: SymbolPayment): String =
    when (payment) {
        is SymbolPayment.WithTwoLife -> "2 life"
        is SymbolPayment.WithMana -> "${manaGlyph(payment.mana)} ${manaSourceLabel(payment.source)}"
    }

/** Where a symbol's mana comes from (CR 106.4/605.3): the pool, or tapping a named source class. */
private fun manaSourceLabel(source: ManaSourceChoice): String =
    when (source) {
        ManaSourceChoice.FromPool -> "from pool"
        is ManaSourceChoice.ByTapping -> "tapping ${source.sourceClass.card.name}"
    }

/** The brace glyph of a produced mana type (CR 106.1b), e.g. {R} for red or {C} for colorless. */
fun manaGlyph(mana: ManaType): String =
    when (mana) {
        ManaType.WHITE -> "{W}"
        ManaType.BLUE -> "{U}"
        ManaType.BLACK -> "{B}"
        ManaType.RED -> "{R}"
        ManaType.GREEN -> "{G}"
        ManaType.COLORLESS -> "{C}"
    }
