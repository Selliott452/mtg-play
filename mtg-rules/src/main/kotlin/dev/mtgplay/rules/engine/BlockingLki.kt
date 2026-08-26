package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.BlockAssignment
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/*
 * The CR 113.7c / CR 608.2h capture behind [dev.mtgplay.core.definition.TargetSpec.CreatureBlockedBySource]
 * (`W9-F`) — Tinder Wall's "{R}, Sacrifice this creature: It deals 2 damage to target creature it's
 * blocking."
 *
 * **Why a capture at all.** Every other targeting restriction in the engine is re-derivable from the
 * board at the CR 608.2b re-check, because it asks about the *candidate*. This one asks about the
 * ability's **source**, and an ability whose cost sacrificed its own source outlives it: by the time the
 * ability resolves, the Wall is a new object in a graveyard (CR 400.7), in no combat and on no
 * battlefield. CR 608.2h answers such a question once, as the effect is applied — here, as the ability is
 * activated, which is also the only moment the rules guarantee the source is still in combat, since
 * CR 601.2c chooses targets before CR 601.2h pays costs.
 *
 * **What is captured is the relation, not an id.** Capturing the source's id would name a dead object
 * one zone change later, which is precisely why [Chooser.Ability] carries no id either.
 */

/**
 * The attackers the battlefield object [sourceId] is blocking right now (CR 509.1) — empty outside
 * combat, empty before blockers are declared, and empty for a creature that is not blocking.
 *
 * A set rather than a single id even though [dev.mtgplay.core.state.CombatState] enforces one block per
 * creature: multi-block is a rules-legal shape the MVP pool simply does not exercise, and a set costs
 * nothing while a nullable id would have to be widened the day it does.
 */
internal fun creaturesBlockedBy(
    state: GameState,
    sourceId: ObjectId,
): PersistentSet<ObjectId> {
    val blocks = state.turn.combat?.blocks ?: return persistentSetOf()
    return blocks
        .filter { it.blocker == sourceId }
        .map(BlockAssignment::attacker)
        .toPersistentSet()
}

/**
 * The attackers [chooser]'s source was blocking as its enumeration was made (CR 509.1), captured as
 * CR 113.7c last-known information — empty for a spell and for no chooser at all, neither of which is
 * ever in combat.
 *
 * Deliberately a function here rather than a property on [Chooser]: the emptiness of the two other cases
 * is a fact about the *spec* that reads it, not about those choosers, so it lives beside the capture it
 * mirrors rather than on the type it inspects.
 */
internal fun blockedByChooser(chooser: Chooser): Set<ObjectId> =
    when (chooser) {
        is Chooser.Ability -> chooser.blocking
        is Chooser.Spell, Chooser.Nobody -> emptySet()
    }
