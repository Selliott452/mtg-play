package dev.mtgplay.core.definition

/**
 * A "draw [drawCount] cards, then discard [discardCount] cards" resolution clause (CR 601.2c) — Faithless
 * Looting's "Draw two cards, then discard two cards." Additive, flagged core (P6.2c). Card-definition
 * *declaration*; `mtg-rules` runs it as the spell's resolution: it draws, then pauses for a mandatory
 * selection of exactly [discardCount] hand cards (clamped to the hand size), each discarded through the
 * CR 614/616 framework — so a discarded madness card (Fiery Temper) is exiled instead and its reflexive cast
 * fires, the Madness deck's flagship loot-into-madness line.
 *
 * The discard is **mandatory** and may remove more than one card, distinguishing this from the optional,
 * single-card [OptionalDiscardDraw]; the "then" fixes the order (draw first, discard second).
 *
 * @property drawCount how many cards to draw first (Faithless Looting's two).
 * @property discardCount how many cards to discard after (Faithless Looting's two).
 */
data class DrawThenDiscard(
    val drawCount: Int,
    val discardCount: Int,
) {
    init {
        require(drawCount >= 0) { "CR 120.1: a draw count is non-negative, was $drawCount" }
        require(discardCount >= 1) {
            "CR 601.2c: a 'then discard' clause discards at least one card, was $discardCount"
        }
    }
}
