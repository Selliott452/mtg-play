package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.toPersistentList

/**
 * Records [exileId] on the battlefield permanent [sourceId] as linked information (CR 607.2) — the one
 * place [dev.mtgplay.core.state.GameObject.linkedExiled] is written.
 *
 * Two callers need it and they arrive by different routes: a linked exile that is an ordinary
 * [dev.mtgplay.core.definition.ResolutionEffect] (Journey to Nowhere, via
 * [dev.mtgplay.rules.effect.exileLinkedToSource]) and one that is a mid-resolution clause the engine
 * orchestrates (Mesmeric Fiend, whose exiled card is chosen from a revealed hand and so cannot be an
 * effect at all). Giving them one home means the CR 607.2 record has a single writer, and the
 * append-order guarantee — exiled order, which is the order a multi-exile linked ability would return
 * in — is stated once.
 *
 * A no-op if [sourceId] is not on the battlefield (CR 607.3: an ability whose source has already gone
 * records nothing, and its linked partner then finds nothing).
 */
internal fun recordLinkedExile(
    state: GameState,
    sourceId: ObjectId,
    exileId: ObjectId,
): GameState {
    if (state.sharedZones.battlefield.none { it.id == sourceId }) return state
    return state.updateBattlefield { battlefield ->
        battlefield
            .map { permanent ->
                if (permanent.id == sourceId) {
                    permanent.copy(linkedExiled = permanent.linkedExiled.adding(exileId))
                } else {
                    permanent
                }
            }.toPersistentList()
    }
}
