package dev.mtgplay.core.definition

/**
 * An "each opponent discards a card; for each opponent who can't, you draw a card" clause (CR 701.7a) —
 * Refurbished Familiar. Card-definition data, additive and flagged core (`FW-NONCTRLDEC`,
 * docs/design/exile-and-return.md §6).
 *
 * A [ResolutionClauses] member because it needs a mid-resolution pause the [ResolutionEffect] signature
 * cannot express — and, uniquely among the six clauses, **the pause belongs to a player who is not the
 * resolving object's controller.** "Each opponent discards a card" means each of them chooses which
 * card of their own, from a zone the controller may not see (CR 701.7a: "the player chooses a card in
 * their hand").
 *
 * That makes this the packet's ADR-005 and ADR-007 question at once, answered in
 * docs/design/exile-and-return.md §6.1: the discarding opponent is the deciding seat and is handed the
 * enumerated options (their own hand); the controller is handed nothing but the fact that the question
 * was asked and of whom. It is the second decision in the engine whose decider is not the resolving
 * object's controller — `FW-COUNTER`'s unless-pay clause was the first — and the first whose **option
 * list is hidden information**, which the unless-pay clause's never was.
 *
 * The "for each opponent who can't" half needs no separate decision: an opponent with an empty hand
 * cannot discard, so no request is surfaced for that seat at all and the controller draws instead
 * (CR 701.7a — an impossible discard simply does not happen).
 *
 * @property count how many cards each opponent discards (Refurbished Familiar's is 1); at least 1.
 * @property drawPerOpponentWhoCannot how many cards the controller draws for each opponent who cannot
 *   discard (Refurbished Familiar's is 1); zero for a card with no such rider.
 */
data class EachOpponentDiscards(
    val count: Int,
    val drawPerOpponentWhoCannot: Int = 0,
) {
    init {
        require(count >= 1) { "CR 701.7a: an each-opponent discard clause discards at least one card, was $count" }
        require(drawPerOpponentWhoCannot >= 0) {
            "CR 701.7a: the draw for an opponent who cannot discard is non-negative, was $drawPerOpponentWhoCannot"
        }
    }
}
