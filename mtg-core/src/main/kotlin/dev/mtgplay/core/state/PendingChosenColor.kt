package dev.mtgplay.core.state

import dev.mtgplay.core.identity.PlayerId

/**
 * A "choose a colour, then do something with it" clause the engine is gathering mid-resolution
 * (CR 609.4) — Prismatic Strands. Additive, flagged core (`FW-PREVENT2`). The resolving object is still
 * on top of the stack; the engine has paused for the [decider]'s colour choice and completes the
 * resolution once the choice arrives. Non-null only at that mid-resolution pause, where no player holds
 * priority.
 *
 * **Deliberately as thin as [PendingColorChoice], and for the same reason.** What is to be *done* with
 * the colour is re-derived from the resolving object's own
 * [dev.mtgplay.core.definition.ResolutionClauses.chosenColorEffect] rather than copied here, so the
 * paused state has exactly one statement of it and the two cannot drift (ADR-004: a decision
 * re-derives from state).
 *
 * **A separate record from [PendingColorChoice], which it resembles and is not.** That one is CR 614.12
 * — a *permanent* choosing a colour as it enters — and its resume path completes a battlefield entry
 * and stores the colour on the entering object. This one's resume path runs a clause and finishes a
 * resolution. Folding them into one record with a mode flag would give the engine a single field whose
 * meaning depended on a card's type, and two resume paths behind one branch.
 *
 * @property decider the player making the choice — the resolving object's controller (CR 609.4).
 */
data class PendingChosenColor(
    val decider: PlayerId,
)
