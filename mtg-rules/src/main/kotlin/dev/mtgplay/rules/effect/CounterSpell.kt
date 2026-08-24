package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.narrateLeaveStackExile
import dev.mtgplay.rules.engine.putSpellOffStack
import dev.mtgplay.rules.engine.spellOnStack

/**
 * Counters the spell [target] on behalf of the resolving object [counteredBy] (CR 701.5) — the published
 * primitive card definitions compose (ADR-003), the way they compose [dealDamage] and
 * [returnToOwnersHand]. Counterspell, Dispel, Negate, Annul, Envelop, Remove Soul.
 *
 * **To counter a spell is to remove it from the stack so that it does not resolve** (CR 701.5a). Its card
 * is put into its owner's graveyard as a new object (CR 400.7) — or into exile, if it was cast via
 * flashback (CR 702.34e) — and none of its instructions are performed, including its own "unless" and
 * conditional clauses: a countered Force Spike never asks anyone to pay.
 *
 * What countering deliberately is **not**:
 * - **not a refund.** Costs stay paid (CR 701.5a): mana spent is spent, a sacrificed Mountain stays
 *   sacrificed. So this is a stack removal, never the cast pipeline run in reverse.
 * - **not an un-cast.** The spell *was* cast, so the "whenever a player casts a spell" triggers that
 *   fired at CR 601.2i (Murmuring Mystic's) are already on the stack and resolve regardless.
 * - **not destruction** (CR 701.7), which applies only to permanents on the battlefield.
 * - **not a fizzle** (CR 608.2b). Same state transition, different game event, and this one emits
 *   [GameEvent.SpellCountered] where a fizzle emits [GameEvent.SpellFizzled]
 *   (docs/design/countering-spells.md §3).
 *
 * The target is located by its stack-residence id and removed from wherever it sits, not from the top:
 * two counters can stack above one spell. A [target] that has already left the stack is an **engine
 * defect**, not a silent no-op — the CR 608.2b re-check runs before any resolution, so a counter whose
 * target is gone has already fizzled and never reaches here (ADR-005).
 *
 * @param target the spell to counter; a [Target] of any other kind is a definition defect and fails loudly.
 * @param counteredBy the resolving object doing the countering
 *   ([dev.mtgplay.core.definition.ResolutionContext.source]), for the event log's narration.
 */
fun counterSpell(
    state: GameState,
    target: Target,
    counteredBy: ObjectId?,
): GameState {
    val spellTarget =
        target as? Target.SpellOnStack
            ?: error("CR 701.5a: only a spell on the stack can be countered, got $target")
    val source =
        counteredBy
            ?: error("CR 701.5a: countering names the object that countered; the resolution context had no source")
    return counterSpellById(state, spellTarget.id, source)
}

/**
 * Counters the spell whose stack-residence id is [counteredObjectId] (CR 701.5a) on behalf of
 * [counteredBy]. The by-id half of [counterSpell], shared with the engine's "counter unless its
 * controller pays" orchestration (CR 118.3a), which holds its victim as an id rather than as a
 * [Target].
 */
internal fun counterSpellById(
    state: GameState,
    counteredObjectId: ObjectId,
    counteredBy: ObjectId,
): GameState {
    val entry =
        spellOnStack(state, counteredObjectId)
            ?: error(
                "CR 701.5a: spell $counteredObjectId is not on the stack — the CR 608.2b re-check " +
                    "should have fizzled its counter before resolution",
            )
    val left = putSpellOffStack(state, entry)
    val countered =
        left.state.emit(
            GameEvent.SpellCountered(
                controller = entry.controller,
                objectId = entry.obj.id,
                card = entry.obj.card,
                graveyardObjectId = left.newObjectId,
                counteredBy = counteredBy,
            ),
        )
    return narrateLeaveStackExile(countered, entry, left)
}
