package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target

/*
 * Target legality (CR 115, ADR-005): the engine enumerates the legal targets for a spec, and
 * "legal" is defined *by* that enumeration — choice at cast time (CR 601.2c) picks from it,
 * and the resolution re-check (CR 608.2b) tests membership in it, so cast-time and
 * resolution-time legality can never drift apart.
 */

/**
 * Every legal target for [spec] in [state], in deterministic enumeration order (ADR-005).
 *
 * [TargetSpec.AnyTarget] (CR 115.4) enumerates the players in turn order — a player may target
 * themself — and nothing else until targetable objects exist on the battlefield (Phase 3
 * extends this enumeration, not the spec). [TargetSpec.None] enumerates nothing: an untargeted
 * spell never surfaces a target decision.
 */
internal fun legalTargets(
    state: GameState,
    spec: TargetSpec,
): List<Target> =
    when (spec) {
        TargetSpec.None -> emptyList()
        TargetSpec.AnyTarget -> state.players.keys.map { Target.Player(it) }
    }

/**
 * Whether [target] is (still) a legal choice for [spec] in [state] — the CR 608.2b re-check,
 * defined as membership in the current legal-target enumeration so legality has a single
 * source of truth (ADR-005). In P2.1 a targeted player only stops being legal by not being
 * seated (nothing grants protection or hexproof yet), which no reachable state exhibits in a
 * two-player game; the check is honest anyway and unit-tested directly.
 */
internal fun isTargetLegal(
    state: GameState,
    spec: TargetSpec,
    target: Target,
): Boolean = target in legalTargets(state, spec)
