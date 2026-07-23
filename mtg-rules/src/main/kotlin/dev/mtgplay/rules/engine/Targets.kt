package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target

/*
 * Target legality (CR 115, ADR-005): the engine enumerates the legal targets for a spec, and
 * "legal" is defined *by* that enumeration — choice at cast time (CR 601.2c) picks from it,
 * and the resolution re-check (CR 608.2b) tests membership in it, so cast-time and
 * resolution-time legality can never drift apart.
 *
 * [you] is the deciding player — the caster at cast time, the spell's controller at resolution.
 * Most specs ignore it; an Aura's [TargetSpec.Enchantable] with a "you control" restriction reads
 * it (control is ownership in the MVP pool, docs/design/layer-system.md §4).
 */

/**
 * Every legal target for [spec] in [state] for the deciding player [you], in deterministic
 * enumeration order (ADR-005).
 *
 * [TargetSpec.AnyTarget] (CR 115.4) enumerates the players in turn order — a player may target
 * themself — followed by every creature on the battlefield in battlefield order (CR 302.1) that
 * [you] may target. Planeswalkers and battles are not in the MVP pool, so they never enter this
 * enumeration. Hexproof (CR 702.11) is the one targeting restriction in the pool: a creature with
 * hexproof among its effective keywords is excluded when [you] is *not* its controller — its own
 * controller targets it freely ([targetableBy]). [TargetSpec.Enchantable] (CR 601.2c) enumerates
 * every battlefield object satisfying the Aura's enchant restriction (CR 303.4a) for [you] and
 * targetable by [you] — so a GW-Bogles player enchants their own hexproof creatures, but an
 * opponent's Aura cannot. [TargetSpec.None] enumerates nothing: an untargeted spell never surfaces
 * a target decision.
 */
internal fun legalTargets(
    state: GameState,
    spec: TargetSpec,
    you: PlayerId,
): List<Target> =
    when (spec) {
        TargetSpec.None -> emptyList()
        TargetSpec.AnyTarget ->
            state.players.keys.map { Target.Player(it) } +
                state.sharedZones.battlefield
                    .filter { isCreature(state, it) && targetableBy(state, it, you) }
                    .map { Target.Permanent(it.id) }
        is TargetSpec.Enchantable ->
            state.sharedZones.battlefield
                .filter {
                    satisfiesEnchantRestriction(
                        state,
                        spec.restriction,
                        it,
                        you,
                    ) &&
                        targetableBy(state, it, you)
                }.map { Target.Permanent(it.id) }
    }

/**
 * Whether the deciding player [you] may target the battlefield object [obj] (CR 115.4, CR 702.11):
 * a hexproof object can't be the target of spells or abilities its opponents control, so it is
 * untargetable by anyone who is not its controller — ownership is control in the MVP pool
 * (docs/design/layer-system.md §4). Every non-hexproof object, and every object [you] controls, is
 * targetable. Hexproof is read from effective keywords, so an aura-granted hexproof restricts
 * targeting exactly as a printed one does (CR 613 layer 6).
 */
private fun targetableBy(
    state: GameState,
    obj: GameObject,
    you: PlayerId,
): Boolean = obj.owner == you || Keyword.HEXPROOF !in effectiveKeywords(state, obj.id)

/**
 * Whether [target] is (still) a legal choice for [spec] in [state] for the deciding player [you] —
 * the CR 608.2b re-check, defined as membership in the current legal-target enumeration so legality
 * has a single source of truth (ADR-005). A targeted creature (or an Aura's enchanted object) stops
 * being legal the moment it leaves the battlefield — most often by dying to a state-based action
 * (CR 704.5g/f) — which makes the CR 608.2b fizzle genuinely reachable (a spell whose only target
 * has died does not resolve). A targeted player only stops being legal by leaving the game, which in
 * a two-player game is the game ending (CR 104.2a), so the players-only fizzle stays unreachable.
 */
internal fun isTargetLegal(
    state: GameState,
    spec: TargetSpec,
    target: Target,
    you: PlayerId,
): Boolean = target in legalTargets(state, spec, you)
