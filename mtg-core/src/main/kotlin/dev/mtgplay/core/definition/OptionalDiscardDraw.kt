package dev.mtgplay.core.definition

/**
 * An optional "you may discard a card; if you do, draw [drawCount] cards" clause (CR 601.3b, CR 701.8) —
 * Melded Moxite's enters-the-battlefield "you may discard a card. If you do, draw two cards." Additive,
 * flagged core (P6.2a). Card-definition *declaration*; `mtg-rules` orchestrates the resolution flow: a
 * yes/no ("you may"), then, on yes, a discard selection, then the draw. The discard routes through the
 * CR 614/616 framework so a discarded madness card is exiled instead — the Madness deck's core synergy.
 *
 * @property drawCount how many cards to draw if the discard is made (Melded Moxite's two).
 */
data class OptionalDiscardDraw(
    val drawCount: Int,
) {
    init {
        require(drawCount >= 1) { "CR 601.3b: an 'if you do, draw' clause draws at least one card, was $drawCount" }
    }
}
