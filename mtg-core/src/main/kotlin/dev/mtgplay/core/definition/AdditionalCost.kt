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
}
