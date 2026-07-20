package dev.mtgplay.rules.decision

/**
 * One enumerated legal option inside a priority window (ADR-005): something the player holding
 * priority may do (CR 117.1).
 *
 * The hierarchy is sealed so drivers handle every kind exhaustively. In P1.2 the only member is
 * [Pass]; the casting/stack packet (P2.1) and later phases add the action options — casting a
 * spell, playing a land, activating an ability (CR 117.1a–c) — as new members, which is why a
 * priority window carries a list of typed options rather than a yes/no.
 */
sealed interface PriorityOption {
    /**
     * Decline to take an action, passing priority (CR 117.3d). Always legal for the player
     * holding priority, so it is present in every priority window's options.
     */
    data object Pass : PriorityOption
}
