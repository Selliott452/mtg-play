package dev.mtgplay.rules.engine

import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaSymbol
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.rules.decision.ManaActivation
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SymbolPayment

/**
 * Validates that [plan] pays exactly [cost]: one payment per expanded symbol, each satisfying
 * its symbol (CR 601.2g), and every activation choosing a mana its source class can actually
 * add (CR 605.1a). Enumeration only builds satisfying plans (ADR-005), so a violation here is
 * an engine defect, not a player error — it fails loudly before payment executes, which keeps
 * the cast atomic (see the pipeline's contract).
 */
internal fun validatePlanShape(
    cost: ManaCost,
    plan: PaymentPlan,
) {
    val units = expandToUnits(cost)
    require(plan.payments.size == units.size) {
        "CR 601.2g: plan pays ${plan.payments.size} symbol(s) but ${cost.render()} expands to ${units.size}"
    }
    units.zip(plan.payments).forEach { (symbol, payment) ->
        require(paymentSatisfies(symbol, payment)) {
            "CR 601.2g: $payment does not satisfy the symbol ${symbol.render()} of ${cost.render()}"
        }
    }
    plan.activations.forEach { activation ->
        require(activation.alternative in activation.sourceClass.profile) {
            "CR 605.1a: ${activation.sourceClass.card.name} has no production alternative ${activation.alternative}"
        }
        validateActivationCostPayment(activation)
    }
}

/**
 * Validates that [activation] records exactly one mana per expanded symbol of its own ability's mana
 * cost, each satisfying its symbol (CR 601.2g) — the `FW-MANACOST` half of the shape check, and the
 * reason the executor may spend the recorded mana without asking anything.
 *
 * A free ability records nothing, which is every activation on every board before `FW-MANACOST`.
 */
private fun validateActivationCostPayment(activation: ManaActivation) {
    val mana = activation.alternative.manaCost
    if (mana == null) {
        require(activation.costPayment.isEmpty()) {
            "CR 601.2g: ${activation.sourceClass.card.name}'s ability is free but the plan pays " +
                "${activation.costPayment} for it"
        }
        return
    }
    val units = expandToUnits(mana.cost)
    require(activation.costPayment.size == units.size) {
        "CR 601.2g: the plan pays ${activation.costPayment.size} mana toward " +
            "${activation.sourceClass.card.name}'s ${mana.cost.render()}, which expands to ${units.size}"
    }
    units.zip(activation.costPayment).forEach { (symbol, paid) ->
        require(paymentSatisfies(symbol, SymbolPayment.WithMana(paid))) {
            "CR 601.2g: $paid does not satisfy ${symbol.render()} of ${mana.cost.render()}"
        }
    }
}

/** Whether [payment] is a legal way to pay [symbol] (CR 107.4, CR 601.2g). */
internal fun paymentSatisfies(
    symbol: ManaSymbol,
    payment: SymbolPayment,
): Boolean =
    when (payment) {
        is SymbolPayment.WithMana ->
            when (symbol) {
                is ManaSymbol.Colored -> payment.mana == manaTypeOf(symbol.color)
                // CR 107.4c: {C} demands specifically colorless mana.
                ManaSymbol.Colorless -> payment.mana == ManaType.COLORLESS
                // CR 107.4d: generic accepts one mana of any type per expanded unit.
                is ManaSymbol.Generic -> symbol.amount == 1
                is ManaSymbol.Hybrid ->
                    payment.mana == manaTypeOf(symbol.first) || payment.mana == manaTypeOf(symbol.second)
                is ManaSymbol.Phyrexian -> payment.mana == manaTypeOf(symbol.color)
            }
        // CR 107.4: only a Phyrexian symbol accepts the 2-life alternative.
        SymbolPayment.WithTwoLife -> symbol is ManaSymbol.Phyrexian
    }
