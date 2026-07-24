package dev.mtgplay.protocol

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.MatchResult
import kotlinx.serialization.Serializable

/**
 * Wire form of a [MatchResult] (CR 104.2a): who won, who lost, and why.
 *
 * @property winner the winning seat's index.
 * @property loser the losing seat's index.
 * @property reason why the loser lost (CR 104.3).
 */
@Serializable
data class MatchResultDto(
    val winner: Int,
    val loser: Int,
    val reason: LossReasonDto,
)

/** [MatchResult] to its wire form. */
fun MatchResult.toDto(): MatchResultDto = MatchResultDto(winner.seat, loser.seat, reason.toDto())

/** [MatchResultDto] back to the engine value. */
fun MatchResultDto.toDomain(): MatchResult = MatchResult(PlayerId(winner), PlayerId(loser), reason.toDomain())
