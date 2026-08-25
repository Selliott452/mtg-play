package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.gainLife

/*
 * The post-damage results of one combat-damage step (CR 510.2), both computed per damage source: the
 * lifelink lifegain (CR 702.15, a result of the damage — no stack, no trigger) and the
 * enchanted-creature-deals-damage triggers (CR 603.2, which do go on the stack). Combat damage is one
 * event, so a source that split its damage among several recipients is treated as having dealt its
 * total *once* — the aggregation both results share ([damageBySource]).
 *
 * Ordering is what keeps lifelink and the Armadillo Cloak trigger from racing: lifelink is applied
 * first, in the same atomic transition as the damage, mutating life now; the Cloak trigger is only
 * *enqueued*, to be put on the stack and resolved later. A creature with both therefore yields the
 * immediate lifelink gain and a separate Cloak gain on the stack for the same one damage event.
 */

/**
 * Gains life for every lifelink source's controller (CR 702.15): a source whose effective keywords
 * include lifelink gains its controller its total damage dealt this event. Part of this same
 * transition (no stack, no trigger). Control is ownership in the MVP pool
 * (docs/design/layer-system.md §4). Aggregation order is source-first-appearance, deterministic
 * (ADR-006).
 */
internal fun applyCombatLifelink(
    state: GameState,
    assignments: List<DamageAssignment>,
): GameState =
    damageBySource(assignments).entries.fold(state) { current, (source, total) ->
        if (total > 0 && Keyword.LIFELINK in effectiveKeywords(current, source)) {
            gainLife(current, current.battlefieldObject(source).owner, total)
        } else {
            current
        }
    }

/**
 * Fires the enchanted-creature-deals-damage triggers (CR 603.2) for a combat-damage step: each
 * source's total is what its Aura's "gain that much life" sees (Armadillo Cloak). Zero damage is not
 * dealt (CR 120.8), so it fires nothing. The aggregation order is source-first-appearance, for a
 * deterministic pending-trigger queue (ADR-006).
 */
internal fun fireCombatDamageTriggers(
    state: GameState,
    assignments: List<DamageAssignment>,
): GameState {
    val toPlayers = damageBySource(assignments.filter { it.recipient is Target.Player })
    return damageBySource(assignments).entries.fold(state) { current, (source, total) ->
        // CR 603.2: the Aura trigger reads the source's *whole* damage for this event...
        val aura = fireEnchantedDamageTriggers(current, source, total)
        // ...while the combat-damage-to-a-player trigger (CR 510.2, `FW-TRIGCOMBAT`) reads only the share
        // that reached a player. A blocked attacker whose damage all went to blockers fires the first and
        // not the second, which is the entire difference between the two conditions.
        fireCombatDamageToPlayerTriggers(aura, source, toPlayers[source] ?: 0)
    }
}

// The total combat damage each source dealt this event, keyed by source in first-appearance order
// (CR 510.2: combat damage is one event, so a source that split its damage dealt it once).
private fun damageBySource(assignments: List<DamageAssignment>): Map<ObjectId, Int> =
    assignments
        .groupBy(DamageAssignment::source)
        .mapValues { (_, list) -> list.sumOf(DamageAssignment::amount) }
