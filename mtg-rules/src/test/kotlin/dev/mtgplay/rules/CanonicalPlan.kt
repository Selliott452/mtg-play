package dev.mtgplay.rules

import dev.mtgplay.rules.decision.ManaActivation
import dev.mtgplay.rules.decision.SymbolPayment

/**
 * A payment plan's canonical identity for the completeness oracle: the activation multiset, and the
 * payment multiset of each run of identical cost symbols. Two plans share a [CanonicalPlan] exactly
 * when docs/design/mana-payment.md §3.3 calls them the same plan, so the enumerator emitting two of
 * them is a duplicate and the oracle holding one the enumerator does not is a gap.
 */
internal data class CanonicalPlan(
    val activations: Map<ManaActivation, Int>,
    val payments: Map<Int, Map<SymbolPayment, Int>>,
)
