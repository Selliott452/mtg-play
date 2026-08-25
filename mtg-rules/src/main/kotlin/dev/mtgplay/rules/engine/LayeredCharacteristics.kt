package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Quality
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * A battlefield object's in-game characteristics after the CR 613 continuous-effect layer system
 * has been applied (docs/design/layer-system.md) — the single source of truth the combat/SBA
 * accessors and the acceptance rig both read through. Computed on demand ([layeredCharacteristics]),
 * never stored: every read reflects the current state, so a dynamic magnitude tracks the board and a
 * stale read is impossible (CR 613.3c, §5).
 *
 * @property power the layered power (CR 613.3), or `null` for an object with no printed P/T box
 *   (a non-creature); layer 7c never invents a P/T box, whether the modifier came from an effect or
 *   from a counter (CR 122.1a).
 * @property toughness the layered toughness, or `null` for an object with no printed P/T box.
 * @property keywords the layered keyword abilities (CR 702): printed keywords unioned with layer-6
 *   grants active on the object, including the keywords its own keyword counters grant (CR 122.1b).
 * @property manaAbilities the layered tap-for-mana abilities (CR 605.1a): printed abilities followed
 *   by layer-6 grants — how an Abundant-Growth-enchanted land gains "add one mana of any color".
 * @property protections the layered protection abilities, one per quality (CR 702.16): printed
 *   protections unioned with layer-6 grants active on the object — how a Mask-of-Law-and-Grace-
 *   enchanted creature gains protection from black and from red (`FW-PROTECT`). A set, so
 *   CR 702.16m's "multiple instances … are redundant" needs no code.
 */
data class LayeredCharacteristics(
    val power: Int?,
    val toughness: Int?,
    val keywords: PersistentSet<Keyword>,
    val manaAbilities: PersistentList<ManaAbility>,
    val protections: PersistentSet<Quality> = persistentSetOf(),
)

/**
 * The in-game characteristics of the battlefield object [id] (CR 613): its printed base with every
 * active continuous effect applied, layer by layer (docs/design/layer-system.md §3). The one
 * function the three `effective*` combat/SBA accessors and the acceptance rig delegate to, so
 * characteristic computation has a single home. An object with no definition is inert: no keywords,
 * no P/T, no mana abilities. Fails loudly if [id] is not on the battlefield (CR 110.1).
 */
fun layeredCharacteristics(
    state: GameState,
    id: ObjectId,
): LayeredCharacteristics {
    val obj = state.battlefieldObject(id)
    val definition = state.definitions[obj.card]
    val printed = definition?.characteristics
    val base =
        LayeredCharacteristics(
            power = printed?.powerToughness?.power,
            toughness = printed?.powerToughness?.toughness,
            keywords = printed?.keywords ?: persistentSetOf(),
            manaAbilities = definition?.manaAbilities ?: persistentListOf(),
            protections = printed?.protections ?: persistentSetOf(),
        )
    // CR 122.1: the object's own counters are applied by the same walk, at the layers the rules give
    // them — layer 6 for a keyword counter (CR 122.1b), sublayer 7c for a P/T counter (CR 613.4c).
    return applyContinuousEffects(state, base, activeEffectsOn(state, id), obj.counters)
}

/**
 * The layered toughness of the battlefield creature [id] (CR 208.1, CR 613): the public convenience
 * the acceptance lethality invariant and fuzz classification read (docs/design/layer-system.md §5).
 * Fails loudly on an object with no P/T box — only creatures have toughness.
 */
fun layeredToughness(
    state: GameState,
    id: ObjectId,
): Int =
    layeredCharacteristics(state, id).toughness
        ?: error("CR 208.1: object $id has no toughness; only creatures have printed power/toughness")

/**
 * The layered power of the battlefield creature [id] (CR 208.1, CR 613). Fails loudly on an object
 * with no P/T box — only creatures have power.
 */
fun layeredPower(
    state: GameState,
    id: ObjectId,
): Int =
    layeredCharacteristics(state, id).power
        ?: error("CR 208.1: object $id has no power; only creatures have printed power/toughness")

/**
 * Every active continuous effect whose affected object is [affectedId], from **both** generators
 * (docs/design/duration.md §5.2) — this is the effect-collection step docs/design/layer-system.md §2
 * reserved as the duration hook, and taking it is the whole of the layer engine's change.
 *
 * 1. **Static abilities of battlefield permanents** (CR 604.3, CR 611.2): an Aura's "enchanted X
 *    gets/has …", active only while its source is on the battlefield with its affected set
 *    non-empty. An Aura attached to nothing on the battlefield is pending the CR 704.5m fall-off and
 *    contributes nothing.
 * 2. **Resolution-generated effects still running** (CR 611.2): the
 *    [dev.mtgplay.core.state.GameState.timedEffects] store, filtered to those naming [affectedId].
 *    A stored effect whose affected object has left the battlefield contributes nothing and is
 *    **not** an error — a CR 611.2 effect does not end when its object dies, and the object that
 *    comes back is a different one (CR 400.7), so it simply applies to nothing for the rest of its
 *    duration.
 *
 * The two lists are concatenated unsorted; [applyContinuousEffects] does the CR 613.7 ordering, and
 * both timestamps come from one allocation sequence so the sort is meaningful across generators.
 */
internal fun activeEffectsOn(
    state: GameState,
    affectedId: ObjectId,
): List<ActiveEffect> = staticEffectsOn(state, affectedId) + timedEffectsOn(state, affectedId)

/** The CR 604.3 static half of [activeEffectsOn]: attached-and-active Aura statics. */
private fun staticEffectsOn(
    state: GameState,
    affectedId: ObjectId,
): List<ActiveEffect> =
    state.sharedZones.battlefield.flatMap { source ->
        val definition = state.definitions[source.card] ?: return@flatMap emptyList()
        definition.staticContinuousEffects.mapNotNull { effect ->
            val affected = affectedObjectOf(state, source, effect.affects) ?: return@mapNotNull null
            if (affected != affectedId) {
                null
            } else {
                // CR 613.7c: an Aura's timestamp is when it became attached; in the MVP that is its
                // battlefield-entry order, i.e. its fresh ObjectId value (layer-system.md §3).
                ActiveEffect(
                    source = source.id,
                    affected = affected,
                    grantedKeywords = effect.grantedKeywords,
                    grantedManaAbilities = effect.grantedManaAbilities,
                    grantedProtections = effect.grantedProtections,
                    powerMod = effect.powerMod,
                    toughnessMod = effect.toughnessMod,
                    timestamp = source.id.value,
                )
            }
        }
    }

/**
 * The CR 611.2 resolution-generated half of [activeEffectsOn]: the running timed effects naming
 * [affectedId]. Their modifiers were snapshotted when the effect was created (CR 608.2h, CR 611.2d)
 * and are therefore already constants — presented as [dev.mtgplay.core.definition.Magnitude.Fixed],
 * which is the only shape a timed magnitude can take (docs/design/duration.md §3.2).
 */
private fun timedEffectsOn(
    state: GameState,
    affectedId: ObjectId,
): List<ActiveEffect> =
    state.timedEffects
        .filter { it.affected == affectedId }
        .map { effect ->
            ActiveEffect(
                source = effect.source,
                affected = effect.affected,
                grantedKeywords = effect.modification.grantedKeywords,
                powerMod = Magnitude.Fixed(effect.modification.powerMod),
                toughnessMod = Magnitude.Fixed(effect.modification.toughnessMod),
                timestamp = effect.timestamp,
            )
        }

/**
 * The object a [source]'s effect currently affects for the affected set [affects] (CR 611.2c), or
 * `null` when the set is empty. For [AffectedSet.Enchanted] that is the object [source] is attached
 * to, but only while that object is on the battlefield (CR 604.3 — the ability functions only while
 * the source is attached to a legal permanent). Exhaustive over [AffectedSet] so a new selector
 * breaks compilation.
 */
private fun affectedObjectOf(
    state: GameState,
    source: GameObject,
    affects: AffectedSet,
): ObjectId? =
    when (affects) {
        AffectedSet.Enchanted -> {
            val attached = source.attachedTo
            if (attached != null && state.sharedZones.battlefield.any { it.id == attached }) attached else null
        }
    }
