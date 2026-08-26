package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState

/*
 * The one affordability gate both legality paths use (CR 601.2b/c/f) — split out of `CastLegality.kt` by
 * `W10-D`, which pushed that file past detekt's function budget.
 *
 * The seam is real rather than budget-driven: everything left in `CastLegality.kt` asks whether a *cast*
 * is permitted at all — timing, a permission's state condition, an additional cost that can be satisfied
 * — and this asks the one question that is arithmetic. It is also the question the two paths must answer
 * identically, and a shared file is the cheapest way to make that structural: `ActionEnumeration`'s
 * ordinary cast and `CastLegality`'s permission cast call this and nothing else.
 */

/**
 * Whether [seat] can pay for a cast of [subject] at *some* legal combination of announcements and targets
 * (CR 601.2b/c/f) — the one affordability gate both legality paths use, so the two cannot drift.
 *
 * **Priced at the cheapest announcement**: no kicker, X = 0. That is what "is this castable at all?" means
 * — declining a kicker is always legal and a larger X only ever costs more, so a cast payable at any
 * announcement is payable at this one.
 *
 * **Priced at the cheapest target choice**, and which side of "cheapest" is safe depends on the direction
 * of the modification, which is why there are two branches here rather than one:
 *
 * - A target-conditional **reduction** (Ride's End) is priced by [cheapestTargetsFor], which hands the
 *   pricer *every* candidate so the discount applies exactly when some legal choice would make it apply.
 *   Pricing without the discount could only over-charge and hide a payable cast (`FW-TGTCOND`).
 * - A cost **increase** (Kaervek's Torch) is the unsafe direction for that treatment: pricing at no
 *   targets *under*-charges, admitting a cast whose every target the filter then removes. So when a tax
 *   could apply the gate becomes the minimum over legal choices — the same
 *   [affordableTargetOptions] call the target request makes, which makes the two consistent by
 *   construction rather than by argument (`W10-D`, `StackTargetTax.kt`).
 *
 * The second branch is reachable only with a taxing spell on the stack *and* a card that can name a spell,
 * so every other cast in every other position is priced exactly as it was before.
 */
internal fun castCostIsPayable(
    state: GameState,
    seat: PlayerId,
    subject: CastSubject,
): Boolean {
    val definition = subject.definition
    if (wholeCastTaxedPricingApplies(state, definition)) {
        return taxedCastIsPayable(state, seat, subject)
    }
    return enumeratePaymentPlans(
        state,
        seat,
        totalCost(
            state,
            seat,
            subject.copy(
                targets =
                    subject.castObjectId
                        ?.let { cheapestTargetsFor(state, seat, definition, it) }
                        .orEmpty(),
            ),
        ),
        minimalSacrificeReservation(state, seat, definition),
    ).isNotEmpty()
}
