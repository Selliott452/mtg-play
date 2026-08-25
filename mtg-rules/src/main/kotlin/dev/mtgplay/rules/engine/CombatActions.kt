package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.AttackerAssignment
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * The combat turn-based actions and their decisions (CR 508–509), fully decision-driven and
 * fully state-derived (ADR-004): the engine holds no hidden combat position — every combat pause
 * is recomputed from the turn's [CombatState] by [pendingCombatDecision], and every combat
 * transition ends by calling [resumeCombat], which recomputes the *same* derivation to decide
 * whether another combat decision is due or priority is granted. Deriver and transition agreeing
 * on that one function is what keeps a stored state resumable to exactly the right request.
 *
 * The combat-damage step (CR 510) is a turn-based action with no decision (architect decision,
 * P3.1: the deterministic CR minimum, see CombatDamage.kt) and lives there, not here.
 */

// --- Eligibility (CR 508.1a, CR 509.1a) ---

/**
 * The active player's creatures eligible to be declared as attackers (CR 508.1a), in battlefield
 * order: a creature they control (owner in P3.1, until control-changing effects arrive), untapped,
 * not barred from attacking by defender (CR 702.3b), and either not summoning sick (CR 302.6) or
 * possessed of haste (CR 702.10b).
 *
 * Both keyword clauses are read through the effective-keyword seam ([hasHaste], [hasDefender]), so a
 * granted haste or a haste counter (CR 122.1b) lifts the bar and a granted defender imposes one,
 * without this function knowing where the keyword came from. Under ADR-005 this list *is* the
 * enumerated attack action space, so each clause is the difference between an option existing and
 * not existing — never a rule applied after the fact.
 */
internal fun eligibleAttackers(state: GameState): List<GameObject> {
    val active = state.turn.activePlayer
    return state.sharedZones.battlefield.filter { obj ->
        obj.owner == active &&
            isCreature(state, obj) &&
            !obj.tapped &&
            // CR 702.3b: a creature with defender can't attack, sick or not, tapped or not.
            !hasDefender(state, obj.id) &&
            // CR 302.6, lifted by CR 702.10b.
            (!obj.summoningSick || hasHaste(state, obj.id))
    }
}

/**
 * Every legal (blocker, attacker) pairing (CR 509.1), ordered by blocker battlefield order then
 * attacker declaration order. A blocker is a creature the defending player controls that is
 * untapped (CR 509.1a); a pairing is legal only if the blocker can block that attacker's evasion
 * (CR 509.1b, [canBlock]).
 */
internal fun eligibleBlockPairings(
    state: GameState,
    combat: CombatState,
): List<DecisionRequest.DeclareBlockers.Option> {
    val defender = defendingPlayerOf(combat)
    val blockers =
        state.sharedZones.battlefield.filter { obj ->
            obj.owner == defender && isCreature(state, obj) && !obj.tapped
        }
    return buildList {
        for (blocker in blockers) {
            for (assignment in combat.attackers) {
                if (canBlock(state, blocker.id, assignment.attacker)) {
                    add(
                        DecisionRequest.DeclareBlockers.Option(
                            blocker = blocker.id,
                            blockerCard = blocker.card,
                            attacker = assignment.attacker,
                            attackerCard = state.battlefieldObject(assignment.attacker).card,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Whether [blocker] may legally block [attacker] given the attacker's evasion (CR 509.1b). Two
 * evasions in the pool restrict who may block, and — since `FW-COUNTERS` added [Keyword.REACH] —
 * they are **not** the same restriction and no longer compose:
 *
 * - **Flying** (CR 702.9b): "a creature with flying can't be blocked except by creatures with flying
 *   and/or reach". A reaching blocker satisfies it.
 * - **Silhana Ledgewalker's** "can't be blocked except by creatures with flying"
 *   ([Evasion.BLOCKABLE_ONLY_BY_FLYING]): the printed line says flying and only flying. Reach does
 *   not satisfy it.
 *
 * Until reach existed the two demanded the same thing and shared one predicate; keeping that sharing
 * would have quietly let a reaching Wall block a Ledgewalker. Both restrictions apply
 * independently — an attacker that had both would need a blocker with flying — so each is its own
 * conjunct here rather than a branch of one "requires a flying blocker" test.
 */
private fun canBlock(
    state: GameState,
    blocker: ObjectId,
    attacker: ObjectId,
): Boolean {
    val blockerHasFlying = Keyword.FLYING in effectiveKeywords(state, blocker)
    // CR 702.9b: flying is beaten by flying or reach.
    val beatsFlying = blockerHasFlying || hasReach(state, blocker)
    val flyingSatisfied = Keyword.FLYING !in effectiveKeywords(state, attacker) || beatsFlying
    // Silhana Ledgewalker: flying literally, reach does not help.
    val evasionSatisfied = !printsFlyingOnlyEvasion(state, attacker) || blockerHasFlying
    return flyingSatisfied && evasionSatisfied
}

// Whether [attacker] prints the "can't be blocked except by creatures with flying" evasion.
private fun printsFlyingOnlyEvasion(
    state: GameState,
    attacker: ObjectId,
): Boolean {
    val evasions =
        state.definitions[state.battlefieldObject(attacker).card]
            ?.characteristics
            ?.evasions
            .orEmpty()
    return Evasion.BLOCKABLE_ONLY_BY_FLYING in evasions
}

/** The single defending player of [combat] (two-player); fails loudly on a multiplayer combat. */
internal fun defendingPlayerOf(combat: CombatState): PlayerId =
    combat.attackers
        .map(AttackerAssignment::defendingPlayer)
        .distinct()
        .singleOrNull()
        ?: error("CR 509.1: a single defending player is required in a two-player game, got ${combat.attackers}")

// --- Pure decision derivation (ADR-004) ---

/**
 * The combat decision [state] is paused at, or `null` when combat is not waiting on one (either
 * combat is not in progress, the pending pause is a priority window, or it is not a combat pause
 * at all). Purely state-derived: the current combat step and how far [CombatState] has progressed
 * fully determine the request.
 *
 * - declare-attackers step, combat not engaged yet **and the active player has an eligible
 *   attacker** -> [DecisionRequest.DeclareAttackers] (CR 508.1). With no eligible attacker there
 *   is nothing to declare, so combat never engages and the step is a plain priority window — the
 *   only combat any creature-less game (every real game until P3.2 puts creatures on the
 *   battlefield) ever sees;
 * - declare-blockers step, combat engaged, no blocks chosen yet -> [DecisionRequest.DeclareBlockers]
 *   (CR 509.1);
 * - declare-blockers step, blocks chosen, some attacker blocked by 2+ still unordered ->
 *   [DecisionRequest.OrderBlockers] for the first such attacker (CR 509.2).
 *
 * When the active player has an eligible attacker and declares none, combat *is* engaged (an empty
 * [CombatState]) and CR 508.8 skips the declare-blockers and combat-damage steps — the meaningful
 * "chose not to attack" path. A board with no eligible attackers at all never engages combat, so
 * those steps run as bare priority windows exactly as they did before P3.1.
 */
internal fun pendingCombatDecision(state: GameState): DecisionRequest? {
    if (state.turn.phase != TurnPhase.COMBAT) return null
    val combat = state.turn.combat
    return when (state.turn.step) {
        TurnStep.DECLARE_ATTACKERS -> {
            if (combat != null) {
                null
            } else {
                val eligible = eligibleAttackers(state)
                if (eligible.isEmpty()) null else declareAttackersRequest(state, eligible)
            }
        }
        TurnStep.DECLARE_BLOCKERS ->
            when {
                // Combat never engaged (no eligible attacker existed): a plain priority window.
                combat == null -> null
                combat.blocks == null -> declareBlockersRequest(state, combat)
                else -> orderBlockersRequestOrNull(state, combat)
            }
        // CR 510.1c / 702.19e: a blocked trampler with positive excess needs its assignment chosen
        // before this step's damage is dealt; every other combat-damage step needs no decision.
        TurnStep.COMBAT_DAMAGE -> if (combat == null) null else pendingTrampleDecision(state, combat)
        else -> null
    }
}

private fun declareAttackersRequest(
    state: GameState,
    eligible: List<GameObject>,
): DecisionRequest.DeclareAttackers {
    val active = state.turn.activePlayer
    val defender = state.opponentOf(active)
    val options =
        eligible.map { attacker ->
            DecisionRequest.DeclareAttackers.Option(attacker.id, attacker.card, defender)
        }
    return DecisionRequest.DeclareAttackers(
        id = DecisionRequestId(active, state.player(active).decisionsAnswered),
        options = options,
    )
}

private fun declareBlockersRequest(
    state: GameState,
    combat: CombatState,
): DecisionRequest.DeclareBlockers {
    val defender = defendingPlayerOf(combat)
    return DecisionRequest.DeclareBlockers(
        id = DecisionRequestId(defender, state.player(defender).decisionsAnswered),
        options = eligibleBlockPairings(state, combat),
    )
}

private fun orderBlockersRequestOrNull(
    state: GameState,
    combat: CombatState,
): DecisionRequest.OrderBlockers? {
    val blocks = combat.blocks ?: return null
    val active = state.turn.activePlayer
    val attacker =
        combat.attackers
            .map(AttackerAssignment::attacker)
            .firstOrNull { att ->
                blocks.count { it.attacker == att } >= MINIMUM_ORDERED_BLOCKERS && att !in combat.blockerOrder
            }
    return attacker?.let { att ->
        val options =
            blocks
                .filter { it.attacker == att }
                .map { DecisionRequest.OrderBlockers.Option(it.blocker, state.battlefieldObject(it.blocker).card) }
        DecisionRequest.OrderBlockers(
            id = DecisionRequestId(active, state.player(active).decisionsAnswered),
            attacker = att,
            options = options,
        )
    }
}

// CR 509.2: only an attacker blocked by two or more creatures has its blockers ordered.
private const val MINIMUM_ORDERED_BLOCKERS: Int = 2

// --- Continuation ---

/**
 * Resumes combat after any combat transition: if another combat decision is now due
 * ([pendingCombatDecision]), suspends with it; otherwise the combat sub-actions are complete and
 * the step's priority round is granted (CR 508.2 / CR 509.4). Sharing the derivation with the
 * pause computation is what keeps a resumed state landing on exactly the same request.
 */
internal fun resumeCombat(state: GameState): AdvanceResult {
    val request = pendingCombatDecision(state)
    return if (request != null) AdvanceResult.NeedsDecision(state, request) else grantPriorityRound(state)
}
