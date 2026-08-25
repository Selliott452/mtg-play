package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState

/*
 * The CR 603.4 "intervening if" clause, implementing [InterveningIf].
 *
 * > CR 603.4 — "A triggered ability may read 'When/Whenever/At [trigger event], if [condition],
 * > [effect].' When the trigger event occurs, the ability checks whether the condition is true. The
 * > ability triggers only if it is; otherwise it does nothing. If the ability triggers, it checks
 * > whether the condition is still true as it resolves. If the condition isn't true at that time, the
 * > ability is removed from the stack and does nothing."
 *
 * **One derivation, two callers** — the discipline `P-MANASICK` established for `manaSourceUsable` and
 * `FW-MANA` for `sourceClassKeyOf`, and it is load-bearing for exactly the same reason. The two checks
 * CR 603.4 demands are at opposite ends of a trigger's life: [enqueuePendingTrigger] runs the first, so
 * a false condition puts nothing on the stack at all, and [resolveAbility] runs the second, so a
 * condition that has since become false removes the ability. If those two ever asked different
 * questions, an ability could fire and then always fizzle, or fizzle and then always fire — and both
 * are silent, because the observable difference is in the *action space* (whether a trigger was on the
 * stack to be responded to) rather than in the final board state.
 *
 * The first check is wired into [enqueuePendingTrigger] rather than into each of the half-dozen
 * detectors, so a detector added later cannot forget it. That is deliberate placement, not tidiness: a
 * missed intervening-if is an ability that triggers when the rules say it does not, which is an
 * enumerated action the rules forbid (ADR-005).
 */

/**
 * Whether [ability]'s CR 603.4 intervening-if clause holds right now for the source object [sourceId] —
 * `true` for an ability with no such clause, which is every ability that predates `FW-OPTCOST`.
 *
 * Asked twice per trigger, at firing and at resolution, and the answers may legitimately differ: that
 * divergence *is* the rule. For [InterveningIf.SourceWasKicked] the two can never differ, because
 * kicked-ness is fixed when the permanent enters and nothing can change it — so the observable effect
 * of the clause is entirely in the firing check, which is what stops an unkicked Goblin Bushwhacker
 * putting an ability on the stack for its controller's opponent to respond to.
 * [InterveningIf.YouControlAnotherCreatureNamed] is the member where they *do* differ: it reads the live
 * battlefield, so a second Faerie Miscreant arriving (or leaving) while the trigger is on the stack
 * changes the answer between the two checks, exactly as CR 603.4 describes.
 *
 * A source that is no longer on the battlefield answers `false` for [InterveningIf.SourceWasKicked]
 * rather than throwing. That is the CR 603.4 resolution check working as written for a permanent that
 * has left since it fired — the condition is not true, so the ability does nothing — and it is
 * reachable in ordinary play (kill the Bushwhacker in response to its own trigger). Note the CR-correct
 * answer here is *not* last-known information: CR 603.4 asks whether the condition is true *at that
 * time*, and a permanent that has left the battlefield does not satisfy a condition about itself. A
 * condition that says nothing about its own source ([InterveningIf.YouControlAnotherCreatureNamed])
 * keeps answering about the board after the source is gone, which is why the departed-source answer is
 * a property of each member and not of this function.
 *
 * @param controller the ability's controller (CR 109.5's "you"); ownership in the MVP pool.
 */
internal fun interveningIfHolds(
    state: GameState,
    ability: TriggeredAbility,
    sourceId: ObjectId,
    controller: PlayerId,
): Boolean =
    when (val condition = ability.interveningIf) {
        null -> true
        // CR 702.33f: "was it kicked" is a fact carried on the permanent by the spell that became it.
        InterveningIf.SourceWasKicked ->
            state.sharedZones.battlefield
                .firstOrNull { it.id == sourceId }
                ?.kickedWhenCast == true
        // CR 201.2 with CR 109.1: a creature this player controls, named as printed, that is not the
        // ability's own source object. Control is ownership in the MVP pool.
        is InterveningIf.YouControlAnotherCreatureNamed ->
            state.sharedZones.battlefield.any { obj ->
                obj.id != sourceId &&
                    obj.owner == controller &&
                    isCreature(state, obj) &&
                    obj.card.name == condition.name
            }
        // CR 702.74a: "was its evoke cost paid" is carried across CR 400.7 the same way kicked-ness is.
        InterveningIf.SourceWasEvoked ->
            state.sharedZones.battlefield
                .firstOrNull { it.id == sourceId }
                ?.evokedWhenCast == true
    }
