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
 * every additional-cost shape exhaustively. The MVP pool needs exactly [DiscardCards] (Grab the
 * Prize); other shapes are the extension point.
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
}
