package dev.mtgplay.core.state

import dev.mtgplay.core.definition.PermanentSelectionAction
import dev.mtgplay.core.identity.PlayerId

/**
 * An untargeted mid-resolution choice of battlefield permanents the engine is gathering (CR 609.4) —
 * Snap's "Untap up to two lands", Azorius Chancery's "return a land you control to its owner's hand".
 * Additive, flagged core (`FW-TAPUNTAP`). The resolving object's ordinary effect has already run and it
 * is still the top of the stack; the engine has paused for the [decider] to choose between [minimum]
 * and [maximum] of the matching permanents. On the answer [action] is performed on each chosen
 * permanent and the resolution completes. Non-null only at that pause.
 *
 * **The option list is not stored here**, deliberately and for the reason every other pending record
 * keeps its options out of the state: the choices are a pure function of the paused state and the
 * clause's filter, so re-deriving them is the resumability contract (ADR-004) and a stored copy would
 * be a second source of truth that could disagree with the one the answer is validated against.
 *
 * [minimum] and [maximum] *are* stored, already clamped to what the board offered when the pause
 * opened, so the request the engine re-derives cannot demand more permanents than exist.
 *
 * @property decider the resolving object's controller, who makes the choice (CR 609.4 — "you").
 * @property action what is done to each chosen permanent when the selection is answered.
 * @property minimum the fewest permanents that must be chosen; already clamped to the board.
 * @property maximum the most that may be chosen; already clamped to the board, and at least [minimum].
 */
data class PendingPermanentSelection(
    val decider: PlayerId,
    val action: PermanentSelectionAction,
    val minimum: Int,
    val maximum: Int,
) {
    init {
        require(minimum >= 0) { "CR 609.4: a pending selection's minimum is non-negative, was $minimum" }
        require(maximum >= minimum) {
            "CR 609.4: a pending selection's range runs from its minimum up, got $minimum..$maximum"
        }
    }
}
