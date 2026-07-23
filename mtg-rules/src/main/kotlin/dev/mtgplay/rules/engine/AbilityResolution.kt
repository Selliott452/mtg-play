package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import kotlinx.collections.immutable.persistentListOf

/**
 * Resolves a triggered ability on the stack (CR 608.2, CR 113.7a) — reached when all players pass with
 * the ability on top of the stack (CR 117.4). The ability performs its [ResolutionEffect] instructions
 * (CR 608.2c) against a [ResolutionContext] carrying its controller and the trigger's captured linked
 * information ([dev.mtgplay.core.state.PendingTrigger.amount] and `subject`), then **ceases to exist**
 * (CR 113.7a): unlike a spell, no card moves to the graveyard — the ability was never a card. Any zone
 * changes the effect itself makes (a token, a draw, a return-to-hand) are the effect's own.
 *
 * A triggered ability has no targets in the MVP pool (CR 603.3d), so no CR 608.2b re-check applies; its
 * targets are the empty list. Afterwards the active player receives priority (CR 117.3b) in a fresh
 * round, exactly as after a spell resolves.
 */
internal fun resolveAbility(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult {
    check(state.sharedZones.stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    val trigger = entry.trigger
    val context =
        ResolutionContext(
            controller = trigger.controller,
            targets = persistentListOf(),
            amount = trigger.amount,
            subject = trigger.subject,
        )
    val resolved = trigger.ability.effect.resolve(state, context)
    require(resolved.sharedZones.stack == state.sharedZones.stack) {
        "CR 113.7a: a triggered ability's effect performs its instructions but does not move the ability " +
            "off the stack — that cessation is the engine's move"
    }
    val ceased = resolved.updateStack { it.removingAt(it.lastIndex) }
    return grantPriorityRound(
        ceased.emit(GameEvent.TriggeredAbilityResolved(trigger.controller, trigger.sourceCard)),
    )
}
