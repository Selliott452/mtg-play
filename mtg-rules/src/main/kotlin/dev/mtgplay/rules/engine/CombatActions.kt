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
 * and not summoning sick (CR 302.6). No creature type in the MVP pool has haste, so summoning
 * sickness is an absolute bar.
 */
internal fun eligibleAttackers(state: GameState): List<GameObject> {
    val active = state.turn.activePlayer
    return state.sharedZones.battlefield.filter { obj ->
        obj.owner == active && isCreature(state, obj) && !obj.tapped && !obj.summoningSick
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
 * evasions in the pool require a flying blocker: flying itself (a flying attacker is blockable only
 * by a flyer, no reach exists) and Silhana Ledgewalker's "can't be blocked except by creatures with
 * flying" ([Evasion.BLOCKABLE_ONLY_BY_FLYING]). They impose the identical requirement, so they
 * compose here — either one demands the blocker have flying; otherwise any creature may block.
 */
private fun canBlock(
    state: GameState,
    blocker: ObjectId,
    attacker: ObjectId,
): Boolean =
    if (requiresFlyingBlocker(state, attacker)) {
        Keyword.FLYING in effectiveKeywords(state, blocker)
    } else {
        true
    }

// Whether [attacker]'s evasion demands a flying blocker (CR 509.1b): it has flying, or it prints the
// "blockable only by flying" evasion. The evasion is a printed characteristic no MVP effect grants
// or removes, so it is read straight from the definition (like the printed type read).
private fun requiresFlyingBlocker(
    state: GameState,
    attacker: ObjectId,
): Boolean {
    if (Keyword.FLYING in effectiveKeywords(state, attacker)) return true
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
