package dev.mtgplay.rules.decision

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId

/**
 * One enumerated legal option inside a priority window (ADR-005): something the player holding
 * priority may do (CR 117.1).
 *
 * The hierarchy is sealed so drivers handle every kind exhaustively. P1.2 shipped [Pass]; P2.1
 * adds [CastSpell] (CR 117.1a via the CR 601 pipeline); later phases add the remaining action
 * options — playing a land, activating an ability (CR 117.1b–c) — as new members, which is why
 * a priority window carries a list of typed options rather than a yes/no.
 */
sealed interface PriorityOption {
    /**
     * Decline to take an action, passing priority (CR 117.3d). Always legal for the player
     * holding priority, so it is present in every priority window's options.
     */
    data object Pass : PriorityOption

    /**
     * Begin casting the hand card [objectId] (CR 601.2). Enumerated only when the cast is
     * fully legal from this window (ADR-005): the timing class permits it (CR 117.1a), at
     * least one payment plan exists for its cost, and every required target has at least one
     * legal choice — so choosing this option never dead-ends.
     *
     * @property objectId the castable object in the deciding player's hand.
     * @property card the printed identity, for display; the object is reborn on the stack with
     *   a fresh id when the cast's pipeline runs (CR 400.7).
     */
    data class CastSpell(
        val objectId: ObjectId,
        val card: CardRef,
    ) : PriorityOption
}
