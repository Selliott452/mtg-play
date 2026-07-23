package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.effect.dealDamage

/*
 * The combat-damage step (CR 510), a turn-based action with no decision.
 *
 * ARCHITECT DECISION (P3.1): there is no damage-assignment decision. Damage is assigned by the
 * deterministic Comprehensive-Rules minimum — unblocked attackers hit the defending player; a
 * blocked attacker assigns lethal to each of its blockers in the chosen order and dumps the rest
 * on the last one, where excess is WASTED (trample, CR 702.19, is P5). Blockers hit the attacker
 * they block. The player-facing damage-assignment choice (CR 510.1c "the attacking player may
 * assign the rest as they choose") arrives with trample in P5.
 *
 * First strike (CR 510.5) is scaffolded: if any combatant has first strike as the step begins,
 * the phase runs two combat-damage steps — first-strikers deal in the first, everyone else in the
 * second — tracked by the two flags on [CombatState]. There is no double strike in the pool, so a
 * first-striker never deals in the second step. Nothing dies from marked damage in P3.1: the
 * lethal-damage state-based action (CR 704.5g) that would destroy a creature is P3.2, and hooks in
 * at the priority grant that follows each damage step (see [grantPriorityRound]).
 */

private enum class DamageStep {
    /** CR 510.5: only first-strike (and, from a later pool, double-strike) creatures deal. */
    FIRST_STRIKE,

    /** CR 510: creatures without first strike deal — everyone, when no first strike is present. */
    REGULAR,
}

/**
 * Performs the combat-damage step (CR 510): deals this step's combat damage and grants the
 * following priority round (CR 510.4). Re-entered for the second combat-damage step when first
 * strike split it in two (see [needsSecondCombatDamageStep]); the two [CombatState] flags record
 * which steps have happened so the re-entry deals the *regular* damage.
 *
 * A creature-less game engages no combat, so this step is a bare priority window there (P3.1) —
 * the same passthrough it was before combat existed.
 */
internal fun combatDamageStep(state: GameState): AdvanceResult {
    val combat = state.turn.combat ?: return grantPriorityRound(state)
    return when {
        combatHasFirstStriker(state, combat) && !combat.firstStrikeDamageDealt ->
            grantPriorityRound(dealCombatDamage(state, DamageStep.FIRST_STRIKE))
        !combat.regularDamageDealt ->
            grantPriorityRound(dealCombatDamage(state, DamageStep.REGULAR))
        else -> error("CR 510: the combat-damage step was re-entered after all combat damage was dealt")
    }
}

/**
 * Whether a second combat-damage step is due (CR 510.5): the first-strike step happened and the
 * regular step has not. This is what [endOfPriorityRound] checks to re-enter the combat-damage
 * position instead of advancing to end of combat.
 */
internal fun needsSecondCombatDamageStep(state: GameState): Boolean {
    val combat = state.turn.combat ?: return false
    return combat.firstStrikeDamageDealt && !combat.regularDamageDealt
}

/** Whether any attacker or blocker has first strike as the combat-damage step begins (CR 510.5). */
private fun combatHasFirstStriker(
    state: GameState,
    combat: CombatState,
): Boolean {
    val combatants = combat.attackers.map { it.attacker } + combat.blocks.orEmpty().map { it.blocker }
    return combatants.any { Keyword.FIRST_STRIKE in effectiveKeywords(state, it) }
}

// One creature's combat-damage assignment: [source] deals [amount] to [recipient] (CR 510.1).
private data class DamageAssignment(
    val source: ObjectId,
    val recipient: Target,
    val amount: Int,
)

// Deals one combat-damage step's damage simultaneously (CR 510.2) and records the step as done.
private fun dealCombatDamage(
    state: GameState,
    step: DamageStep,
): GameState {
    val combat = state.turn.combat ?: error("CR 510: no combat is engaged")
    // CR 510.2 simultaneity: every assignment is computed from the pre-step state — in particular
    // CR 510.1c lethal uses damage *already* marked (a prior first-strike step's, or none) — and
    // only then applied, so ganging blockers and a trading attacker/blocker all see each other's
    // pre-step values.
    val assignments =
        buildList {
            for (assignment in combat.attackers) {
                if (!dealsThisStep(state, assignment.attacker, step)) continue
                val blockers = orderedBlockersOf(combat, assignment.attacker)
                if (blockers.isEmpty()) {
                    // CR 510.1c: an unblocked attacker assigns its combat damage to the player it
                    // is attacking.
                    val amount = effectivePower(state, assignment.attacker)
                    add(DamageAssignment(assignment.attacker, Target.Player(assignment.defendingPlayer), amount))
                } else {
                    addAll(assignToBlockers(state, assignment.attacker, blockers))
                }
            }
            for (block in combat.blocks.orEmpty()) {
                if (!dealsThisStep(state, block.blocker, step)) continue
                // CR 510.1d: a blocking creature assigns its combat damage to the attacker it blocks.
                add(
                    DamageAssignment(
                        block.blocker,
                        Target.Permanent(block.attacker),
                        effectivePower(state, block.blocker),
                    ),
                )
            }
        }
    val damaged = assignments.fold(state) { current, a -> dealDamage(current, a.recipient, a.amount) }
    val triggered = fireCombatDamageTriggers(damaged, assignments)
    return triggered.updateCombat {
        when (step) {
            DamageStep.FIRST_STRIKE -> it.copy(firstStrikeDamageDealt = true)
            DamageStep.REGULAR -> it.copy(regularDamageDealt = true)
        }
    }
}

/**
 * Fires the enchanted-creature-deals-damage triggers (CR 603.2) for a combat-damage step. Combat
 * damage is one event (CR 510.2), so a creature that split its damage among several recipients dealt
 * damage *once*: [assignments] are aggregated per source, and each source's total is what its Aura's
 * "gain that much life" sees (Armadillo Cloak). The aggregation order is source-first-appearance for a
 * deterministic pending-trigger queue (ADR-006).
 */
private fun fireCombatDamageTriggers(
    state: GameState,
    assignments: List<DamageAssignment>,
): GameState =
    assignments
        .groupBy(DamageAssignment::source)
        .mapValues { (_, list) -> list.sumOf(DamageAssignment::amount) }
        .entries
        .fold(state) { current, (source, total) -> fireEnchantedDamageTriggers(current, source, total) }

// Whether [id] assigns combat damage in [step] (CR 510.5): first-strikers in the first step,
// everyone else in the second (no double strike in the pool means a first-striker never deals twice).
private fun dealsThisStep(
    state: GameState,
    id: ObjectId,
    step: DamageStep,
): Boolean {
    val hasFirstStrike = Keyword.FIRST_STRIKE in effectiveKeywords(state, id)
    return when (step) {
        DamageStep.FIRST_STRIKE -> hasFirstStrike
        DamageStep.REGULAR -> !hasFirstStrike
    }
}

// CR 510.1c deterministic minimum: lethal to each blocker in order, remainder wasted on the last.
private fun assignToBlockers(
    state: GameState,
    attacker: ObjectId,
    orderedBlockers: List<ObjectId>,
): List<DamageAssignment> {
    var remaining = effectivePower(state, attacker)
    return orderedBlockers.mapIndexed { index, blocker ->
        val amount =
            if (index == orderedBlockers.lastIndex) {
                // The last blocker absorbs the whole remainder; excess is wasted (no trample, P5).
                remaining
            } else {
                // CR 510.1c: lethal is toughness minus damage already marked, never negative.
                val alreadyMarked = state.battlefieldObject(blocker).damageMarked
                val lethal = (effectiveToughness(state, blocker) - alreadyMarked).coerceAtLeast(0)
                minOf(remaining, lethal)
            }
        remaining -= amount
        DamageAssignment(attacker, Target.Permanent(blocker), amount)
    }
}

/**
 * The blockers of [attacker] in the order combat damage is assigned to them (CR 509.2): the
 * attacker's declaration-order block list when singly (or un-) blocked, otherwise the attacking
 * player's chosen order. Fails loudly if a multi-blocked attacker has no recorded order — the
 * engine never guesses an order.
 */
internal fun orderedBlockersOf(
    combat: CombatState,
    attacker: ObjectId,
): List<ObjectId> {
    val blockers =
        combat.blocks
            .orEmpty()
            .filter { it.attacker == attacker }
            .map { it.blocker }
    return if (blockers.size <= 1) {
        blockers
    } else {
        combat.blockerOrder[attacker]?.toList()
            ?: error("CR 509.2: multi-blocked attacker $attacker has no damage-assignment order")
    }
}
