package dev.mtgplay.acceptance

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState

/*
 * The brute-force layer oracle (P4.3, docs/design/layer-system.md §8), in the mana-payment.md oracle
 * house style: an independent, deliberately naive recomputation of a battlefield object's in-game
 * characteristics (CR 613), set-compared to the engine's [dev.mtgplay.rules.engine.layeredCharacteristics]
 * over random boards.
 *
 * **Independence is the whole point.** This file references NONE of the layer engine's CR 613
 * classification code — not `Layer`, `layersOf`, `applyLayer`, `applyContinuousEffects`, or
 * `activeEffectsOn`. It reads only card-definition *data* (the [dev.mtgplay.core.definition.StaticContinuousEffect]
 * declaration and the core [Magnitude]) and computes, from first principles, the design note §8 recipe:
 * printed base + Σ every attached additive P/T modifier + ∪ every grant. A [Magnitude.Dynamic] is
 * evaluated naively against the same live state ([evaluateMagnitudeNaively]); the magnitude is card
 * data — a pure function of state (CR 613.3c, §2) — so evaluating it is not a shared classification
 * path, it is reading the same declaration the engine reads. If the engine mis-attributes an aura,
 * double-counts, or routes a P/T modifier through the keyword set, the two disagree.
 */

/**
 * A battlefield object's in-game characteristics as the naive oracle computes them (CR 613), set-shaped
 * for comparison against the engine (docs/design/layer-system.md §8).
 *
 * @property power the printed power plus every attached additive modifier, or `null` for an object
 *   with no printed P/T box (a non-creature); the oracle, like layer 7c, never invents a P/T box.
 * @property toughness the printed toughness plus every attached additive modifier, or `null`.
 * @property keywords the printed keyword abilities unioned with every attached layer-6 keyword grant.
 * @property manaAbilities the printed mana abilities followed by every attached layer-6 mana grant;
 *   compared as a set (design note §8), so any dedup difference in the engine's union is not material.
 */
internal data class OracleCharacteristics(
    val power: Int?,
    val toughness: Int?,
    val keywords: Set<Keyword>,
    val manaAbilities: List<ManaAbility>,
)

/**
 * The naive oracle's characteristics for the battlefield object [id] (CR 613, docs/design/layer-system.md
 * §8): printed base, then every Aura attached to [id] adds its additive P/T modifier and unions its
 * keyword/mana grants. The affected set is [dev.mtgplay.core.definition.AffectedSet.Enchanted] — the one
 * object an Aura is attached to (CR 611.2c) — and at a game pause a dangling Aura is illegal (CR 704.5m),
 * so an Aura whose `attachedTo` names this battlefield object is by construction active. Deliberately
 * independent of the layer engine: it walks attachment and sums/unions itself, referencing no CR 613
 * classification code.
 */
internal fun oracleCharacteristics(
    state: GameState,
    id: ObjectId,
): OracleCharacteristics {
    val obj =
        state.sharedZones.battlefield.firstOrNull { it.id == id }
            ?: error("oracle: object $id is not on the battlefield (CR 110.1)")
    val definition = state.definitions[obj.card]
    val printed = definition?.characteristics
    var power = printed?.powerToughness?.power
    var toughness = printed?.powerToughness?.toughness
    var keywords: Set<Keyword> = printed?.keywords?.toSet() ?: emptySet()
    var manaAbilities: List<ManaAbility> = definition?.manaAbilities?.toList() ?: emptyList()

    for (aura in state.sharedZones.battlefield.filter { it.attachedTo == id }) {
        val auraDefinition = state.definitions[aura.card] ?: continue
        for (effect in auraDefinition.staticContinuousEffects) {
            keywords = keywords + effect.grantedKeywords
            manaAbilities = manaAbilities + effect.grantedManaAbilities
            // A P/T modifier applies only to an object with a printed P/T box; on a non-creature it
            // contributes nothing (the enchant restrictions keep P/T Auras on creatures, so a nonzero
            // modifier never lands here — the engine loud-fails if one ever did).
            val currentPower = power
            if (currentPower != null) power = currentPower + evaluateMagnitudeNaively(effect.powerMod, state, aura.id)
            val currentToughness = toughness
            if (currentToughness != null) {
                toughness = currentToughness + evaluateMagnitudeNaively(effect.toughnessMod, state, aura.id)
            }
        }
    }
    return OracleCharacteristics(power, toughness, keywords, manaAbilities)
}

/**
 * The value of [magnitude] right now (CR 613.3c): a fixed amount, or a dynamic pure function of the
 * current [state] and the effect's [source]. The trivial `when` over the core [Magnitude] type — not
 * the engine's private evaluator — so the oracle and the additivity property read magnitudes without
 * borrowing any layer-classification code.
 */
internal fun evaluateMagnitudeNaively(
    magnitude: Magnitude,
    state: GameState,
    source: ObjectId,
): Int =
    when (magnitude) {
        is Magnitude.Fixed -> magnitude.amount
        is Magnitude.Dynamic -> magnitude.evaluate(state, source)
    }
