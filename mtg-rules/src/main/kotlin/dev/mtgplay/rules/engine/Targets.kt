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
 * [you] is the deciding player — the caster at cast time (CR 601.2c), the activator while activating
 * an ability (CR 602.2b), the ability's controller as a trigger is put on the stack (CR 603.3d), and
 * the spell's or ability's controller at the CR 608.2b resolution re-check. Most specs ignore it; an
 * Aura's [TargetSpec.Enchantable] with a "you control" restriction reads it (control is ownership in
 * the MVP pool, docs/design/layer-system.md §4).
 *
 * The one targeting restriction in the pool, hexproof (CR 702.11), is **opponent-relative**: its whole
 * input is who is deciding, which is what [you] carries. Two further parameters are already designed
 * and deliberately not added yet, because no card needs them and both would change every call site:
 * a *prospective source*, which protection needs (CR 702.16b, docs/design/protection.md §2.4), and a
 * *self-exclusion*, which targeting a spell on the stack needs (docs/design/countering-spells.md §4).
 * Keeping every caller funnelled through this one function is what keeps each of those a one-file
 * change (docs/design/targeted-abilities.md §5).
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
 * opponent's Aura cannot. [TargetSpec.TargetOpponent] (CR 115.1a, CR 102.1) enumerates every player
 * but [you], in turn order, and no permanent — the one spec whose enumeration depends on who is
 * deciding rather than only on the board. [TargetSpec.None] enumerates nothing: an untargeted spell
 * or ability never surfaces a target decision.
 */
internal fun legalTargets(
    state: GameState,
    spec: TargetSpec,
    you: PlayerId,
): List<Target> =
    when (spec) {
        TargetSpec.None -> emptyList()
        // CR 115.1a/102.1: every player but the one choosing. A player is always targetable —
        // hexproof and shroud are object qualities (CR 702.11) — so only the opponent test applies.
        TargetSpec.TargetOpponent ->
            state.players.keys
                .filter { it != you }
                .map { Target.Player(it) }
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

/**
 * The CR 608.2b verdict, shared by every resolution: whether a resolving object that targets has **all**
 * of its [targets] illegal now, so it does not resolve and none of its instructions are performed. An
 * object with *some* legal targets still resolves, doing what it can — a distinction with no observable
 * case until multi-target objects exist.
 *
 * Defined here, beside the enumeration that defines legality, so the three resolution sites — a spell
 * (`StackResolution.kt`), a triggered ability and an activated one (`AbilityResolution.kt`,
 * `ActivationExecution.kt`) — cannot drift on *when* the verdict is true. What they must **not** share is
 * what happens next: a spell's card leaves the stack for a graveyard or exile as a new object
 * (CR 608.2m, CR 702.34e), while an ability has no card and simply ceases to exist (CR 113.7a). See
 * docs/design/targeted-abilities.md §6.
 *
 * An ability that targets and carries **no** targets — a triggered ability whose controller had no legal
 * choice at CR 603.3d — is vacuously all-illegal here, which is exactly the right answer: it was put on
 * the stack and now does nothing.
 */
internal fun allTargetsIllegal(
    state: GameState,
    spec: TargetSpec,
    targets: List<Target>,
    controller: PlayerId,
): Boolean = spec != TargetSpec.None && targets.none { isTargetLegal(state, spec, it, controller) }
