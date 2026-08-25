package dev.mtgplay.core.definition

/**
 * A bare optional "you may draw [drawCount] card(s)" clause (CR 601.3b), or the "you may" half of a
 * trigger that offers nothing in exchange — Ninja of the Deep Hours' *"Whenever this creature deals
 * combat damage to a player, you may draw a card."* Additive, flagged core (`FW-OPTDRAW`).
 *
 * **The sibling of [OptionalDiscardDraw] with the cost removed, and it is genuinely a different clause
 * rather than that one with a zero cost.** [OptionalDiscardDraw] is "you may discard a card; **if you
 * do**, draw N": the draw is conditional on a payment, so with an empty hand the "may" cannot be taken
 * at all and the clause does nothing. This clause has no payment, so it is always takeable and the only
 * question is the yes/no. Encoding Ninja of the Deep Hours as `OptionalDiscardDraw(1)` would demand a
 * discard the card never asks for; encoding it as a mandatory [drawCount] draw would delete a real
 * decision — the one that matters when the library is nearly empty, where drawing loses the game on the
 * next draw step (CR 104.3c). Both are the silent approximation CONVENTIONS.md forbids, in opposite
 * directions.
 *
 * **Core/rules split (ADR-009).** This declares *that* the draw is optional and how big it is;
 * `mtg-rules` owns the flow — surfacing the enumerated yes/no (ADR-005, ADR-004: a resolution effect may
 * not call back into a player) and drawing on acceptance.
 *
 * @property drawCount how many cards the accepted "may" draws (Ninja of the Deep Hours' one).
 */
data class OptionalDraw(
    val drawCount: Int,
) {
    init {
        require(drawCount >= 1) { "CR 601.3b: an optional draw clause draws at least one card, was $drawCount" }
    }
}
