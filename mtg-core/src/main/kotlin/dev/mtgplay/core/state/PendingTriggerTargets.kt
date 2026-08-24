package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * A fired triggered ability awaiting its CR 603.3d target choice, at the moment it is being put on the
 * stack. Additive, flagged core (`FW-ABILTGT`, docs/design/targeted-abilities.md §3.2).
 *
 * **CR 603.3d: "this is done as the ability is put on the stack".** Not when the trigger fires, and not
 * when it resolves. Placement happens between a state-based-action check and a priority window
 * (`TriggerPlacement.kt`), so this is the one pause the engine takes with **no priority round open and
 * possibly an empty stack** — which is why it is neither a gathering pause (no player holds priority for
 * it) nor a mid-resolution pause (nothing is resolving).
 *
 * Non-null only at that pause. Carried in the state so the pending decision stays a pure function of it
 * (ADR-004 no-hidden-position): a paused game records who must choose and what they are choosing for.
 *
 * **Which trigger is being targeted is derived, not stored** — it is the first
 * [GameState.pendingTriggers] entry controlled by [controller]. The list order *is* the CR 603.3b
 * placement order (the ordering decision rewrites it), so the front of the controller's group is exactly
 * the ability being put on the stack. Storing an index as well would be redundant state that could drift
 * across the reorder; the invariant checker pins the derivation instead.
 *
 * @property controller the ability's controller, who chooses its targets (CR 603.3d) — the deciding seat
 *   of the surfaced `ChooseTargets` request, which need not be the player who will receive priority.
 * @property sourceId the source object's last-known id (CR 603.10), for display.
 * @property sourceCard the source's printed identity, for display.
 */
data class PendingTriggerTargets(
    val controller: PlayerId,
    val sourceId: ObjectId,
    val sourceCard: CardRef,
)
