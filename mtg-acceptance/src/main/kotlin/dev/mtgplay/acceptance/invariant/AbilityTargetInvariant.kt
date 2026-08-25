package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target

/**
 * [Invariant.ABILITY_TARGET_SANITY]: the two pieces of state `FW-ABILTGT` introduces are well-formed
 * (docs/design/targeted-abilities.md §8). Top-level so the [InvariantChecker] file stays small.
 *
 * **On the stack.** An ability entry's target list matches its spec's arity: an ability whose
 * [TargetSpec] is [TargetSpec.None] carries no targets at all, and a targeting one carries **at most
 * [dev.mtgplay.core.definition.TargetCount.maximum]** — one for every line that predates `FW-MULTITGT`,
 * two for Faerie Macabre's and Blood Fountain's "up to two". It also carries **no object twice**
 * (CR 601.2c's same-object rule), which is checked here on the recorded targets rather than on the
 * indices an agent sent, so an entry that reached the stack by any route is covered.
 *
 * A targeting ability carrying *zero* targets is legal and deliberately not flagged — it is CR 603.3d's
 * "no legal target existed at placement" case, which goes on the stack and then does nothing
 * (CR 608.2b). That case is reachable only for a **triggered** ability whose spec *demands* a target:
 * an activated one that demands a target and has none cannot be activated at all (CR 601.2c), so such
 * an entry on the stack *is* a violation, and this is the only place that asymmetry is machine-checked.
 *
 * "Demands a target" is [dev.mtgplay.core.definition.TargetCount.minimum]` > 0` and was written as
 * "is not [TargetSpec.None]" before `FW-MULTITGT`, when the two were the same statement. They part
 * company for an "up to N" ability, which targets and may legitimately carry none — Faerie Macabre
 * activated with two empty graveyards is on the stack with an empty target list and resolves. The
 * check is the same rule, stated against the count rather than against a proxy for it.
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
                            targets = entry.targets,
                            sourceName = entry.trigger.sourceCard.name,
                            kind = "triggered",
                        ),
                    )
                is StackEntry.ActivatedAbilityOnStack -> {
                    addAll(
                        checkArity(
                            spec = entry.ability.targetSpec,
                            targets = entry.targets,
                            sourceName = entry.sourceCard.name,
                            kind = "activated",
                        ),
                    )
                    if (entry.ability.targetSpec.count.minimum > 0 && entry.targets.isEmpty()) {
                        add(
                            Violation(
                                Invariant.ABILITY_TARGET_SANITY,
                                "CR 601.2c: ${entry.sourceCard.name}'s activated ability demands a target but " +
                                    "carries none; an ability with no legal target cannot be activated at all",
                            ),
                        )
                    }
                }
            }
        }
        addAll(checkTriggerTargetPause(state))
    }

/**
 * The CR 601.2c arity and same-object check shared by both ability stack entries: at most the spec's
 * [dev.mtgplay.core.definition.TargetCount.maximum] targets — zero for [TargetSpec.None] — and no
 * object named twice.
 */
private fun checkArity(
    spec: TargetSpec,
    targets: List<Target>,
    sourceName: String,
    kind: String,
): List<Violation> =
    buildList {
        if (targets.size > spec.count.maximum) {
            add(
                Violation(
                    Invariant.ABILITY_TARGET_SANITY,
                    "CR 601.2c: $sourceName's $kind ability demands at most ${spec.count.maximum} target(s) " +
                        "but carries ${targets.size}",
                ),
            )
        }
        // CR 601.2c: "the same target can't be chosen multiple times for any one instance of the word
        // 'target'" — checked on the objects, so it holds however the entry reached the stack.
        if (targets.distinct().size != targets.size) {
            add(
                Violation(
                    Invariant.ABILITY_TARGET_SANITY,
                    "CR 601.2c: $sourceName's $kind ability names one target more than once: $targets",
                ),
            )
        }
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
