package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.Color
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet

/**
 * A declared reduction of a spell's total cost (CR 601.2f), in the two structurally different shapes
 * the pool prints. Additive, flagged core (`FW-COST`, docs/design/cost-modification.md §1).
 *
 * Both shapes reduce by **an amount of generic mana**, which CR 118.7a confines to the generic
 * component of the cost: Cryptic Serpent `{5}{U}{U}` with seven instants and sorceries in the
 * graveyard costs `{U}{U}`, never less. `mtg-rules` owns the arithmetic, the CR 601.2f lock-in, and
 * the floor at `{0}`; card definitions carry only the declaration.
 *
 * The two shapes differ in *what they read*, not in how they are applied:
 *
 * - [PerMatching] is a **count** — affinity's "{1} less for each artifact you control" (CR 702.41a)
 *   and the Terrors' "{1} less for each instant and sorcery card in your graveyard".
 * - [IfAll] is a **flat amount gated on a board condition** — Of One Mind's "{2} less if you control
 *   a Human creature and a non-Human creature".
 *
 * Both are declared on the *spell* ([SpellDefinition.costReduction]). The other-object shape — a
 * battlefield permanent reducing *other* spells — is [SpellCostReduction], declared on
 * [CardDefinition] instead, because its reader and its subject are different objects.
 */
sealed interface CostReduction {
    /**
     * Reduce by [amountPerMatch] generic mana for **each** object in [scope] matching [predicate]
     * (CR 702.41a for affinity). The object being cast is never counted — CR 601.2a has already moved
     * it out of its source zone by the time the total cost is determined, and `mtg-rules` excludes it
     * explicitly so the gathering-time and execution-time answers agree by construction.
     */
    data class PerMatching(
        val amountPerMatch: Int,
        val scope: CountScope,
        val predicate: ObjectPredicate,
    ) : CostReduction {
        init {
            require(amountPerMatch > 0) { "a per-match reduction reduces by at least 1, was $amountPerMatch" }
        }
    }

    /**
     * Reduce by [amount] generic mana when **every** condition in [conditions] holds; by nothing
     * otherwise. Of One Mind's two conditions are "a Human creature you control" and "a non-Human
     * creature you control", each at least one.
     */
    data class IfAll(
        val amount: Int,
        val conditions: PersistentList<CountCondition>,
    ) : CostReduction {
        init {
            require(amount > 0) { "a conditional reduction reduces by at least 1, was $amount" }
            require(conditions.isNotEmpty()) { "a conditional reduction states at least one condition" }
        }
    }
}

/**
 * One board condition: at least [atLeast] objects in [scope] match [predicate]. A component of
 * [CostReduction.IfAll], read once at CR 601.2f like every other cost input.
 */
data class CountCondition(
    val scope: CountScope,
    val predicate: ObjectPredicate,
    val atLeast: Int,
) {
    init {
        require(atLeast > 0) { "a count condition demands at least one match, was $atLeast" }
    }
}

/**
 * A **battlefield permanent's** static ability reducing the cost of *other* spells its controller
 * casts (CR 604.5, CR 601.2f) — Sunscape Familiar's "Green spells and blue spells you cast cost {1}
 * less to cast". Additive, flagged core (`FW-COST`, docs/design/cost-modification.md §1, C6).
 *
 * Declared on [CardDefinition] rather than [SpellDefinition] because the *reader* is a permanent and
 * the *subject* is somebody else's spell — the two-slot answer to the design note's open question 4.
 *
 * This is a continuous effect that modifies the **rules** rather than an object (CR 613.11), so it is
 * applied at cost determination and **never enters the CR 613 layer system**; there is no timestamp
 * and no dependency to resolve, because CR 601.2f's arithmetic is order-independent for generic
 * reductions (design note §3).
 *
 * @property amount the generic mana to reduce by; applied once per matching *reducer*, so two
 *   Familiars reduce by two.
 * @property spellColors the spell colours this reduces (CR 202.2). A spell matches when it has **any**
 *   of these colours. Read from the spell's **printed** mana cost, never the alternative cost being
 *   paid: a madness, flashback, escape, or plot cast keeps the card's printed colours (CR 202.2), and
 *   a `{0}` plot cost would otherwise make every spell colourless and silently stop matching.
 */
data class SpellCostReduction(
    val amount: Int,
    val spellColors: PersistentSet<Color>,
) {
    init {
        require(amount > 0) { "a spell cost reduction reduces by at least 1, was $amount" }
        require(spellColors.isNotEmpty()) {
            "a colour-gated reduction names at least one colour; an ungated reducer is a different shape"
        }
    }
}
