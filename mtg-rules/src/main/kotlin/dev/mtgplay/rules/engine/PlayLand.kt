package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.AdvanceResult

/**
 * Executes the play-land special action (CR 116.2a, CR 305.1): [player] plays the land
 * [cardObjectId] from their hand. A special action is not a spell — it uses no stack and no
 * CR 601 pipeline (CR 305.4: lands are never cast) — so the whole action is this one pure
 * transition:
 * 1. the hand card moves to the battlefield, becoming a new object (CR 400.7) that enters
 *    untapped (CR 110.5a) — unless its definition declares
 *    [dev.mtgplay.core.definition.CardDefinition.entersTapped], the CR 614.1c self-replacement
 *    "this land enters tapped", which modifies the entering event itself and so takes effect here
 *    rather than as a subsequent tap;
 * 2. the turn's land-drop count advances (CR 305.2);
 * 3. [GameEvent.LandPlayed] narrates it, and the land's own enters-the-battlefield triggers fire
 *    (CR 603.6a) — both through [announceBattlefieldEntry], which is one step rather than two;
 * 4. [player] receives priority again (CR 116.4 — taking a special action does not pass
 *    priority) in a fresh round: an action was taken, so every pass-flag resets and the
 *    CR 117.4 "all players pass in succession" count starts over. State-based actions are
 *    checked before the window opens (CR 704.3, inside [priorityTo]), and that is also where any
 *    trigger fired in step 3 is put on the stack (CR 603.3b).
 *
 * **CR 603.6a applies to a played land exactly as it does to a resolving permanent.** Being played
 * rather than cast (CR 305.1) changes how the object gets to the battlefield, not what happens once
 * it is there — an object entered the battlefield, so its enters-the-battlefield abilities trigger.
 * This transition used to narrate the entry and skip the triggers, which the gauntlet triage records
 * as **T18**: unreachable while no encoded land had such a trigger, and completely silent, since a
 * trigger that never fires leaves nothing behind to notice its absence.
 *
 * Legality is re-checked loudly: enumeration only offers legal plays (ADR-005), so a violation
 * here is an engine defect, never a player error.
 */
internal fun executePlayLand(
    state: GameState,
    player: PlayerId,
    cardObjectId: ObjectId,
    source: CastSource = CastSource.HAND,
): AdvanceResult {
    val zone = objectsInZone(state, player, source)
    val index = zone.indexOfFirst { it.id == cardObjectId }
    require(index >= 0) { "CR 115.2a: object $cardObjectId is not in $player's $source zone" }
    val card = zone[index]
    val definition = state.definitions[card.card]
    require(definition.isLand()) {
        "CR 305.1: ${card.card.name} is not a defined land card; enumeration must not have offered it (ADR-005)"
    }
    require(playLandIsLegal(state, player)) {
        "CR 116.2a: playing a land is not legal for $player now; enumeration must not have offered it (ADR-005)"
    }
    val (id, allocated) = state.allocateObjectId()
    // CR 400.7: a new object with no memory of its former self. It enters untapped (CR 110.5a) unless
    // the card's own CR 614.1c "this land enters tapped" replacement says otherwise — read here,
    // against the battlefield the land has not yet joined, so a conditional clause's count is over
    // the *other* permanents (Gingerbread Cabin).
    // CR 400.7 again: the play permission that let this land be played from exile does not survive
    // the move — the battlefield object is a new object, and a land that later returns to exile is
    // not still playable.
    val land = card.copy(id = id, tapped = entersTappedNow(allocated, player, definition), playGrantedTurn = null)
    val played =
        allocated
            .let { removePlayedLandFromSource(it, player, source, cardObjectId) }
            .copy(turn = allocated.turn.copy(landsPlayedThisTurn = allocated.turn.landsPlayedThisTurn + 1))
            .let { it.copy(sharedZones = it.sharedZones.copy(battlefield = it.sharedZones.battlefield.adding(land))) }
            // CR 603.6a: narrating the entry and firing the land's own enters-the-battlefield
            // triggers are one indivisible step (T18).
            .let { announceBattlefieldEntry(it, id, GameEvent.LandPlayed(player, id, card.card)) }
    return priorityTo(clearPriorityRound(played), player)
}

/**
 * Removes the played land [cardObjectId] from the [source] zone it was played from (CR 400.7) — the
 * hand for an ordinary land drop, exile for a land an effect granted permission to play (Reckless
 * Impulse). Additive (`W8-D`).
 *
 * A graveyard source is unreachable and fails loudly rather than being silently supported: no card in
 * the pool grants permission to play a land from a graveyard, and a branch that quietly handled one
 * would be an untested path pretending to be a tested one.
 */
private fun removePlayedLandFromSource(
    state: GameState,
    player: PlayerId,
    source: CastSource,
    cardObjectId: ObjectId,
): GameState =
    when (source) {
        CastSource.HAND ->
            state.updatePlayer(player) { p ->
                p.copy(hand = p.hand.removingAt(p.hand.indexOfFirst { it.id == cardObjectId }))
            }
        CastSource.EXILE ->
            state.updateExile { exile -> exile.removingAt(exile.indexOfFirst { it.id == cardObjectId }) }
        CastSource.GRAVEYARD ->
            error("CR 305.1: no pool card plays a land from a graveyard, but $cardObjectId was")
    }
