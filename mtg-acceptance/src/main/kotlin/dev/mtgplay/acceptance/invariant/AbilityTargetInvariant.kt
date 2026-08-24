package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry

/**
 * [Invariant.ABILITY_TARGET_SANITY]: the two pieces of state `FW-ABILTGT` introduces are well-formed
 * (docs/design/targeted-abilities.md §8). Top-level so the [InvariantChecker] file stays small.
 *
 * **On the stack.** An ability entry's target list matches its spec's arity: an ability whose
 * [TargetSpec] is [TargetSpec.None] carries no targets at all, and a targeting one carries **at most
 * one** (every spec in the pool demands exactly one; "up to N" is `FW-MULTITGT`). A targeting ability
 * carrying *zero* targets is legal and deliberately not flagged — it is CR 603.3d's "no legal target
 * existed at placement" case, which goes on the stack and then does nothing (CR 608.2b). That case is
 * reachable only for a **triggered** ability: an activated one with no legal target cannot be activated
 * (CR 601.2c), so an empty-targeted activated ability on the stack *is* a violation, and this is the
 * only place that asymmetry is machine-checked.
 *
 * **At the placement pause.** When [GameState.pendingTriggerTargets] is set, its controller is seated,
 * they are the APNAP-first controller with pending triggers (CR 603.3b — placement runs in APNAP
 * order), the front trigger of their group actually targets (otherwise the engine would have placed it
 * outright rather than pausing), and no cast or activation gathering coexists (trigger placement happens
 * between a state-based-action check and a priority window, never inside one).
 */
internal fun checkAbilityTargetSanity(state: GameState): List<Violation> =
    buildList {
        state.sharedZones.stack.forEach { entry ->
            when (entry) {
                is StackEntry.Spell -> Unit
                is StackEntry.Ability ->
                    addAll(
                        checkArity(
                            spec = entry.trigger.ability.targetSpec,
                            count = entry.targets.size,
                            sourceName = entry.trigger.sourceCard.name,
                            kind = "triggered",
                        ),
                    )
                is StackEntry.ActivatedAbilityOnStack -> {
                    addAll(
                        checkArity(
                            spec = entry.ability.targetSpec,
                            count = entry.targets.size,
                            sourceName = entry.sourceCard.name,
                            kind = "activated",
                        ),
                    )
                    if (entry.ability.targetSpec != TargetSpec.None && entry.targets.isEmpty()) {
                        add(
                            Violation(
                                Invariant.ABILITY_TARGET_SANITY,
                                "CR 601.2c: ${entry.sourceCard.name}'s activated ability targets but carries no " +
                                    "target; an ability with no legal target cannot be activated at all",
                            ),
                        )
                    }
                }
            }
        }
        addAll(checkTriggerTargetPause(state))
    }

/**
 * The CR 601.2c arity check shared by both ability stack entries: no target for [TargetSpec.None], at
 * most one otherwise.
 */
private fun checkArity(
    spec: TargetSpec,
    count: Int,
    sourceName: String,
    kind: String,
): List<Violation> =
    when {
        spec == TargetSpec.None && count > 0 ->
            listOf(
                Violation(
                    Invariant.ABILITY_TARGET_SANITY,
                    "CR 601.2c: $sourceName's $kind ability targets nothing but carries $count target(s)",
                ),
            )
        spec != TargetSpec.None && count > 1 ->
            listOf(
                Violation(
                    Invariant.ABILITY_TARGET_SANITY,
                    "CR 601.2c: $sourceName's $kind ability demands one target but carries $count",
                ),
            )
        else -> emptyList()
    }

/** The CR 603.3d placement pause's well-formedness (see [checkAbilityTargetSanity]). */
private fun checkTriggerTargetPause(state: GameState): List<Violation> =
    buildList {
        val pending = state.pendingTriggerTargets ?: return@buildList
        if (pending.controller !in state.players) {
            add(
                Violation(
                    Invariant.ABILITY_TARGET_SANITY,
                    "CR 603.3d: the trigger-targeting pause names unseated controller ${pending.controller}",
                ),
            )
            return@buildList
        }
        val front = state.pendingTriggers.firstOrNull { it.controller == pending.controller }
        if (front == null) {
            add(
                Violation(
                    Invariant.ABILITY_TARGET_SANITY,
                    "CR 603.3d: a trigger-targeting pause is open but ${pending.controller} has no pending trigger",
                ),
            )
            return@buildList
        }
        if (front.ability.targetSpec == TargetSpec.None) {
            add(
                Violation(
                    Invariant.ABILITY_TARGET_SANITY,
                    "CR 603.3d: the trigger being placed (${front.sourceCard.name}) does not target, so it " +
                        "should have been put on the stack without a pause",
                ),
            )
        }
        if (state.pendingCast != null || state.pendingActivation != null) {
            add(
                Violation(
                    Invariant.ABILITY_TARGET_SANITY,
                    "CR 603.3b: triggers are placed between state-based actions and a priority window, so no " +
                        "cast or activation gathering may be open during a trigger-targeting pause",
                ),
            )
        }
    }
