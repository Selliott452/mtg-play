package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState

/*
 * The CR 613 continuous-effect algorithm (docs/design/layer-system.md §3): apply active continuous
 * effects to an object's printed base, layer by layer 1→7 and sublayer 7a→7d, in timestamp order
 * within a layer. The full ordered spine is real even though the pinned pool populates only two
 * stages — layer 6 (ability adding) and sublayer 7c (P/T modification that doesn't set) — so any
 * effect that would land elsewhere fails loudly rather than being silently dropped (§1).
 *
 * Timestamps are the source permanent's battlefield-entry order (its fresh ObjectId, CR 400.7): an
 * MVP Aura enters already attached (CR 303.4f) and never re-attaches, so "became attached"
 * (CR 613.7c) and "entered" coincide, and entry order is ObjectId order — monotonic and replay-safe
 * (§3). The sort is performed (the 613.7 spine is real) even though every within-layer MVP
 * interaction commutes (additive grants and additive modifiers), so the order is not yet observable.
 *
 * Dependency ordering (CR 613.8) is deferred and correct-by-construction: no interaction in the pool
 * creates a dependency (only additive layer-6 grants and additive 7c modifiers exist, §3), so a
 * dependency-inducing effect kind is off the implemented list and is refused by the same loud gate
 * as every other unimplemented kind.
 */

/**
 * The CR 613.1 / 613.3 layers, in application order. Layers 1→7, with layer 7 split into sublayers
 * 7a→7d. Only [ABILITY_ADDING] (layer 6) and [PT_MODIFYING] (sublayer 7c) are populated in the MVP
 * pool; the rest are ordered stages the algorithm walks and the loud gate keeps empty.
 */
internal enum class Layer {
    /** Layer 1 — copy effects (CR 613.2). Unpopulated in the MVP pool. */
    COPY,

    /** Layer 2 — control-changing effects (CR 613.1b). Unpopulated; the GameObject KDoc's slot. */
    CONTROL,

    /** Layer 3 — text-changing effects (CR 613.1c). Unpopulated. */
    TEXT,

    /** Layer 4 — type-changing effects (CR 613.1d). Unpopulated. */
    TYPE,

    /** Layer 5 — color-changing effects (CR 613.1e). Unpopulated. */
    COLOR,

    /** Layer 6 — ability adding/removing (CR 613.1f). Populated: additive keyword/mana grants. */
    ABILITY_ADDING,

    /** Sublayer 7a — characteristic-defining P/T (CR 613.4b). Unpopulated (no `*` P/T in the pool). */
    PT_CHARACTERISTIC_DEFINING,

    /** Sublayer 7b — P/T setting effects (CR 613.4c). Unpopulated (no "becomes a 1/1"). */
    PT_SETTING,

    /** Sublayer 7c — P/T modifiers that don't set (CR 613.4d). Populated: additive +X/+Y. */
    PT_MODIFYING,

    /** Sublayer 7d — P/T changes from counters (CR 613.4e). Unpopulated (no +1/+1 counters). */
    PT_COUNTERS,
}

/**
 * One active continuous effect: the [effect] a battlefield [source] generates, applied to the
 * [affected] object, with [timestamp] the source's battlefield-entry order (CR 613.7c). Collected
 * by [activeEffectsOn].
 */
internal data class ActiveEffect(
    val source: ObjectId,
    val affected: ObjectId,
    val effect: StaticContinuousEffect,
    val timestamp: Long,
)

/**
 * The CR 613 layers an [effect] contributes to (docs/design/layer-system.md §2). The classification
 * point: a keyword or mana-ability grant is layer 6; a nonzero P/T modifier is sublayer 7c. A new
 * effect kind (copy, control, type-change, set-P/T, counters) adds its field here and routes to its
 * layer, where [applyLayer]'s loud gate then refuses it until that layer is implemented.
 *
 * A [Magnitude.Dynamic] modifier always contributes to 7c even if it currently evaluates to zero:
 * the layer *contribution* exists; its magnitude is read live (CR 613.3c).
 */
internal fun layersOf(effect: StaticContinuousEffect): Set<Layer> =
    buildSet {
        if (effect.grantedKeywords.isNotEmpty() || effect.grantedManaAbilities.isNotEmpty()) {
            add(Layer.ABILITY_ADDING)
        }
        if (effect.powerMod != Magnitude.Zero || effect.toughnessMod != Magnitude.Zero) {
            add(Layer.PT_MODIFYING)
        }
    }

/**
 * Applies the active continuous effects [active] to the printed [base] characteristics of an object,
 * layer by layer (CR 613.1, 613.3). Every effect must classify into an implemented layer first
 * (the loud gate: an effect that produces no layer-6 grant and no layer-7c modifier is an
 * unimplemented kind and must not be silently dropped — §1); then the ordered spine is walked, each
 * layer receiving its own contributing effects in timestamp order (CR 613.7).
 */
internal fun applyContinuousEffects(
    state: GameState,
    base: LayeredCharacteristics,
    active: List<ActiveEffect>,
): LayeredCharacteristics {
    active.forEach(::requireImplementedKind)
    val byTimestamp = active.sortedBy(ActiveEffect::timestamp)
    return Layer.entries.fold(base) { acc, layer ->
        applyLayer(state, acc, layer, byTimestamp.filter { layer in layersOf(it.effect) })
    }
}

/**
 * Applies the [effects] contributing to one [layer] to [acc], in the order given (already
 * timestamp-sorted by [applyContinuousEffects]). Exhaustive over [Layer]: the two populated stages
 * act; every unpopulated stage is a loud gate that refuses any effect classified there (CR 613 —
 * unimplemented in the MVP pool, docs/design/layer-system.md §1), collapsing the CR 613.8 dependency
 * gate into the same refusal. Internal so the gate is unit-testable directly.
 */
internal fun applyLayer(
    state: GameState,
    acc: LayeredCharacteristics,
    layer: Layer,
    effects: List<ActiveEffect>,
): LayeredCharacteristics =
    when (layer) {
        // CR 613.1f: layer 6 unions granted keywords and mana abilities onto the object.
        Layer.ABILITY_ADDING -> effects.fold(acc) { current, active -> current.granting(active.effect) }
        // CR 613.4d: sublayer 7c adds the (possibly dynamic) P/T modifiers.
        Layer.PT_MODIFYING -> effects.fold(acc) { current, active -> current.modifying(state, active) }
        Layer.COPY, Layer.CONTROL, Layer.TEXT, Layer.TYPE, Layer.COLOR,
        Layer.PT_CHARACTERISTIC_DEFINING, Layer.PT_SETTING, Layer.PT_COUNTERS,
        -> {
            require(effects.isEmpty()) {
                "CR 613: continuous effects in $layer are not implemented in the MVP pool " +
                    "(docs/design/layer-system.md §1); refusing to silently drop " +
                    effects.map(ActiveEffect::source)
            }
            acc
        }
    }

/** The loud gate: an effect must contribute to an implemented layer (6 or 7c), never nothing. */
private fun requireImplementedKind(active: ActiveEffect) {
    require(layersOf(active.effect).isNotEmpty()) {
        "CR 613: the static continuous effect from ${active.source} classifies into no implemented " +
            "layer (a layer-6 grant or a layer-7c P/T modifier); an unimplemented effect kind must " +
            "fail loudly, never silently drop (docs/design/layer-system.md §1)"
    }
}

/** Layer 6 (CR 613.1f): unions [effect]'s granted keywords and mana abilities onto the object. */
private fun LayeredCharacteristics.granting(effect: StaticContinuousEffect): LayeredCharacteristics =
    copy(
        keywords = keywords.addingAll(effect.grantedKeywords),
        manaAbilities = manaAbilities.addingAll(effect.grantedManaAbilities),
    )

/**
 * Sublayer 7c (CR 613.4d): adds [active]'s power/toughness modifiers, evaluating a dynamic magnitude
 * against the current [state] (CR 613.3c). Fails loudly if the object has no P/T box — a 7c modifier
 * on a non-creature is an engine defect (the enchant restrictions keep P/T Auras on creatures).
 */
private fun LayeredCharacteristics.modifying(
    state: GameState,
    active: ActiveEffect,
): LayeredCharacteristics {
    val basePower = power
    val baseToughness = toughness
    require(basePower != null && baseToughness != null) {
        "CR 613.4d: a layer-7c P/T modifier from ${active.source} applies to an object with no " +
            "printed power/toughness; only creatures have P/T"
    }
    return copy(
        power = basePower + evaluateMagnitude(active.effect.powerMod, state, active.source),
        toughness = baseToughness + evaluateMagnitude(active.effect.toughnessMod, state, active.source),
    )
}

/** The value of [magnitude] now (CR 613.3c): a fixed amount, or a dynamic pure function of state. */
private fun evaluateMagnitude(
    magnitude: Magnitude,
    state: GameState,
    source: ObjectId,
): Int =
    when (magnitude) {
        is Magnitude.Fixed -> magnitude.amount
        is Magnitude.Dynamic -> magnitude.evaluate(state, source)
    }
