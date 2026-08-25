package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.ManaAmount
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.ProductionAlternative
import dev.mtgplay.rules.decision.SourceClassKey

/*
 * How a battlefield object becomes a payment **source class** (docs/design/mana-payment.md §2, §8):
 * what its mana abilities may add right now, how much of it a CR 605.2 board condition says, what
 * CR 605.1b bonus rides along, and the [SourceClassKey] that bundles all of it.
 *
 * It lives in its own file for the reason [manaSourceUsable] next door does, and the reason is now
 * load-bearing rather than tidy. The planner ([manaSourceClasses]) builds a class's membership from
 * [sourceClassKeyOf] and the executor ([resolveTapForMana]) identifies the member it activates with
 * the same function; before `FW-MANA` the executor rebuilt the key inline, and `P-MANASICK` flagged
 * that as "a standing hazard of the same shape: a future change to the key that misses this call
 * site fails the same way". Making the key state-dependent *was* that future change, so the
 * derivation was given one home instead of being written out twice.
 */

/**
 * The battlefield objects that must **not** fund [ability]'s mana component, because another
 * component of the same composite cost (CR 602.1) has already claimed them — the CR 601.2/602.2b
 * "you cannot pay the same cost twice" reading, and the fix for the trap the gauntlet triage records
 * as **T17**.
 *
 * The defect it closes is an ADR-005 one rather than a rules corner. `enumeratePaymentPlans` is
 * given a cost and a seat and nothing else, so for "{1}, {T}: …" on a permanent that is *also* a
 * mana source it would happily offer a plan that taps that very permanent for the `{1}`. The plan
 * enumerates, the agent picks it, mana is paid, and then the `{T}` component throws
 * "CR 602.2a: a {T} cost requires an untapped source". An enumerated action the rules do not permit
 * is the failure mode this whole document exists to prevent (docs/design/mana-payment.md §2.1).
 *
 * Which objects are reserved is exact rather than blunt, because over-reserving would trade a crash
 * for a *silently missing* legal plan:
 *
 * - **[AbilityCost.TapSelf]** reserves the source outright. Every way of producing mana from it
 *   either taps it (breaking the `{T}`) or sacrifices it (removing it), so it can fund nothing.
 * - **[AbilityCost.SacrificeSelf]** reserves the source only when it is a *sacrifice*-cost mana
 *   source ([isSacrificeSource]), which would consume it before the cost's own sacrifice. Tapping a
 *   permanent for mana and then sacrificing it is legal Magic (CR 701.17 does not care that the
 *   permanent is tapped), and that plan stays enumerated.
 * - **[AbilityCost.Sacrifice]** reserves nothing *about the source* — the permanent it sacrifices is a
 *   chosen one, which need not be the source and usually is not (Krark-Clan Shaman is no artifact).
 *   What it reserves is the chosen permanent, passed in as [chosenSacrifice] and reserved by the same
 *   rule [AbilityCost.SacrificeSelf] uses: only when it is itself a sacrifice-cost mana source
 *   ([sacrificeSourcesAmong]). The choice is gathered **before** the payment plan (CR 601.2b–i order),
 *   which is precisely what makes an exact reservation available here instead of a blanket one.
 * - **[AbilityCost.ReturnPermanentYouControl]** reserves nothing about the source, for
 *   [AbilityCost.Sacrifice]'s reason — the permanent it returns is a chosen one. What it reserves is
 *   that chosen permanent, passed in as [chosenReturn], and it reserves it **unconditionally** rather
 *   than only when it is a sacrifice-cost mana source. That is the one place the two chosen-object
 *   components part company, and the difference is a zone-change rule rather than a judgement call: a
 *   sacrificed permanent may legally be tapped for mana first (CR 601.2g precedes CR 601.2h, and
 *   CR 701.17 does not care that it is tapped), while a permanent that has gone to its owner's hand is
 *   a **new object** (CR 400.7) with no tapped status at all (CR 110.5) — so tapping it for mana and
 *   then returning it is not a legal sequencing of one payment, it is paying with an object the cost
 *   then cannot find. Reserving it is what stops that plan being enumerated (ADR-005).
 * - Nothing else reserves anything. An ability whose cost is mana alone may be paid by tapping its
 *   own source, and always could.
 *
 * Only a battlefield-scoped ability can reserve **its source**: a hand-functioning ability's source is
 * not a mana source at all. A chosen sacrifice or return is a battlefield permanent either way, so the
 * scope guard does not reach it.
 *
 * @param chosenSacrifice the permanents already chosen for an [AbilityCost.Sacrifice] component, empty
 *   before that selection is answered (and for every ability without one).
 * @param chosenReturn the permanents already chosen for an [AbilityCost.ReturnPermanentYouControl]
 *   component, empty before that selection is answered (and for every ability without one).
 */
internal fun manaSourcesReservedBy(
    state: GameState,
    source: GameObject,
    ability: ActivatedAbility,
    chosenSacrifice: List<ObjectId> = emptyList(),
    chosenReturn: List<ObjectId> = emptyList(),
): Set<ObjectId> {
    val reservesSource =
        ability.zoneScope == AbilityZoneScope.Battlefield &&
            ability.cost.any { component ->
                when (component) {
                    AbilityCost.TapSelf -> true
                    AbilityCost.SacrificeSelf -> isSacrificeSource(state, source.id)
                    is AbilityCost.Mana,
                    AbilityCost.DiscardSelf,
                    AbilityCost.DiscardACard,
                    is AbilityCost.Sacrifice,
                    is AbilityCost.ReturnPermanentYouControl,
                    -> false
                }
            }
    val fromSource = if (reservesSource) setOf(source.id) else emptySet()
    return fromSource + sacrificeSourcesAmong(state, chosenSacrifice) + chosenReturn
}

/**
 * The payment source class the battlefield object [obj] currently belongs to, or `null` if it is
 * no mana source in this state (no definition, no mana ability, or every production alternative
 * evaluates to nothing).
 *
 * **One derivation, two callers.** This is the companion of [manaSourceUsable] and exists for the
 * same reason it does: [manaSourceClasses] builds a class's *membership* from it (the planner) and
 * [resolveTapForMana] identifies the member it activates by it (the executor), and the two must
 * never disagree. `P-MANASICK` found the usability predicate written out twice and flagged the key
 * being rebuilt inline in `resolveTapForMana` as "a standing hazard of the same shape: a future
 * change to the key that misses this call site fails the same way". `FW-MANA` is that future
 * change — the key now carries a state-derived count — so the hazard is closed by giving the
 * derivation a single home rather than by being careful twice.
 *
 * Note it does **not** check usability. Whether an object may be activated right now is
 * [manaSourceUsable]'s question and belongs to the caller; this answers only "what class is it",
 * which is a property both halves must agree on regardless.
 */
internal fun sourceClassKeyOf(
    state: GameState,
    obj: GameObject,
): SourceClassKey? {
    val profile = productionProfile(state, obj) ?: return null
    return SourceClassKey(obj.card, profile, triggeredManaBonus(state, obj.id))
}

/**
 * The extra mana an activation of the battlefield source [sourceId] adds *in addition to* its
 * primary production (CR 605.1b): the [dev.mtgplay.core.definition.TriggeredManaAbility]s of every Aura
 * attached to it — Utopia Sprawl's "add one mana of the chosen colour" on an enchanted Forest, Wild
 * Growth's additional `{G}` on an enchanted land — expanded to [ManaType]s, in battlefield-then-ability
 * order. Empty for a source with no such Aura. This is the [SourceClassKey.bonus] that keeps an
 * enchanted Forest a distinct source class, and — since P8.3 — the second half of the activation's
 * [activationYield], spendable by the very cost whose payment produced it.
 */
internal fun triggeredManaBonus(
    state: GameState,
    sourceId: ObjectId,
): List<ManaType> =
    state.sharedZones.battlefield
        .filter { it.attachedTo == sourceId }
        .flatMap { aura ->
            state.definitions[aura.card]?.triggeredManaAbilities.orEmpty().flatMap { ability ->
                when (ability) {
                    is dev.mtgplay.core.definition.TriggeredManaAbility.AddChosenColor -> {
                        val color = aura.chosenColor
                        if (color == null) emptyList() else List(ability.amount) { manaTypeOf(color) }
                    }

                    is dev.mtgplay.core.definition.TriggeredManaAbility.AddFixedMana ->
                        List(ability.amount) { ability.manaType }
                }
            }
        }

/**
 * Everything one activation of [key] choosing [alternative] puts in the pool (CR 605.1a–b): the
 * source's own mana, plus the CR 605.1b triggered bonus of the Auras attached to it. All of it is
 * spendable by the plan that declared the activation — by its symbol payments *and*, since
 * `FW-MANACOST`, by another activation's own mana cost; whatever the plan does not spend floats until
 * the step ends (CR 500.4).
 *
 * **This was the `FW-MANA` seam** (docs/design/mana-payment.md §8.1), and it turned out to be a
 * genuinely local one: making the primary half a multiset rather than a single [ManaType] is the
 * whole of the change here, and the plan shape, the payment search, the dedup rule and the executor
 * are all indifferent to how long this list is, exactly as P8.3 predicted. It stayed the seam under
 * `FW-MANACOST`: the yield is unchanged, and what the packet added is a *consumption* beside it
 * ([ProductionAlternative.manaCost]) rather than a second production route.
 */
internal fun activationYield(
    key: SourceClassKey,
    alternative: ProductionAlternative,
): List<ManaType> = alternative.produced + key.bonus

/**
 * The production profile of the battlefield object [obj]: the canonical list of **alternatives** one
 * activation of its mana abilities may take right now — each naming its cost and the multiset it adds
 * — or `null` if it is no mana source in this state.
 *
 * The alternatives are ordered by cost first ([PRODUCTION_ALTERNATIVE_ORDER], a total,
 * state-independent order) and then lexicographically by [ManaType] ordinal, shorter first. Every
 * source that existed before `FW-MANACOST` has the same cost on every alternative, so on those boards
 * the order collapses to exactly the produced-multiset order `FW-MANA` established, which in turn
 * collapses on singleton alternatives to plain WUBRG-then-colorless (CR 105.1). No ordinary board's
 * enumeration order has moved across either packet (ADR-006).
 *
 * Reads the object's **layered** mana abilities ([layeredCharacteristics]), so a land granted
 * "{T}: add one mana of any color" by an Abundant Growth (CR 613 layer 6) taps for the granted
 * colors here (docs/design/layer-system.md §6). It reads the **live board** for each ability's
 * [ManaAmount] (CR 605.2), which is what makes an assembled Urza's Tower a different source class
 * from an unassembled one without touching the equivalence relation, and — since `FW-MANACOST` — for
 * each ability's **availability** ([manaAbilityAvailable]), which is what makes a Wall of Roots that
 * has spent its CR 602.5b once-each-turn activation no source at all. The CR 605.1b *triggered* bonus
 * is [triggeredManaBonus], kept separate because it is added rather than chosen between.
 *
 * `null` covers four cases that are the same thing to a payment plan: no definition, no mana ability,
 * **no available** mana ability, and every alternative evaluating to zero mana — a Priest of Titania
 * with no Elf anywhere. The last is dropped rather than represented as an empty alternative because
 * an activation that adds nothing can never appear in a legal plan anyway (the no-idle rule,
 * docs/design/mana-payment.md §4 — it would have nothing to spend), so pruning it here removes no
 * plan and keeps [ProductionAlternative]'s "never empty" invariant exact rather than aspirational.
 */
internal fun productionProfile(
    state: GameState,
    obj: GameObject,
): List<ProductionAlternative>? {
    val abilities = layeredCharacteristics(state, obj.id).manaAbilities
    requireSingleOncePerTurnAbility(obj, abilities)
    val alternatives =
        abilities
            .filterIndexed { index, ability -> manaAbilityAvailable(state, obj, index, ability) }
            .flatMap { ability ->
                producedMultisets(state, obj, ability).map { produced ->
                    ProductionAlternative(ability.cost, produced, ability.oncePerTurn)
                }
            }.distinct()
            .sortedWith(PRODUCTION_ALTERNATIVE_ORDER)
    return alternatives.ifEmpty { null }
}

/**
 * Fails loudly unless [obj]'s CR 602.5b "Activate only once each turn" mana abilities number at most
 * one, and that one is **printed** on the card rather than granted.
 *
 * Both halves are what makes [GameObject.manaAbilitiesActivatedThisTurn] an unambiguous record. It
 * stores indices into the layered ability list, whose printed abilities are its prefix
 * ([LayeredCharacteristics.manaAbilities]), so a printed ability's index is stable; a *granted* one's
 * is not, and two identically-costed restricted abilities on one source could not be told apart when
 * the executor comes to mark the activation. Neither shape exists in the gauntlet pool. Making the
 * assumption an assertion rather than a comment is the difference between a card that cannot be
 * encoded and a card that is silently activated twice a turn (CONVENTIONS: fail loudly).
 */
private fun requireSingleOncePerTurnAbility(
    obj: GameObject,
    abilities: List<ManaAbility>,
) {
    val restricted = abilities.withIndex().filter { it.value.oncePerTurn }
    require(restricted.size <= 1) {
        "CR 602.5b: ${obj.card.name} has ${restricted.size} 'activate only once each turn' mana abilities; " +
            "the per-object activation record cannot tell them apart"
    }
}

/**
 * The canonical order of production alternatives: by cost first ([activationCostRank] component by
 * component, shorter cost first), then lexicographic by [ManaType] ordinal with the shorter multiset
 * first. Total and state-independent, so equal states enumerate equal plan lists (ADR-006).
 *
 * Cost precedes production so that a source's *free* alternatives sort ahead of its costed ones —
 * Conduit Pylons' "{T}: Add {C}" ahead of its "{1}, {T}: Add one mana of any color" — which keeps the
 * cheapest lines at the low plan indices an agent sees first, and keeps every pre-`FW-MANACOST` board
 * (one cost, uniformly) ordered exactly as it was.
 */
private val PRODUCTION_ALTERNATIVE_ORDER: Comparator<ProductionAlternative> =
    Comparator { left, right ->
        var verdict =
            INT_LIST_ORDER.compare(left.cost.map(::activationCostRank), right.cost.map(::activationCostRank))
        if (verdict == 0) {
            verdict =
                INT_LIST_ORDER.compare(
                    left.produced.map(ManaType::ordinal),
                    right.produced.map(ManaType::ordinal),
                )
        }
        // Two alternatives whose costs rank equally but differ in a component's payload (two counter
        // kinds) are still distinct; the rendered cost breaks the tie totally and state-independently.
        if (verdict == 0) verdict = left.cost.toString().compareTo(right.cost.toString())
        verdict
    }

/** Lexicographic order over int sequences, the shorter first on a shared prefix. */
private val INT_LIST_ORDER: Comparator<List<Int>> =
    Comparator { left, right ->
        val shared = minOf(left.size, right.size)
        var index = 0
        var verdict = 0
        while (index < shared && verdict == 0) {
            verdict = left[index].compareTo(right[index])
            index += 1
        }
        if (verdict != 0) verdict else left.size.compareTo(right.size)
    }

/**
 * A total, state-independent rank for one [ManaAbilityCost] component, used only to order production
 * alternatives deterministically (ADR-006). Free-to-pay components rank ahead of the mana component
 * so that a costed alternative always sorts after a free one; within [ManaAbilityCost.Mana] the mana
 * value orders the cheaper activation first. Exhaustive, so a new component must choose its rank
 * rather than silently colliding with an existing one.
 */
private fun activationCostRank(component: ManaAbilityCost): Int =
    when (component) {
        ManaAbilityCost.TapSelf -> TAP_SELF_RANK
        ManaAbilityCost.SacrificeSelf -> SACRIFICE_SELF_RANK
        ManaAbilityCost.TapAnotherCreature -> TAP_ANOTHER_CREATURE_RANK
        is ManaAbilityCost.PutCounterOnSelf -> PUT_COUNTER_RANK
        is ManaAbilityCost.Mana -> MANA_COST_RANK_BASE + component.cost.manaValue
    }

/** The rank of [ManaAbilityCost.TapSelf], first because it is the cost of almost every source. */
private const val TAP_SELF_RANK: Int = 0

/** The rank of [ManaAbilityCost.SacrificeSelf]. */
private const val SACRIFICE_SELF_RANK: Int = 1

/** The rank of [ManaAbilityCost.TapAnotherCreature]. */
private const val TAP_ANOTHER_CREATURE_RANK: Int = 2

/** The rank of [ManaAbilityCost.PutCounterOnSelf]. */
private const val PUT_COUNTER_RANK: Int = 3

/** The first rank of the [ManaAbilityCost.Mana] block, above every free component's rank. */
private const val MANA_COST_RANK_BASE: Int = 100

/**
 * The multisets one activation of [ability] on the battlefield source [obj] may add, evaluated against
 * the **current** board (CR 605.1a, CR 605.2) — one entry per alternative the activator may choose
 * between, and **empty** when the ability would add nothing at all.
 *
 * This is the read point the whole framework is about, and the CR paragraph matters. A mana
 * ability's amount is *not* determined in advance the way a CR 601.2f cost reduction is: the
 * ability resolves during CR 601.2g, in the middle of paying a cost that was already fixed, and the
 * count it reads is the count at that moment. So this function is called by the planner while
 * building the source classes and again by the executor while activating, and both calls read live
 * state rather than a snapshot. Nothing memoises it (see [layeredCharacteristics]' "computed on
 * demand, never stored" rule, for the same reason).
 *
 * **Two shapes, not one, since `FW-TAPUNTAP`.** Three of the four [ManaAmount] members are a *count*
 * that the ability's [ManaAbility.options] are multiplied by — one alternative per option, each a
 * uniform multiset — while [ManaAmount.FixedMultiset] supplies its own types and yields exactly **one**
 * alternative with no choice in it. That is the difference between Azorius Chancery's "Add {W}{U}" and
 * a hypothetical "add one mana of white or blue", and it is why this returns multisets rather than the
 * `Int` it used to: the count was never able to express a production whose mana differ from each other.
 *
 * A count that evaluates to zero yields **no** alternative (rather than an empty one), because "adds at
 * least one mana" is an invariant of [ProductionAlternative] rather than a filter applied afterwards —
 * a Priest of Titania with no Elf on the battlefield.
 *
 * Exhaustive over [ManaAmount], so a new production shape breaks compilation here rather than
 * approximating.
 */
private fun producedMultisets(
    state: GameState,
    obj: GameObject,
    ability: ManaAbility,
): List<List<ManaType>> {
    val count =
        when (val amount = ability.amount) {
            // CR 605.1a: the mixed production is not a count at all — it names its mana outright, so it
            // short-circuits the option cross product with the single alternative it describes.
            is ManaAmount.FixedMultiset -> return listOf(amount.types)
            is ManaAmount.Fixed -> amount.count
            is ManaAmount.PerPermanent -> countMatching(state, obj.owner, amount.each)
            is ManaAmount.Conditional ->
                if (amount.requires.all { countMatching(state, obj.owner, it) > 0 }) amount.ifMet else amount.otherwise
        }
    return if (count <= 0) emptyList() else ability.options.map { option -> List(count) { option } }
}

/**
 * How many battlefield permanents match [filter] from the point of view of the source [obj]
 * (CR 109.4, CR 205.3) — the shared count in [countMatchingPermanents], with the source's
 * controller (ownership in the MVP pool) supplying the filter's "you".
 *
 * The count itself moved to `PermanentCount.kt` when `FW-DURATION` became its second consumer, on
 * docs/design/cost-modification.md §6's verdict: share the counting *noun*, never the consumer, so
 * that a mana ability's live CR 605.2 read and an until-end-of-turn effect's frozen CR 608.2h read
 * stay distinguishable at the type level.
 */
internal fun countMatching(
    state: GameState,
    you: PlayerId,
    filter: PermanentFilter,
): Int = countMatchingPermanents(state, filter, you)
