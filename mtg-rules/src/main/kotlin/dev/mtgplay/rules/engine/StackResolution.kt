package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
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
 *    the spell does not resolve — none of its instructions are performed ("fizzles"), and it goes
 *    to its owner's graveyard without ever becoming a permanent.
 * 2. Otherwise it resolves per its card type:
 *    - an **instant or sorcery** performs its [dev.mtgplay.core.definition.ResolutionEffect]
 *      instructions (CR 608.2c) while still on the stack, then its card is put into its owner's
 *      graveyard as a new object (CR 608.2m, CR 400.7);
 *    - a **permanent spell** (a creature, in the P3.2 pool) has no CR 608.2c effect of its own and
 *      instead becomes a permanent on the battlefield (CR 608.3) — see
 *      [putResolvedSpellOntoBattlefield].
 *   Either move is the engine's, never the effect's.
 *
 * A spell with *some* legal targets still resolves (CR 608.2b), performing what it can — a
 * distinction with no observable case until multi-target spells exist, since every spell in the
 * pool has at most one target.
 */
private fun resolveSpell(
    state: GameState,
    entry: StackEntry.Spell,
): AdvanceResult {
    val spec = entry.definition.targetSpec
    val fizzles = spec != TargetSpec.None && entry.targets.none { isTargetLegal(state, spec, it, entry.controller) }
    if (fizzles) {
        // CR 608.2b: a spell that does not resolve is put into its owner's graveyard and never
        // enters the battlefield, whatever its card type would have become.
        val (finished, graveyardId) = putResolvedSpellIntoGraveyard(state, entry)
        return grantPriorityRound(
            finished.emit(GameEvent.SpellFizzled(entry.controller, entry.obj.id, entry.obj.card, graveyardId)),
        )
    }
    return if (isPermanentSpell(entry)) {
        val (entered, battlefieldId) = putResolvedSpellOntoBattlefield(state, entry)
        val announced =
            entered.emit(GameEvent.PermanentEntered(entry.controller, entry.obj.id, entry.obj.card, battlefieldId))
        // CR 303.4f: an Aura enters attached; announce the attachment after it has entered.
        val attachedTo = entered.battlefieldObject(battlefieldId).attachedTo
        val withAura =
            if (attachedTo == null) {
                announced
            } else {
                announced.emit(GameEvent.AuraAttached(battlefieldId, attachedTo, entry.obj.card))
            }
        grantPriorityRound(withAura)
    } else {
        val resolved =
            entry.definition.resolution.resolve(state, ResolutionContext(entry.controller, entry.targets))
        require(resolved.sharedZones.stack == state.sharedZones.stack) {
            "CR 608.2m: a resolution effect must not move the resolving spell — that is the engine's move"
        }
        val (finished, graveyardId) = putResolvedSpellIntoGraveyard(resolved, entry)
        grantPriorityRound(
            finished.emit(GameEvent.SpellResolved(entry.controller, entry.obj.id, entry.obj.card, graveyardId)),
        )
    }
}

/**
 * Whether [entry] is a permanent spell (CR 608.3): every card type in the MVP pool is either an
 * instant or a sorcery (which resolve into the graveyard) or a permanent type (which resolves onto
 * the battlefield). Lands are the one permanent type that is *played*, not cast (CR 305.1), so a
 * land spell never reaches resolution; the only permanent spells in the P3.2 pool are creatures.
 */
private fun isPermanentSpell(entry: StackEntry.Spell): Boolean {
    val types = entry.definition.characteristics.cardTypes
    return CardType.INSTANT !in types && CardType.SORCERY !in types
}

/**
 * The CR 608.3 move for a permanent spell: the resolving spell leaves the stack and enters the
 * battlefield under its controller's control as a **new** object (CR 400.7) — summoning sick
 * (CR 302.6), untapped, and with no marked damage (the [GameObject] defaults). An Aura enters
 * **attached** to the object it targeted while on the stack (CR 303.4f, CR 601.2c); every other
 * permanent spell attaches to nothing. Controller is owner in the MVP pool (control-changing effects
 * are Phase 4). The vanilla-and-keyword-only creatures and the Auras of the pool have no
 * enters-the-battlefield effect (Phase 5), so entering the battlefield is the whole of resolution.
 */
private fun putResolvedSpellOntoBattlefield(
    state: GameState,
    entry: StackEntry.Spell,
): Pair<GameState, ObjectId> {
    val stack = state.sharedZones.stack
    check(stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    val (id, allocated) = state.allocateObjectId()
    val permanent =
        GameObject(id = id, card = entry.obj.card, owner = entry.obj.owner, attachedTo = auraAttachmentTargetOf(entry))
    val moved =
        allocated
            .updateStack { it.removingAt(it.lastIndex) }
            .updateBattlefield { it.adding(permanent) }
    return moved to id
}

/**
 * The object an Aura enters attached to (CR 303.4f): the permanent it targeted while on the stack
 * (CR 601.2c). A permanent spell that is not an Aura (no [TargetSpec.Enchantable]) attaches to
 * nothing. Fails loudly if an Aura's settled target is not a permanent — the CR 608.2b re-check has
 * already run, so reaching here with a gone/wrong target is an engine defect (ADR-005).
 */
private fun auraAttachmentTargetOf(entry: StackEntry.Spell): ObjectId? =
    when (entry.definition.targetSpec) {
        TargetSpec.None, TargetSpec.AnyTarget -> null
        is TargetSpec.Enchantable ->
            (entry.targets.singleOrNull() as? Target.Permanent)?.id
                ?: error("CR 303.4f: an Aura must enter attached to its permanent target, got ${entry.targets}")
    }

/**
 * The CR 608.2m move for an instant or sorcery: the resolved (or fizzled) spell's card leaves
 * the stack and is put on top of its owner's graveyard as a new object (CR 400.7).
 */
private fun putResolvedSpellIntoGraveyard(
    state: GameState,
    entry: StackEntry.Spell,
): Pair<GameState, ObjectId> {
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
