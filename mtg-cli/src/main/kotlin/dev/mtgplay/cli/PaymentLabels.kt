package dev.mtgplay.cli

import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.rules.decision.ManaActivation
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SymbolPayment

/*
 * How one payment plan (CR 601.2g-h) reads as text: the sources it activates and then the mana it
 * spends, so the player can tell two plans apart by both halves (docs/design/mana-payment.md). The
 * corpus brief asks payment menus to render their assignments rather than a bare index.
 */

/**
 * A payment plan's label: the CR 601.2g activations, then what the CR 601.2h payments spend;
 * "free ({0})" for an empty plan. A plan with no activations pays entirely from mana already in
 * the pool, and says so.
 */
fun paymentPlanLabel(plan: PaymentPlan): String {
    val spent = plan.payments.joinToString(" ") { symbolPaymentLabel(it) }
    val activated = plan.activations.joinToString(", ") { activationLabel(it) }
    return when {
        plan.payments.isEmpty() -> "free ({0})"
        plan.activations.isEmpty() -> "$spent from pool"
        else -> "$activated; pay $spent"
    }
}

/** One symbol's payment: one mana of a type, or a Phyrexian symbol's 2 life (CR 107.4). */
private fun symbolPaymentLabel(payment: SymbolPayment): String =
    when (payment) {
        is SymbolPayment.WithTwoLife -> "2 life"
        is SymbolPayment.WithMana -> manaGlyph(payment.mana)
    }

/**
 * One mana ability activation (CR 601.2g): tapping — or, for a sacrifice-cost ability (CR 605.1a),
 * sacrificing — a member of the named source class for the mana it was chosen to add. The mana is a
 * multiset, so an Urza's Tower with Tron assembled reads "tap Urza's Tower for {C}{C}{C}" and the
 * player can tell an assembled Tron apart from an unassembled one without leaving the payment menu.
 *
 * Since `FW-MANACOST` the activation may cost mana of its own, and the label says so — "pay {G}, tap
 * Giant's Boulder for {R}" — because two plans that differ only in which mana funded the activation
 * are genuinely different lines and a player choosing by index has to be able to tell them apart.
 */
private fun activationLabel(activation: ManaActivation): String {
    val verb = if (activation.alternative.viaSacrifice) "sacrifice" else "tap"
    val mana = activation.produced.joinToString("") { manaGlyph(it) }
    val paid =
        if (activation.costPayment.isEmpty()) {
            ""
        } else {
            "pay ${activation.costPayment.joinToString("") { manaGlyph(it) }}, "
        }
    return "$paid$verb ${activation.sourceClass.card.name} for $mana"
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
