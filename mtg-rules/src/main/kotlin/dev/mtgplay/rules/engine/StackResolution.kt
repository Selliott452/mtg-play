package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult

/**
 * Resolves the topmost object of the stack (CR 608.1) — reached when all players pass in
 * succession with a nonempty stack (CR 117.4). Afterwards the active player receives priority
 * (CR 117.3b) in a fresh round: pass-flags reset because a resolution is game action, so the
 * "all pass in succession" count starts over.
 */
internal fun resolveTopOfStack(state: GameState): AdvanceResult {
    val top = state.sharedZones.stack.lastOrNull() ?: error("CR 608.1: resolution requires a nonempty stack")
    return when (top) {
        is StackEntry.Spell -> resolveSpell(state, top)
    }
}

/**
 * Resolves the spell [entry] (CR 608.2):
 * 1. **Target re-check** (CR 608.2b): if the spell targets and *every* target is now illegal,
 *    the spell does not resolve — none of its instructions are performed ("fizzles").
 * 2. Otherwise its [dev.mtgplay.core.definition.ResolutionEffect] performs its instructions
 *    (CR 608.2c) while the spell is still on the stack.
 * 3. The card is put into its owner's graveyard as a new object (CR 608.2m, CR 400.7) —
 *    the engine's move, never the effect's.
 *
 * A spell with *some* legal targets still resolves (CR 608.2b), performing what it can — a
 * distinction with no observable case until multi-target spells exist, since every P2.1 spell
 * has at most one target.
 */
private fun resolveSpell(
    state: GameState,
    entry: StackEntry.Spell,
): AdvanceResult {
    val spec = entry.definition.targetSpec
    val fizzles = spec != TargetSpec.None && entry.targets.none { isTargetLegal(state, spec, it) }
    val performed =
        if (fizzles) {
            state
        } else {
            val resolved =
                entry.definition.resolution.resolve(state, ResolutionContext(entry.controller, entry.targets))
            require(resolved.sharedZones.stack == state.sharedZones.stack) {
                "CR 608.2m: a resolution effect must not move the resolving spell — that is the engine's move"
            }
            resolved
        }
    val (finished, graveyardId) = putResolvedSpellIntoGraveyard(performed, entry)
    val narrated =
        finished.emit(
            if (fizzles) {
                GameEvent.SpellFizzled(entry.controller, entry.obj.id, entry.obj.card, graveyardId)
            } else {
                GameEvent.SpellResolved(entry.controller, entry.obj.id, entry.obj.card, graveyardId)
            },
        )
    return grantPriorityRound(narrated)
}

/**
 * The CR 608.2m move for an instant or sorcery: the resolved (or fizzled) spell's card leaves
 * the stack and is put on top of its owner's graveyard as a new object (CR 400.7). Any other
 * card type would become a permanent instead (CR 608.3a) — unsupported until permanent spells
 * arrive (Phase 3), and loud about it.
 */
private fun putResolvedSpellIntoGraveyard(
    state: GameState,
    entry: StackEntry.Spell,
): Pair<GameState, ObjectId> {
    val types = entry.definition.characteristics.cardTypes
    check(CardType.INSTANT in types || CardType.SORCERY in types) {
        "CR 608.3a: resolving the permanent spell ${entry.obj.card.name} is not supported until Phase 3; " +
            "only instants and sorceries resolve in P2.x"
    }
    val stack = state.sharedZones.stack
    check(stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    val (id, allocated) = state.allocateObjectId()
    val reborn = entry.obj.copy(id = id)
    val moved =
        allocated
            .updateStack { it.removingAt(it.lastIndex) }
            .updatePlayer(entry.obj.owner) { it.copy(graveyard = it.graveyard.adding(reborn)) }
    return moved to id
}
