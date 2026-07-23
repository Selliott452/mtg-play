package dev.mtgplay.rules.decision

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaType

/**
 * One enumerated way to pay a mana cost (CR 601.2g–h) — the option payload of a
 * [DecisionRequest.ChoosePaymentPlan], per the model in docs/design/mana-payment.md.
 *
 * A plan is **declarative**: it assigns each cost symbol (in printed order, generic symbols
 * expanded in place) one [SymbolPayment], naming source *classes* rather than source objects —
 * interchangeable sources are collapsed, and execution picks concrete members
 * deterministically (first untapped in battlefield order). Plans are enumerated exhaustively
 * for the paused state, deduplicated, and deterministically ordered (ADR-005; see the design
 * note for the ordering rules), so a recorded plan index replays unambiguously (ADR-006).
 *
 * @property payments one payment per expanded cost symbol, in printed order; empty exactly
 *   for a `{0}` cost.
 */
data class PaymentPlan(
    val payments: List<SymbolPayment>,
)

/**
 * How one cost symbol is paid within a [PaymentPlan].
 *
 * Sealed and exhaustive: the two members are exactly the CR-legal ways the MVP pool pays a
 * symbol — with one mana (CR 601.2h) or, for a Phyrexian symbol only, with 2 life (CR 107.4).
 */
sealed interface SymbolPayment {
    /**
     * Pay the symbol with one mana of type [mana], drawn from [source]. The mana type is fixed
     * in the plan — this is where a hybrid symbol's side and an any-color source's color are
     * chosen (docs/design/mana-payment.md).
     */
    data class WithMana(
        val mana: ManaType,
        val source: ManaSourceChoice,
    ) : SymbolPayment

    /**
     * Pay a Phyrexian symbol's alternative: 2 life instead of the mana (CR 107.4). Enumerated
     * only when the caster's life can cover every life payment in the plan (CR 118.8); paying
     * down to 0 or less is legal — the CR 704.5a state-based action follows.
     */
    data object WithTwoLife : SymbolPayment
}

/**
 * Where a [SymbolPayment.WithMana]'s mana comes from.
 */
sealed interface ManaSourceChoice {
    /** Mana already in the caster's pool (CR 106.4). */
    data object FromPool : ManaSourceChoice

    /**
     * Activate the tap-for-mana ability of one member of [sourceClass] (CR 605.3): the engine
     * taps the class's first untapped member in battlefield order — members are
     * payment-equivalent by construction, so which one is rules-irrelevant.
     */
    data class ByTapping(
        val sourceClass: SourceClassKey,
    ) : ManaSourceChoice
}

/**
 * The identity of one class of payment-equivalent mana sources (docs/design/mana-payment.md):
 * untapped battlefield sources under the caster's control with the same printed [card] and the
 * same production [profile] are interchangeable, so plans reference the class, never a member.
 *
 * @property card the printed card every member shares.
 * @property profile the canonical list of mana types a member's tap can add, in
 *   WUBRG-then-colorless order (CR 105.1); the load-bearing half of equivalence.
 * @property bonus the extra mana a member's tap adds *in addition to* its primary production, from a
 *   triggered mana ability that fires when it is tapped for mana (CR 605.1b) — Utopia Sprawl's chosen
 *   colour on an enchanted Forest. Additive, flagged (P6.2a); empty for an ordinary source. Part of the
 *   equivalence key so an enchanted Forest forms a **distinct** source class from a bare Forest (their
 *   taps leave genuinely different pools), but it is *not* a candidate payment — the primary [profile]
 *   still fixes what the tap pays toward the cost; the bonus floats into the pool (docs/design/
 *   mana-payment.md §"Triggered mana abilities mid-payment").
 * @property viaSacrifice whether a member is **sacrificed** to produce its mana rather than tapped
 *   (CR 605.1a) — an Eldrazi Spawn's "Sacrifice this token: Add {C}". Additive, flagged (P6.2a); `false`
 *   for a tap source. Part of the equivalence key so a sacrifice source never collapses with a tap
 *   source of the same production (their activations leave genuinely different battlefields), and a
 *   sacrifice source is usable whether or not it is tapped.
 */
data class SourceClassKey(
    val card: CardRef,
    val profile: List<ManaType>,
    val bonus: List<ManaType> = emptyList(),
    val viaSacrifice: Boolean = false,
)
