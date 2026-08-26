package dev.mtgplay.rules.effect

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.updatePlayer

/**
 * Whether [player] has had a land enter the battlefield under their control during the current turn
 * (CR 702.135a) — the **landfall** condition, published for card definitions to read. Additive (`W9-C`).
 *
 * **A published predicate rather than a field read, because "landfall" is a rules reading and not a
 * number.** [dev.mtgplay.core.state.PlayerState.landsEnteredThisTurn] is a count, and every card that
 * prints landfall asks the same question of it — "was there at least one?" — so asking it in one place
 * means a second landfall card cannot answer it differently, and means the day the engine has to
 * distinguish a land *entering* from a land being *played* there is one call site to correct rather than
 * one per card. It is the only member of the reading half of `mtg-rules`'s card-facing surface, the rest
 * of which performs transitions; it is here rather than in `engine` because `engine` is internal and this
 * is for cards.
 *
 * **Read at resolution, not at cast.** CR 608.2 performs a spell's instructions as it resolves, and
 * landfall is one of them: Searing Blaze checks this as it resolves, so a land that entered while it sat
 * on the stack counts. That is not a hypothetical — a land can enter during a response without any player
 * playing one.
 *
 * The counter it reads is reset for every player as each turn begins, so "this turn" needs no timestamp.
 */
fun hadLandEnterThisTurn(
    state: GameState,
    player: PlayerId,
): Boolean = (state.players[player]?.landsEnteredThisTurn ?: 0) > 0

/**
 * Records a land's entry against its controller's per-turn tally (CR 305, `W9-C`) — the write half of the
 * landfall fact [hadLandEnterThisTurn] reads.
 *
 * **Called from the single battlefield-entry announcement site**, `announceBattlefieldEntry`, and it is
 * there for exactly the reason that site exists: a landfall counter maintained at each entry call site
 * would be forgotten by the next entry path somebody adds, and the failure would be silent — a landfall
 * spell that quietly deals its small number instead of its large one, with no crash and no invariant to
 * notice. Announcing an entry and counting a land are one indivisible step.
 *
 * It counts **entries**, so a land put onto the battlefield by a search or a return counts as readily as a
 * played one; see [dev.mtgplay.core.state.PlayerState.landsEnteredThisTurn] for why that is not
 * [dev.mtgplay.core.state.Turn.landsPlayedThisTurn]. Control is ownership in the MVP pool
 * (docs/design/layer-system.md §4), so the entering object's owner is the player it entered under.
 *
 * It lives beside the reader rather than in `engine` so that one file owns both halves of the counter; a
 * non-land entry is a no-op, which is the overwhelming majority of calls.
 */
internal fun countLandfall(
    state: GameState,
    battlefieldId: ObjectId,
): GameState {
    val entering = state.sharedZones.battlefield.firstOrNull { it.id == battlefieldId }
    val types = entering?.let { state.definitions[it.card]?.characteristics?.cardTypes }.orEmpty()
    return if (entering == null || CardType.LAND !in types) {
        state
    } else {
        state.updatePlayer(entering.owner) { it.copy(landsEnteredThisTurn = it.landsEnteredThisTurn + 1) }
    }
}
