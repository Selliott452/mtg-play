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
 * - [IfTargets] is a **flat amount gated on the spell's own chosen targets** — Ride's End's "{3} less to
 *   cast if it targets a tapped permanent" (`FW-TGTCOND`).
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

    /**
     * Reduce by [amount] generic mana when **at least one of the spell's own chosen targets** satisfies
     * [condition]; by nothing otherwise (CR 601.2f). Ride's End's "This spell costs {3} less to cast if it
     * targets a tapped permanent". Additive, flagged core (`FW-TGTCOND`).
     *
     * **The first cost input that is not a property of the board.** [PerMatching] and [IfAll] both read
     * zones; this one reads the *choice the caster just made*, which is what makes it a genuinely different
     * shape rather than a third predicate. CR 601.2 already sequences it correctly and the engine's
     * pipeline already follows that sequence — targets are chosen at CR 601.2c, the total cost is
     * determined at CR 601.2f, and the payment plan is enumerated after both — so no stage moves to
     * support this. What it does force are two things `mtg-rules` owns and states in full:
     *
     * 1. **Cast legality is decided before targets exist**, so the castability gate must price the spell at
     *    the *cheapest cost any legal target choice could reach*, not at the printed cost. Pricing it
     *    unreduced would hide the two-mana Ride's End from a seat holding two mana and a tapped blocker,
     *    which is ADR-005's silent defect in the direction that deletes a legal play.
     * 2. **The target enumeration must then be filtered by affordability**, so a seat that can pay only the
     *    reduced cost is not offered a target that would price the cast out of reach mid-cast. CR 601.2h
     *    answers that case with a rewind (CR 728) the engine has no representation for, and offering an
     *    option that dead-ends is ADR-005's other direction. It is the same gate the kicker announcement
     *    already applies to itself.
     *
     * **Only one instance of the word "target"** is contemplated here: "at least one chosen target" is the
     * printed reading of every card in the family, and a spell whose cost depended on a *combination* of
     * several chosen targets would make the affordability filter a subset enumeration rather than a
     * per-option test. `mtg-rules` refuses that case loudly rather than filtering wrongly.
     *
     * @property amount the generic mana to reduce by; at least one.
     * @property condition what a chosen target must satisfy for the reduction to apply.
     */
    data class IfTargets(
        val amount: Int,
        val condition: TargetCondition,
    ) : CostReduction {
        init {
            require(amount > 0) { "a target-conditional reduction reduces by at least 1, was $amount" }
        }
    }

    /**
     * Reduce by [amountPerDraw] generic mana for **each card the caster has drawn this turn** (CR 121.1)
     * — Deem Inferior's *"This spell costs {1} less to cast for each card you've drawn this turn."*
     * Additive, flagged core (`W9-F`).
     *
     * **A fourth member rather than a [CountScope], and the reason is what it counts.** [PerMatching]
     * multiplies its amount by the objects in a *zone* matching an [ObjectPredicate]; this counts an
     * **event tally** that no zone holds — the cards drawn are in a hand, indistinguishable from the ones
     * that were there at the draw step, and cards drawn and then discarded still count. The tally has
     * lived on [dev.mtgplay.core.state.PlayerState.drawsThisTurn] since Sneaky Snacker's
     * [TriggerCondition.DrewNthCardThisTurn], so this reads state the engine already keeps; what it
     * cannot do is pretend to be a set of objects.
     *
     * The reading is **"you", the caster** (CR 601.2f reads the game state as the total cost is
     * determined), and the tally is per-player, so an opponent's draws on your turn reduce nothing.
     * The turn's own draw step counts: a Deem Inferior cast in a main phase is already `{2}{U}`.
     *
     * @property amountPerDraw the generic mana to reduce by per card drawn; at least one.
     */
    data class PerDrawThisTurn(
        val amountPerDraw: Int,
    ) : CostReduction {
        init {
            require(amountPerDraw > 0) { "a per-draw reduction reduces by at least 1, was $amountPerDraw" }
        }
    }
}

/**
 * What a chosen target must be for a [CostReduction.IfTargets] to apply (CR 601.2f) — the noun half of
 * "if it targets a tapped permanent". Additive, flagged core (`FW-TGTCOND`).
 *
 * A closed enum rather than a predicate, for [PermanentRestriction]'s reasons: a card definition is data
 * (ADR-003), the value takes part in the structural equality this engine leans on, and a new condition must
 * break the rules-side `when` rather than slip through. Members exist only where a card in the pool prints
 * them, which today is one.
 *
 * **Distinct from [PermanentRestriction], which it superficially resembles.** That type says which
 * permanents may be *chosen*; this one says which chosen permanents make the spell cheaper. Ride's End
 * targets any creature or Vehicle and is discounted by a tapped one, so the two are different sets on the
 * same card and folding them together would make the discount decide legality.
 */
enum class TargetCondition {
    /**
     * A **tapped permanent** (CR 110.5b) — Ride's End's condition. Read off the chosen target's live
     * status, at CR 601.2f, once: the cost is locked in there, so untapping the permanent in response to
     * the spell does not re-price it (and cannot, since no player has priority between CR 601.2f and the
     * spell being cast).
     *
     * A target that is not a permanent at all — a player, a spell on the stack, a card in a graveyard —
     * simply does not satisfy this, rather than being an error: "targets a tapped permanent" is false for
     * them in exactly the way the printed card means.
     */
    TAPPED_PERMANENT,
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
