package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.cardObject
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * The multiset of printed cards present in a game: how many objects of each
 * [CardRef] exist across every zone (CR 400.2).
 *
 * This is the conserved quantity behind [Invariant.CARD_CONSERVATION]. Because card conservation
 * is a property *across time* — the census must not change from one state to the next — a single
 * state cannot be judged in isolation: the driver captures the census of the game's first state
 * as the baseline and compares every later state's census against it (see
 * [InvariantChecker.check]).
 *
 * The count is keyed by [CardRef] rather than [dev.mtgplay.core.identity.ObjectId] on purpose: an
 * object is reborn with a fresh id on every zone change (CR 400.7), so ids are *not* conserved,
 * but the printed card each object carries is.
 *
 * **Tokens are excluded (CR 111, P5.1).** A token is not a card — it is created by an effect and
 * ceases to exist off the battlefield (CR 704.5d) — so it is *not* a conserved quantity: it is left
 * out of the census entirely (identified by `definitions[card] is TokenDefinition`), and card
 * conservation continues to hold exactly over the real cards while tokens come and go.
 *
 * @property counts how many non-token objects of each printed card exist; the map is insertion-ordered
 *   by first appearance for deterministic reporting (never a hash map, per the [GameState] iteration
 *   rule).
 */
@JvmInline
value class CardCensus internal constructor(
    val counts: PersistentMap<CardRef, Int>,
) {
    companion object {
        /** The empty census: no cards present. */
        val EMPTY: CardCensus = CardCensus(persistentMapOf())

        /**
         * Tallies the multiset of printed cards across every zone of [state], in ascending seat
         * order then shared zones, so equal states always produce equal censuses.
         */
        fun of(state: GameState): CardCensus {
            val tally = LinkedHashMap<CardRef, Int>()

            fun count(objects: Iterable<GameObject>) {
                // CR 111, CR 704.5d: tokens are not cards and are not conserved — exclude them.
                objects
                    .filter { state.definitions[it.card] !is TokenDefinition }
                    .forEach { tally[it.card] = (tally[it.card] ?: 0) + 1 }
            }
            state.players.entries
                .sortedBy { it.key.seat }
                .forEach { (_, player) ->
                    count(player.library)
                    count(player.hand)
                    count(player.graveyard)
                }
            count(state.sharedZones.battlefield)
            // The stack holds typed entries; a spell's card object counts (CR 405.2), a triggered
            // ability contributes no card (CR 113.7a, P5.1).
            count(state.sharedZones.stack.mapNotNull { it.cardObject })
            count(state.sharedZones.exile)
            return CardCensus(tally.toPersistentMap())
        }
    }
}
