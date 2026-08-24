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
 * ([SourceClassKey.viaSacrifice]) — and that ability adds the mana of [produced].
 *
 * The activation's full **yield** is [produced] plus [SourceClassKey.bonus], the CR 605.1b
 * triggered mana the Auras attached to the source add. All of it enters the pool, and all of it
 * is spendable by this plan's [PaymentPlan.payments].
 *
 * @property sourceClass the class of payment-equivalent sources one member of which is activated.
 * @property produced the mana the source's own ability was chosen to add — one of the alternatives
 *   in [SourceClassKey.profile], and therefore a *multiset*: `[GREEN]` for a Forest, `[RED]` or
 *   `[GREEN]` (the choice) for a dual land, `[COLORLESS, COLORLESS, COLORLESS]` for an Urza's Tower
 *   with Tron assembled. Never empty.
 */
data class ManaActivation(
    val sourceClass: SourceClassKey,
    val produced: List<ManaType>,
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
 * **The profile is computed from live state, and that is what makes conditional production a
 * *profile* problem rather than a *relation* problem** (docs/design/mana-payment.md §2, §8). An
 * Urza's Tower with Tron assembled has profile `[[C, C, C]]` and one without has `[[C]]`, so the
 * two are automatically different classes with no change to the equivalence relation — exactly as
 * an Abundant-Growth-enchanted Forest already differed from a bare one. It also means the key is
 * the engine's **correspondence certificate**: because the state-derived count is *inside* the key,
 * and the executor locates the member it activates by re-deriving the key against live state, an
 * activation whose count moved between planning and payment cannot execute at all — it fails
 * loudly instead of quietly producing a different amount (§8.3).
 *
 * @property card the printed card every member shares.
 * @property profile the **alternatives** one activation of a member may add, each a multiset of
 *   mana types in WUBRG-then-colorless order (CR 105.1), the alternatives themselves in that same
 *   order; never empty, and no alternative is empty. The load-bearing half of equivalence, and the
 *   set a [ManaActivation.produced] is chosen from. A Forest is `[[GREEN]]`; a Bridge offering a
 *   choice is `[[BLUE], [RED]]`; an assembled Urza's Mine is `[[COLORLESS, COLORLESS]]`.
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
    val profile: List<List<ManaType>>,
    val bonus: List<ManaType> = emptyList(),
    val viaSacrifice: Boolean = false,
) {
    init {
        require(profile.isNotEmpty()) { "CR 605.1a: a mana source class has at least one production alternative" }
        require(profile.none { it.isEmpty() }) {
            "CR 605.1a: a production alternative adds at least one mana; an empty one is no mana source " +
                "and must be filtered out before the class is built (card ${card.name})"
        }
    }
}
