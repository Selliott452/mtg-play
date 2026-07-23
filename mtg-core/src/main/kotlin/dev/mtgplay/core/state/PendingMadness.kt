package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * A resolved madness reflexive trigger awaiting its owner's yes/no cast decision (CR 702.35b).
 * Additive, flagged core (P5.2).
 *
 * When the synthesized madness trigger resolves and the madness cast is currently possible (a legal
 * target and an affordable madness cost exist), the engine suspends here rather than inside a
 * [dev.mtgplay.core.definition.ResolutionEffect] — the CR 702.35b "may cast" is a genuine player
 * choice, so it flows through a `DecisionRequest` (ADR-004). Recording it in the state keeps the
 * pending decision (the yes/no) a pure function of the state (ADR-004 no-hidden-position): the
 * reflexive trigger is already off the stack, and this record is what remains of it.
 *
 * On **yes** the engine opens a cast of [exiledObjectId] from exile at the madness cost (the normal
 * CR 601 pipeline). On **no** — or when the cast turns out impossible — the card is put into its
 * owner's graveyard (CR 702.35b) and its [GameObject.awaitingMadness] marker cleared.
 *
 * @property owner the card's owner, who may cast it (CR 702.35b); the deciding seat of the yes/no.
 * @property exiledObjectId the exiled madness card ([GameObject.awaitingMadness] set) the choice is
 *   about.
 */
data class PendingMadness(
    val owner: PlayerId,
    val exiledObjectId: ObjectId,
)
