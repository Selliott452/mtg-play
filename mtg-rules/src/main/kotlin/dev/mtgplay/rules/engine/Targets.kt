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
 * themself — followed by every creature on the battlefield in battlefield order (P3.2: the
 * "creature" half of "any target", now that creatures exist as targetable permanents; CR 302.1).
 * Planeswalkers and battles are not in the MVP pool, so they never enter this enumeration. No
 * targeting restriction (hexproof, protection) is granted by any card in the pool yet, so every
 * seated player and every battlefield creature is a legal choice. [TargetSpec.None] enumerates
 * nothing: an untargeted spell never surfaces a target decision.
 */
internal fun legalTargets(
    state: GameState,
    spec: TargetSpec,
): List<Target> =
    when (spec) {
        TargetSpec.None -> emptyList()
        TargetSpec.AnyTarget ->
            state.players.keys.map { Target.Player(it) } +
                state.sharedZones.battlefield
                    .filter { isCreature(state, it) }
                    .map { Target.Permanent(it.id) }
    }

/**
 * Whether [target] is (still) a legal choice for [spec] in [state] — the CR 608.2b re-check,
 * defined as membership in the current legal-target enumeration so legality has a single
 * source of truth (ADR-005). A targeted creature stops being legal the moment it leaves the
 * battlefield — most often by dying to a state-based action (CR 704.5g/f) — which makes the
 * CR 608.2b fizzle genuinely reachable from P3.2 on (a spell whose only target has died does not
 * resolve). A targeted player only stops being legal by leaving the game, which in a two-player
 * game is the game ending (CR 104.2a), so the players-only fizzle stays unreachable end-to-end.
 */
internal fun isTargetLegal(
    state: GameState,
    spec: TargetSpec,
    target: Target,
): Boolean = target in legalTargets(state, spec)
