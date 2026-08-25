package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId

/*
 * *What* a target enumeration is being made for (`P-ABILSOURCE`) — the parameter `legalTargets` and
 * everything downstream of it carry beside the deciding player.
 *
 * It replaces the bare `self: ObjectId?` that preceded it, which conflated two genuinely different
 * questions under one nullable id and, in doing so, left CR 702.16b half-built:
 *
 * 1. **Which object, if any, must be excluded from a stack enumeration?** "Counter target spell"
 *    never offers the counter itself. The cast pipeline runs `proposeSpell` (CR 601.2a, card onto the
 *    stack) *before* `establishTargets` (CR 601.2c, re-validation), so without naming the choosing
 *    spell the gathering-time enumeration (counter in hand, absent from the stack) and the
 *    re-validation (counter on the stack) would name different sets. Only a **spell** has an id to
 *    exclude; an ability is not a card and has none (CR 113.7a).
 * 2. **Whose characteristics does protection test?** CR 702.16b: "A permanent or player with
 *    protection can't be targeted by spells with the stated quality and can't be targeted by
 *    abilities **from a source** with the stated quality." A spell is its own source (CR 113.7c); an
 *    ability's source is the object that generated it (CR 113.7b), which is a *different* object
 *    with a different id and different characteristics.
 *
 * One nullable id cannot answer both, and the answer it silently gave was "abilities have no source":
 * every ability-targeting site passed `null`, so `Targets.kt` had a live `error()` for any protected
 * object reached from an ability enumeration. A sealed type makes the two questions separate and makes
 * "an ability with no source" unrepresentable, so the gap cannot reopen by someone writing `null`
 * again. See docs/design/protection.md §2.4 and §6.
 */

/**
 * The object a target enumeration is being made for, in whatever zone it currently occupies.
 *
 * Not merely "the choosing object": [Ability] carries no id at all, because CR 113.7a is explicit that
 * an ability on the stack is not a card and has no id — what it carries is its *source's* printed
 * identity, which is the only thing CR 702.16b asks of it.
 */
internal sealed interface Chooser {
    /**
     * A spell being cast, gathered for, re-validated, or resolving — named by its **current** object
     * id, which changes as the card moves between zones (CR 400.7).
     *
     * A spell is its own source (CR 113.7c), so this one id answers both questions above: it is the
     * object excluded from a stack enumeration *and* the object whose characteristics protection
     * tests. The identity is resolved from the id at the point of use rather than captured here,
     * because a spell is always somewhere findable — in its caster's hand while the CR 601.2c choice
     * is enumerated, on the stack by the CR 608.2b re-check — and resolving it late is what keeps
     * those two enumerations equal.
     */
    data class Spell(
        val objectId: ObjectId,
    ) : Chooser

    /**
     * An activated or triggered ability, named by its source's printed identity ([sourceCard]) —
     * CR 113.7b, "the source of an ability is the object that generated it".
     *
     * **The identity is captured rather than looked up, and that is load-bearing.** CR 113.7c settles
     * an ability's source characteristics by *last known information* when the source has left the
     * zone it was in, and an ability whose cost sacrificed its own source is the ordinary case, not a
     * corner: Tinder Wall's "{R}, Sacrifice this creature: It deals 2 damage to target creature it's
     * blocking" is on the stack with its source already in a graveyard as a **new object under a new
     * id** (CR 400.7). An id captured at activation would name nothing by the CR 608.2b re-check, so
     * this must be the card, and the stack entries already carry exactly that
     * ([dev.mtgplay.core.state.StackEntry.ActivatedAbilityOnStack.sourceCard],
     * [dev.mtgplay.core.state.PendingTrigger.sourceCard]) for the same reason.
     *
     * It excludes nothing from a stack enumeration: there is no id to exclude (CR 113.7a), and an
     * ability's source is a permanent or a card in hand, never a spell on the stack beside the one
     * being targeted.
     */
    data class Ability(
        val sourceCard: CardRef,
    ) : Chooser

    /**
     * No object is choosing — a unit test asking what the board offers, with no spell and no ability
     * behind the question.
     *
     * The **only** case that cannot answer CR 702.16b, and therefore the only one the protection gate
     * in `Targets.kt` still fails loudly on. Before `P-ABILSOURCE` that gate was reachable from every
     * real ability site; now it is reachable only from a caller that has genuinely named nothing,
     * which is the honest reading of "protection from a source" when there is no source.
     */
    data object Nobody : Chooser
}

/**
 * The object id a stack enumeration must exclude for this chooser (CR 601.2c), or `null` when there is
 * nothing to exclude — an ability (CR 113.7a) or no chooser at all.
 *
 * Deliberately a property of the chooser rather than a `when` inside the stack branch of
 * `legalTargets`, so the CR 113.7a reasoning is stated once beside the type that encodes it.
 */
internal val Chooser.excludedFromStack: ObjectId?
    get() =
        when (this) {
            is Chooser.Spell -> objectId
            is Chooser.Ability, Chooser.Nobody -> null
        }
