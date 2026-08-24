package dev.mtgplay.rules.decision

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaType

/**
 * One enumerated way to pay a mana cost (CR 601.2g–h) — the option payload of a
 * [DecisionRequest.ChoosePaymentPlan], per the model in docs/design/mana-payment.md.
 *
 * A plan is **declarative** and split into the CR's own two halves: [activations] are the mana
 * abilities to activate (CR 601.2g) and [payments] are what each cost symbol is then paid with
 * (CR 601.2h). Because production is hoisted out of the per-symbol list, **one activation may pay
 * several symbols** — a Wild-Growth'd Forest tapped once pays `{1}{G}` — and whatever a plan
 * produces and does not spend floats until the step ends (CR 500.4).
 *
 * Plans name source *classes* rather than source objects: interchangeable sources are collapsed,
 * and execution picks concrete members deterministically (first usable in battlefield order).
 * Plans are enumerated exhaustively for the paused state, deduplicated, and deterministically
 * ordered (ADR-005; see the design note for the ordering rules and the dedup argument), so a
 * recorded plan index replays unambiguously (ADR-006).
 *
 * @property activations the mana abilities to activate before paying, in the canonical order of
 *   docs/design/mana-payment.md §3.2; empty when the pool already covers the cost. Every
 *   activation spends at least one of its mana in this plan (the §4 bound).
 * @property payments one payment per expanded cost symbol, in printed order; empty exactly
 *   for a `{0}` cost.
 */
data class PaymentPlan(
    val activations: List<ManaActivation>,
    val payments: List<SymbolPayment>,
)

/**
 * One mana ability to activate while paying (CR 601.2g): the engine activates the first usable
 * member of [sourceClass] — tapping it, or sacrificing it for a sacrifice-cost ability
 * ([SourceClassKey.viaSacrifice]) — and that ability adds one mana of type [produced].
 *
 * The activation's full **yield** is `[produced]` plus [SourceClassKey.bonus], the CR 605.1b
 * triggered mana the Auras attached to the source add. All of it enters the pool, and all of it
 * is spendable by this plan's [PaymentPlan.payments].
 *
 * @property sourceClass the class of payment-equivalent sources one member of which is activated.
 * @property produced the mana type chosen for the source's own ability — the choice an "add one
 *   mana of any color" source offers; a member of [SourceClassKey.profile].
 */
data class ManaActivation(
    val sourceClass: SourceClassKey,
    val produced: ManaType,
)

/**
 * How one cost symbol is paid within a [PaymentPlan].
 *
 * Sealed and exhaustive: the two members are exactly the CR-legal ways the MVP pool pays a
 * symbol — with one mana (CR 601.2h) or, for a Phyrexian symbol only, with 2 life (CR 107.4).
 */
sealed interface SymbolPayment {
    /**
     * Pay the symbol with one mana of type [mana] from the caster's pool (CR 601.2h). The mana
     * type is fixed in the plan — this is where a hybrid symbol's side is chosen.
     *
     * A payment does not name a source. After CR 601.2g there is only one place mana can come
     * from, and whether a spent mana was floating beforehand or was produced by this plan's
     * [PaymentPlan.activations] is fully determined by that list (docs/design/mana-payment.md §3.4).
     */
    data class WithMana(
        val mana: ManaType,
    ) : SymbolPayment

    /**
     * Pay a Phyrexian symbol's alternative: 2 life instead of the mana (CR 107.4). Enumerated
     * only when the caster's life can cover every life payment in the plan (CR 118.8); paying
     * down to 0 or less is legal — the CR 704.5a state-based action follows.
     */
    data object WithTwoLife : SymbolPayment
}

/**
 * The identity of one class of payment-equivalent mana sources (docs/design/mana-payment.md):
 * usable battlefield sources under the caster's control with the same printed [card], the same
 * production [profile], the same [bonus] and the same activation cost are interchangeable, so
 * plans reference the class, never a member.
 *
 * @property card the printed card every member shares.
 * @property profile the canonical list of mana types a member's own ability may add, in
 *   WUBRG-then-colorless order (CR 105.1); the load-bearing half of equivalence, and the set a
 *   [ManaActivation.produced] is chosen from.
 * @property bonus the extra mana a member's activation adds *in addition to* [produced], from a
 *   triggered mana ability that fires when it is tapped for mana (CR 605.1b) — Utopia Sprawl's
 *   chosen colour, Wild Growth's printed `{G}`. Empty for an ordinary source. Part of the
 *   equivalence key so an enchanted Forest forms a **distinct** source class from a bare Forest
 *   (their activations leave genuinely different pools), *and* part of the activation's yield —
 *   since P8.3 this mana is spendable by the very cost whose payment produced it.
 * @property viaSacrifice whether a member is **sacrificed** to produce its mana rather than tapped
 *   (CR 605.1a) — an Eldrazi Spawn's "Sacrifice this token: Add {C}". `false` for a tap source.
 *   Part of the equivalence key so a sacrifice source never collapses with a tap source of the
 *   same production (their activations leave genuinely different battlefields), and a sacrifice
 *   source is usable whether or not it is tapped.
 */
data class SourceClassKey(
    val card: CardRef,
    val profile: List<ManaType>,
    val bonus: List<ManaType> = emptyList(),
    val viaSacrifice: Boolean = false,
)
