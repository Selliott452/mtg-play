package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.DeathReplacement
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.TimedDeathReplacement
import dev.mtgplay.rules.effect.exilePermanent

/*
 * The **delayed** death-replacement interception point (CR 614.1a, CR 700.4), `W9-D`.
 *
 * The replacement framework in `Replacements.kt` intercepts two events, both of them about a card's own
 * zone change and both declared on the card's definition: a discard (madness, CR 702.35a) and a spell
 * leaving the stack (flashback, CR 702.34e). This is the third interception point and the first of a
 * different kind — the replacement is created by a *resolution*, ends on a clock, and watches **other**
 * objects. Its store is [GameState.deathReplacements]; see [TimedDeathReplacement] for why it is a store
 * of its own rather than a widening of either neighbour.
 *
 * **The event intercepted is "a permanent would die", and CR 700.4 defines that as "would be put into a
 * graveyard from the battlefield" — every route, not only the lethal-damage one.** The engine has four
 * such routes and each one calls [replaceBattlefieldDeath] before it moves anything:
 *
 * - the CR 704.5f/g creature-death state-based action (`CreatureDeath.kt`),
 * - the CR 701.7a destroy effect (`Destroy.kt`),
 * - the CR 701.17 sacrifice, both the cost side and the effect side (`Sacrifice.kt`),
 * - the CR 704.5m Aura fall-off (`AuraFallOff.kt`).
 *
 * Missing any one of them would be the silent kind of defect: a Torched creature that a later Terminate
 * finishes off would reach the graveyard for its controller to escape or flash back, which is the exact
 * value the printed line exists to deny. The other three battlefield departures — exile, bounce, and the
 * CR 704.5d token cessation — are *not* deaths and are deliberately not intercepted.
 */

/**
 * The delayed death replacement that applies to [objectId] dying (CR 614.1a), or `null` when none does.
 *
 * **The first applicable one wins, and today they cannot disagree.** CR 616.1 hands the affected object's
 * controller a choice when two or more replacements apply to one event; every member of
 * [DeathReplacement] the engine implements is the same exile, so any order produces the same game and a
 * choice would be a decision with one outcome — which ADR-005 would have to enumerate and an agent would
 * have to answer for nothing. The first [DeathReplacement] whose result differs from
 * [DeathReplacement.ExileInstead] is what must add the CR 616.1 request, and the `when` in
 * [replaceBattlefieldDeath] is what will break when it arrives.
 */
internal fun deathReplacementFor(
    state: GameState,
    objectId: ObjectId,
): TimedDeathReplacement? = state.deathReplacements.firstOrNull { objectId in it.affected }

/**
 * Applies the CR 614.1a delayed replacement to [objectId]'s death, or returns `null` when no replacement
 * watches it — in which case the caller performs its ordinary graveyard move.
 *
 * A `null` return rather than a returned-unchanged state, so a caller cannot forget to branch: the four
 * death routes each begin with `replaceBattlefieldDeath(state, id)?.let { return it }`, and a
 * replacement's result is a *different move to a different zone*, not a modification of theirs.
 *
 * [DeathReplacement.ExileInstead] is [exilePermanent] verbatim, and reusing that primitive is the ruling
 * rather than convenience: the replaced event is an exile (CR 701.3a), so it fires the general CR 603.6c
 * leaves-the-battlefield triggers, fires **no** CR 603.6b put-into-a-graveyard trigger (nothing was put
 * into a graveyard — the replaced event never happens, CR 614.6), releases the permanent from combat
 * (CR 506.4), and produces a new object in exile (CR 400.7). Indestructible is not consulted here and
 * must not be: a caller reaches this only once its own event is going to happen, so
 * [dev.mtgplay.rules.effect.destroy] has already declined to destroy an indestructible permanent and
 * never asks.
 *
 * The replacement is **not** consumed. CR 614.5's "applies once per event" is satisfied by the object
 * itself: a permanent that has left the battlefield comes back as a new object with a new id (CR 400.7)
 * and is no longer in [TimedDeathReplacement.affected], so there is no second event for the same entry to
 * catch. Removing the id would say the same thing less clearly and would lose the record of what the
 * spell touched, which is what a seat reads out of [dev.mtgplay.rules.SeatView.deathReplacements].
 */
internal fun replaceBattlefieldDeath(
    state: GameState,
    objectId: ObjectId,
): GameState? {
    val replacement = deathReplacementFor(state, objectId) ?: return null
    return when (replacement.effect) {
        DeathReplacement.ExileInstead -> exilePermanent(state, objectId)
    }
}
