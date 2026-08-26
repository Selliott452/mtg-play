package dev.mtgplay.acceptance

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.TimedContinuousEffect

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
 * @property cardTypes the printed card types unioned with every layer-4 addition (CR 613.1d).
 * @property subtypes the printed subtypes unioned with every layer-4 addition (CR 613.1d).
 */
internal data class OracleCharacteristics(
    val power: Int?,
    val toughness: Int?,
    val keywords: Set<Keyword>,
    val manaAbilities: List<ManaAbility>,
    val cardTypes: Set<CardType> = emptySet(),
    val subtypes: Set<Subtype> = emptySet(),
)

/**
 * The naive oracle's characteristics for the battlefield object [id] (CR 613, docs/design/layer-system.md
 * §8): printed base, then every Aura attached to [id] adds its additive P/T modifier and unions its
 * keyword/mana grants. The affected set is [dev.mtgplay.core.definition.AffectedSet.Enchanted] — the one
 * object an Aura is attached to (CR 611.2c) — and at a game pause a dangling Aura is illegal (CR 704.5m),
 * so an Aura whose `attachedTo` names this battlefield object is by construction active. Deliberately
 * independent of the layer engine: it walks attachment and sums/unions itself, referencing no CR 613
 * classification code.
 *
 * `FW-DURATION` adds the second generator (CR 611.2, docs/design/duration.md §5.2): the running
 * [dev.mtgplay.core.state.GameState.timedEffects] naming [id]. The oracle's independence rule is
 * kept — it reads the store's already-snapshotted integers and still references no classification
 * code — and the two generators are summed the same way, which is the property the engine must also
 * have.
 *
 * `FW-TYPECHANGE` adds the layer-4 union and the sublayer-7b set, and they are the first contributions
 * the oracle cannot compute in one pass over an unordered pile. Setting must happen **before** the
 * additive 7c work or a set-then-pumped creature comes out at the set value, so the walk below is two
 * passes over the same store rather than one: pass one applies every type addition and every set-P/T,
 * pass two adds every modifier. That is the CR 613.4b/4c ordering restated in the crudest possible form,
 * which is exactly what an oracle is for — if the engine ever collapses the two sublayers, the pile
 * still says 3/3 and the engine says 0/0.
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
    val timed = state.timedEffects.filter { it.affected == id }
    val base =
        OracleCharacteristics(
            power = printed?.powerToughness?.power,
            toughness = printed?.powerToughness?.toughness,
            keywords = printed?.keywords?.toSet() ?: emptySet(),
            manaAbilities = definition?.manaAbilities?.toList() ?: emptyList(),
            cardTypes = printed?.cardTypes?.toSet() ?: emptySet(),
            subtypes = printed?.subtypes?.toSet() ?: emptySet(),
        )
    // The three stages in CR 613 order, as three passes over the same data rather than one fused walk.
    // Splitting them is the oracle's method, not a concession to a complexity budget: the *order* is the
    // claim being checked, so it has to be visible on the page.
    return base
        .withTypesAndSetPowerToughness(timed)
        .withAttachedAuras(state, id)
        .withTimedModifiers(timed)
}

/**
 * CR 613.1d (layer 4) and CR 613.4b (sublayer 7b) from the timed store: union every added type, then
 * overwrite P/T with every set. Both run before any additive contribution, which is the ordering the
 * engine must independently reproduce.
 */
private fun OracleCharacteristics.withTypesAndSetPowerToughness(
    timed: List<TimedContinuousEffect>,
): OracleCharacteristics {
    var result = this
    for (effect in timed) {
        result =
            result.copy(
                cardTypes = result.cardTypes + effect.modification.addedCardTypes,
                subtypes = result.subtypes + effect.modification.addedSubtypes,
            )
    }
    for (effect in timed) {
        result =
            result.copy(
                power = effect.modification.setPower ?: result.power,
                toughness = effect.modification.setToughness ?: result.toughness,
            )
    }
    return result
}

/**
 * The Aura generator (CR 604.3): every Aura attached to [id] adds its additive P/T modifier and unions
 * its keyword and mana grants.
 *
 * A P/T modifier applies only to an object that has a P/T box by this point; on a non-creature it
 * contributes nothing (the enchant restrictions keep P/T Auras on creatures, so a nonzero modifier never
 * lands there — the engine loud-fails if one ever did).
 */
private fun OracleCharacteristics.withAttachedAuras(
    state: GameState,
    id: ObjectId,
): OracleCharacteristics {
    var result = this
    for (aura in state.sharedZones.battlefield.filter { it.attachedTo == id }) {
        val auraDefinition = state.definitions[aura.card] ?: continue
        for (effect in auraDefinition.staticContinuousEffects) {
            result =
                result.copy(
                    keywords = result.keywords + effect.grantedKeywords,
                    manaAbilities = result.manaAbilities + effect.grantedManaAbilities,
                    power = result.power?.plus(evaluateMagnitudeNaively(effect.powerMod, state, aura.id)),
                    toughness =
                        result.toughness?.plus(evaluateMagnitudeNaively(effect.toughnessMod, state, aura.id)),
                )
        }
    }
    return result
}

/**
 * CR 611.2's additive half: the running resolution-generated effects naming this object. Their modifiers
 * were snapshotted at creation (CR 608.2h, CR 611.2d), so the oracle adds the stored integers with no
 * evaluation at all — exactly the semantics the engine must have (docs/design/duration.md §3). An effect
 * whose object has left the battlefield names a different id and was filtered out by the caller, never by
 * a special case.
 */
private fun OracleCharacteristics.withTimedModifiers(timed: List<TimedContinuousEffect>): OracleCharacteristics {
    var result = this
    for (effect in timed) {
        result =
            result.copy(
                keywords = result.keywords + effect.modification.grantedKeywords,
                power = result.power?.plus(effect.modification.powerMod),
                toughness = result.toughness?.plus(effect.modification.toughnessMod),
            )
    }
    return result
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
