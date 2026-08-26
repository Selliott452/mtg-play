package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updateBattlefield

/*
 * Attaching and unattaching a permanent already on the battlefield (CR 701.3) — the primitives
 * `FW-EQUIP` had to add, and the reason Inventor's Axe could not be encoded before it.
 *
 * **The field existed; the verb did not.** [GameObject.attachedTo] has been there since P4.1, but every
 * path that *wrote* it was Aura-shaped: an Aura spell resolves against a `TargetSpec.Enchantable` and
 * the engine attaches it as the permanent enters (CR 303.4f), once, forever. Nothing could attach a
 * permanent that was already on the battlefield, which is exactly what equip does (CR 702.6b) and also
 * what Inventor's Axe's own "When this Equipment enters, attach it to target creature you control" does
 * — a *trigger* asking for something only a resolving Aura spell could do.
 *
 * These are pure state moves. What makes an attachment legal, and what happens when it stops being
 * legal, are separate rules: CR 704.5n unattaches an Equipment from an illegal permanent (leaving it on
 * the battlefield), where CR 704.5m puts a dangling Aura into its owner's graveyard. Those two live in
 * the state-based actions, which is where the difference between the two attachment kinds is decided.
 */

/**
 * Effect primitive: attaches the battlefield permanent [attachment] to the battlefield permanent
 * [target] (CR 701.3a), moving it there from whatever it was attached to before. Emits
 * [GameEvent.AuraAttached].
 *
 * **Moving is the normal case, not an edge case.** CR 701.3a says attaching an already-attached
 * permanent to a new object first unattaches it, and equip is printed to be activated repeatedly — the
 * Axe walks from a dying creature to a fresh one, which is the whole reason a deck plays an Equipment
 * rather than an Aura. So there is no separate "move" verb: this *is* the move, and attaching to the
 * object it is already attached to is a legal no-op that still emits its event.
 *
 * Fails loudly if either object is off the battlefield, or if they are the same object. Every caller
 * arrives after a CR 608.2b re-check has confirmed its target is still a legal battlefield permanent
 * (ADR-005), so a missing one is an engine defect rather than a rules case; and CR 701.3c forbids
 * attaching a permanent to itself, which [GameObject]'s own invariant would reject a moment later.
 */
fun attachPermanent(
    state: GameState,
    attachment: ObjectId,
    target: ObjectId,
): GameState {
    require(attachment != target) { "CR 701.3c: a permanent cannot be attached to itself ($attachment)" }
    val attaching = battlefieldPermanentForAttachment(state, attachment, "attach")
    require(state.sharedZones.battlefield.any { it.id == target }) {
        "CR 701.3a: an attachment's new host must be on the battlefield, but $target is not"
    }
    return state
        .replacingBattlefieldObject(attaching.copy(attachedTo = target))
        .emit(GameEvent.AuraAttached(attachment, target, attaching.card))
}

/**
 * Effect primitive: unattaches the battlefield permanent [attachment] from whatever it is attached to
 * (CR 701.3d). It **stays on the battlefield** — which is the whole difference between CR 704.5n and
 * the Aura's CR 704.5m, and the reason this is a state edit rather than a zone move. Emits
 * [GameEvent.EquipmentUnattached].
 *
 * A no-op on a permanent attached to nothing, and deliberately not a loud failure: the CR 704.5n
 * state-based action collects a batch and performs it, and CR 704.3 allows an action in a batch to have
 * been made redundant by another in the same batch.
 */
fun unattachPermanent(
    state: GameState,
    attachment: ObjectId,
): GameState {
    val attaching = battlefieldPermanentForAttachment(state, attachment, "unattach")
    val previous = attaching.attachedTo ?: return state
    return state
        .replacingBattlefieldObject(attaching.copy(attachedTo = null))
        .emit(GameEvent.EquipmentUnattached(attachment, attaching.card, previous))
}

/** The battlefield permanent [objectId], or a loud failure naming the [action] that wanted it. */
private fun battlefieldPermanentForAttachment(
    state: GameState,
    objectId: ObjectId,
    action: String,
): GameObject =
    state.sharedZones.battlefield.firstOrNull { it.id == objectId }
        ?: error("CR 701.3: cannot $action $objectId; only a battlefield permanent carries an attachment")

/**
 * [state] with the battlefield entry sharing [updated]'s id replaced by [updated], **in place** —
 * battlefield order is the engine's determinism spine (CR 613.7 timestamps derive from entry order),
 * so changing an attachment must never reorder the zone.
 */
private fun GameState.replacingBattlefieldObject(updated: GameObject): GameState =
    updateBattlefield { battlefield ->
        val index = battlefield.indexOfFirst { it.id == updated.id }
        battlefield.removingAt(index).addingAt(index, updated)
    }
