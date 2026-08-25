package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.PersistentMap

/*
 * The CR 613 continuous-effect algorithm (docs/design/layer-system.md §3): apply active continuous
 * effects to an object's printed base, layer by layer 1→7 and sublayer 7a→7d, in timestamp order
 * within a layer. The full ordered spine is real even though the pinned pool populates only two
 * stages — layer 6 (ability adding) and sublayer 7c (P/T modification that doesn't set) — so any
 * effect that would land elsewhere fails loudly rather than being silently dropped (§1).
 *
 * **Counters (CR 122) enter at those same two stages, not at a stage of their own.** CR 613.4c is
 * explicit that sublayer 7c applies "effects *and counters* that modify power and/or toughness", and
 * CR 122.1b routes a keyword counter through CR 613.1f, layer 6, exactly like a granted keyword. They
 * reach the walk differently from an effect — a counter lives on the affected [dev.mtgplay.core.state.GameObject]
 * itself, not on some source permanent's static ability, so it is threaded in as its own argument
 * rather than as an [ActiveEffect] with a timestamp. CR 613.7 gives counters no timestamp of their
 * own, and within 7c every contribution is an addition, so where in the sublayer they land is
 * unobservable; they are applied after that sublayer's effects for definiteness.
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
 *
 * **The sublayer citations were off by one until `FW-COUNTERS` and are now checked against the
 * Comprehensive Rules text of 2026-08-19.** CR 613.4 has exactly four lettered sublayers, a–d:
 * 613.4a is 7a (characteristic-defining P/T), 613.4b is 7b (setting), **613.4c is 7c ("Effects *and
 * counters* that modify power and/or toughness")**, and **613.4d is 7d (switching power and
 * toughness)**. **There is no rule 613.4e.** The enum previously named a `PT_COUNTERS` sublayer 7d
 * citing a nonexistent 613.4e, and every sublayer citation from 7a down was shifted one letter to
 * make room for it. Counters were never their own sublayer; 7d is switching, which no card in the
 * gauntlet pool does, so that stage keeps its place on the spine and its loud gate under its real
 * name, [PT_SWITCHING].
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

    /** Sublayer 7a — characteristic-defining P/T (CR 613.4a). Unpopulated (no `*` P/T in the pool). */
    PT_CHARACTERISTIC_DEFINING,

    /** Sublayer 7b — P/T setting effects (CR 613.4b). Unpopulated (no "becomes a 1/1"). */
    PT_SETTING,

    /**
     * Sublayer 7c — effects **and counters** that modify P/T without setting it (CR 613.4c).
     * Populated twice over: additive +X/+Y from Aura statics, and the [Counter.PowerToughness]
     * counters on the object itself (CR 122.1a).
     */
    PT_MODIFYING,

    /**
     * Sublayer 7d — effects that **switch** a creature's power and toughness (CR 613.4d).
     * Unpopulated: no card in the gauntlet pool switches P/T. Not the counters slot — see the
     * citation note on [Layer].
     */
    PT_SWITCHING,
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
    counters: PersistentMap<Counter, Int>,
): LayeredCharacteristics {
    active.forEach(::requireImplementedKind)
    val byTimestamp = active.sortedBy(ActiveEffect::timestamp)
    return Layer.entries.fold(base) { acc, layer ->
        applyLayer(state, acc, layer, byTimestamp.filter { layer in layersOf(it.effect) }, counters)
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
    counters: PersistentMap<Counter, Int>,
): LayeredCharacteristics =
    when (layer) {
        // CR 613.1f: layer 6 unions granted keywords and mana abilities onto the object, then the
        // keywords the object's own keyword counters grant it (CR 122.1b).
        Layer.ABILITY_ADDING ->
            effects
                .fold(acc) { current, active -> current.granting(active.effect) }
                .grantingKeywordCounters(counters)
        // CR 613.4c: sublayer 7c adds the (possibly dynamic) P/T modifiers, then the object's own
        // P/T counters (CR 122.1a) — the sublayer the rule names for both.
        Layer.PT_MODIFYING ->
            effects
                .fold(acc) { current, active -> current.modifying(state, active) }
                .modifiedByCounters(counters)
        Layer.COPY, Layer.CONTROL, Layer.TEXT, Layer.TYPE, Layer.COLOR,
        Layer.PT_CHARACTERISTIC_DEFINING, Layer.PT_SETTING, Layer.PT_SWITCHING,
        -> {
            require(effects.isEmpty()) {
                "CR 613: continuous effects in $layer are not implemented in the MVP pool " +
                    "(docs/design/layer-system.md §1); refusing to silently drop " +
                    effects.map(ActiveEffect::source)
            }
            acc
        }
    }

/**
 * Layer 6 (CR 613.1f, CR 122.1b): unions onto the object the keywords its own keyword counters
 * grant it. Unconditional — a keyword counter grants its keyword to any object, creature or not
 * (CR 122.1b names permanents *and* cards in other zones), so there is nothing here to gate on a
 * P/T box the way [modifiedByCounters] must.
 */
private fun LayeredCharacteristics.grantingKeywordCounters(
    counters: PersistentMap<Counter, Int>,
): LayeredCharacteristics {
    val granted = counters.keys.filterIsInstance<Counter.KeywordCounter>().map(Counter.KeywordCounter::keyword)
    return if (granted.isEmpty()) this else copy(keywords = keywords.addingAll(granted))
}

/**
 * Sublayer 7c (CR 613.4c, CR 122.1a): adds the object's own `+X/+Y` counters to its power and
 * toughness — N counters of a kind contribute N times that kind's components.
 *
 * Fails loudly on an object with P/T counters and no P/T box. CR 122.1a would leave such an object
 * with nothing to modify (a `+1/+1` counter on a noncreature artifact is real and inert until the
 * artifact becomes a creature), but nothing in the gauntlet pool can place one — the card that
 * does, Kenku Artificer, needs the layer-4 type change and layer-7b P/T setting that would give the
 * artifact a P/T box in the first place, and neither layer is implemented. Silently ignoring the
 * counters would make that card look encodable when it is not.
 */
private fun LayeredCharacteristics.modifiedByCounters(counters: PersistentMap<Counter, Int>): LayeredCharacteristics {
    var powerDelta = 0
    var toughnessDelta = 0
    for ((kind, count) in counters) {
        if (kind !is Counter.PowerToughness) continue
        powerDelta += kind.power * count
        toughnessDelta += kind.toughness * count
    }
    if (powerDelta == 0 && toughnessDelta == 0) return this
    val basePower = power
    val baseToughness = toughness
    require(basePower != null && baseToughness != null) {
        "CR 613.4c / CR 122.1a: an object with no printed power/toughness carries P/T counters " +
            "($counters); only a creature has power and toughness to modify, and no implemented " +
            "layer can make this object one"
    }
    return copy(power = basePower + powerDelta, toughness = baseToughness + toughnessDelta)
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
 * Sublayer 7c (CR 613.4c): adds [active]'s power/toughness modifiers, evaluating a dynamic magnitude
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
        "CR 613.4c: a layer-7c P/T modifier from ${active.source} applies to an object with no " +
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
