package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CounterUnlessPaid
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.definition.Ward
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.Target

/*
 * Ward (CR 702.21a) — the becomes-the-target detector and the trigger the engine synthesizes from a
 * card's declared [Ward] cost. Additive (`FW-WARD`); docs/design/countering-spells.md §13 reserved this
 * as a non-goal and this packet builds it.
 *
 * CR 702.21a in full: *"Ward [cost]" means "Whenever this permanent becomes the target of a spell or
 * ability an opponent controls, counter that spell or ability unless that player pays [cost]."* Four
 * facts follow, and each is load-bearing:
 *
 * 1. **It triggers on *becoming* a target**, which happens while the targeting object is being put on
 *    the stack — CR 601.2c for a spell, CR 602.2b for an activated ability, CR 603.3d for a triggered
 *    one. For a spell that is *before* CR 601.2g's payment, so an opponent who taps out for removal
 *    watches it get countered with no way to pay. Detection therefore hangs off the three
 *    target-establishment sites and not off resolution.
 * 2. **"a spell or ability"** — a targeted ability an opponent controls triggers ward exactly as a spell
 *    does, which is why the countered object is named by [dev.mtgplay.core.state.stackObjectId] rather
 *    than by a spell's card-object id.
 * 3. **"an opponent controls"** — an object its own controller targets triggers nothing, so a ward
 *    creature can be pumped or protected freely by its controller.
 * 4. **"counter *that* spell or ability"** — the victim is the object that did the targeting, captured
 *    on the fired trigger as [PendingTrigger.targetedBy]. It is linked information rather than a target,
 *    because ward's own trigger targets nothing: nothing re-checks it under CR 608.2b, and it may name an
 *    object that has since left the stack.
 *
 * A permanent targeted **twice** by one object triggers ward twice (CR 702.21b), which is what the
 * per-target fold below produces; the pool prints no such spell, and the fold is written so that the
 * first one that arrives is already right.
 */

/**
 * Fires the ward triggers (CR 702.21a) of every permanent that [targets] names and that is warded against
 * [targetingController], on behalf of the stack object [targetingStackObjectId] that just chose them.
 *
 * Called from each of the three target-establishment sites. A no-op — and the overwhelmingly common case
 * — when no chosen target is a warded permanent, so the three call sites pay nothing for the check.
 */
internal fun detectWardTriggers(
    state: GameState,
    targets: List<Target>,
    targetingController: PlayerId,
    targetingStackObjectId: ObjectId,
): GameState =
    targets.fold(state) { current, target ->
        val warded = wardedPermanent(current, target, targetingController) ?: return@fold current
        enqueuePendingTrigger(
            current,
            PendingTrigger(
                sourceId = warded.first,
                sourceCard = current.battlefieldObject(warded.first).card,
                // CR 108.4: control is ownership in this engine's pool, so the ward permanent's owner is
                // the player whose opponent had to be the one targeting.
                controller = current.battlefieldObject(warded.first).owner,
                ability = wardTrigger(warded.second),
                targetedBy = targetingStackObjectId,
            ),
        )
    }

/**
 * The warded battlefield permanent [target] names together with its ward cost, or `null` when [target]
 * does not name one, names one with no ward, or names one [targetingController] controls themself
 * (CR 702.21a's "an opponent controls").
 */
private fun wardedPermanent(
    state: GameState,
    target: Target,
    targetingController: PlayerId,
): Pair<ObjectId, Ward>? {
    val permanent =
        (target as? Target.Permanent)
            ?.let { named -> state.sharedZones.battlefield.firstOrNull { it.id == named.id } }
            // CR 702.21a's "an opponent controls": a permanent its own controller targets fires nothing.
            ?.takeIf { it.owner != targetingController }
    return permanent?.let { state.definitions[it.card]?.ward }?.let { permanent.id to it }
}

/**
 * The triggered ability CR 702.21a's reminder text spells out, built from a declared [ward] cost.
 *
 * Synthesized rather than declared per card for ninjutsu's and madness's reason: the ability text is the
 * *mechanic's*, identical wherever the keyword is printed, so a card that restated it would be free to
 * restate it wrongly. Its effect is empty — everything ward does is the
 * [TriggeredAbility.counterUnlessPaid] clause, which the engine orchestrates because the payment is a
 * decision (ADR-004) made by a seat that is not this ability's controller.
 *
 * The condition is [TriggerCondition.BecameTargetOfOpponentsSpellOrAbility], which is *never matched by
 * the detectors*: ward triggers are fired by [detectWardTriggers] from the declared cost, not found by
 * scanning printed [TriggeredAbility] lists. It is here so the fired record says what fired it, and so a
 * card that one day prints the condition longhand has a name for it.
 */
private fun wardTrigger(ward: Ward): TriggeredAbility =
    TriggeredAbility(
        condition = TriggerCondition.BecameTargetOfOpponentsSpellOrAbility,
        effect = ResolutionEffect { state, _ -> state },
        counterUnlessPaid = CounterUnlessPaid(ward.cost),
    )
