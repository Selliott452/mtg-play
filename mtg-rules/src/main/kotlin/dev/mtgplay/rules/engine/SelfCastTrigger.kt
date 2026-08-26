package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.StackEntry

/*
 * "When you cast this spell" (CR 603.2) — the self-referential cast trigger, one concept and one file.
 *
 * The fourth ability the casting pipeline *synthesizes* rather than detects, after madness, rebound and
 * cascade/storm, and for the reason [TriggerZoneScope.Stack] records: nothing in this engine scans the
 * stack, so a stack-scoped ability has to be fired by the transition that puts its object there. Unlike
 * the other three it is not the engine's own keyword — the ability is printed on the card, carries the
 * card's own [dev.mtgplay.core.definition.ResolutionEffect], and resolves through the ordinary trigger
 * path (`AbilityResolution.kt` intercepts only the synthesized may-casts and storm's copying).
 *
 * The card this exists for is Writhing Chrysalis, whose "create two Eldrazi Spawn" arrives even when the
 * creature is countered — the observable difference between this and an enters-the-battlefield trigger,
 * and the reason the substitution is not available (PLAN.md §7).
 */

/**
 * Fires the [TriggerCondition.CastSelf] abilities printed on the card just cast as [castEntry]
 * (CR 601.2i, CR 603.2), appending one pending trigger per ability in printed order.
 *
 * **The trigger's source is the spell**, not a permanent: `sourceId` is the stack object's id and
 * `controller` is the spell's controller, so an ability that reads its own source at resolution correctly
 * finds an object that is no longer on the stack (CR 603.10 last-known information) rather than a
 * battlefield permanent that may never exist. Nothing in the pool reads it; the record is honest anyway.
 *
 * A card with no such ability — every card but one — folds over an empty list and returns [state]
 * unchanged, so no cast pays for a scan it does not need. The scope check is what keeps a hypothetical
 * battlefield-scoped [TriggerCondition.CastSelf] out: the two would be different abilities and only the
 * stack-scoped one is this function's.
 */
internal fun detectSelfCastTriggers(
    state: GameState,
    castEntry: StackEntry.Spell,
): GameState =
    castEntry.definition.triggeredAbilities
        .filter { it.zoneScope == TriggerZoneScope.Stack && it.condition == TriggerCondition.CastSelf }
        .fold(state) { current, ability ->
            enqueuePendingTrigger(
                current,
                PendingTrigger(
                    sourceId = castEntry.obj.id,
                    sourceCard = castEntry.obj.card,
                    controller = castEntry.controller,
                    ability = ability,
                ),
            )
        }
