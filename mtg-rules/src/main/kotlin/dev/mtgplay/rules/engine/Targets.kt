package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
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
 * [self] is the *object doing the choosing*, by whatever id it currently carries — no object is ever a
 * legal target for itself. It is what closes a real ordering divergence that `FW-COUNTER` exposed: the
 * cast pipeline runs `proposeSpell` (CR 601.2a, card onto the stack) **before** `establishTargets`
 * (CR 601.2c, re-validation), so a "counter target spell" enumeration computed at gathering time (the
 * counter in hand, absent from the stack) and the same enumeration at re-validation (the counter now on
 * the stack) would name different sets. Passing the choosing object's current id at each site makes them
 * the same set again. `null` means "no object is choosing" — a triggered ability being placed, or a unit
 * test asking what the board offers.
 *
 * The one targeting restriction in the pool, hexproof (CR 702.11), is **opponent-relative**: its whole
 * input is who is deciding, which is what [you] carries. One further parameter is already designed and
 * deliberately not added yet, because no card needs it and it would change every call site: a
 * *prospective source*, which protection needs (CR 702.16b, docs/design/protection.md §2.4). Keeping
 * every caller funnelled through this one function is what keeps that a one-file change
 * (docs/design/targeted-abilities.md §5).
 */

/**
 * Every legal target for [spec] in [state] for the deciding player [you], excluding the choosing object
 * [self], in deterministic enumeration order (ADR-005).
 *
 * [TargetSpec.TargetPlayer] (CR 115.1a) enumerates exactly the players, in turn order — a player may
 * target themself, and no object is ever a legal choice (Thought Scour).
 * [TargetSpec.AnyTarget] (CR 115.4) enumerates the players in turn order — a player may target
 * themself — followed by every creature on the battlefield in battlefield order (CR 302.1) that
 * [you] may target. Planeswalkers and battles are not in the MVP pool, so they never enter this
 * enumeration. Hexproof (CR 702.11) is the one targeting restriction in the pool: a creature with
 * hexproof among its effective keywords is excluded when [you] is *not* its controller — its own
 * controller targets it freely ([targetableBy]). [TargetSpec.Enchantable] (CR 601.2c) enumerates
 * every battlefield object satisfying the Aura's enchant restriction (CR 303.4a) for [you] and
 * targetable by [you] — so a GW-Bogles player enchants their own hexproof creatures, but an
 * opponent's Aura cannot. [TargetSpec.TargetPermanent] (CR 115.1b) enumerates every battlefield
 * permanent satisfying its [dev.mtgplay.core.definition.PermanentRestriction]
 * ([satisfiesPermanentRestriction], which is decider-relative — "target creature **you control**"
 * offers a different set in each seat's hand, CR 109.5) and targetable by [you], in battlefield order,
 * and never a
 * player — the removal specs, and plain "target creature" (Skred, Terminate) among them. Its
 * enumeration is the *only* battlefield thing that makes a removal spell's CR 608.2b fizzle reachable,
 * because a permanent leaves the battlefield in ways a player never leaves the game.
 * [TargetSpec.TargetOpponent] (CR 115.1a, CR 102.1) enumerates every player but [you], in turn order,
 * and no permanent — the one spec whose enumeration depends on who is deciding rather than only on the
 * board. [TargetSpec.SpellOnStack] (CR 115.1, CR 111.1) enumerates every **spell** on the stack
 * satisfying its [dev.mtgplay.core.definition.SpellRestriction] ([satisfiesSpellRestriction]) other than
 * [self], in stack order from the bottom up; an *ability* on the stack is never offered, because it is
 * not a card and carries no object id to name it by (CR 113.7a). It is the only spec drawing on a zone
 * that churns several times within one priority round, which is why its CR 608.2b fizzle is the most
 * reachable of all. [TargetSpec.CardInGraveyard] (CR 115.1, CR 404) enumerates every card in the
 * graveyards its [dev.mtgplay.core.definition.GraveyardScope] admits — [you]'s alone for "your
 * graveyard", both seats' for "a graveyard" — that satisfies its
 * [dev.mtgplay.core.definition.GraveyardCardRestriction] ([satisfiesGraveyardCardRestriction]), in turn
 * order of graveyard and graveyard order within each, and never a permanent, a spell, or a player.
 * Hexproof is not consulted: it is a quality of a permanent (CR 702.11), and nothing in the pool makes a
 * graveyard card untargetable. It is the second decider-relative spec after [TargetSpec.TargetOpponent]
 * and the first whose *objects* depend on who is choosing — which is why the same [you] must be passed
 * at the CR 608.2b re-check as at the CR 601.2c/603.3d choice. **Every option it names is public
 * information** (CR 400.2 — a graveyard is a public zone), so ADR-005's option list and ADR-007's
 * per-seat filter agree here with no filtering rule; the structural reason they cannot drift is on
 * [Target.CardInGraveyard]. [TargetSpec.None] enumerates nothing: an untargeted spell or ability never
 * surfaces a target decision.
 *
 * Two [dev.mtgplay.core.definition.PermanentRestriction] members make [TargetSpec.TargetPermanent]
 * decider-relative as well (`FW-MULTITGT`): `PERMANENT_YOU_CONTROL` offers only [you]'s own permanents
 * (Tamiyo's Safekeeping) and `CREATURE_AN_OPPONENT_CONTROLS` only the other seat's creatures
 * (Brinebarrow Intruder), the latter further narrowed by the hexproof gate below.
 *
 * **The result is the *pool* of legal choices, never the choice itself, and it is count-independent**
 * (`FW-MULTITGT`, docs/design/multi-target.md §3). "Up to two target cards from graveyards" and "target
 * card from a graveyard" enumerate the same list; how many of it may be taken is [TargetSpec.count]'s
 * business, read by [targetChoiceBounds] and by the request the engine surfaces. That separation is
 * what makes the CR 601.2c same-object rule a property of the *answer* rather than of the enumeration.
 *
 * **Every option is distinct, and multi-target correctness rests on it.** Each member of the returned
 * list names one game object (or one player) by an id unique across the game (CR 400.7), and every
 * branch below maps over a zone whose objects are distinct — the two graveyards
 * [TargetSpec.CardInGraveyard] draws from under [GraveyardScope.ANY] are disjoint for the same reason.
 * That is what lets "the same object can't be chosen twice for any one instance of the word 'target'"
 * (CR 601.2c) be enforced as *distinct indices* on the answer: distinct indices into a duplicate-free
 * list are distinct objects. `MultiTargetSpec` pins the invariant directly rather than leaving it here
 * as a comment.
 */
internal fun legalTargets(
    state: GameState,
    spec: TargetSpec,
    you: PlayerId,
    self: ObjectId?,
): List<Target> =
    when (spec) {
        TargetSpec.None -> emptyList()
        // CR 115.1a: "target player" enumerates the players in turn order and nothing else. The spec's
        // count says how many of them may be named; the pool of choices is the same either way.
        is TargetSpec.TargetPlayer -> state.players.keys.map { Target.Player(it) }
        // CR 115.1a/102.1: every player but the one choosing. A player is always targetable —
        // hexproof and shroud are object qualities (CR 702.11) — so only the opponent test applies.
        TargetSpec.TargetOpponent ->
            state.players.keys
                .filter { it != you }
                .map { Target.Player(it) }
        TargetSpec.AnyTarget ->
            state.players.keys.map { Target.Player(it) } +
                state.sharedZones.battlefield
                    .filter { isCreature(state, it) && targetableBy(state, it, you, self) }
                    .map { Target.Permanent(it.id) }
        // CR 115.1b: every battlefield permanent satisfying the restriction, in battlefield order
        // (CR 302.1); no player is ever offered.
        is TargetSpec.TargetPermanent ->
            state.sharedZones.battlefield
                .filter {
                    satisfiesPermanentRestriction(state, spec.restriction, it, you) &&
                        targetableBy(state, it, you, self)
                }.map { Target.Permanent(it.id) }
        is TargetSpec.Enchantable ->
            state.sharedZones.battlefield
                .filter {
                    satisfiesEnchantRestriction(
                        state,
                        spec.restriction,
                        it,
                        you,
                    ) &&
                        targetableBy(state, it, you, self)
                }.map { Target.Permanent(it.id) }
        // CR 115.1/111.1: every spell on the stack satisfying the restriction, bottom-up, never the
        // choosing object itself and never an ability (CR 113.7a — no card, so no id to name it by).
        is TargetSpec.SpellOnStack ->
            state.sharedZones.stack
                .filterIsInstance<StackEntry.Spell>()
                .filter { it.obj.id != self && satisfiesSpellRestriction(state, spec.restriction, it) }
                .map { Target.SpellOnStack(it.obj.id) }
        // CR 115.1/404: every card in an admitted graveyard satisfying the restriction, in turn order of
        // graveyard and then graveyard order within each; no permanent, no spell, no player.
        is TargetSpec.CardInGraveyard ->
            graveyardsInScope(state, spec.scope, you)
                .flatMap { seat -> state.players.getValue(seat).graveyard }
                .filter { satisfiesGraveyardCardRestriction(state, spec.restriction, it) }
                .map { Target.CardInGraveyard(it.id) }
    }

/**
 * The seats whose graveyards a [TargetSpec.CardInGraveyard] draws from (CR 404), in turn order so the
 * enumeration is deterministic (ADR-005): the deciding player alone for [GraveyardScope.YOURS] ("from
 * your graveyard"), every player for [GraveyardScope.ANY] ("from a graveyard").
 *
 * [you] is the deciding player at *every* site that reaches here — the caster at CR 601.2c, the
 * ability's controller at CR 603.3d, and the same controller again at the CR 608.2b re-check — which is
 * what stops a "your graveyard" spell from being cast against one graveyard and re-checked against
 * another.
 */
private fun graveyardsInScope(
    state: GameState,
    scope: GraveyardScope,
    you: PlayerId,
): List<PlayerId> =
    when (scope) {
        GraveyardScope.YOURS -> state.players.keys.filter { it == you }
        GraveyardScope.ANY -> state.players.keys.toList()
    }

/**
 * Whether the deciding player [you], choosing for the object [self], may target the battlefield
 * object [obj]. Two independent restrictions, and their shapes are not the same.
 *
 * **Hexproof (CR 115.4, CR 702.11) is decider-relative.** A hexproof object can't be the target of
 * spells or abilities its opponents control, so it is untargetable by anyone who is not its
 * controller — ownership is control in the MVP pool (docs/design/layer-system.md §4). The only extra
 * input it needs is who is deciding.
 *
 * **Protection (CR 702.16b) is quality-relative**, and that is the single biggest shape difference
 * (`FW-PROTECT`, docs/design/protection.md §2.4): "A permanent or player with protection can't be
 * targeted by spells with the stated quality and can't be targeted by abilities from a source with
 * the stated quality." It does not care who controls the source — a player may not target their own
 * creature that has protection from white with their own white spell — but it does need the
 * *prospective source's* characteristics, which is what [self] supplies.
 *
 * Both are read through the layered seams ([effectiveKeywords], [effectiveProtections]), so a
 * granted restriction restricts exactly as a printed one does (CR 613 layer 6), and both are read
 * at the *enumeration*, which is what makes cast-time choice (CR 601.2c) and the resolution-time
 * re-check (CR 608.2b) incapable of drifting apart (ADR-005).
 *
 * **The gap this fails loudly on.** [self] is the choosing object, and every *spell* call site
 * passes one — the cast enumeration, the CR 601.2c validation and the CR 608.2b re-check all name
 * the card object. Every *ability* call site passes `null`, because [self] was introduced to exclude
 * the choosing spell from a stack enumeration and an ability has no card to exclude (CR 113.7a). So
 * CR 702.16b's "abilities from a source with the stated quality" half has no source to read here,
 * and closing it means giving the four ability sites a source — `Activation.kt`,
 * `ActivationGathering.kt`, `ActivationExecution.kt`, `TriggerTargeting.kt`. Until that lands, an
 * object with protection reached from an ability enumeration **throws** rather than being offered as
 * a target it might not legally be: a silently illegal option handed to a training agent is the
 * failure mode ADR-005 exists to prevent, and it is the one thing `EnumerationProbe` structurally
 * cannot catch (enumerator and validator are the same function — docs/design/protection.md §6).
 * Nothing in the pool prints or grants protection today, so the gate is unreachable; it fires the
 * moment that stops being true.
 *
 * Neither restriction is a quality of a *spell* on the stack, so the stack enumeration consults
 * neither: nothing in the pool makes a spell untargetable while it is being cast, and "can't be
 * countered" is a separate, absent predicate (docs/design/countering-spells.md §13).
 */
private fun targetableBy(
    state: GameState,
    obj: GameObject,
    you: PlayerId,
    self: ObjectId?,
): Boolean {
    // CR 115.4 / CR 702.11: decider-relative, needs only who is choosing.
    val hexproofPermits = obj.owner == you || Keyword.HEXPROOF !in effectiveKeywords(state, obj.id)
    // CR 702.16b: quality-relative, needs the prospective source's characteristics.
    return hexproofPermits && !protectedFromSource(state, obj, self)
}

/**
 * The CR 702.16b half of [targetableBy]: whether [obj] has protection from a quality the prospective
 * source [self] has, and so can't be targeted by it.
 *
 * The common case is checked first and needs no source at all — an object with no protection is
 * protected from nothing — which is what keeps the loud gate below unreachable while nothing in the
 * pool prints or grants protection.
 */
private fun protectedFromSource(
    state: GameState,
    obj: GameObject,
    self: ObjectId?,
): Boolean {
    val protections = effectiveProtections(state, obj.id)
    if (protections.isEmpty()) return false
    val sourceCard =
        self?.let { printedIdentityOf(state, it) }
            ?: error(
                "CR 702.16b: ${obj.card} has protection $protections, but this target enumeration " +
                    "carries no prospective source to test it against — an ability's targeting " +
                    "(Activation.kt, ActivationGathering.kt, ActivationExecution.kt, " +
                    "TriggerTargeting.kt) passes self = null. Offering the object anyway would be a " +
                    "silently illegal option (ADR-005); see docs/design/protection.md §2.4",
            )
    return protections.any { sourceHasQuality(state, sourceCard, it) }
}

/**
 * Whether [target] is (still) a legal choice for [spec] in [state] for the deciding player [you], with
 * the choosing object [self] excluded — the CR 608.2b re-check, defined as membership in the current
 * legal-target enumeration so legality has a single source of truth (ADR-005).
 *
 * A targeted creature (or an Aura's enchanted object) stops being legal the moment it leaves the
 * battlefield — most often by dying to a state-based action (CR 704.5g/f) — which makes the CR 608.2b
 * fizzle genuinely reachable. A targeted **spell** stops being legal the moment it leaves the stack,
 * whether it resolved or was itself countered: the departing card is reborn under a fresh id (CR 400.7),
 * so the stale [Target.SpellOnStack] names nothing anywhere and the counter above it fizzles, which is
 * the correct answer reached with no new code. A targeted player only stops being legal by leaving the
 * game, which in a two-player game is the game ending (CR 104.2a), so the players-only fizzle stays
 * unreachable.
 */
internal fun isTargetLegal(
    state: GameState,
    spec: TargetSpec,
    target: Target,
    you: PlayerId,
    self: ObjectId?,
): Boolean = target in legalTargets(state, spec, you, self)

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
 *
 * **An "up to N" object that chose zero targets is the opposite case, and the two are told apart by
 * [TargetSpec.count]** (`FW-MULTITGT`). Rooftop Percher's controller may decline both of its targets;
 * the ability then has no illegal target, resolves, and still gains 3 life. Faerie Macabre exiling
 * nothing from two empty graveyards is the same shape. So an empty target list means "does not resolve"
 * only when the spec's [TargetCount.minimum] is above zero — a *required* instance of the word "target"
 * that could not be filled. Without the count these two are indistinguishable, and reading them the
 * same way silently deletes the non-targeting half of every "up to" card in the pool.
 *
 * [self] is the resolving object's own id where it has one (a spell), or `null` for an ability
 * (CR 113.7a); it keeps the re-check's enumeration identical to the one the choice was made from.
 */
internal fun allTargetsIllegal(
    state: GameState,
    spec: TargetSpec,
    targets: List<Target>,
    controller: PlayerId,
    self: ObjectId?,
): Boolean =
    when {
        // An object that targets nothing has nothing to re-check (this also covers TargetSpec.None,
        // whose count is zero).
        spec.count.maximum == 0 -> false
        // CR 601.2c/603.3d: a required instance of the word "target" that was never filled. The
        // "up to N" case reaches here with minimum 0 and resolves, doing what it can.
        targets.isEmpty() -> spec.count.minimum > 0
        else -> targets.none { isTargetLegal(state, spec, it, controller, self) }
    }

/**
 * The bounds a target choice for [spec] must satisfy given [optionCount] legal options (CR 601.2c) — the
 * one place the printed cardinality is reconciled with what the board actually offers
 * (`FW-MULTITGT`, docs/design/multi-target.md §4).
 *
 * The maximum is clamped to [optionCount]: "up to two target cards" with one card in the graveyards
 * offers a choice of nought or one, not a demand for two that cannot be met. The minimum is **not**
 * clamped — a spec demanding more targets than the board holds is not castable at all
 * ([targetsAvailable] rejects it before any request is built), so a minimum above [optionCount] here is
 * an engine defect rather than a rules case, and the [require] says so.
 */
internal fun targetChoiceBounds(
    spec: TargetSpec,
    optionCount: Int,
): IntRange {
    require(spec.count.minimum <= optionCount) {
        "CR 601.2c: $spec demands at least ${spec.count.minimum} target(s) but only $optionCount " +
            "legal option(s) exist; such an object is never enumerated as castable or activatable"
    }
    return spec.count.minimum..minOf(spec.count.maximum, optionCount)
}

/**
 * Whether the open choice for [spec] is **vacuous** — settled without asking anybody (ADR-004).
 *
 * True in exactly two cases, and they are genuinely different rules:
 * - the object targets nothing at all ([TargetSpec.None], count zero), so CR 601.2c has no stage; and
 * - the enumeration is empty. For a required instance that is CR 603.3d's mandatory triggered ability
 *   with no legal target, which goes on the stack target-less and does nothing (a cast or activation
 *   never reaches here, since [targetsAvailable] excluded it). For an "up to N" instance it is Faerie
 *   Macabre with two empty graveyards: legal, castable, and with nothing whatever to decide.
 *
 * Surfacing a request with an empty option list instead would be the alternative, and it is refused for
 * the reason `DecisionRequest.ChooseTargets` already refuses it: an option list an agent cannot pick
 * from is not a decision, and ADR-005's completeness property is about choices that exist.
 */
internal fun targetChoiceIsVacuous(
    state: GameState,
    spec: TargetSpec,
    you: PlayerId,
    self: ObjectId?,
): Boolean = spec.count.maximum == 0 || legalTargets(state, spec, you, self).isEmpty()

/**
 * Fails loudly unless [targets] is a well-formed choice for [spec] (CR 601.2c) — the arity and
 * same-object half of the re-validation both the cast pipeline and the activation pipeline run, beside
 * the per-target legality check they each already do.
 *
 * Two rules, and the second is the one a multi-target enumeration most easily gets wrong:
 * 1. **Arity.** The number chosen lies within [TargetSpec.count], with the maximum clamped by how many
 *    options the board offered ([targetChoiceBounds]).
 * 2. **CR 601.2c's same-object rule** — "the same target can't be chosen multiple times for any one
 *    instance of the word 'target'". Enforced here on the *recorded* targets, as well as at
 *    `validateDecision` on the answer's indices, and the redundancy is the point: index distinctness
 *    proves object distinctness only while the option list is duplicate-free, so this check holds even
 *    if a future enumeration branch ever offers one object twice.
 *
 * Reaching a violation is an engine defect, not a rules corner: the choice came from an enumeration and
 * was validated on the way in (ADR-005), and nothing can change between gathering and execution — the
 * whole activation is one transition, and a cast's own stages cannot add or remove a target.
 */
internal fun requireWellFormedTargetChoice(
    spec: TargetSpec,
    targets: List<Target>,
    optionCount: Int,
    describe: String,
) {
    val bounds = targetChoiceBounds(spec, optionCount)
    require(targets.size in bounds) {
        "CR 601.2c: $describe demands ${bounds.first}..${bounds.last} target(s), got ${targets.size}: $targets"
    }
    require(targets.distinct().size == targets.size) {
        "CR 601.2c: the same target can't be chosen twice for one instance of the word 'target', " +
            "but $describe chose $targets"
    }
}
