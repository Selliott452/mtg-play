package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.TargetContext
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * The CR 601.2b announcement of a variable cost on the **activation** path (CR 602.2b, CR 107.3), and the
 * bound on its option set. `W9-C`, docs/design/dependent-targets.md §3. Gorilla Shaman's
 * "`{X}{X}{1}`: Destroy target noncreature artifact with mana value X" is the pool's only printing.
 *
 * > CR 602.2b — "the remainder of the process described in rules 601.2b–601.2i is followed"
 * > CR 107.3b — "the value of X isn't defined by the text of that spell [or ability] … that player
 * > announces the value of X … as part of casting the spell [or activating the ability]"
 *
 * ## The ordering decision, stated out loud
 *
 * `FW-X` landed X for **spells only**, and `PendingCastRequest.kt`'s header records why the cast path
 * announces it *last*, after every other cost selection and out of CR 601.2b's printed order: the bound on
 * X is "every value whose total cost is payable", and payability is only exact once the sibling selections
 * that reserve mana sources are settled. That header also names the card shape that would force the order
 * back — "a card printing 'X target creatures'" — and Gorilla Shaman is that shape, from the other side:
 * its *targets* are a function of its announced X, so CR 601.2c has nothing to enumerate until X is known.
 *
 * Three options were open, and the third is the one this file takes:
 *
 * 1. **Reorder globally** — move the announcement above the target stage on both paths. Correct by
 *    CR 601.2b's letter, and rejected: every *other* cast in the game would then bound its X against
 *    `minimalSacrificeReservation` rather than the exact, choice-aware one, which is the
 *    enumerated-then-unpayable direction ADR-005 forbids. One card does not get to charge the whole
 *    payment model.
 * 2. **Drop the card.** What `W8-C` did, and it was right then, because the activation path had no
 *    `chosenX` at all and the reorder was the only visible route.
 * 3. **Let the path that needs the printed order take it, and pay for it locally.** The activation path
 *    announces X at CR 601.2b's printed position — *before* CR 601.2c — and the cast path is untouched.
 *    This costs nothing, because the activation path has **no pool to protect**: no encoded activated
 *    ability carries X, so there is no existing bound to weaken and no existing replay to rewrite. The
 *    asymmetry between the two paths is therefore not a compromise between them; it is each path taking
 *    the order that is exact for the cards it actually has.
 *
 * The resulting sequence on an activation is CR 601.2b (X) → CR 601.2c (targets) → CR 601.2b/h (the
 * chosen-object cost selections) → CR 601.2f–g (payment), and the one deviation left is that the
 * *chosen-object* selections still sit below targets, exactly where CR 602.2b's existing pipeline put
 * them.
 *
 * ## What the weaker bound costs, and the gate that keeps it honest
 *
 * Announcing first means X is bounded before the sacrifice/return/discard selections have reserved
 * anything, so [abilityXValueOptions] prices each candidate against the *unreserved* battlefield. For an
 * ability that also chooses an object to sacrifice or return, that is over-permissive in exactly the way
 * `FW-ADDSAC` documented: a value could be offered whose payment plan needs a permanent the later
 * selection takes away. No ability in the gauntlet prints both — Gorilla Shaman's whole cost is
 * `{X}{X}{1}` — so rather than approximate, [requireXBoundIsExact] **fails loudly** on the pairing. The
 * day one is printed, the fix is a joint enumeration over (X, chosen object), not a wider filter here.
 *
 * ## The second bound, which is not about mana at all
 *
 * A value of X is offered only when it is **payable *and* leaves the ability with legal targets**. That
 * second half is new with this file and it is the whole point of the ordering: Gorilla Shaman announcing
 * X = 4 against a board of nothing but Ornithopters would reach CR 601.2c with an empty option list, and
 * an activation cannot be abandoned mid-gathering — CR 601.2c's answer is that such an activation was
 * never legal (CR 602.2b), so the value must not be offered. The same test decides whether the *ability*
 * is enumerated at all ([abilityActivatableAtSomeX]): an ability with no payable-and-targetable value of
 * X is not an option in the priority window.
 */

/** The mana component of [ability]'s cost, or `null` for an ability that costs no mana. */
private fun manaCostOf(ability: ActivatedAbility): ManaCost? =
    ability.cost
        .filterIsInstance<AbilityCost.Mana>()
        .firstOrNull()
        ?.cost

/**
 * Whether activating [ability] needs a CR 601.2b announcement of X — whether its mana component carries
 * the variable symbol at all (CR 107.3).
 *
 * An ability with no mana component announces nothing, which is every `{T}`-only and sacrifice-only
 * ability in the pool.
 */
internal fun abilityAnnouncesX(ability: ActivatedAbility): Boolean = manaCostOf(ability)?.hasX == true

/**
 * Fails loudly if [ability] both announces X and chooses an object for another cost component
 * (CR 601.2b) — the gate on the weaker reservation this path's ordering accepts.
 *
 * See the file header: announcing X above the target stage means it is bounded before the chosen-object
 * selections reserve anything, which is exact only while no ability prints both. Reaching this is not a
 * rules corner but an un-modelled card shape, so it says which shape and what it would need.
 */
internal fun requireXBoundIsExact(ability: ActivatedAbility) {
    val chosen =
        ability.cost.filter {
            it is AbilityCost.Sacrifice || it is AbilityCost.ReturnPermanentYouControl || it == AbilityCost.DiscardACard
        }
    require(chosen.isEmpty()) {
        "CR 601.2b: an activated ability costing $chosen as well as an {X} announces X before that " +
            "object is chosen, so the announcement would be bounded against a reservation the payment " +
            "enumeration does not use; that needs a joint (X, object) enumeration (`W9-C`)"
    }
}

/**
 * The values of X [seat] may announce to activate [ability] of [source] (CR 107.3b, CR 601.2b via
 * CR 602.2b), in ascending order — every non-negative value that is **both** payable and leaves the
 * ability a legal target choice, and no others.
 *
 * The mana half mirrors [xValueOptions] on the cast path exactly, and for the same reasons recorded in
 * `XCost.kt`: each candidate is tested by asking [enumeratePaymentPlans] rather than by arithmetic (mana
 * types do not substitute and an activation may consume mana), no monotonicity is assumed, and
 * [maxProducibleMana] makes the scan finite.
 *
 * The targeting half has no counterpart there and is this path's own: a candidate survives only if
 * `targetsAvailable` holds *at that value* ([TargetContext.chosenX]). Without it the announcement could
 * hand a seat a value that reaches CR 601.2c with nothing to point at, which is not a rules position an
 * activation can be in — an ability that cannot be fully targeted cannot be activated at all
 * (CR 601.2c via CR 602.2b), so the value simply is not a legal announcement.
 *
 * May be **empty**, unlike its cast-path sibling, and the difference is exactly the targeting half: a
 * cast is always announceable at X = 0 because the gate priced it there, whereas X = 0 may be perfectly
 * payable and still name no legal target. An empty result means the ability is not activatable at all,
 * which is what [abilityActivatableAtSomeX] reports to the enumerator.
 */
internal fun abilityXValueOptions(
    state: GameState,
    seat: PlayerId,
    source: GameObject,
    ability: ActivatedAbility,
): List<Int> {
    requireXBoundIsExact(ability)
    val cost = manaCostOf(ability) ?: return emptyList()
    val reserved = manaSourcesReservedBy(state, source, ability)
    return (0..maxProducibleMana(state, seat, reserved))
        .filter { candidate ->
            targetsAvailable(
                state,
                ability.targetSpec,
                seat,
                Chooser.Ability(source.card),
                TargetContext(chosenX = candidate),
            ) &&
                enumeratePaymentPlans(state, seat, cost.substitutingX(candidate), reserved).isNotEmpty()
        }
}

/**
 * Whether [ability] of [source] can be activated by [seat] for **some** announceable value of X
 * (CR 601.2b, CR 601.2c) — the offerability test the priority enumeration uses for an X ability, in place
 * of the separate "cost payable" and "targets available" tests it uses for every other one.
 *
 * They cannot stay separate here, and that is the ordering's second consequence: with X unannounced,
 * "is the cost payable?" and "does a legal target exist?" are questions about *different* values of X,
 * and answering them independently would offer an ability whose payable values name no target and whose
 * targetable values cannot be paid for. Joining them is what makes the enumerated action honest
 * (ADR-005): the ability appears in the priority window exactly when some single value of X carries the
 * whole activation through.
 */
internal fun abilityActivatableAtSomeX(
    state: GameState,
    seat: PlayerId,
    source: GameObject,
    ability: ActivatedAbility,
): Boolean = abilityXValueOptions(state, seat, source, ability).isNotEmpty()
