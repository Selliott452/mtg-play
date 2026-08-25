package dev.mtgplay.rules.effect

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.sacrificeControlledPermanent

/**
 * Effect primitive: **sacrifices the battlefield permanent** [objectId] as part of a resolution
 * (CR 701.17a) — the published building block a "sacrifice it" instruction composes (ADR-003;
 * Mulldrifter's evoke trigger is the first client). Additive, flagged (`W8-D`).
 *
 * **A sacrifice performed by an *effect*, not paid as a cost**, and that is the whole reason it is a
 * published primitive rather than a reuse of the cost path. CR 701.17b — "a player can't sacrifice
 * something that isn't a permanent, or that's controlled by another player" — governs both, but the two
 * differ in when they may fail: a cost that cannot be paid means the spell or ability was never
 * enumerated (ADR-005), while an *effect* that says "sacrifice it" quite normally arrives at a permanent
 * that has already left the battlefield. Mulldrifter's own trigger is the witness: kill the creature in
 * response and the sacrifice trigger resolves with nothing to sacrifice.
 *
 * So a missing permanent is a **no-op**, not a failure — the honest last-known-information answer
 * (CR 608.2b, CR 603.10) that [returnToOwnersHand] and [exileCardFromGraveyard] already give, and the
 * opposite of the cost path's loud `require`.
 *
 * The permanent moves to its owner's graveyard as a new object (CR 400.7); its leaves-the-battlefield
 * and dies triggers fire (CR 603.6b, CR 603.10) against its pre-sacrifice state, and combat releases it
 * (CR 506.4). Every one of those seams is the cost path's, reached through the same engine function, so
 * a sacrificed-by-effect permanent and a sacrificed-as-a-cost one are indistinguishable afterwards.
 *
 * **The sacrificing player is the permanent's controller**, which is its owner in the MVP pool. It is
 * read off the permanent rather than passed in, because CR 701.17b makes any other answer illegal: an
 * effect instructing a player to sacrifice a permanent someone else controls does nothing, so a
 * parameter would only ever create a way to state something the rules forbid.
 */
fun sacrificePermanent(
    state: GameState,
    objectId: ObjectId,
): GameState = sacrificeControlledPermanent(state, objectId)
