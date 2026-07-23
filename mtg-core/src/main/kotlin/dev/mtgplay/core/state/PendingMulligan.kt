package dev.mtgplay.core.state

import dev.mtgplay.core.identity.PlayerId

/**
 * The pre-game London-mulligan phase's progress (CR 103.4/103.5), or `null` once the phase is over
 * and turn 1 has begun. Additive, flagged core (P6.1).
 *
 * The whole phase is encoded here so the pending decision — whose keep-or-mulligan choice, or whose
 * bottom-cards choice, is due — is a pure function of the state (ADR-004). Players are processed one
 * at a time in turn order (starting player first); [deciding] is the player whose choice is pending,
 * [mulliganCount] is how many mulligans that player has taken so far, and [stage] says which of the
 * two mulligan decisions is open. When a player keeps after N mulligans they bottom N cards
 * (CR 103.5), then the next player begins; when the last player keeps, the phase ends and the game's
 * first turn begins.
 *
 * @property deciding the player whose mulligan decision is currently pending.
 * @property mulliganCount how many mulligans [deciding] has taken so far (0 before their first
 *   decision); the number of cards they bottom on keeping (capped at their hand size).
 * @property stage which mulligan decision is open for [deciding].
 */
data class PendingMulligan(
    val deciding: PlayerId,
    val mulliganCount: Int,
    val stage: MulliganStage,
) {
    init {
        require(mulliganCount >= 0) { "mulligan count must be non-negative, was $mulliganCount" }
        require(stage != MulliganStage.BOTTOM || mulliganCount > 0) {
            "CR 103.5: cards are bottomed only after at least one mulligan, but stage is BOTTOM with count 0"
        }
    }
}

/** Which of the two London-mulligan decisions is pending for the deciding player (CR 103.5). */
enum class MulliganStage {
    /** The keep-or-mulligan choice (CR 103.4): keep the drawn hand, or shuffle it away and redraw. */
    DECLARE,

    /**
     * The put-cards-on-the-bottom choice (CR 103.5): having kept after one or more mulligans, the
     * player chooses that many cards from their hand to put on the bottom of their library.
     */
    BOTTOM,
}
