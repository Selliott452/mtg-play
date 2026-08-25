package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.AttackerAssignment
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.effect.dealDamage

/*
 * The combat-damage step (CR 510) — its orchestration and damage assembly. The trample-assignment
 * decision lives in TrampleAssignment.kt; lifelink and the enchanted-creature-deals-damage triggers,
 * both post-damage results of this step, live in CombatDamageResults.kt.
 *
 * ARCHITECT DECISION (P3.1, refined P5.3): the step is deterministic except for trample. An
 * unblocked attacker hits the defending player; a blocked attacker assigns at least lethal to each
 * of its surviving blockers in the chosen order (CR 510.1c). For an attacker *without* trample the
 * whole assignment is deterministic — where the above-lethal overkill lands is outcome-irrelevant,
 * so it is collapsed onto the last blocker rather than surfaced as a choice. Only trample
 * (CR 702.19) makes the above-lethal excess matter: [pendingTrampleDecision] surfaces the choice
 * before this step's damage is dealt. A blocked trampler whose blockers all left combat assigns all
 * its damage to the player (CR 702.19g); a blocked non-trampler in that spot assigns none
 * (CR 510.1c).
 *
 * First strike (CR 510.5): if any combatant has first strike as the step begins, the phase runs two
 * combat-damage steps — first-strikers deal in the first, everyone else in the second — tracked by
 * the two flags on [CombatState]. There is no double strike in the pool, so a first-striker never
 * deals in the second step, and its trample assignment (if any) happens in the first step.
 */

internal enum class DamageStep {
    /** CR 510.5: only first-strike (and, from a later pool, double-strike) creatures deal. */
    FIRST_STRIKE,

    /** CR 510: creatures without first strike deal — everyone, when no first strike is present. */
    REGULAR,
}

/** One creature's combat-damage assignment: [source] deals [amount] to [recipient] (CR 510.1). */
internal data class DamageAssignment(
    val source: ObjectId,
    val recipient: Target,
    val amount: Int,
)

/**
 * The [DamageSource] a combat [assignment]'s source object is (CR 120.1), read from the pre-step
 * state — the same state CR 510.2 computes every assignment from. A creature that has already left
 * the battlefield within this step still deals its damage as last-known information (CR 113.7c), so
 * the [CardRef] is looked up leniently and falls back to nothing only if the object is gone from the
 * battlefield entirely, which CR 510.2's simultaneity makes unreachable within a step.
 */
private fun damageSourceOf(
    state: GameState,
    source: ObjectId,
): DamageSource =
    DamageSource(
        objectId = source,
        card =
            state.sharedZones.battlefield
                .firstOrNull { it.id == source }
                ?.card
                ?: error("CR 120.1: combat damage's source $source is not on the battlefield"),
    )

/**
 * Performs the combat-damage step (CR 510): first surfaces any pending trample-assignment decision
 * ([pendingTrampleDecision]); once none remains, deals this step's combat damage and grants the
 * following priority round (CR 510.4). Re-entered for the second combat-damage step when first
 * strike split it in two (see [needsSecondCombatDamageStep]); the two [CombatState] flags record
 * which steps have happened so the re-entry deals the *regular* damage.
 *
 * A creature-less game engages no combat, so this step is a bare priority window there (P3.1).
 */
internal fun combatDamageStep(state: GameState): AdvanceResult {
    val combat = state.turn.combat ?: return grantPriorityRound(state)
    return pendingTrampleDecision(state, combat)?.let { AdvanceResult.NeedsDecision(state, it) }
        ?: dealCurrentDamageStep(state, combat)
}

// Deals this step's damage and grants the following priority round (CR 510.4), or fails loudly if
// the step was re-entered after all combat damage of this combat has already been dealt.
private fun dealCurrentDamageStep(
    state: GameState,
    combat: CombatState,
): AdvanceResult =
    when (val step = currentDamageStep(state, combat)) {
        DamageStep.FIRST_STRIKE, DamageStep.REGULAR -> grantPriorityRound(dealCombatDamage(state, step))
        null -> error("CR 510: the combat-damage step was re-entered after all combat damage was dealt")
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

/**
 * The combat-damage step about to be dealt (CR 510.5), or `null` when all combat damage of this
 * combat is done: the first-strike step while a first-striker is present and it has not run, else
 * the regular step while it has not run.
 */
internal fun currentDamageStep(
    state: GameState,
    combat: CombatState,
): DamageStep? =
    when {
        combatHasFirstStriker(state, combat) && !combat.firstStrikeDamageDealt -> DamageStep.FIRST_STRIKE
        !combat.regularDamageDealt -> DamageStep.REGULAR
        else -> null
    }

/** Whether any attacker or blocker has first strike as the combat-damage step begins (CR 510.5). */
private fun combatHasFirstStriker(
    state: GameState,
    combat: CombatState,
): Boolean {
    val combatants = combat.attackers.map { it.attacker } + combat.blocks.orEmpty().map { it.blocker }
    return combatants.any { Keyword.FIRST_STRIKE in effectiveKeywords(state, it) }
}

// Deals one combat-damage step's damage simultaneously (CR 510.2), applies lifelink, fires the
// enchanted-creature-deals-damage triggers, and records the step as done.
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
                if (dealsThisStep(state, assignment.attacker, step)) addAll(attackerDamage(state, combat, assignment))
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
    // CR 120.1: combat has always known each assignment's source and used to drop it at this call.
    // It is passed now, so a blocker with protection from the attacker takes no damage (CR 702.16e)
    // while the lethal assignment that was computed for it above stands unchanged (CR 510.1c).
    val damaged =
        assignments.fold(state) { current, a ->
            dealDamage(current, damageSourceOf(state, a.source), a.recipient, a.amount)
        }
    // CR 615.6: prevented damage never happens, so it is not damage *dealt* — and both results below
    // are results of damage dealt (CR 702.15 lifelink, CR 603.2 the enchanted-creature trigger).
    // Reading the verdict from the same pre-step state the assignments were computed from keeps
    // CR 510.2 simultaneity: nothing this step does can change whether this step's damage is
    // prevented. Assignments themselves are *not* filtered — CR 510.1c lethal and the CR 702.19b
    // trample excess are computed pre-prevention and stand (docs/design/protection.md §2.1) — and
    // every assignment still goes through dealDamage, which emits the DamagePrevented narration.
    val dealt =
        assignments.filterNot { damageIsPrevented(state, damageSourceOf(state, it.source), it.recipient) }
    // CR 702.15: lifelink is a result of the damage, applied in this same transition, before any
    // trigger is placed — so it can never race the Armadillo Cloak "gain that much life" trigger.
    val lifelinked = applyCombatLifelink(damaged, dealt)
    val triggered = fireCombatDamageTriggers(lifelinked, dealt)
    return triggered.updateCombat {
        when (step) {
            DamageStep.FIRST_STRIKE -> it.copy(firstStrikeDamageDealt = true)
            DamageStep.REGULAR -> it.copy(regularDamageDealt = true)
        }
    }
}

/**
 * The combat damage one attacker assigns (CR 510.1c): an unblocked attacker hits the defending
 * player; a blocked attacker assigns to its surviving blockers, and — if it has trample (CR 702.19)
 * — the recorded excess to the player ([assignBlockedDamage]); a blocked attacker whose blockers all
 * left combat assigns all its damage to the player if it has trample (CR 702.19g), or none if it does
 * not (CR 510.1c).
 */
private fun attackerDamage(
    state: GameState,
    combat: CombatState,
    assignment: AttackerAssignment,
): List<DamageAssignment> {
    val attacker = assignment.attacker
    val toDefender =
        DamageAssignment(attacker, Target.Player(assignment.defendingPlayer), effectivePower(state, attacker))
    val survivors = orderedBlockersOf(combat, attacker)
    val hasTrample = Keyword.TRAMPLE in effectiveKeywords(state, attacker)
    return when {
        attacker !in combat.blockedAttackers -> listOf(toDefender)
        survivors.isEmpty() -> if (hasTrample) listOf(toDefender) else emptyList()
        else -> {
            val toPlayer = if (hasTrample) combat.trampleAssignments[attacker] ?: 0 else 0
            assignBlockedDamage(state, attacker, survivors, assignment.defendingPlayer, toPlayer)
        }
    }
}

// Whether [id] assigns combat damage in [step] (CR 510.5): first-strikers in the first step,
// everyone else in the second (no double strike in the pool means a first-striker never deals twice).
internal fun dealsThisStep(
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

/**
 * The blockers of [attacker] in the order combat damage is assigned to them (CR 509.2): the
 * attacker's declaration-order block list when singly (or un-) blocked, otherwise the attacking
 * player's chosen order. The list holds only *surviving* blockers — a dead blocker was removed from
 * the block list when it left the battlefield (CR 506.4, clearCombatReferences). Fails loudly if a
 * multi-blocked attacker has no recorded order — the engine never guesses an order.
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
