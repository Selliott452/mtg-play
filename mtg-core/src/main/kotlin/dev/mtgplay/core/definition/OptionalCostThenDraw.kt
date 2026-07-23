package dev.mtgplay.core.definition

import kotlinx.collections.immutable.PersistentList

/**
 * An optional "you may [discard a card | sacrifice a land]; if you do, draw [drawCount]" clause resolved as
 * part of a spell's resolution (CR 601.3b) — Highway Robbery's "You may discard a card or sacrifice a land.
 * If you do, draw two cards." Additive, flagged core (P6.2c). Card-definition *declaration*; `mtg-rules`
 * orchestrates the resolution flow: it offers the deciding player a mode choice (decline, or one of the
 * performable [modes]), then that mode's object selection, then the draw. Unlike the trigger-scoped
 * [OptionalDiscardDraw], this hangs on the spell's own resolution and adds the sacrifice-a-land alternative.
 *
 * @property drawCount how many cards to draw if a cost is paid (Highway Robbery's two).
 * @property modes the cost modes the clause offers, in printed order (Highway Robbery: discard, then
 *   sacrifice); each is offered only when performable, and declining is always legal.
 */
data class OptionalCostThenDraw(
    val drawCount: Int,
    val modes: PersistentList<OptionalCostMode>,
) {
    init {
        require(drawCount >= 1) { "CR 601.3b: an 'if you do, draw' clause draws at least one card, was $drawCount" }
        require(modes.isNotEmpty()) { "CR 601.3b: a cost-then-draw clause offers at least one mode" }
    }
}
