package dev.mtgplay.core.definition

/**
 * A "the **owner** of target nonland permanent puts it into their library second from the top or on the
 * bottom" clause (CR 401.1, CR 108.3) — Deem Inferior's whole printed effect. Additive, flagged core
 * (`W9-F`).
 *
 * **Two things make it a clause rather than a [ResolutionEffect], and they are independent.**
 *
 * 1. **The depth is a decision**, and ADR-004 keeps decisions out of resolution effects.
 * 2. **The decision belongs to somebody else.** The owner chooses, and a resolving spell's effect has no
 *    way to ask a player who is not its controller anything. That is the same reason
 *    [EachOpponentDiscards] and [TargetPlayerExilesFromGraveyard] are clauses; this one adds the third
 *    reading of "somebody else" — an **owner** (CR 108.3, fixed for the game) rather than an opponent of
 *    the controller or a targeted player.
 *
 * **A data object, because there is nothing to vary.** The one card printing this offers exactly the two
 * positions [LibraryPosition] names and demands exactly one target. A position set, a count, and a
 * [PermanentRestriction] are the extension points, and each becomes a property here rather than a second
 * clause.
 *
 * **The permanent is a target and the depth is not.** The target is chosen at CR 601.2c and re-checked
 * at CR 608.2b, so a spell whose permanent has already left the battlefield does not resolve and nobody
 * is asked anything. The depth is chosen during resolution and is never re-checked, because nothing can
 * happen between the question and the answer.
 *
 * **Core/rules split (ADR-009).** This declares the shape; `mtg-rules` owns reading the owner off the
 * targeted permanent, surfacing the two-option request to *them*, and performing the CR 400.7 move with
 * every consequence of leaving the battlefield (CR 603.6c triggers, CR 506.4 combat removal) — see
 * `putPermanentIntoOwnersLibrary`.
 */
data object OwnerLibraryPlacement
