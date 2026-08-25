package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState

/*
 * The CR 601.2b announcement of a variable cost (CR 107.3), and the bound on its option set.
 *
 * > CR 107.3b — "If a player is casting a spell that has an {X} in its mana cost, the value of X isn't
 * > defined by the text of that spell, that player announces the value of X … as part of casting the
 * > spell."
 *
 * **The bound is the whole design problem, and it is load-bearing rather than a convenience.** X ranges
 * over the non-negative integers, so an unbounded announcement is an *infinite* enumerated action space
 * — which ADR-005 does not merely discourage, it cannot represent: a `DecisionRequest` carries a list of
 * options and an agent answers with an index into it. Some bound has to exist, and the only defensible
 * one is the game's own: **a value of X is offered exactly when the resulting total cost can be paid.**
 * Anything narrower deletes a legal play; anything wider hands an agent an option that dead-ends when
 * the payment plan for it turns out to be empty, which is the ADR-005 defect in its most expensive
 * direction.
 *
 * ## Why the set is computed rather than derived arithmetically
 *
 * The obvious cheap bound is "total mana available minus the cost's fixed part". It is wrong in both
 * directions on boards this engine already builds. Mana types do not substitute for one another, so a
 * seat with five green and a `{X}{R}` spell can afford no X at all despite five mana; and since
 * `FW-MANACOST` an activation can *consume* mana as well as produce it (Barrels of Blasting Jelly's
 * "{1}: Add one mana of any color"), so the arithmetic is not even monotone in the obvious way. The
 * only thing that knows whether a cost is payable is [enumeratePaymentPlans], so that is what decides
 * each candidate — one call per candidate, and the answer is exact by construction rather than by an
 * argument about mana.
 *
 * **Every candidate is tested independently, and no monotonicity is assumed.** The set could have been
 * found by ascending from zero and stopping at the first unpayable value, which is cheaper and would be
 * correct if payability were monotone in the generic component. It almost certainly is. But "almost
 * certainly" buys a silently *missing* legal play if it is ever false — the failure this codebase
 * treats as worse than a crash (docs/design/mana-payment.md §2.2) — and the scan is cheap enough that
 * the assumption need not be made at all. [xValueOptions] therefore returns whatever the tests say,
 * contiguous or not, and a test pins that it *is* contiguous on real boards so a future
 * non-monotonicity shows up as a failing expectation rather than as a vanished option.
 *
 * ## What makes the scan finite
 *
 * [maxProducibleMana] — the pool plus the largest yield every usable source class could contribute if
 * every member of it were activated for its most productive alternative. A seat cannot pay more mana
 * than it can obtain, so no value of X above that figure is payable and the scan stops there. It is an
 * upper bound rather than an exact figure (it ignores that activation costs consume some of what is
 * produced, and that types must match), which is the correct direction for a ceiling: too high merely
 * costs a few doomed enumerations, while too low would delete a legal announcement.
 *
 * ## The reservation, and why the announcement comes last
 *
 * The candidate costs are enumerated against the **same** `reserved` set the eventual
 * `ChoosePaymentPlan` will use (docs/design/mana-payment.md §2.2). That is what makes "an announced
 * value never dead-ends" a structural property rather than a hope, and it is the reason the
 * announcement is settled after every sibling cost selection rather than at CR 601.2b's printed
 * position — see [dev.mtgplay.core.state.PendingCast.chosenX] for the full argument and the one card
 * shape that would force it back.
 */

/**
 * The values of X [seat] may announce for casting [definition] via [permission] (CR 107.3b,
 * CR 601.2b), in ascending order — every non-negative value whose resulting total cost has at least one
 * payment plan, and no others.
 *
 * Never empty for a cast that was legally enumerated: `castIsLegal` and [permissionCastIsLegal] price
 * the spell at X = 0, the cheapest announcement there is, so a cast a seat can begin always has at
 * least the zero option waiting for it.
 *
 * @param kicked whether the caster has announced they are paying the kicker (CR 702.33a) — part of the
 *   cost each candidate is priced against, since the kicker announcement is settled first.
 * @param reserved battlefield objects a sibling component of the same cost has already claimed; the
 *   identical set the payment enumeration for the chosen value will use.
 */
internal fun xValueOptions(
    state: GameState,
    seat: PlayerId,
    subject: CastSubject,
    kicked: Boolean,
    reserved: Set<ObjectId>,
): List<Int> =
    (0..maxProducibleMana(state, seat, reserved))
        .filter { candidate ->
            enumeratePaymentPlans(
                state,
                seat,
                totalCost(state, seat, subject, CostAnnouncements(kicked, candidate)),
                reserved,
            ).isNotEmpty()
        }

/**
 * The most mana [seat] could possibly have available for one cost right now: their floating pool plus,
 * for every usable source class, its membership times the largest amount any one activation of it could
 * add (its widest production alternative plus its CR 605.1b triggered bonus).
 *
 * **An upper bound, deliberately loose in two directions.** It ignores that an activation may *cost*
 * mana (`FW-MANACOST`), and it ignores that mana types do not substitute for one another — so the real
 * payable maximum is usually smaller. Both slacks are safe: this figure only decides where
 * [xValueOptions] stops scanning, and every candidate below it is still tested for real. Tightening it
 * would risk cutting the scan short of a payable value, which is the one error a ceiling must not make.
 *
 * [reserved] is honoured so the ceiling reflects the same battlefield the payment enumeration will see.
 */
internal fun maxProducibleMana(
    state: GameState,
    seat: PlayerId,
    reserved: Set<ObjectId> = emptySet(),
): Int {
    val pool = state.player(seat).manaPool.size
    val fromSources =
        manaSourceClasses(state, seat, reserved).sumOf { sourceClass ->
            val widest = sourceClass.key.profile.maxOf { it.produced.size } + sourceClass.key.bonus.size
            sourceClass.members.size * widest
        }
    return pool + fromSources
}

/**
 * Whether [seat] can afford to announce the kicker for [definition] cast via [permission]
 * (CR 702.33a) — the gate on offering the announcement at all (ADR-005), so a seat is never asked a
 * yes/no whose "yes" leaves it with no payable plan.
 *
 * Priced at **X = 0** alongside the kicker, which is the cheapest kicked announcement available. That
 * is exact for the question being asked ("is *some* kicked cast payable?") and it is the same reasoning
 * `castIsLegal` uses to price an unkicked cast: a value of X above zero only ever costs more, so if no
 * kicked cast is payable at zero none is payable at all.
 *
 * `false` for a card without kicker — there is nothing to announce — which is what keeps the
 * announcement off every cast in the pool that does not print the keyword.
 */
internal fun kickerAffordable(
    state: GameState,
    seat: PlayerId,
    subject: CastSubject,
    reserved: Set<ObjectId>,
): Boolean {
    if (subject.definition.kicker == null) return false
    return enumeratePaymentPlans(
        state,
        seat,
        totalCost(state, seat, subject, CostAnnouncements(kicked = true, chosenX = 0)),
        reserved,
    ).isNotEmpty()
}

/**
 * Whether casting [definition] via [permission] needs a CR 601.2b announcement of X — whether the cost
 * the cast starts from carries the variable symbol at all (CR 107.3).
 *
 * Read off the **base** cost rather than the printed one, because an alternative cost replaces the
 * printed cost entirely (CR 118.9): a card with `{X}{G}` printed and a `{0}` alternative announces no X
 * when cast that way, and one whose *alternative* cost carried an X would announce one even though its
 * printed cost does not. No card in the pool exercises either half, and reading the base cost is what
 * makes both correct without a special case.
 */
internal fun announcesX(subject: CastSubject): Boolean = baseCost(subject.definition, subject.permission).hasX
