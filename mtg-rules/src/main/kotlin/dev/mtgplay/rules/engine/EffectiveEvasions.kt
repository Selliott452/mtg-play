package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.PersistentSet

/**
 * The in-game block-legality restrictions of the battlefield object [id] (CR 509.1b, CR 613 layer 6):
 * printed evasions unioned with active grants, via [layeredCharacteristics]. An object with no
 * definition has none.
 *
 * The fifth `effective*` seam, and the newest. Until the keyword-tail packet the block-legality check
 * read evasions **straight off the definition registry**, which was correct only for as long as no
 * card granted one; Gingerbrute's "{1}: This creature can't be blocked this turn except by creatures
 * with haste" is a resolution-generated grant (CR 611.2) with no printed value to read at all, so the
 * read had to move onto the layer system before the card could exist.
 *
 * Routing it here also means a granted evasion and a printed one are indistinguishable to
 * [eligibleBlockPairings] — the property that stops Silhana Ledgewalker's printed restriction and
 * Gingerbrute's granted one drifting apart, which is exactly what happened to flying and reach before
 * `FW-COUNTERS` separated them.
 */
internal fun effectiveEvasions(
    state: GameState,
    id: ObjectId,
): PersistentSet<Evasion> = layeredCharacteristics(state, id).evasions
