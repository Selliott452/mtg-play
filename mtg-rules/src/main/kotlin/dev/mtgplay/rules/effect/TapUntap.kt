package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.announceBecameTapped
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updateBattlefield

/*
 * Tapping and untapping as **resolution effects** (CR 701.20, CR 701.21) — the published primitives a
 * card's "tap target permanent" or "untap target creature" composes (ADR-003). Additive, flagged
 * (`FW-TAPUNTAP`).
 *
 * Until this packet the engine could only tap and untap as *bookkeeping*: `tapObjectForCost` pays a
 * `{T}` cost (CR 602.2a), `tapForManaAbility` pays a mana ability's (CR 605.1a), and
 * `untapStepTurnBasedActions` runs CR 502.2. All three are private to their own flows and all three are
 * the wrong shape for a card: a cost's tap **requires** an untapped source and throws otherwise, which
 * is exactly right for a cost and exactly wrong for Harrier Strix, whose trigger taps whatever it
 * points at and does nothing if that permanent is already tapped.
 *
 * That difference — **a cost demands, an effect merely does** — is the whole reason these are separate
 * functions rather than a shared one with a flag. CR 701.21a and CR 701.21b both say a permanent that
 * is already in the requested status is simply unaffected; CR 602.2a says a `{T}` cost cannot be paid at
 * all by a tapped permanent. Folding them together would mean one body whose behaviour depends on who
 * called it, which is how a rules read drifts (the argument `returnPermanentToOwnersHand` makes for
 * staying separate from `returnToOwnersHand`).
 *
 * Two properties both functions share:
 * - **Battlefield only** (CR 110.5): tapped is a status only permanents have, so an id that is not on
 *   the battlefield is a no-op rather than an error. That is the honest reading for an effect whose
 *   object may have left — a Snap resolving after its chosen land was destroyed in response cannot
 *   happen (the choice is made *inside* the resolution), but a delayed or LKI-carrying caller could,
 *   and a silent no-op is what CR 608.2 prescribes for an instruction it cannot carry out. Contrast the
 *   cost-side `tapObjectForCost`, which fails loudly because ADR-005 guarantees its object is there.
 * - **A "becomes tapped" trigger is fired, and only on a real flip.** This note used to record that
 *   nothing in the gauntlet watched for a permanent becoming tapped; `W8-C` added Cryoshatter, so
 *   [setTapped] now calls [announceBecameTapped] at the one point the status changes from untapped to
 *   tapped (CR 701.20a). Untapping still fires nothing — no card in the pool watches for it. A
 *   *triggered mana ability* (CR 605.1b) is unrelated and unchanged: it fires off a mana activation
 *   rather than off the status change, and lives inside `resolveManaActivation`.
 */

/**
 * Effect primitive: taps the battlefield permanent [objectId] (CR 701.21a) — Harrier Strix's "tap
 * target permanent", Sleep of the Dead's "Tap target creature".
 *
 * A permanent that is **already tapped is unaffected** (CR 701.21a) and no event is emitted: the status
 * did not change, and narrating a tap that did not happen would put a lie in the replay log. Emits
 * [GameEvent.ObjectTapped] exactly when the permanent goes from untapped to tapped.
 */
fun tapPermanent(
    state: GameState,
    objectId: ObjectId,
): GameState = setTapped(state, objectId, tapped = true)

/**
 * Effect primitive: untaps the battlefield permanent [objectId] (CR 701.21b) — Quirion Ranger's "Untap
 * target creature", Snap's "Untap up to two lands".
 *
 * A permanent that is **already untapped is unaffected** (CR 701.21b) and no event is emitted, the
 * mirror of [tapPermanent]. Emits [GameEvent.ObjectUntapped] exactly when the permanent goes from
 * tapped to untapped.
 *
 * **[dev.mtgplay.core.state.GameObject.skipsNextUntapStep] is not consulted**, and that is the rule
 * rather than an omission: Sleep of the Dead's rider stops the permanent untapping "during its
 * controller's next untap step" (CR 502.2), which is a turn-based action, and says nothing about an
 * effect that untaps it. A Snap can free a creature the Sleep put to sleep, and the marker survives to
 * be spent on the untap step that follows — two independent facts, and the engine keeps them so.
 */
fun untapPermanent(
    state: GameState,
    objectId: ObjectId,
): GameState = setTapped(state, objectId, tapped = false)

/**
 * Sets the battlefield permanent [objectId]'s tapped status to [tapped] (CR 110.5b), emitting the
 * matching event only when the status actually changes. A no-op for a permanent already in the
 * requested status (CR 701.21a, CR 701.21b).
 *
 * **An id that is not on the battlefield fails loudly**, and the distinction between the two cases is
 * the point. "Already tapped" is a rules answer — CR 701.21a says tapping a tapped permanent does
 * nothing — so absorbing it is correct. "Not a permanent" is not a rules answer at all: every caller
 * reaches here either from a resolving effect whose CR 608.2b re-check has just confirmed its target
 * is a legal battlefield permanent, or from an untargeted CR 609.4 selection made moments earlier in
 * the same resolution. A bad id therefore means the engine offered or retained a choice it should not
 * have, which is the ADR-005 failure, and swallowing it would hide the defect behind a board that
 * merely looks wrong.
 *
 * The object is replaced **in place**: battlefield order is the engine's determinism spine (CR 613.7
 * timestamps derive from entry order), so a status change must never reorder the zone.
 */
private fun setTapped(
    state: GameState,
    objectId: ObjectId,
    tapped: Boolean,
): GameState {
    val index = state.sharedZones.battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) {
        "CR 110.5b: only a permanent on the battlefield has a tapped status, but $objectId is not on it"
    }
    val permanent: GameObject = state.sharedZones.battlefield[index]
    // Already in the requested status: nothing happens and nothing is said (CR 701.21a, CR 701.21b).
    if (permanent.tapped == tapped) return state
    val event =
        if (tapped) {
            GameEvent.ObjectTapped(objectId, permanent.card)
        } else {
            GameEvent.ObjectUntapped(objectId, permanent.card)
        }
    val flipped =
        state
            .updateBattlefield { it.removingAt(index).addingAt(index, permanent.copy(tapped = tapped)) }
            .emit(event)
    // CR 603.2/701.20a: a real untapped -> tapped flip is the "becomes tapped" event. Untapping fires
    // nothing; no card in the pool watches for it.
    return if (tapped) announceBecameTapped(flipped, objectId) else flipped
}

/**
 * Effect primitive: marks the battlefield permanent [objectId] as not untapping during its controller's
 * **next** untap step (CR 302.6, CR 502.2) — the second sentence of Sleep of the Dead, "It doesn't
 * untap during its controller's next untap step".
 *
 * A separate primitive from [tapPermanent] rather than a parameter on it, because the two halves of
 * that card are genuinely independent instructions: the tap happens now and this one is a standing fact
 * about a future step, and a permanent that was **already tapped** still receives the marker even
 * though the tap did nothing. Composing them as two calls is what makes that come out right.
 *
 * Fails loudly off the battlefield, for [setTapped]'s reason, and absorbs a repeat marking, for the
 * same reason it absorbs a repeated tap. The marker is consumed by
 * `untapStepTurnBasedActions`, which skips the permanent and clears it, so exactly one untap step is
 * missed however many turns pass first; a permanent that leaves the battlefield loses it with every
 * other battlefield-only fact (CR 400.7).
 */
fun skipNextUntapStep(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val index = state.sharedZones.battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) {
        "CR 502.2: only a permanent on the battlefield can be held down, but $objectId is not on it"
    }
    val permanent = state.sharedZones.battlefield[index]
    // Already marked: the marker is a fact, not a counter.
    if (permanent.skipsNextUntapStep) return state
    return state.updateBattlefield {
        it.removingAt(index).addingAt(index, permanent.copy(skipsNextUntapStep = true))
    }
}
