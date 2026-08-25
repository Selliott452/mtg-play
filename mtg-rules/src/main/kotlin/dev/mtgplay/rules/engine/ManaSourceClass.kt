package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.ManaAmount
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
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
 * - Nothing else reserves anything. An ability whose cost is mana alone may be paid by tapping its
 *   own source, and always could.
 *
 * Only a battlefield-scoped ability can reserve **its source**: a hand-functioning ability's source is
 * not a mana source at all. A chosen sacrifice is a battlefield permanent either way, so the scope
 * guard does not reach it.
 *
 * @param chosenSacrifice the permanents already chosen for an [AbilityCost.Sacrifice] component, empty
 *   before that selection is answered (and for every ability without one).
 */
internal fun manaSourcesReservedBy(
    state: GameState,
    source: GameObject,
    ability: ActivatedAbility,
    chosenSacrifice: List<ObjectId> = emptyList(),
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
                    -> false
                }
            }
    val fromSource = if (reservesSource) setOf(source.id) else emptySet()
    return fromSource + sacrificeSourcesAmong(state, chosenSacrifice)
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
    return SourceClassKey(obj.card, profile, triggeredManaBonus(state, obj.id), isSacrificeSource(state, obj.id))
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
 * Everything one activation of [key] choosing the alternative [produced] puts in the pool
 * (CR 605.1a–b): the source's own mana, plus the CR 605.1b triggered bonus of the Auras attached to
 * it. All of it is spendable by the plan that declared the activation; whatever the plan does not
 * spend floats until the step ends (CR 500.4).
 *
 * **This was the `FW-MANA` seam** (docs/design/mana-payment.md §8.1), and it turned out to be a
 * genuinely local one: making the primary half a multiset rather than a single [ManaType] is the
 * whole of the change here, and the plan shape, the payment search, the dedup rule and the executor
 * are all indifferent to how long this list is, exactly as P8.3 predicted.
 */
internal fun activationYield(
    key: SourceClassKey,
    produced: List<ManaType>,
): List<ManaType> = produced + key.bonus

/**
 * The production profile of the battlefield object [obj]: the canonical list of **alternatives**
 * one activation of its own mana abilities may add, or `null` if it is no mana source in this state.
 *
 * Each alternative is a multiset of mana types sorted WUBRG-then-colorless (CR 105.1), and the
 * alternatives are ordered lexicographically by that same ordinal sequence — which for the
 * one-mana sources that make up almost the whole pool reproduces the plain WUBRG-then-colorless
 * type order the profile used before `FW-MANA`, so nothing about the enumeration order of an
 * ordinary board moved.
 *
 * Reads the object's **layered** mana abilities ([layeredCharacteristics]), so a land granted
 * "{T}: add one mana of any color" by an Abundant Growth (CR 613 layer 6) taps for the granted
 * colors here (docs/design/layer-system.md §6). It also reads the **live board** for each ability's
 * [ManaAmount] (CR 605.2), which is what makes an assembled Urza's Tower a different source class
 * from an unassembled one without touching the equivalence relation. The CR 605.1b *triggered*
 * bonus is [triggeredManaBonus], kept separate because it is added rather than chosen between.
 *
 * `null` covers three cases that are the same thing to a payment plan: no definition, no mana
 * ability, and **every alternative evaluating to zero mana** — a Priest of Titania with no Elf
 * anywhere. The third is dropped rather than represented as an empty alternative because an
 * activation that adds nothing can never appear in a legal plan anyway (the no-idle rule,
 * docs/design/mana-payment.md §4 — it would have nothing to spend), so pruning it here removes no
 * plan and keeps [SourceClassKey]'s "no empty alternative" invariant exact rather than aspirational.
 */
internal fun productionProfile(
    state: GameState,
    obj: GameObject,
): List<List<ManaType>>? {
    val alternatives =
        layeredCharacteristics(state, obj.id)
            .manaAbilities
            .flatMap { ability ->
                val count = manaAmountOf(state, obj, ability.amount)
                ability.options.map { option -> List(count) { option } }
            }.filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(PRODUCTION_ALTERNATIVE_ORDER)
    return alternatives.ifEmpty { null }
}

/**
 * The canonical order of production alternatives: lexicographic by [ManaType] ordinal, shorter
 * first on a tie. Total and state-independent, so equal states enumerate equal plan lists
 * (ADR-006), and on singleton alternatives it is exactly WUBRG-then-colorless (CR 105.1).
 */
private val PRODUCTION_ALTERNATIVE_ORDER: Comparator<List<ManaType>> =
    Comparator { left, right ->
        val shared = minOf(left.size, right.size)
        var index = 0
        var verdict = 0
        while (index < shared && verdict == 0) {
            verdict = left[index].ordinal.compareTo(right[index].ordinal)
            index += 1
        }
        if (verdict != 0) verdict else left.size.compareTo(right.size)
    }

/**
 * How many mana one activation of a [ManaAmount] on the battlefield source [obj] adds, evaluated
 * against the **current** board (CR 605.2).
 *
 * This is the read point the whole framework is about, and the CR paragraph matters. A mana
 * ability's amount is *not* determined in advance the way a CR 601.2f cost reduction is: the
 * ability resolves during CR 601.2g, in the middle of paying a cost that was already fixed, and the
 * count it reads is the count at that moment. So this function is called by the planner while
 * building the source classes and again by the executor while activating, and both calls read live
 * state rather than a snapshot. Nothing memoises it (see [layeredCharacteristics]' "computed on
 * demand, never stored" rule, for the same reason).
 *
 * Exhaustive over [ManaAmount], so a new production shape breaks compilation here rather than
 * approximating.
 */
private fun manaAmountOf(
    state: GameState,
    obj: GameObject,
    amount: ManaAmount,
): Int =
    when (amount) {
        is ManaAmount.Fixed -> amount.count
        is ManaAmount.PerPermanent -> countMatching(state, obj.owner, amount.each)
        is ManaAmount.Conditional ->
            if (amount.requires.all { countMatching(state, obj.owner, it) > 0 }) amount.ifMet else amount.otherwise
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
