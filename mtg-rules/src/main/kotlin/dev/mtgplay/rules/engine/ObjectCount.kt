package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CountScope
import dev.mtgplay.core.definition.ObjectPredicate
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Evaluation of the declarative [ObjectPredicate] noun from `mtg-core` (docs/design/cost-modification.md
 * §6): one predicate language, one counting function, and deliberately **no** shared consumer.
 *
 * The design note's §6 argument for stopping here: six consumers in the pool want a count, and they
 * read it at four incompatible moments — locked in at CR 601.2f (cost reductions), once on resolution
 * (CR 608.2h), live on every characteristic read (CR 613.5, the layer system's `Magnitude.Dynamic`),
 * and unfrozen mid-payment (CR 605.2, the Urza lands). Collapsing those into one count-and-consume
 * abstraction would make the wrong read semantics the easy default, and the wrong semantics does not
 * crash — it silently produces a plausible wrong game (PLAN.md §7). So this file extracts the *noun*
 * and leaves each consumer to name its own read point in its own type.
 */

/**
 * The number of objects in [seat]'s [scope] that match [predicate], excluding [excluding].
 *
 * [excluding] is the object being cast (CR 601.2a): by the time a total cost is determined the card
 * has left its source zone, so it must not count itself. Naming it makes the *gathering-time* answer
 * — taken while the card is still in the graveyard — equal the *execution-time* answer, taken once it
 * is on the stack, which is the property that keeps legality, request derivation, and execution
 * pricing identically (design note §2.3). Pass `null` where there is no such object.
 *
 * Control is ownership across the MVP pool: no layer-2 control-changing effect exists.
 */
internal fun countMatching(
    state: GameState,
    seat: PlayerId,
    scope: CountScope,
    predicate: ObjectPredicate,
    excluding: ObjectId? = null,
): Int =
    objectsIn(state, seat, scope).count { it.id != excluding && matches(state, it, predicate, scope.isBattlefield) }

/** The objects [scope] names for [seat], in zone order (CR 400.1). */
private fun objectsIn(
    state: GameState,
    seat: PlayerId,
    scope: CountScope,
): List<GameObject> =
    when (scope) {
        // CR 403: control is ownership in the MVP pool.
        CountScope.BATTLEFIELD_YOU_CONTROL -> state.sharedZones.battlefield.filter { it.owner == seat }
        CountScope.YOUR_GRAVEYARD -> state.player(seat).graveyard
    }

/**
 * Whether [scope] names objects on the **battlefield**, and therefore objects CR 613 reaches. The
 * `when` is exhaustive so a new scope must state which side of the layer boundary it falls on rather
 * than inheriting an answer.
 */
private val CountScope.isBattlefield: Boolean
    get() =
        when (this) {
            CountScope.BATTLEFIELD_YOU_CONTROL -> true
            CountScope.YOUR_GRAVEYARD -> false
        }

/**
 * Whether [obj] satisfies [predicate] (CR 109.3), read from its **in-game** characteristics when it is
 * on the battlefield ([onBattlefield]) and from its printed ones everywhere else.
 *
 * **The split is CR 613's own scope, not a compromise.** CR 613 applies to objects on the battlefield;
 * a card in a graveyard has its printed types and nothing else, so a layered read there would have
 * nothing extra to say and no battlefield object to compute from. This function used to read printed in
 * both cases with a KDoc promising a reroute "when the first type-changing effect arrives"; it has
 * arrived (`FW-TYPECHANGE`), and the battlefield half now goes through [effectiveCardTypes] and
 * [hasSubtype]. The observable consequence is that an affinity-style count of "artifacts you control"
 * sees a permanent that *became* an artifact.
 *
 * An object whose card has no definition matches nothing: an inert card (architect decision, P2.1) is
 * legal to hold and to mill, and counting it as an artifact because nobody said otherwise would be
 * exactly the silent wrong answer this framework is built to avoid.
 */
private fun matches(
    state: GameState,
    obj: GameObject,
    predicate: ObjectPredicate,
    onBattlefield: Boolean,
): Boolean {
    val characteristics = state.definitions[obj.card]?.characteristics ?: return false
    return when (predicate) {
        ObjectPredicate.Anything -> true
        is ObjectPredicate.HasCardType ->
            if (onBattlefield) {
                predicate.cardType in effectiveCardTypes(state, obj.id)
            } else {
                predicate.cardType in characteristics.cardTypes
            }
        // CR 702.73a: changeling works in every zone, which is why the off-battlefield answer is the
        // printed characteristics' own subtype accessor rather than the raw set — a Shapeshifter in a
        // graveyard is a Human there too. On the battlefield the same accessor is reached through
        // [hasSubtype], which additionally unions the layer-4 additions and the layer-6 changeling grant.
        is ObjectPredicate.HasSubtype ->
            if (onBattlefield) {
                hasSubtype(state, obj.id, predicate.subtype)
            } else {
                characteristics.hasSubtype(predicate.subtype)
            }
        is ObjectPredicate.Not -> !matches(state, obj, predicate.predicate, onBattlefield)
        is ObjectPredicate.And -> predicate.predicates.all { matches(state, obj, it, onBattlefield) }
        is ObjectPredicate.AnyOf -> predicate.predicates.any { matches(state, obj, it, onBattlefield) }
    }
}
