package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.announceBattlefieldDeparture
import dev.mtgplay.rules.engine.clearCombatReferences
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.isIndestructible
import dev.mtgplay.rules.engine.updateBattlefield
import dev.mtgplay.rules.engine.updatePlayer

/**
 * Effect primitive: **destroys** the battlefield permanent [objectId] (CR 701.7a) — the published
 * building block a "destroy target …" resolution composes (ADR-003; Terminate, Cast Down, Ancient
 * Grudge and Smash to Smithereens are the first clients).
 *
 * This is the engine's first destruction *effect*. Until now the only destruction it performed was
 * the CR 704.5g lethal-damage state-based action, which the game does to itself; this is a spell
 * doing it deliberately, and the two are kept apart for the reason `CreatureDeathCause` records:
 * destruction is a distinguishable event, and the CR 704.5f zero-toughness move is **not** one.
 *
 * **Indestructible (CR 702.12b) is honoured, and honoured through the one seam.** The check is
 * `isIndestructible`, the CR 613-layer-6 accessor `EffectiveCharacteristics.kt` publishes, never a
 * re-derivation from printed keywords — so a *granted* indestructible would stop this effect exactly
 * as a printed one does. An indestructible permanent is not destroyed: the state comes back
 * unchanged and **no** [GameEvent.PermanentDestroyed] is emitted, which is how the log distinguishes
 * "destroyed" from "a destroy effect resolved and destroyed nothing". The spell itself still
 * resolved; CR 701.7b makes only the destruction fail.
 *
 * The destroyed permanent moves to its **owner's** graveyard (CR 701.7a, CR 400.7) as a new object
 * carrying no battlefield memory — untapped, no marked damage, attached to nothing. A
 * "put into a graveyard from the battlefield" trigger (CR 603.6b) is then detected against its
 * pre-destruction state (CR 603.10), and combat releases it (CR 506.4), so a destroyed attacker or
 * blocker leaves combat exactly as a dead one does. An Aura on the destroyed permanent is not
 * touched here: it falls off at the next CR 704.5m state-based check, which is where that rule lives.
 *
 * **Regeneration is deliberately absent, not forgotten.** No card in the pool creates a regeneration
 * shield and the engine has no CR 701.15 replacement at all, so a regeneration-free destroy is exact
 * today — including for Terminate, whose "it can't be regenerated" clause is therefore a genuine
 * no-op (docs/gauntlet-card-triage.md trap T8). When regeneration lands, it lands as a replacement
 * consulted *here*, and Terminate becomes the card that must opt out of it; a card must not encode
 * that clause as a flag on this function before the shield it negates exists.
 *
 * Fails loudly if [objectId] is not on the battlefield: every caller reaches this after the
 * CR 608.2b re-check has confirmed its target is still a legal battlefield permanent (ADR-005), so a
 * missing one is an engine defect, not a rules case.
 */
fun destroy(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) { "CR 701.7a: a destroyed permanent must be on the battlefield, but $objectId is not" }
    // CR 702.12b: an indestructible permanent is not destroyed; the effect simply does nothing to it.
    if (isIndestructible(state, objectId)) return state
    val permanent = battlefield[index]
    val (graveyardId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = graveyardId, card = permanent.card, owner = permanent.owner)
    val moved =
        allocated
            .updateBattlefield { it.removingAt(index) }
            .updatePlayer(permanent.owner) { it.copy(graveyard = it.graveyard.adding(reborn)) }
            .emit(GameEvent.PermanentDestroyed(objectId, permanent.card, graveyardId))
    // CR 603.6b, CR 603.10: a leaves-the-battlefield trigger fires against the pre-destruction state.
    val triggered = announceBattlefieldDeparture(moved, permanent, graveyardId)
    // CR 506.4: a destroyed permanent that was in combat is removed from it.
    return clearCombatReferences(triggered, setOf(objectId))
}
