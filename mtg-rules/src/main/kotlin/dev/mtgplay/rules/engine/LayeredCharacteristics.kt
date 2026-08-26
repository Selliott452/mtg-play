package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Quality
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.StaticCondition
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
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
 * @property evasions the layered block-legality restrictions (CR 509.1b): printed evasions unioned
 *   with layer-6 grants active on the object — how Gingerbrute gains "can't be blocked except by
 *   creatures with haste" for a turn from its own activated ability. Combat reads this rather than
 *   the printed set, which is the change the keyword-tail packet had to make before any evasion could
 *   be granted at all: [dev.mtgplay.rules.engine.eligibleBlockPairings] previously read evasions
 *   straight off the definition registry, bypassing CR 613 entirely.
 * @property cardTypes the layered card types (CR 205.2, CR 613 layer 4): printed card types unioned
 *   with every layer-4 addition active on the object — how a Kenku-Artificer'd Sky Skiff is an
 *   artifact **creature** while staying an artifact (CR 205.1b). Added by `FW-TYPECHANGE`; before it
 *   there was nowhere for a type-changing effect to write, which is the real reason `Layer.TYPE`
 *   stood declared-but-refused for four packets.
 * @property subtypes the layered subtypes (CR 205.3, CR 613 layer 4): printed subtypes unioned with
 *   every layer-4 addition — the "Homunculus" in "becomes a 0/0 Homunculus artifact creature".
 *
 *   **Read it through [hasSubtype], not directly**, at every battlefield site: this set is the printed
 *   and layer-4 half only, and CR 702.73a changeling is a *layer-6 ability* that no set of subtype
 *   words can carry. The two halves meet in that one seam and nowhere else.
 */
data class LayeredCharacteristics(
    val power: Int?,
    val toughness: Int?,
    val keywords: PersistentSet<Keyword>,
    val manaAbilities: PersistentList<ManaAbility>,
    val protections: PersistentSet<Quality> = persistentSetOf(),
    val evasions: PersistentSet<Evasion> = persistentSetOf(),
    val cardTypes: PersistentSet<CardType> = persistentSetOf(),
    val subtypes: PersistentSet<Subtype> = persistentSetOf(),
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
    // CR 613.1/CR 718.3b: the layer walk starts from the object's *base* characteristics, which for a
    // permanent that was cast prototyped is the card's alternative set rather than its printed one
    // (`W9-G`). Prototype is a copiable value (CR 718.2a), not a continuous effect, so it belongs here
    // in the base rather than in any layer.
    val printed = baseCharacteristics(state, obj)
    val base =
        LayeredCharacteristics(
            power = printed?.powerToughness?.power,
            toughness = printed?.powerToughness?.toughness,
            keywords = printed?.keywords ?: persistentSetOf(),
            manaAbilities = definition?.manaAbilities ?: persistentListOf(),
            protections = printed?.protections ?: persistentSetOf(),
            evasions = printed?.evasions ?: persistentSetOf(),
            cardTypes = printed?.cardTypes ?: persistentSetOf(),
            subtypes = printed?.subtypes ?: persistentSetOf(),
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

/**
 * The CR 604.3 static half of [activeEffectsOn]: the static abilities of battlefield permanents whose
 * affected set is non-empty and names [affectedId], and whose "as long as …" condition — if they have
 * one — currently holds.
 *
 * **The condition is evaluated here, on every read, and that is CR 604.3 rather than an optimisation**
 * (`FW-CONDSTATIC`). A conditional static ability's effect applies exactly while its condition is
 * true, with no trigger, no stack and no player receiving priority in between — so Goblin Tomb
 * Raider's haste appears the instant an artifact enters and is gone the instant the last one leaves.
 * Because characteristics are computed on read and never cached (docs/design/layer-system.md §5), that
 * continuity costs exactly this one filter and no invalidation machinery.
 */
private fun staticEffectsOn(
    state: GameState,
    affectedId: ObjectId,
): List<ActiveEffect> =
    state.sharedZones.battlefield.flatMap { source ->
        val definition = state.definitions[source.card] ?: return@flatMap emptyList()
        definition.staticContinuousEffects.mapNotNull { effect ->
            val affected = affectedObjectOf(state, source, effect.affects) ?: return@mapNotNull null
            if (affected != affectedId || !conditionHolds(state, source, effect.condition)) {
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
                    // CR 613.1d: a static ability may change types too (`W10-C`) — the Spacecraft that
                    // "is an artifact creature at 7+" while its condition holds.
                    addedCardTypes = effect.addedCardTypes,
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
 *
 * **This is still the only generator of a CR 613 layer-7b set-P/T, and is no longer the only generator
 * of a layer-4 type change** (`FW-TYPECHANGE`, corrected by `W10-C`). That packet's note here predicted
 * the correction almost word for word — "the day a card prints … the static declaration gains the
 * fields and [staticEffectsOn] threads them exactly as this does — nothing below [ActiveEffect]
 * changes" — and the prediction held exactly: the field was added to
 * [dev.mtgplay.core.definition.StaticContinuousEffect], `staticEffectsOn` passes it through, and not one
 * line of `Layers.kt` or `LayerApplication.kt` moved.
 *
 * What the old note got *wrong* is worth recording rather than deleting, because it was a claim about
 * the pool and not about the engine: it said every type change in the gauntlet is printed on a resolving
 * ability. Pinnacle Kill-Ship's is printed on the permanent — "it's an artifact creature at 7+" — and
 * nothing resolves, nothing goes on the stack, and no player gets priority when the seventh counter
 * lands (CR 604.3). A resolution-generated encoding of that line would have been a card that became a
 * creature once and stayed one.
 *
 * Set-P/T remains timed-only, and for the *stated* reason rather than by omission: a Spacecraft's 7/7 is
 * **printed on the card** (CR 208.1b), so the type change alone gives it a P/T box and there is nothing
 * for a static 7b effect to set.
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
                grantedEvasions = effect.modification.grantedEvasions,
                powerMod = Magnitude.Fixed(effect.modification.powerMod),
                toughnessMod = Magnitude.Fixed(effect.modification.toughnessMod),
                addedCardTypes = effect.modification.addedCardTypes,
                addedSubtypes = effect.modification.addedSubtypes,
                setPower = effect.modification.setPower,
                setToughness = effect.modification.setToughness,
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
        // CR 611.2c: a permanent whose static ability modifies only itself. The walk that reaches here
        // is over battlefield permanents, so the source is present by construction and the set is
        // never empty — the one way [AffectedSet.Self] is simpler than [AffectedSet.Enchanted].
        AffectedSet.Self -> source.id
    }

/**
 * Whether [source]'s conditional static ability is currently active (CR 604.3): vacuously true for an
 * unconditional ability (a `null` condition), otherwise the board-state question the condition asks.
 * Exhaustive over [StaticCondition] so a new condition shape breaks compilation — the specific silent
 * failure being guarded against is a new condition falling through to "true", which turns a
 * conditional ability into an unconditional one that still looks right in every log.
 *
 * "You" is the source's controller, which is its owner across this pool (no layer-2 control-changing
 * effect exists, docs/design/layer-system.md §4). The count runs through the one shared
 * [countMatchingPermanents] seam rather than a private scan, so a conditional static and Gingerbread
 * Cabin's CR 614.1c "unless you control three or more other Forests" cannot disagree about what
 * counts.
 */
private fun conditionHolds(
    state: GameState,
    source: GameObject,
    condition: StaticCondition?,
): Boolean =
    when (condition) {
        null -> true
        is StaticCondition.YouControl ->
            countMatchingPermanents(state, condition.filter, controllerOf(source)) >= condition.atLeast
        // CR 604.3 with CR 122.6: the source's own counters, counted on every read. The count is taken
        // off the [GameObject] rather than through the layer system on purpose — counters are state, not
        // characteristics, so nothing here can recurse back into the walk that called it.
        is StaticCondition.CountersOnSelf -> source.counterCount(condition.counter) >= condition.atLeast
    }

/** The controller of [source] (CR 108.4); ownership across this pool. */
private fun controllerOf(source: GameObject): PlayerId = source.owner
