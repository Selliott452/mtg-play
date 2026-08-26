package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Quality
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The CR 613 continuous-effect algorithm (docs/design/layer-system.md §3): apply active continuous
 * effects to an object's printed base, layer by layer 1→7 and sublayer 7a→7d, in timestamp order
 * within a layer. The full ordered spine is real even though the pinned pool populates only four
 * stages — layer 4 (type changing), layer 6 (ability adding), sublayer 7b (P/T setting) and sublayer
 * 7c (P/T modification that doesn't set) — so any effect that would land elsewhere fails loudly rather
 * than being silently dropped (§1).
 *
 * **Two generators, one algorithm** (`FW-DURATION`, docs/design/duration.md §5.2). A continuous
 * effect comes either from a permanent's *static ability* (CR 604.3) — an Aura's "enchanted creature
 * gets …", active while it is attached — or from the *resolution of a spell or ability* (CR 611.2) —
 * "target creature gets +2/+2 until end of turn", active until its duration expires. [ActiveEffect]
 * is deliberately the shape both collapse to: it carries the layer payload directly rather than
 * wrapping a card's declaration, so neither generator is privileged and nothing below this line
 * knows which one produced an effect.
 *
 * Timestamps come from **one** strictly monotonic sequence, the [dev.mtgplay.core.state.GameState]
 * object-id allocation counter. A static effect's timestamp is its source permanent's
 * battlefield-entry order (its fresh ObjectId, CR 400.7, CR 613.7c — an MVP Aura enters already
 * attached and never re-attaches, so "became attached" and "entered" coincide); a resolution-
 * generated effect's is allocated when it is created (CR 613.7d). Drawing both from one sequence is
 * what makes them comparable at all: two counters would order by which counter a value came from
 * (docs/design/duration.md §4). The sort is performed because the 613.7 spine is real, even though
 * every within-layer interaction in the implemented set commutes (additive grants, additive
 * modifiers), so the order is not yet observable.
 * **Counters (CR 122) enter at those same two stages, not at a stage of their own.** CR 613.4c is
 * explicit that sublayer 7c applies "effects *and counters* that modify power and/or toughness", and
 * CR 122.1b routes a keyword counter through CR 613.1f, layer 6, exactly like a granted keyword. They
 * reach the walk differently from an effect — a counter lives on the affected [dev.mtgplay.core.state.GameObject]
 * itself, not on some source permanent's static ability, so it is threaded in as its own argument
 * rather than as an [ActiveEffect] with a timestamp. CR 613.7 gives counters no timestamp of their
 * own, and within 7c every contribution is an addition, so where in the sublayer they land is
 * unobservable; they are applied after that sublayer's effects for definiteness.
 *
 * **Within-layer order is still unobservable, and `FW-SETPT` narrowed the argument for it.** Layers 4,
 * 6 and 7c contribute additively, so their internal order cannot matter. Sublayer 7b does **not**: two
 * set-P/T effects on one object disagree, and the later timestamp wins (CR 613.7). That is why the sort
 * that was previously performed only because the spine is real is now performed because a stage needs
 * it — the fold applies 7b effects in timestamp order, so the last one written is the last one
 * standing. No gauntlet card can put two set-P/T effects on the same object, so the case is still
 * untaken in play; it is correct rather than merely absent.
 *
 * Dependency ordering (CR 613.8) is deferred, and `FW-TYPECHANGE` is where that deferral stops being
 * free by accident and starts resting on a stated fact. A type change is the classic dependency source:
 * an effect that reads "creature" applies to a different set once layer 4 has run. It creates none here
 * because no continuous effect in the pool selects its affected set by card type —
 * [dev.mtgplay.core.definition.AffectedSet] has two members, `Enchanted` and `Self`, and neither reads
 * a type line, while a *timed* effect's affected object was fixed at CR 611.2c and cannot be re-selected
 * at all. The first affected set that filters on a characteristic is the first true CR 613.8 dependency
 * and must land with the ordering rule, not before it.
 */

/**
 * The CR 613.1 / 613.3 layers, in application order. Layers 1→7, with layer 7 split into sublayers
 * 7a→7d. Four are populated in the gauntlet pool — [TYPE] (layer 4), [ABILITY_ADDING] (layer 6),
 * [PT_SETTING] (sublayer 7b) and [PT_MODIFYING] (sublayer 7c); the rest are ordered stages the
 * algorithm walks and the loud gate keeps empty.
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

    /**
     * Layer 4 — type-changing effects (CR 613.1d). **Populated** since `FW-TYPECHANGE`: card types
     * and subtypes an effect *adds* to an object, unioned onto its printed type line (CR 205.1b —
     * "becomes an artifact creature" keeps every type the permanent already had). Type *removal* is
     * still refused, because no gauntlet card prints it: [ActiveEffect] has no removal field, so a
     * removing effect cannot be constructed rather than being silently ignored.
     */
    TYPE,

    /** Layer 5 — color-changing effects (CR 613.1e). Unpopulated. */
    COLOR,

    /** Layer 6 — ability adding/removing (CR 613.1f). Populated: additive keyword/mana grants. */
    ABILITY_ADDING,

    /** Sublayer 7a — characteristic-defining P/T (CR 613.4a). Unpopulated (no `*` P/T in the pool). */
    PT_CHARACTERISTIC_DEFINING,

    /**
     * Sublayer 7b — P/T **setting** effects (CR 613.4b). **Populated** since `FW-SETPT`: Kenku
     * Artificer's "becomes a 0/0".
     *
     * Its position on the spine is the whole of its correctness. 7b runs strictly before
     * [PT_MODIFYING] (7c), so an artifact set to 0/0 and given three `+1/+1` counters in the same
     * resolution is a 3/3 at the first state-based-action check and survives CR 704.5f; had setting
     * been folded into 7c the two contributions would commute and the card would be a coin flip
     * against its own text.
     *
     * It is also the one stage that can **create** a P/T box where the printed card has none — a
     * noncreature artifact has `null` power and toughness until layer 4 makes it a creature and 7b
     * gives it numbers. Every other stage refuses to invent one.
     */
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
 * One active continuous effect, whichever generator produced it (CR 604.3 or CR 611.2), reduced to
 * the payload the CR 613 layers act on. Collected by [activeEffectsOn].
 *
 * The magnitudes stay [Magnitude]-shaped because a *static* effect's may be
 * [Magnitude.Dynamic] — read live on every computation (CR 613.3c). A *resolution-generated*
 * effect's magnitude is already a frozen integer by the time it reaches here (CR 608.2h, CR 611.2d),
 * and is presented as [Magnitude.Fixed]; that is not a conversion so much as an admission of what it
 * already is (docs/design/duration.md §3.2).
 *
 * @property source the object generating the effect: the Aura, or the resolved ability's source as
 *   last-known information (CR 113.7c). `null` where the engine has none — a timed effect whose
 *   source it never recorded. Diagnostics only; nothing in the algorithm depends on it existing.
 * @property affected the object the effect modifies (CR 611.2c).
 * @property addedCardTypes card types the effect adds in layer 4 (CR 613.1d, CR 205.1b).
 * @property addedSubtypes subtypes the effect adds in layer 4 (CR 613.1d, CR 205.3).
 * @property setPower the power the effect *sets* in sublayer 7b (CR 613.4b), or `null`. A plain
 *   [Int] rather than a [Magnitude] because only a resolution-generated effect can carry one and its
 *   value is snapshotted by then (CR 608.2h); a `*`-P/T characteristic-defining ability would be
 *   sublayer 7a, a stage that is still an empty gate.
 * @property setToughness the toughness the effect sets in sublayer 7b (CR 613.4b), or `null`.
 * @property timestamp the CR 613.7 timestamp, in the single allocation sequence described above.
 */
internal data class ActiveEffect(
    val source: ObjectId?,
    val affected: ObjectId,
    val grantedKeywords: PersistentSet<Keyword> = persistentSetOf(),
    val grantedManaAbilities: PersistentList<ManaAbility> = persistentListOf(),
    val grantedProtections: PersistentSet<Quality> = persistentSetOf(),
    val grantedEvasions: PersistentSet<Evasion> = persistentSetOf(),
    val powerMod: Magnitude = Magnitude.Zero,
    val toughnessMod: Magnitude = Magnitude.Zero,
    val addedCardTypes: PersistentSet<CardType> = persistentSetOf(),
    val addedSubtypes: PersistentSet<Subtype> = persistentSetOf(),
    val setPower: Int? = null,
    val setToughness: Int? = null,
    val timestamp: Long,
)

/**
 * The CR 613 layers an [active] effect contributes to (docs/design/layer-system.md §2). The
 * classification point: a keyword or mana-ability grant is layer 6; a nonzero P/T modifier is
 * sublayer 7c. A new effect kind (copy, control, type-change, set-P/T, counters) adds its field to
 * [ActiveEffect] and routes to its layer here, where [applyLayer]'s loud gate then refuses it until
 * that layer is implemented.
 *
 * A [Magnitude.Dynamic] modifier always contributes to 7c even if it currently evaluates to zero:
 * the layer *contribution* exists; its magnitude is read live (CR 613.3c).
 */
internal fun layersOf(active: ActiveEffect): Set<Layer> =
    buildSet {
        // CR 613.1d: a type or subtype addition is layer 4, whatever else the same effect does — one
        // effect contributing to several layers is CR 613.1's normal case, not a special one.
        if (active.addedCardTypes.isNotEmpty() || active.addedSubtypes.isNotEmpty()) {
            add(Layer.TYPE)
        }
        if (grantsAnAbility(active)) {
            add(Layer.ABILITY_ADDING)
        }
        // CR 613.4b: setting P/T is sublayer 7b and is a *different* contribution from modifying it.
        if (active.setPower != null || active.setToughness != null) {
            add(Layer.PT_SETTING)
        }
        if (active.powerMod != Magnitude.Zero || active.toughnessMod != Magnitude.Zero) {
            add(Layer.PT_MODIFYING)
        }
    }

/**
 * Whether [active] adds an ability to the object it affects, and therefore contributes to CR 613.1f
 * layer 6. All four kinds are the same rule: keywords, mana abilities, protections and evasions are
 * each *an ability*, unioned additively. They are four fields rather than one only because [Keyword]
 * is a parameterless enum and cannot carry protection's quality (docs/design/protection.md §5), and
 * because an evasion is ability *text* rather than a named CR 702 keyword.
 */
private fun grantsAnAbility(active: ActiveEffect): Boolean =
    active.grantedKeywords.isNotEmpty() ||
        active.grantedManaAbilities.isNotEmpty() ||
        active.grantedProtections.isNotEmpty() ||
        active.grantedEvasions.isNotEmpty()

/**
 * Applies the active continuous effects [active] to the printed [base] characteristics of an object,
 * layer by layer (CR 613.1, 613.3). Every effect must classify into an implemented layer first
 * (the loud gate: an effect that lands in none of layers 4, 6, 7b and 7c is an unimplemented kind
 * and must not be silently dropped — §1); then the ordered spine is walked, each layer receiving its
 * own contributing effects in timestamp order (CR 613.7).
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
        applyLayer(state, acc, layer, byTimestamp.filter { layer in layersOf(it) }, counters)
    }
}

/**
 * Applies the [effects] contributing to one [layer] to [acc], in the order given (already
 * timestamp-sorted by [applyContinuousEffects]). Exhaustive over [Layer]: the four populated stages
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
        // CR 613.1d: layer 4 unions the added card types and subtypes onto the object's type line.
        Layer.TYPE -> effects.fold(acc) { current, active -> current.retyping(active) }
        // CR 613.1f: layer 6 unions granted keywords and mana abilities onto the object, then the
        // keywords the object's own keyword counters grant it (CR 122.1b).
        Layer.ABILITY_ADDING ->
            effects
                .fold(acc) { current, active -> current.granting(active) }
                .grantingKeywordCounters(counters)
        // CR 613.4b: sublayer 7b *sets* power and/or toughness, before any 7c modifier is added.
        Layer.PT_SETTING -> effects.fold(acc) { current, active -> current.settingPowerToughness(active) }
        // CR 613.4c: sublayer 7c adds the (possibly dynamic) P/T modifiers, then the object's own
        // P/T counters (CR 122.1a) — the sublayer the rule names for both.
        Layer.PT_MODIFYING ->
            effects
                .fold(acc) { current, active -> current.modifying(state, active) }
                .modifiedByCounters(counters)
        Layer.COPY, Layer.CONTROL, Layer.TEXT, Layer.COLOR,
        Layer.PT_CHARACTERISTIC_DEFINING, Layer.PT_SWITCHING,
        -> {
            require(effects.isEmpty()) {
                "CR 613: continuous effects in $layer are not implemented in the MVP pool " +
                    "(docs/design/layer-system.md §1); refusing to silently drop " +
                    effects.map(ActiveEffect::source)
            }
            acc
        }
    }

/** The loud gate: an effect must contribute to an implemented layer (4, 6, 7b or 7c), never nothing. */
private fun requireImplementedKind(active: ActiveEffect) {
    require(layersOf(active).isNotEmpty()) {
        "CR 613: the continuous effect from ${active.source} classifies into no implemented " +
            "layer (a layer-4 type change, a layer-6 grant, a layer-7b set-P/T or a layer-7c P/T " +
            "modifier); an unimplemented effect kind must fail loudly, never silently drop " +
            "(docs/design/layer-system.md §1)"
    }
}
