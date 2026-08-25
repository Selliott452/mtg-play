package dev.mtgplay.rules.decision

import dev.mtgplay.core.definition.ManaAbilityCost
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
 * **[activations] is a multiset, not a schedule** (docs/design/mana-payment.md §11.2). Since
 * `FW-MANACOST` an activation may itself *cost* mana, so the order activations run in is no longer
 * free — two Conduit Pylons must not fund each other's `{1}` out of nothing. The order is not
 * recorded here, because recording it would multiply every plan by its permutations and destroy the
 * §3.3 dedup argument; it is **derived** from the multiset, the pool and the recorded
 * [ManaActivation.costPayment]s by `manaActivationOrder`, the one function the enumerator uses to
 * decide feasibility and the executor uses to run the plan.
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
 * member of [sourceClass], paying [alternative]'s cost — tapping it, sacrificing it, tapping another
 * creature, putting a counter on it, and/or spending [costPayment] out of the pool — and that ability
 * adds the mana of [produced].
 *
 * The activation's full **yield** is [produced] plus [SourceClassKey.bonus], the CR 605.1b
 * triggered mana the Auras attached to the source add. All of it enters the pool, and all of it
 * is spendable by this plan's [PaymentPlan.payments] *and* by another activation's [costPayment].
 *
 * @property sourceClass the class of payment-equivalent sources one member of which is activated.
 * @property alternative the production alternative chosen — one entry of [SourceClassKey.profile],
 *   naming both what the activation costs and what it adds. It names the alternative rather than only
 *   its mana because, since `FW-MANACOST`, two alternatives of one source may add the *same* mana at
 *   *different* costs, and the executor must know which ability it is activating.
 * @property costPayment one mana per expanded symbol of [alternative]'s own mana cost, in printed
 *   order; empty for a free ability. This is the choice the plan has to record: a `{1}` activation
 *   cost paid with a green mana and one paid with a red leave genuinely different pools, and the
 *   executor may not pause to ask (CR 601.2g admits no decision point).
 */
data class ManaActivation(
    val sourceClass: SourceClassKey,
    val alternative: ProductionAlternative,
    val costPayment: List<ManaType> = emptyList(),
) {
    /** The mana this activation's own ability adds (CR 605.1a) — [ProductionAlternative.produced]. */
    val produced: List<ManaType> get() = alternative.produced
}

/**
 * One way a member of a source class may be activated for mana (CR 605.1a): what it [cost]s and what
 * it [produced]s. Additive (`FW-MANACOST`); before it, an alternative was its produced multiset alone
 * and the cost was a single `viaSacrifice` flag on the class key.
 *
 * The cost moved **into** the alternative rather than staying on the key because one permanent may
 * print two mana abilities with different costs — Conduit Pylons' free "{T}: Add {C}" beside its
 * "{1}, {T}: Add one mana of any color" — so which cost applies is a per-activation choice, not a
 * property of the source. That is the resolution of the gap docs/design/mana-payment.md §9 recorded
 * as "alternative activation costs are the class key, not a choice".
 *
 * @property cost the components one activation of this alternative pays (CR 602.1), in printed order;
 *   never empty. `[TapSelf]` for an ordinary source.
 * @property produced the mana the source's own ability adds, as a *multiset*: `[GREEN]` for a Forest,
 *   `[COLORLESS, COLORLESS, COLORLESS]` for an Urza's Tower with Tron assembled. Never empty.
 * @property oncePerTurn whether activating this alternative spends the source's CR 602.5b "Activate
 *   only once each turn" allowance for the rest of the turn. Part of the key so that a spent source
 *   and an unspent one are never the same class — the spent one is not a source at all.
 */
data class ProductionAlternative(
    val cost: List<ManaAbilityCost>,
    val produced: List<ManaType>,
    val oncePerTurn: Boolean = false,
) {
    init {
        require(cost.isNotEmpty()) { "CR 602.1: an activated ability has a cost, and a mana ability is one" }
        require(produced.isNotEmpty()) {
            "CR 605.1a: a production alternative adds at least one mana; an empty one is no mana source " +
                "and must be filtered out before the class is built"
        }
    }

    /** Whether activating this alternative **sacrifices** its source rather than tapping it (CR 605.1a). */
    val viaSacrifice: Boolean get() = ManaAbilityCost.SacrificeSelf in cost

    /** The mana this alternative's activation costs (CR 118), or `null` when it is free. */
    val manaCost: ManaAbilityCost.Mana? get() = cost.filterIsInstance<ManaAbilityCost.Mana>().firstOrNull()

    companion object {
        /**
         * The plain `{T}: Add …` alternative (CR 605.1a) — the shape of almost every mana source, and
         * the only shape that existed before `FW-MANACOST`. A Forest is `tapping(GREEN)`; an Urza's
         * Tower with Tron assembled is `tapping(COLORLESS, COLORLESS, COLORLESS)`.
         */
        fun tapping(vararg produced: ManaType): ProductionAlternative =
            ProductionAlternative(listOf(ManaAbilityCost.TapSelf), produced.toList())

        /**
         * The "Sacrifice this permanent: Add …" alternative (CR 605.1a) — an Eldrazi Spawn's `{C}`.
         * Usable whether or not the source is tapped, because the cost has no `{T}`.
         */
        fun sacrificing(vararg produced: ManaType): ProductionAlternative =
            ProductionAlternative(listOf(ManaAbilityCost.SacrificeSelf), produced.toList())
    }
}

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
 * production [profile] and the same [bonus] are interchangeable, so plans reference the class,
 * never a member.
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
 * `FW-MANACOST` widened the profile's element from a produced multiset to a whole
 * [ProductionAlternative] and deleted the key's `viaSacrifice` flag into it, and it did so **without
 * touching the equivalence relation** for the third packet running: a Wall of Roots that has already
 * spent its CR 602.5b once-each-turn activation simply has no available alternative and is no mana
 * source, and one that has not is the same class it always was. The same certificate therefore
 * carries the once-per-turn restriction for free (docs/design/mana-payment.md §11.4).
 *
 * @property card the printed card every member shares.
 * @property profile the **alternatives** one activation of a member may choose between, each naming
 *   its cost and the multiset it adds, in the canonical order of `productionProfile`; never empty.
 *   The load-bearing half of equivalence, and the set a [ManaActivation.alternative] is chosen from.
 * @property bonus the extra mana a member's activation adds *in addition to* the alternative's
 *   [ProductionAlternative.produced], from a triggered mana ability that fires when it is tapped for
 *   mana (CR 605.1b) — Utopia Sprawl's chosen colour, Wild Growth's printed `{G}`. Empty for an
 *   ordinary source. Part of the equivalence key so an enchanted Forest forms a **distinct** source
 *   class from a bare Forest (their activations leave genuinely different pools), *and* part of the
 *   activation's yield — since P8.3 this mana is spendable by the very cost whose payment produced it.
 */
data class SourceClassKey(
    val card: CardRef,
    val profile: List<ProductionAlternative>,
    val bonus: List<ManaType> = emptyList(),
) {
    init {
        require(profile.isNotEmpty()) { "CR 605.1a: a mana source class has at least one production alternative" }
        require(profile.distinct().size == profile.size) {
            "a source class lists each production alternative once (card ${card.name}), got $profile"
        }
    }
}
