package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TargetingRequirement
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target

/*
 * CR 601.2c targeting **requirements** (`W8-G`, docs/design/protection.md §8): the stage between "which
 * targets are legal" and "which targets may be announced".
 *
 * "If any effects say that an object or player must be chosen as a target, the player chooses targets so
 * that they obey the maximum possible number of such effects without violating any rules or effects that
 * say that an object or player can't be chosen as a target."
 *
 * Two sentences, two halves, and the engine already had the second one. `legalTargets` answers "can't be
 * chosen" — hexproof, protection, the spec's own restriction — and that answer is a property of the
 * *object*, checked again at CR 608.2b when the spell resolves. This file answers "must be chosen",
 * which is a property of the *choice* and is checked exactly once, when the choice is made. Keeping them
 * in separate functions is what keeps the CR 608.2b re-check honest: a Standard Bearer that enters the
 * battlefield after a spell's targets were announced changes nothing about that spell, because a
 * requirement was never a legality.
 *
 * **Applied at the announcement sites and nowhere else**, which is how the printed scope is enforced
 * structurally rather than by a flag. Standard Bearer's clause reads "while an opponent is choosing
 * targets as part of **casting a spell** they control or **activating an ability** they control", so it
 * binds four call sites — the cast request and its CR 601.2c re-validation, the activation request and
 * its CR 601.2c re-validation — and deliberately does **not** bind `TriggerTargeting.kt`. A triggered
 * ability chooses targets as it is put on the stack (CR 603.3d); nobody is casting or activating
 * anything, and the card does not say "choosing targets" in general. Routing trigger targeting through
 * here would be a plausible-looking wrong card (PLAN.md §7).
 */

/**
 * The targets [you] may **announce** for [spec], choosing for [chooser] (CR 601.2c, CR 602.2b): the
 * legal pool, narrowed to obey every targeting requirement standing against [you] that can be obeyed.
 *
 * Call this from an announcement; call [legalTargets] from a legality check. The two agree except when
 * an opponent of [you] controls a permanent that declares a [TargetingRequirement], and the difference
 * is exactly the rule.
 *
 * **"If able" is a property of the narrowed set being non-empty**, and it falls out of the order of
 * operations rather than being tested: the pool this narrows has already had hexproof, protection and
 * the spec's own restriction applied, so a Flagbearer the chooser cannot legally target is not in it,
 * the narrowing finds nothing to keep, and the requirement is inert. That is CR 601.2c's
 * restrictions-beat-requirements precedence, obtained by construction.
 *
 * **The narrowing can never empty a non-empty pool**, which is what makes it safe to apply at the
 * castability gate's answer as well as at the request's: it either keeps a non-empty subset or keeps
 * everything.
 */
internal fun announceableTargets(
    state: GameState,
    spec: TargetSpec,
    you: PlayerId,
    chooser: Chooser,
): List<Target> = obeyingTargetingRequirements(state, spec, you, legalTargets(state, spec, you, chooser))

/**
 * [pool], narrowed to the choices that obey the targeting requirements standing against [you]
 * (CR 601.2c). Returns [pool] unchanged when no requirement is in force or none can be obeyed.
 *
 * **Every requirement in force is the same requirement today, and the engine refuses to guess otherwise.**
 * CR 601.2c asks for the *maximum possible number* of requirements to be obeyed, which is a maximisation
 * over the chosen set; with a single distinct required subtype and a single target it collapses to "keep
 * only the permanents that have it". Two Standard Bearers impose one distinct subtype and so collapse the
 * same way. A board carrying two *different* required subtypes, or a spell demanding more than one target
 * while a requirement is obeyable, are both genuine maximisation problems with a different algorithm, and
 * both fail loudly rather than being approximated by this filter — which for a multi-target spell would
 * force *every* target to be a Flagbearer, a rule no card prints.
 *
 * Neither gate is reachable from the gauntlet pool: Standard Bearer is its only requirement, and no card
 * in it names more than one target that a battlefield permanent could satisfy. They are here because the
 * day one arrives, a wrong answer would look exactly like a right one.
 */
private fun obeyingTargetingRequirements(
    state: GameState,
    spec: TargetSpec,
    you: PlayerId,
    pool: List<Target>,
): List<Target> {
    val subtype = requiredSubtypeAgainst(state, you) ?: return pool
    val obeying =
        pool.filter { target -> target is Target.Permanent && hasSubtype(state, target.id, subtype) }
    require(obeying.isEmpty() || spec.count.maximum <= 1) {
        "CR 601.2c: a targeting requirement ($subtype) is obeyable for a spec demanding up to " +
            "${spec.count.maximum} targets ($spec); \"at least one\" over a multi-target choice is a " +
            "combinatorial rule, not a filter, and this engine implements only the single-target case"
    }
    // CR 601.2c "if able": a requirement nothing in the pool can obey narrows nothing.
    return obeying.ifEmpty { pool }
}

/**
 * The one subtype every targeting requirement standing against [you] names (CR 601.2c), or `null` when
 * no requirement is in force. Fails loudly on two *distinct* subtypes — see
 * [obeyingTargetingRequirements] for why that is a maximisation rather than a filter.
 */
private fun requiredSubtypeAgainst(
    state: GameState,
    you: PlayerId,
): Subtype? {
    val required = requirementsAgainst(state, you)
    if (required.isEmpty()) return null
    return required.map { it.subtype }.distinct().singleOrNull()
        ?: error(
            "CR 601.2c: two or more distinct targeting requirements stand against $you " +
                "($required); obeying the maximum possible number of them is a maximisation this " +
                "engine does not implement, and picking one would be silently wrong",
        )
}

/**
 * The targeting requirements standing against [you] right now (CR 601.2c, CR 604.3): those declared by
 * battlefield permanents [you] does **not** control.
 *
 * "While an **opponent** is choosing targets" — so a Standard Bearer never constrains its own
 * controller, and two players each fielding one constrain each other. Control is ownership, the standing
 * simplification the rest of the engine makes until CR 613 layer 2 exists. A battlefield object with no
 * definition is inert and declares nothing.
 */
private fun requirementsAgainst(
    state: GameState,
    you: PlayerId,
): List<TargetingRequirement> =
    state.sharedZones.battlefield
        .filter { it.owner != you }
        .flatMap { state.definitions[it.card]?.targetingRequirements.orEmpty() }
