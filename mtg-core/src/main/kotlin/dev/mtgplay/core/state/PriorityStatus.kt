package dev.mtgplay.core.state

/**
 * Where one player stands within the current priority round (CR 117).
 *
 * A round of priority proceeds by each player either holding priority or having passed it: the
 * active player receives priority first (CR 117.3b), each pass hands priority to the next player
 * in turn order (CR 117.3d), and when all players have passed in succession the round ends
 * (CR 117.4). Holding and having-passed are mutually exclusive, which is why this is one
 * three-valued status rather than two independent flags — the illegal combination is
 * unrepresentable.
 *
 * This is a noun only: the transitions (granting, passing, and ending rounds) are the rules
 * engine's (packet P1.2). Outside a priority round every player is [NONE].
 */
enum class PriorityStatus {
    /** Neither holding priority nor having passed in the current round (or no round is open). */
    NONE,

    /** Currently holding priority (CR 117.1); at most one player at a time. */
    HOLDS_PRIORITY,

    /**
     * Has passed priority in succession since the current round began (CR 117.4); cleared when
     * the round ends or a new round is granted.
     */
    HAS_PASSED,
}
