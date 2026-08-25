package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Counter

/**
 * One component of a **mana ability's** activation cost (CR 605.1a, CR 602.1) — the sealed cost
 * vocabulary a [ManaAbility] is built from. Additive, flagged core (`FW-MANACOST`).
 *
 * **Why this is not [AbilityCost].** The two lists overlap on `{T}`, "sacrifice this" and a mana
 * component, and diverge on everything else, because they are paid by different machinery. An
 * [AbilityCost] is paid by the CR 602.2b gathering pipeline, which may *pause* for a selection (which
 * card to discard, which permanent to sacrifice); a mana ability's cost is paid inside CR 601.2g,
 * in the middle of another cost's payment, where the engine may not pause at all — every choice it
 * needs has to be settled in the [dev.mtgplay.rules.decision.PaymentPlan] before execution starts.
 * So this vocabulary is deliberately restricted to components whose payment is either forced or
 * recorded in the plan, and a component that needs a mid-payment decision cannot be added here
 * without a plan-shape change (docs/design/mana-payment.md §11).
 *
 * Sealed so the payment enumerator, the capacity check and the executor handle every component
 * exhaustively; a new cost shape breaks compilation at each of them rather than being silently
 * treated as free — which would put mana in the enumerated action space (ADR-005) that the rules do
 * not permit, the failure mode docs/design/mana-payment.md §2.1 exists to prevent.
 */
sealed interface ManaAbilityCost {
    /**
     * A mana component (CR 118) — Conduit Pylons' and Giant's Boulder's `{1}`, Barrels of Blasting
     * Jelly's `{1}`. Paid out of the same pool the ability adds to, which is what makes a costed
     * mana ability both a consumer and a producer and forces the payment plan to record *which*
     * mana pays it ([dev.mtgplay.rules.decision.ManaActivation.costPayment]) and the enumerator to
     * prove an execution order exists (docs/design/mana-payment.md §11.2).
     *
     * @property cost the mana this activation costs; never `{0}` (a free ability simply omits the
     *   component).
     */
    data class Mana(
        val cost: ManaCost,
    ) : ManaAbilityCost {
        init {
            require(cost.symbols.isNotEmpty()) {
                "CR 605.1a: a mana ability with a {0} mana component is a free ability; omit the component"
            }
        }
    }

    /** Tap the source permanent (the `{T}` symbol, CR 602.2a) — the cost of an ordinary mana source. */
    data object TapSelf : ManaAbilityCost

    /** Sacrifice the source permanent (CR 701.17) — an Eldrazi Spawn's "Sacrifice this token: Add {C}". */
    data object SacrificeSelf : ManaAbilityCost

    /**
     * Tap an untapped creature its controller controls **other than the source** (CR 602.1) — Saruli
     * Caretaker's "{T}, Tap an untapped creature you control". The source is excluded because the
     * sibling [TapSelf] component has already tapped it, so it is no longer untapped when this
     * component is paid.
     *
     * The tap symbol does **not** appear on the creature being tapped, so CR 602.5a does not reach it:
     * a summoning-sick creature is a perfectly legal choice, exactly as it is for Springleaf Drum.
     *
     * The engine chooses the creature rather than surfacing it (no pause is available inside
     * CR 601.2g): it takes the first untapped creature in battlefield order that the rest of the plan
     * does not still need, which the capacity accounting guarantees exists
     * (docs/design/mana-payment.md §11.3).
     */
    data object TapAnotherCreature : ManaAbilityCost

    /**
     * Put a [counter] on the source permanent (CR 122.1) — Wall of Roots' "Put a -0/-1 counter on this
     * creature: Add {G}". Always payable, so it constrains nothing in the capacity check; what bounds
     * the card is its [ManaAbility.oncePerTurn] restriction, not this component.
     *
     * @property counter the counter one activation places (CR 122.1a).
     */
    data class PutCounterOnSelf(
        val counter: Counter,
    ) : ManaAbilityCost
}
