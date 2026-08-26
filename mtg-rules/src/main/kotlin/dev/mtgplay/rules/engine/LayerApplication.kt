package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.PersistentMap

/*
 * What each populated CR 613 layer actually *does* to a [LayeredCharacteristics] — one small function per
 * stage, called by [applyLayer]'s exhaustive `when` in `Layers.kt`.
 *
 * Split out of that file when `FW-TYPECHANGE` populated two more stages and pushed it past detekt's
 * per-file function budget. The split is along the seam the algorithm already had: `Layers.kt` owns the
 * **spine** — which layers exist, in what order, which effect contributes to which, and the loud gate
 * that refuses an unimplemented kind — and this file owns the **arithmetic** of each stage. Neither half
 * can be read as optional: the `when` is still exhaustive over [Layer], so a new stage still fails to
 * compile until it is answered there, and its body still has to live here.
 */

/**
 * Layer 4 (CR 613.1d): unions [active]'s added card types and subtypes onto the object's type line, then
 * removes the card types it takes away.
 *
 * **Addition is a union and never a replacement, which is CR 205.1b rather than a shortcut.** "That
 * artifact becomes a 0/0 Homunculus artifact creature" leaves the permanent an artifact — it gains the
 * creature type and the Homunculus subtype and loses nothing.
 *
 * **Removal is separate, explicit, and printed** (`W10-C`). Bestow says "it's an Aura enchantment and
 * **not a creature**" (CR 702.103a), so the same effect both adds (the Aura subtype, the enchantment
 * type it already has) and removes (creature). It is a second field rather than a "replace the type
 * line" shape because CR 205.1b's default is still union: only the effects that say they remove
 * something do, and a card that merely adds must not be able to express taking anything away.
 *
 * Additive contributions keep the CR 613.7 within-layer order unobservable *within layer 4*. Removal is
 * not additive, and the ordering it needs is stated where it can be checked rather than left to the
 * fold: an effect's removal is applied to that same effect's additions, and two effects still commute
 * unless one adds what the other removes — a pairing no gauntlet card can produce. A type change **can**
 * create a CR 613.8 dependency across layers; the reason that is not yet a problem is narrower than "the
 * effects commute": no continuous effect in the pool selects its affected set by card type at all. See
 * the header note on `Layers.kt`.
 */
internal fun LayeredCharacteristics.retyping(active: ActiveEffect): LayeredCharacteristics =
    copy(
        cardTypes = cardTypes.addingAll(active.addedCardTypes).removingAll(active.removedCardTypes),
        subtypes = subtypes.addingAll(active.addedSubtypes),
    )

/**
 * Sublayer 7b (CR 613.4b): *sets* [active]'s power and/or toughness, overwriting whatever layers 1–7a
 * produced. A `null` component sets nothing and leaves that half as it was.
 *
 * **The one stage allowed to invent a P/T box.** Every other stage refuses an object with no printed
 * power/toughness, because a modifier needs something to modify; a *setting* effect does not, and Kenku
 * Artificer's target is precisely an object that had none until layer 4 made it a creature one stage
 * earlier. So this is where a noncreature artifact acquires the 0/0 that the CR 704.5f state-based
 * action then measures — after sublayer 7c has added its `+1/+1` counters, never before.
 */
internal fun LayeredCharacteristics.settingPowerToughness(active: ActiveEffect): LayeredCharacteristics =
    copy(
        power = active.setPower ?: power,
        toughness = active.setToughness ?: toughness,
    )

/**
 * Layer 6 (CR 613.1f): unions [active]'s granted keywords, mana abilities, protections and evasions
 * onto the object. Every grant is additive, which is what keeps the within-layer order unobservable and
 * the CR 613.8 dependency gate correct-by-construction — a protection grant changes neither whether
 * another effect exists, nor what it applies to, nor what it does (docs/design/protection.md §5).
 */
internal fun LayeredCharacteristics.granting(active: ActiveEffect): LayeredCharacteristics =
    copy(
        keywords = keywords.addingAll(active.grantedKeywords),
        manaAbilities = manaAbilities.addingAll(active.grantedManaAbilities),
        protections = protections.addingAll(active.grantedProtections),
        evasions = evasions.addingAll(active.grantedEvasions),
    )

/**
 * Layer 6 (CR 613.1f, CR 122.1b): unions onto the object the keywords its own keyword counters grant it.
 * Unconditional — a keyword counter grants its keyword to any object, creature or not (CR 122.1b names
 * permanents *and* cards in other zones), so there is nothing here to gate on a P/T box the way
 * [modifiedByCounters] must.
 */
internal fun LayeredCharacteristics.grantingKeywordCounters(
    counters: PersistentMap<Counter, Int>,
): LayeredCharacteristics {
    val granted = counters.keys.filterIsInstance<Counter.KeywordCounter>().map(Counter.KeywordCounter::keyword)
    return if (granted.isEmpty()) this else copy(keywords = keywords.addingAll(granted))
}

/**
 * Sublayer 7c (CR 613.4c): adds [active]'s power/toughness modifiers, evaluating a dynamic magnitude
 * against the current [state] (CR 613.3c). Fails loudly if the object has no P/T box — a 7c modifier
 * on a non-creature is an engine defect (the enchant restrictions keep P/T Auras on creatures, and a
 * "target creature" spec keeps timed pumps there).
 */
internal fun LayeredCharacteristics.modifying(
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
        power = basePower + evaluateMagnitude(active.powerMod, state, active.source),
        toughness = baseToughness + evaluateMagnitude(active.toughnessMod, state, active.source),
    )
}

/**
 * Sublayer 7c (CR 613.4c, CR 122.1a): adds the object's own `+X/+Y` counters to its power and
 * toughness — N counters of a kind contribute N times that kind's components.
 *
 * Fails loudly on an object with P/T counters and no P/T box, and `FW-TYPECHANGE` **sharpened** what
 * that guard is for rather than removing it. CR 122.1a leaves such an object with nothing to modify —
 * a `+1/+1` counter on a noncreature artifact is real and inert — and Kenku Artificer, the one card in
 * the pool that puts counters somewhere that could be true, avoids it by construction: its ability
 * places the three counters and applies the layer-4/7b change *in a single resolution*, so no
 * state-based-action check ever sees the artifact carrying counters without a P/T box. The require is
 * now the assertion that this pairing held. Loosening it to "ignore counters on a typeless object"
 * would turn a broken pairing — counters placed, type change dropped — into a silently smaller
 * creature, which is exactly the plausible wrong game the loud-failure rule exists to prevent.
 */
internal fun LayeredCharacteristics.modifiedByCounters(counters: PersistentMap<Counter, Int>): LayeredCharacteristics {
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
            "($counters); only a creature has power and toughness to modify, and the layer-4 type " +
            "change that would have given it a P/T box did not run"
    }
    return copy(power = basePower + powerDelta, toughness = baseToughness + toughnessDelta)
}

/**
 * The value of [magnitude] now: a fixed amount — which a resolution-generated effect's snapshotted
 * modifier always is (CR 608.2h, CR 611.2d) — or a dynamic pure function of the live state
 * (CR 613.3c). A [Magnitude.Dynamic] needs the generating object, so it is unreachable without one.
 */
private fun evaluateMagnitude(
    magnitude: Magnitude,
    state: GameState,
    source: ObjectId?,
): Int =
    when (magnitude) {
        is Magnitude.Fixed -> magnitude.amount
        is Magnitude.Dynamic ->
            magnitude.evaluate(
                state,
                source ?: error(
                    "CR 613.3c: a dynamic magnitude is a function of its generating object, but the " +
                        "effect records none; only a static ability's magnitude may be dynamic",
                ),
            )
    }
