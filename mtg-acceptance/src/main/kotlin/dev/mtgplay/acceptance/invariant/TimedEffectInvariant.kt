package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.TimedContinuousEffect

/*
 * [Invariant.TIMED_EFFECT_SANITY] (`FW-DURATION`, docs/design/duration.md §8): the well-formedness
 * of [GameState.timedEffects], the first rules-relevant content of a state that hangs off no object.
 *
 * Its own file, like the other framework invariants, so the checker object stays small and each
 * property is independently testable — including against corruption a real transition cannot
 * produce, which for a duration is the whole point: the failure this guards against is an effect
 * that *should have expired* and did not, and no reachable state exhibits it unless the engine is
 * already wrong.
 */

/**
 * [Invariant.TIMED_EFFECT_SANITY]: every running continuous effect is well-formed at an observed
 * state (CR 611.2, CR 514.2, CR 613.7d). Three properties, each a guarantee rather than a
 * plausible-sounding restatement:
 *
 * 1. **The duration is honoured.** An [EffectDuration.UntilEndOfTurn] effect exists only during the
 *    turn it was created on. This is the whole contract: the CR 514.2 cleanup turn-based action ends
 *    every one of them, so an effect carrying an earlier [TimedContinuousEffect.createdOnTurn] means
 *    that wear-off failed to fire and a pump has silently become permanent. It is caught at the very
 *    next observed pause, on the following turn.
 *
 *    [EffectDuration.Indefinite] is checked in the **opposite** direction and by the same `when`: an
 *    effect with no duration (CR 611.2b) must survive, so it is simply never a violation here. Writing
 *    it as an explicit arm rather than letting it fall through is what makes the pairing checkable —
 *    the cleanup's own `when` and this one are the two halves of one contract, and both are exhaustive
 *    so a third duration cannot be added to one without being answered in the other.
 * 2. **The timestamp sequence is sane.** Every timestamp is strictly below the object-id allocation
 *    counter — timed effects and objects draw from one monotonic sequence so their CR 613.7
 *    timestamps are comparable (docs/design/duration.md §4) — and timestamps strictly increase in
 *    store order, the append-only property the fingerprint's order-stability rests on.
 * 3. **Every stored effect does something.** An effect that grants nothing, changes no type, sets no
 *    P/T and modifies none classifies into no implemented CR 613 layer; it is
 *    docs/design/layer-system.md §1's loud gate restated as a state property, so a bad effect is caught
 *    even on a turn when nothing reads the affected object's characteristics.
 *
 * **What this deliberately does not check.** It does **not** require [TimedContinuousEffect.affected]
 * to name a current battlefield object. Unlike an Aura's attachment (CR 704.5m, checked by
 * [Invariant.ATTACHMENT_INTEGRITY]), a CR 611.2 effect does not end when its object leaves the
 * battlefield — it simply applies to nothing for the rest of its duration, and the object that comes
 * back is a different one (CR 400.7). Asserting otherwise would fail every time a pumped creature
 * dies, which is a line of play rather than a bug.
 *
 * No game-over exemption is needed: none of the three properties depends on state-based actions
 * having run to quiescence, so all three hold in the final game-over state exactly as at any pause.
 */
internal fun checkTimedEffectSanity(state: GameState): List<Violation> =
    buildList {
        addAll(checkDurationsHonoured(state))
        addAll(checkTimestampSequence(state))
        addAll(checkEveryEffectActs(state.timedEffects))
        addAll(checkPreventionDurationsHonoured(state))
    }

/**
 * Property 1 again, for the **global prevention store** (`FW-PREVENT2`, CR 615, CR 514.2): a
 * "this turn" prevention effect exists only during the turn it was created on.
 *
 * The same guarantee as [checkDurationsHonoured] over a second store, and it is checked here rather
 * than in an invariant of its own because it is the same failure: the CR 514.2 turn-based action ends
 * both stores in one transition, so an effect surviving in either means that one action failed. A
 * Prismatic Strands shield that outlived its turn would silently prevent damage on every later turn,
 * which is the worst-behaved bug this framework can have and leaves no other trace in the state.
 *
 * Properties 2 and 3 have no counterpart: a prevention effect stores no timestamp (nothing orders
 * them — see `TimedPreventionEffect`), and its payload is a closed sum every member of which does
 * something, so there is no "acts on nothing" shape to exclude.
 */
private fun checkPreventionDurationsHonoured(state: GameState): List<Violation> =
    state.preventionEffects
        .filter { effect ->
            when (effect.duration) {
                EffectDuration.UntilEndOfTurn -> effect.createdOnTurn != state.turn.number
                // CR 611.2b: an effect with no duration is *supposed* to survive; never a violation.
                EffectDuration.Indefinite -> false
            }
        }.map { effect ->
            Violation(
                Invariant.TIMED_EFFECT_SANITY,
                "CR 514.2: the until-end-of-turn prevention effect from ${effect.sourceCard.name} was " +
                    "created on turn ${effect.createdOnTurn} but survives into turn ${state.turn.number}; " +
                    "the cleanup step's end-of-effects turn-based action failed to fire",
            )
        }

/** Property 1: an "until end of turn" effect exists only during its own turn (CR 514.2). */
private fun checkDurationsHonoured(state: GameState): List<Violation> =
    state.timedEffects
        .filter { effect ->
            when (effect.duration) {
                EffectDuration.UntilEndOfTurn -> effect.createdOnTurn != state.turn.number
                // CR 611.2b: an effect with no duration is *supposed* to survive; never a violation.
                EffectDuration.Indefinite -> false
            }
        }.map { effect ->
            Violation(
                Invariant.TIMED_EFFECT_SANITY,
                "CR 514.2: the until-end-of-turn effect from ${effect.sourceCard.name} was created on " +
                    "turn ${effect.createdOnTurn} but survives into turn ${state.turn.number}; the " +
                    "cleanup step's end-of-effects turn-based action failed to fire",
            )
        }

/** Property 2: timestamps stay below the allocation counter and strictly increase (CR 613.7d). */
private fun checkTimestampSequence(state: GameState): List<Violation> =
    buildList {
        state.timedEffects
            .filter { it.timestamp >= state.nextObjectId }
            .forEach { effect ->
                add(
                    Violation(
                        Invariant.TIMED_EFFECT_SANITY,
                        "CR 400.7: timestamp ${effect.timestamp} from ${effect.sourceCard.name} is not " +
                            "below the allocation counter ${state.nextObjectId}",
                    ),
                )
            }
        state.timedEffects
            .zipWithNext()
            .filterNot { (earlier, later) -> earlier.timestamp < later.timestamp }
            .forEach { (earlier, later) ->
                add(
                    Violation(
                        Invariant.TIMED_EFFECT_SANITY,
                        "CR 613.7d: timed effects are stored in creation order, so timestamps strictly " +
                            "increase; ${earlier.sourceCard.name}@${earlier.timestamp} precedes " +
                            "${later.sourceCard.name}@${later.timestamp}",
                    ),
                )
            }
    }

/** Property 3: every stored effect classifies into an implemented CR 613 layer. */
private fun checkEveryEffectActs(effects: List<TimedContinuousEffect>): List<Violation> =
    effects
        .filter {
            it.modification.grantedKeywords.isEmpty() &&
                it.modification.grantedEvasions.isEmpty() &&
                it.modification.addedCardTypes.isEmpty() &&
                it.modification.addedSubtypes.isEmpty() &&
                it.modification.setPower == null &&
                it.modification.setToughness == null &&
                it.modification.powerMod == 0 &&
                it.modification.toughnessMod == 0
        }.map { effect ->
            Violation(
                Invariant.TIMED_EFFECT_SANITY,
                "CR 613: the stored effect from ${effect.sourceCard.name} grants nothing, changes no " +
                    "type and modifies no power or toughness, so it classifies into no implemented layer",
            )
        }
