package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.Dungeon
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.InitiativeState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.Target
import kotlinx.collections.immutable.persistentMapOf

/*
 * **The initiative** (CR 701.51) — `W10-A`. The dungeon it walks you through is `Dungeon.kt`.
 *
 * The mechanic is printed on a two-faced token whose back face, The Initiative, carries the whole rule:
 *
 *     Whenever one or more creatures a player controls deal combat damage to you, that player takes
 *     the initiative.
 *     Whenever you take the initiative and at the beginning of your upkeep, venture into Undercity.
 *
 * Three pieces live here, and the fourth — the venture keyword action itself — is next door:
 *
 * 1. **Taking the initiative** ([takeInitiative], CR 701.51a) — a designation changes hands, and the new
 *    holder's venture ability triggers. Taking it while you *already* hold it is legal and still
 *    triggers, which is the reading most easily got wrong: the printed line says "whenever you **take**
 *    the initiative", not "whenever you gain it", so a second Goliath Paladin ventures you again. An
 *    engine that only ventured on a change of holder would be a materially weaker card.
 * 2. **The upkeep venture** ([enqueueUpkeepVenture], CR 701.51b) — the same ability, the other half of
 *    its "and", and the reason holding the initiative is worth anything at all.
 * 3. **The handover** ([fireInitiativeHandover], CR 701.51c) — one trigger per *player* whose creatures
 *    connected, not one per creature.
 *
 * **Why *taking* the initiative is a plain transition and not an orchestrated flow.** It performs no
 * decision — it sets a designation and enqueues a trigger — so a card's [ResolutionEffect] may call it
 * through the published [dev.mtgplay.rules.effect.takeTheInitiative] primitive. Everything that *does*
 * decide belongs to the venture, one resolution later, where the engine can pause (ADR-004).
 *
 * That split is also what lets `mtg-rules` implement the mechanic without naming the Undercity
 * (ADR-003): the card supplies the [Dungeon] value once, here, and the two engine-side entry points read
 * it back out of [InitiativeState.dungeon].
 */

/**
 * The venture ability both halves of CR 701.51 fire (see [TriggerCondition.VentureIntoDungeon]). Its
 * effect is never run — the keyword action is the engine's, like rebound's may-cast — so it is a no-op,
 * and the condition is what [resolveVentureTrigger]'s caller dispatches on.
 */
private val ventureAbility =
    TriggeredAbility(
        condition = TriggerCondition.VentureIntoDungeon,
        effect = ResolutionEffect { state, _ -> state },
        zoneScope = TriggerZoneScope.Command,
    )

/**
 * [player] **takes the initiative** (CR 701.51a): they become the initiative holder, and their venture
 * ability triggers. Returns the successor state; nothing decides anything here, so this is an ordinary
 * transition and not an [dev.mtgplay.rules.AdvanceResult].
 *
 * [dungeon] is what taking the initiative ventures into — the Undercity, for every card that prints the
 * line. It is used only the **first** time the designation is created; afterwards the dungeon already in
 * [InitiativeState] is kept, because a second card cannot re-point an existing initiative at a different
 * graph while players are standing in the first one.
 *
 * **Taking the initiative you already hold is legal and still ventures** (CR 701.51a). Nothing here is
 * conditional on the holder changing: the designation is set, the event narrates who held it before, and
 * the venture triggers either way.
 */
internal fun takeInitiative(
    state: GameState,
    player: PlayerId,
    dungeon: Dungeon,
): GameState {
    val standing = state.initiative
    val previous = standing?.holder
    val initiative =
        standing?.copy(holder = player)
            ?: InitiativeState(holder = player, dungeon = dungeon, markers = persistentMapOf())
    return enqueueVentureTrigger(
        state.copy(initiative = initiative).emit(GameEvent.InitiativeTaken(player, previous)),
        player,
    )
}

/**
 * Fires the initiative holder's venture ability as their upkeep begins (CR 701.51b), or returns [state]
 * unchanged when no player has the initiative or [player] is not its holder.
 *
 * Called from the upkeep's turn-based position beside rebound's delayed ability, and enqueuing rather
 * than venturing directly for the reason that one does: a fired trigger is placed on the stack in APNAP
 * order before any player receives priority (CR 603.3b), so the opponent gets a window between the upkeep
 * beginning and the room's ability resolving.
 */
internal fun enqueueUpkeepVenture(
    state: GameState,
    player: PlayerId,
): GameState = if (state.initiative?.holder == player) enqueueVentureTrigger(state, player) else state

/**
 * Hands the initiative to whoever's creatures just dealt combat damage to its holder (CR 701.51c); a
 * no-op when no initiative exists yet or when nothing reached the holder.
 *
 * **One handover per player, not one per creature.** CR 701.51c reads "whenever **one or more** creatures
 * a player controls deal combat damage to you", so an alpha strike of four attackers is a single trigger
 * — and since taking the initiative ventures (CR 701.51a), reading it per creature would walk the new
 * holder four rooms into the Undercity off one attack. [assignments] is therefore reduced to the distinct
 * *controllers* that got through, and each hands over once.
 *
 * [assignments] must be the step's **dealt** damage, past the CR 615.6 prevention filter: prevented
 * damage never happens, so it hands nothing over.
 *
 * A player whose own creature somehow damaged them would take the initiative from themselves, which
 * CR 701.51a permits and which still ventures. Nothing in the pool reaches it — a creature is never
 * declared as an attacker against its own controller — and it is left unguarded rather than
 * special-cased, because the guard would be the wrong rule.
 */
internal fun fireInitiativeHandover(
    state: GameState,
    assignments: List<DamageAssignment>,
): GameState {
    val initiative = state.initiative ?: return state
    val toHolder = Target.Player(initiative.holder)
    // CR 120.8: zero damage is not dealt. Controller is ownership in the current pool, read off the
    // battlefield where the damaging creature still stands — combat damage is dealt before any
    // state-based action can remove it (CR 704.3).
    val takers =
        assignments
            .filter { it.recipient == toHolder && it.amount > 0 }
            .mapNotNull { assignment ->
                state.sharedZones.battlefield
                    .firstOrNull { it.id == assignment.source }
                    ?.owner
            }.distinct()
    return takers.fold(state) { current, taker -> takeInitiative(current, taker, initiative.dungeon) }
}

/** Enqueues [player]'s venture ability (CR 701.51a/b, CR 309.4) — the fired trigger both halves share. */
private fun enqueueVentureTrigger(
    state: GameState,
    player: PlayerId,
): GameState {
    val initiative = state.initiative ?: error("CR 701.51: a venture trigger needs an initiative to hang off")
    // CR 309.2: the dungeon card sits in the venturing player's command zone. A player who is not in a
    // dungeon yet has no card there, so the trigger's source is the card they are about to put there —
    // minted now (CR 400.7) and reused by the entry the venture performs.
    val marker = initiative.markers[player]
    val (sourceId, allocated) = marker?.let { it.dungeonObjectId to state } ?: state.allocateObjectId()
    return enqueuePendingTrigger(
        allocated,
        PendingTrigger(
            sourceId = sourceId,
            sourceCard = CardRef(initiative.dungeon.name),
            controller = player,
            ability = ventureAbility,
        ),
    )
}
