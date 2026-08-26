package dev.mtgplay.core.definition

/**
 * An additional cost intrinsic to casting a spell (CR 601.2b) — a cost the caster must pay *on top
 * of* the mana cost, declared on the card itself. Additive, flagged core (P6.2a). Card-definition
 * *declaration*; `mtg-rules` owns surfacing the selection decision, checking payability, and
 * performing the cost during payment (CR 601.2h).
 *
 * Distinct from a [CastingPermission]'s alternative/additional cost: those ride on a specific cast
 * permission (escape's exile, Fireblast's sacrifice), while this applies to **every** cast of the
 * card — "As an additional cost to cast this spell, discard a card." Sealed so the pipeline handles
 * every additional-cost shape exhaustively. The MVP pool needs [DiscardCards] (Grab the Prize) and
 * [Sacrifice] (Eviscerator's Insight, Reckoner's Bargain, Crop Rotation, Raze); other shapes are the
 * extension point.
 *
 * **"Every cast" includes a permission cast.** A card's additional cost applies whichever way it is
 * cast, which is what flashback's reminder text spells out — "you may cast this card from your
 * graveyard for its flashback cost **and any additional costs**" (CR 702.34a). Eviscerator's Insight
 * is the pool's witness: its flashback cast sacrifices an artifact or creature too.
 */
sealed interface AdditionalCost {
    /**
     * "As an additional cost to cast this spell, discard [count] card(s)" (CR 601.2b) — Grab the
     * Prize's "discard a card" ([count]`= 1`). The discard routes through the CR 614/616 replacement
     * framework, so a card with madness discarded this way is exiled instead (CR 702.35a) and its
     * reflexive cast trigger fires. The discarded card's identity is recorded as linked information on
     * the cast record, readable by the spell's resolution (Grab the Prize's "if the discarded card
     * wasn't a land card").
     *
     * @property count how many cards must be discarded (at least 1).
     */
    data class DiscardCards(
        val count: Int,
    ) : AdditionalCost {
        init {
            require(count >= 1) { "CR 601.2b: a discard additional cost discards at least one card, was $count" }
        }
    }

    /**
     * "As an additional cost to cast this spell, sacrifice [count] permanent(s) matching [filter]"
     * (CR 601.2b, performed at CR 601.2h via CR 701.17) — Eviscerator's Insight's and Reckoner's
     * Bargain's "sacrifice an artifact or creature", Raze's and Crop Rotation's "sacrifice a land",
     * each [count]`= 1`. Additive, flagged core (`FW-ADDSAC`).
     *
     * Deliberately the same shape as [DiscardCards]: a count plus what may be chosen, with the engine
     * enumerating the selection and the caster picking by index (ADR-005). The sacrificed permanents'
     * printed identities are recorded as linked information on the cast record, readable by the
     * spell's resolution — which is what lets Reckoner's Bargain gain life equal to "the sacrificed
     * permanent's mana value".
     *
     * **A cost, not an effect.** It is paid at CR 601.2h, cannot be responded to, and no player gets
     * priority between choosing it and paying it; a spell whose sacrifice cost cannot be paid is not
     * enumerated at all rather than being offered and then failing (ADR-005).
     *
     * @property count how many permanents must be sacrificed (at least 1).
     * @property filter which permanents may be chosen (CR 601.2h).
     */
    data class Sacrifice(
        val count: Int,
        val filter: SacrificeFilter,
    ) : AdditionalCost {
        init {
            require(count >= 1) {
                "CR 601.2b: a sacrifice additional cost sacrifices at least one permanent, was $count"
            }
        }
    }

    /**
     * "As an additional cost to cast this spell, **choose a creature you control or reveal a creature card
     * from your hand**" (CR 601.2b, CR 701.15a) — Monstrous Emergence's. Additive, flagged core (`W9-D`).
     *
     * **The first additional cost that consumes nothing**, and that is the whole reason it is its own
     * member. [DiscardCards] and [Sacrifice] both *spend* what they name: the card leaves the hand, the
     * permanent leaves the battlefield, and the payability question is "does the caster have one to give
     * up". This one asks the caster to *point at* something — the chosen creature stays on the
     * battlefield untouched, the revealed card stays in hand — so the only thing the payment produces is
     * a **[dev.mtgplay.core.state.ChosenPowerSource]** for the resolution to read. Modelling it as a
     * sacrifice with the sacrifice omitted would make every payability check, every mana-reservation
     * exclusion, and every "what did this cost eat" reader wrong at once.
     *
     * **The two branches are one cost, not two modes.** CR 601.2b announces additional costs as a unit,
     * and the caster picks from a single pool — every creature they control plus every creature card in
     * their hand — in one decision. Splitting it into a mode choice followed by an object choice would
     * add a pause the card does not print and would let a seat pick a branch with no legal member.
     *
     * **A `data object` because there is nothing to vary.** The two nouns are the card's, and a second
     * card printing a differently-filtered version of this shape becomes its own member rather than
     * growing this one with filters — which is the discipline [OptionalAdditionalCost.Bargain] applies to
     * its own fixed union.
     *
     * **Payability** (CR 601.2b, ADR-005): the spell is castable only when the pool is non-empty — at
     * least one creature on the battlefield under the caster's control, or at least one creature card in
     * hand other than this spell. With neither, the cost cannot be paid and the cast is not enumerated at
     * all rather than offered and then dead-ending.
     */
    data object ChooseCreatureOrRevealCreatureCard : AdditionalCost
}
