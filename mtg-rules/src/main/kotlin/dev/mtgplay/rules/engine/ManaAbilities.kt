package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.ManaAbilityRider
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.ManaActivation
import dev.mtgplay.rules.decision.ProductionAlternative
import dev.mtgplay.rules.decision.SourceClassKey
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.effect.putCounters

/**
 * Activates one mana ability of the first usable member of [activation]'s source class (CR 605.3): it
 * pays the chosen alternative's whole cost — spending the plan's recorded
 * [ManaActivation.costPayment] out of the pool, **tapping** the source, **sacrificing** it, tapping a
 * second creature, and/or putting a counter on it — resolves the ability immediately, no stack and no
 * priority round (CR 605.3a–b), and adds the alternative's mana to [player]'s pool. Emits
 * [GameEvent.ManaAbilityActivated], then the cost's events, then one [GameEvent.ManaAdded] per mana.
 *
 * Which member is used is rules-irrelevant by construction — class members are payment-equivalent
 * (docs/design/mana-payment.md) — so the deterministic first-in-battlefield pick keeps replay exact
 * (ADR-006) without surfacing a meaningless choice.
 *
 * Triggered mana abilities that trigger off this activation (Utopia Sprawl, CR 605.1b) resolve here,
 * immediately after the intrinsic ability adds its mana and before control returns to payment — extra
 * mana joins the pool without touching the plan shape.
 *
 * A CR 605.1a **rider** — the non-mana half of "{T}: Add {B}. This creature deals 1 damage to you" —
 * runs last, in [applyManaAbilityRider]. It changes nothing about the plan either: it is not a cost,
 * it cannot fail, and nothing gates activation on surviving it.
 *
 * **Planner/executor correspondence under a CR 605.2 count** (docs/design/mana-payment.md §8.3).
 * A mana ability's amount is read when it *resolves*, so an ability that adds "{C}{C}{C} if you
 * control the other two Urza lands" is re-evaluated here rather than taken from the plan. The
 * member search is what makes that safe: [sourceClassKeyOf] re-derives the whole key — profile
 * included, and the state-derived count is *inside* the profile — against the state as it now
 * stands, and only a member whose re-derived key equals the planned [ManaActivation.sourceClass] is
 * activated. So an activation whose count moved between enumeration and payment finds no member and
 * fails loudly below, rather than quietly adding a different amount of mana than the plan promised.
 * The correspondence is therefore structural, not a convention this function has to remember. Since
 * `FW-MANACOST` the key also carries each alternative's **cost** and its CR 602.5b once-each-turn
 * availability, so the same certificate covers those too: a source that has spent its allowance is
 * not in its old class any more.
 *
 * @param remaining the activations this plan has still to run, in execution order. They are needed
 *   only by an [ManaAbilityCost.TapAnotherCreature] component, which must not tap a creature the rest
 *   of the plan is going to activate ([helperCreature]).
 */
internal fun resolveManaActivation(
    state: GameState,
    player: PlayerId,
    activation: ManaActivation,
    remaining: List<ManaActivation>,
): GameState {
    val sourceClass = activation.sourceClass
    val alternative = activation.alternative
    require(alternative in sourceClass.profile) {
        "CR 605: source class ${sourceClass.card.name} has no production alternative $alternative"
    }
    val source =
        state.sharedZones.battlefield.firstOrNull { obj ->
            // The same class derivation the plan's membership was built from — including the CR 602.5a
            // summoning-sickness gate, the CR 605.2 production count and the CR 602.5b once-each-turn
            // record — so planner and executor pick from the identical member set
            // (docs/design/mana-payment.md §2.1, §8.3, §11.4).
            obj.owner == player && sourceClassKeyOf(state, obj) == sourceClass
        }
    require(source != null) {
        "CR 601.2g: no usable member of source class $sourceClass remains for $player; " +
            "the plan was enumerated against this state, so either a member left the battlefield or a " +
            "CR 605.2 production count changed mid-payment — an engine defect either way"
    }
    val announced = state.emit(GameEvent.ManaAbilityActivated(player, source.id, source.card))
    val paid = payManaAbilityCost(announced, player, source, activation, remaining)
    // CR 605.1a: the ability adds every mana of the chosen production alternative — one mana for an
    // ordinary source, three for an Urza's Tower with Tron assembled. The cost above was paid first,
    // which is what stops an activation from funding itself (docs/design/mana-payment.md §11.5).
    val withPrimary = alternative.produced.fold(paid) { current, mana -> addManaToPool(current, player, mana) }
    // CR 605.1b, CR 605.3: triggered mana abilities of Auras on this source (Utopia Sprawl) resolve
    // now — no stack, no priority — adding their mana to the same controller's pool. Whatever the
    // payment does not consume floats until the step ends (CR 500.4). The bonus is read from the state
    // *before* the cost was paid, because a sacrifice cost has since removed the source.
    val withBonus =
        triggeredManaBonus(announced, source.id).fold(withPrimary) { current, bonus ->
            addManaToPool(current, player, bonus)
        }
    return applyManaAbilityRider(withBonus, player, source, alternative)
}

/**
 * Performs the CR 605.1a **rider** of the mana ability just resolved — Elves of Deep Shadow's "This
 * creature deals 1 damage to you" — or returns the state untouched for the overwhelming majority of
 * activations, which have none.
 *
 * **After the mana, deliberately.** CR 605.1a's own wording is "add {B}. This creature deals 1 damage
 * to you", and the order is observable at 1 life: the mana is in the pool before the damage is dealt,
 * so a payment whose last activation kills its own controller has still produced everything the plan
 * promised. The state-based action that ends the game runs later (CR 704.3), never inside a payment.
 *
 * **The damage's source is the ability's source object, as last-known information** (CR 113.7c). It is
 * captured from the [source] the executor located *before* the cost was paid, which matters for the
 * general shape rather than for this card: a `{T}` cost leaves the Elf on the battlefield, but a rider
 * on a sacrifice-cost ability would be dealt by a permanent that no longer exists, and
 * [dev.mtgplay.core.state.DamageSource] is built to answer for exactly that (docs/design/protection.md
 * §3). Routing through [dealDamage] rather than subtracting life is what keeps CR 615 prevention and
 * CR 702.16e protection applicable to it.
 *
 * Exhaustive over [ManaAbilityRider], so a rider shape the pool does not print breaks compilation here
 * rather than being silently skipped mid-payment.
 */
private fun applyManaAbilityRider(
    state: GameState,
    player: PlayerId,
    source: GameObject,
    alternative: ProductionAlternative,
): GameState =
    when (val rider = alternative.rider) {
        null -> state
        is ManaAbilityRider.DamageToController ->
            dealDamage(
                state,
                DamageSource(objectId = source.id, card = source.card),
                Target.Player(player),
                rider.amount,
            )
    }

/**
 * Pays one mana ability's whole activation cost (CR 602.1 through CR 605.1a), component by component
 * in printed order.
 *
 * Every component is either forced or already settled in the plan, which is what makes this runnable
 * inside CR 601.2g where the engine may not pause for a decision: the mana is the plan's recorded
 * [ManaActivation.costPayment], the tap and the sacrifice have no choice in them, the counter has no
 * choice in it, and the second creature is picked by [helperCreature]. A component that needed a
 * genuine mid-payment decision could not be added to [ManaAbilityCost] without reshaping the plan
 * (docs/design/mana-payment.md §11.1).
 */
private fun payManaAbilityCost(
    state: GameState,
    player: PlayerId,
    source: GameObject,
    activation: ManaActivation,
    remaining: List<ManaActivation>,
): GameState =
    activation.alternative.cost
        .fold(state) { current, component ->
            when (component) {
                // CR 601.2h in miniature: one recorded mana per expanded symbol of this ability's own cost.
                // The plan's payment was validated against the symbols before execution began, so the fold
                // is mechanical (see the shape check in `validatePlanShape`).
                is ManaAbilityCost.Mana ->
                    activation.costPayment.fold(current) { pool, unit -> removeManaFromPool(pool, player, unit) }

                ManaAbilityCost.TapSelf -> tapForManaAbility(current, source.id)
                // CR 605.1a: the cost sacrifices the source; no {T}, and it may already be tapped.
                ManaAbilityCost.SacrificeSelf -> sacrificePermanents(current, player, listOf(source.id))
                ManaAbilityCost.TapAnotherCreature ->
                    tapForManaAbility(current, helperCreature(current, player, source, remaining))
                is ManaAbilityCost.PutCounterOnSelf -> putCounters(current, source.id, component.counter)
            }
        }.let { paid -> markOncePerTurn(paid, source, activation.alternative) }

/**
 * The creature an [ManaAbilityCost.TapAnotherCreature] component taps: the first untapped creature its
 * controller controls, in battlefield order, that is neither the [source] itself nor a member the
 * *rest* of this plan still needs to activate.
 *
 * **Why an engine choice rather than a player one.** CR 601.2g resolves mana abilities inside the
 * payment of another cost, where the decision-point engine has nowhere to suspend (ADR-004): the whole
 * plan is one transition. Every other choice a mana ability needs is therefore settled in the plan
 * before execution starts; this one is not, because which creature is tapped is rules-irrelevant to
 * the plan's outcome in the gauntlet pool — no creature there is worth more untapped than another
 * *for the purpose of this payment* — while surfacing it would multiply every plan by the board's
 * creature count.
 *
 * **Why one always exists.** The capacity check counted every creature this plan taps or sacrifices,
 * including the helpers, against the seat's untapped creatures (§11.3). At this point the drain still
 * to come is exactly the reserved members plus this helper, so "untapped creatures remaining ≥
 * reserved + 1" holds and the search below cannot come up empty. It fails loudly if it ever does,
 * because that would mean the enumerator offered a plan execution cannot carry out — the ADR-005
 * defect this model exists to prevent.
 */
private fun helperCreature(
    state: GameState,
    player: PlayerId,
    source: GameObject,
    remaining: List<ManaActivation>,
): ObjectId {
    val reserved = membersReservedBy(state, player, remaining)
    val helper =
        untappedCreatures(state, player).firstOrNull { it.id != source.id && it.id !in reserved }
            ?: error(
                "CR 602.1: ${source.card.name} must tap another untapped creature, but every one of " +
                    "$player's is either this source or reserved by the rest of the plan — the capacity " +
                    "check should have rejected this plan (docs/design/mana-payment.md §11.3)",
            )
    return helper.id
}

/**
 * The battlefield objects [remaining] will activate: for each source class it names, the first `n`
 * members of that class in battlefield order, `n` being how many times the class appears. That is
 * exactly the members [resolveManaActivation] will pick, because it takes the first member whose
 * re-derived key matches and each activation makes its member stop matching.
 */
private fun membersReservedBy(
    state: GameState,
    player: PlayerId,
    remaining: List<ManaActivation>,
): Set<ObjectId> =
    remaining
        .groupingBy { it.sourceClass }
        .eachCount()
        .flatMap { (key, count) ->
            state.sharedZones.battlefield
                .filter { it.owner == player && sourceClassKeyOf(state, it) == key }
                .take(count)
                .map { it.id }
        }.toSet()

/**
 * Records that [source] has activated its CR 602.5b "Activate only once each turn" mana ability, so
 * that [manaAbilityAvailable] drops it for the rest of the turn. A no-op for every unrestricted
 * ability, which keeps [GameObject.manaAbilitiesActivatedThisTurn] empty on ordinary boards and their
 * replay fingerprints unchanged.
 *
 * The index recorded is the ability's index among the card's **printed** mana abilities.
 * [productionProfile] has already asserted there is at most one such ability and that it is printed,
 * which is what makes this lookup unambiguous rather than a guess at which restricted ability just
 * ran.
 */
private fun markOncePerTurn(
    state: GameState,
    source: GameObject,
    alternative: ProductionAlternative,
): GameState {
    if (!alternative.oncePerTurn) return state
    val printed = state.definitions[source.card]?.manaAbilities.orEmpty()
    val index = printed.indexOfFirst { it.oncePerTurn }
    require(index >= 0) {
        "CR 602.5b: ${source.card.name} activated an 'only once each turn' mana ability that is not " +
            "printed on it; the per-turn record indexes printed abilities only"
    }
    // A sacrifice cost has already removed the source, and a permanent that no longer exists carries
    // no restriction — CR 122.2's reasoning for counters, applied to the same turn-scoped shape.
    val current = state.sharedZones.battlefield.firstOrNull { it.id == source.id }
    return if (current == null) {
        state
    } else {
        state.replacingBattlefieldObject(
            current.copy(manaAbilitiesActivatedThisTurn = current.manaAbilitiesActivatedThisTurn.adding(index)),
        )
    }
}

/** Taps the battlefield object [id] to pay a mana ability's cost (CR 602.2a); emits [GameEvent.ObjectTapped]. */
private fun tapForManaAbility(
    state: GameState,
    id: ObjectId,
): GameState {
    val obj =
        state.sharedZones.battlefield.firstOrNull { it.id == id }
            ?: error("CR 602.2a: a {T} cost taps a battlefield source, but $id is not there")
    require(!obj.tapped) { "CR 602.2a: a {T} cost requires an untapped source, but $id is tapped" }
    return state.replacingBattlefieldObject(obj.copy(tapped = true)).emit(GameEvent.ObjectTapped(id, obj.card))
}

/**
 * The pre-`FW-MANACOST` entry point, kept for the callers that name a class and an alternative rather
 * than a whole [ManaActivation]. Equivalent to [resolveManaActivation] with a free alternative and
 * nothing left to run.
 */
internal fun resolveTapForMana(
    state: GameState,
    player: PlayerId,
    sourceClass: SourceClassKey,
    alternative: ProductionAlternative,
): GameState = resolveManaActivation(state, player, ManaActivation(sourceClass, alternative), remaining = emptyList())

/**
 * [this] with the battlefield entry sharing [updated]'s id replaced by [updated], **in place** —
 * battlefield order is the engine's determinism spine (CR 613.7 timestamps derive from entry order),
 * so tapping a source or recording its CR 602.5b activation must never reorder the zone.
 */
private fun GameState.replacingBattlefieldObject(updated: GameObject): GameState =
    updateBattlefield { battlefield ->
        val index = battlefield.indexOfFirst { it.id == updated.id }
        battlefield.removingAt(index).addingAt(index, updated)
    }
