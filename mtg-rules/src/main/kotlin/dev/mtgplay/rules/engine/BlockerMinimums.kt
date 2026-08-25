package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The CR 509.1b blocker-**count** restriction ("this creature can't be blocked except by three or more
 * creatures", Troll of Khazad-dum) — its own file rather than a pair of functions in CombatActions.kt,
 * because it is the one block-legality rule that is not a property of a (blocker, attacker) pairing and
 * so shares nothing with `canBlock` beyond the CR paragraph.
 *
 * Every other evasion narrows *which* creature may block and is therefore answered per pairing, inside
 * the option list. This one narrows *how many*, which no option list can express, so it is published on
 * the declare-blockers request for the deciding seat to see (ADR-005) and enforced across the chosen set
 * in DecisionValidation.kt. Deriver and validator read the same published floors, so the enumeration a
 * seat is shown and the legality its answer is checked against cannot drift apart.
 */

/**
 * The per-attacker CR 509.1b blocker-count floors of this combat, in attacker-declaration order —
 * empty for every combat in which no attacker restricts the number of its blockers, which is all of
 * them but a Troll of Khazad-dûm's.
 *
 * Published on the request rather than left implicit because it is the one block-legality rule the
 * option list cannot express (ADR-005): a lone creature blocking the Troll is an *illegal* line, and a
 * seat that cannot see the floor would pick it. Only floors above one appear — a floor of one is what
 * every creature already has and restricts nothing.
 */
internal fun publishedBlockerMinimums(
    state: GameState,
    combat: CombatState,
): List<DecisionRequest.DeclareBlockers.BlockerMinimum> =
    combat.attackers.mapNotNull { assignment ->
        val minimum = minimumBlockersFor(state, assignment.attacker)
        if (minimum < DecisionRequest.DeclareBlockers.BlockerMinimum.SMALLEST_PUBLISHED_MINIMUM) {
            null
        } else {
            DecisionRequest.DeclareBlockers.BlockerMinimum(
                attacker = assignment.attacker,
                attackerCard = state.battlefieldObject(assignment.attacker).card,
                minimum = minimum,
            )
        }
    }

/**
 * The smallest **non-zero** number of creatures that may legally block [attacker] (CR 509.1b) — `1` for
 * a creature with no count restriction, which is every creature in the pool but Troll of Khazad-dûm.
 *
 * Read through [effectiveEvasions] like every other evasion (the keyword-tail packet's seam), so a
 * granted count restriction would restrict exactly as a printed one does. Two such evasions on one
 * attacker would take the **larger** floor, which is what CR 509.1b's cumulative restrictions mean; the
 * `maxOf` fold says so even though the pool prints one.
 */
internal fun minimumBlockersFor(
    state: GameState,
    attacker: ObjectId,
): Int =
    effectiveEvasions(state, attacker).fold(1) { floor, evasion ->
        when (evasion) {
            Evasion.BLOCKABLE_ONLY_BY_FLYING, Evasion.BLOCKABLE_ONLY_BY_HASTE -> floor
            // Troll of Khazad-dûm: "can't be blocked except by three or more creatures".
            Evasion.BLOCKABLE_ONLY_BY_THREE_OR_MORE -> maxOf(floor, BLOCKERS_REQUIRED_BY_TROLL_EVASION)
        }
    }

// CR 509.1b: "except by three or more creatures" — the printed count of the pool's one such evasion.
private const val BLOCKERS_REQUIRED_BY_TROLL_EVASION: Int = 3
