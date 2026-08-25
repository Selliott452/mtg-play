package dev.mtgplay.protocol

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.TimedContinuousEffect
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.Serializable

/*
 * Wire mirror of a running, resolution-generated continuous effect (CR 611.2) carried by
 * [SeatViewDto.timedEffects] (`FW-DURATION`, docs/design/duration.md §13). Public in full: a spell or
 * ability resolves face-up on the public stack (CR 405) and what it did is what both players reason
 * about for the rest of the turn.
 */

/**
 * Wire form of [TimedContinuousEffect].
 *
 * [grantedKeywords] is carried as exact [Keyword] words, the choice [PrintedCharacteristicsDto]
 * makes for the same vocabulary; [duration] likewise names its sealed member, so a duration this
 * schema's engine version does not know fails loudly on decode rather than being read as
 * "until end of turn".
 *
 * @property affected the modified permanent's object id (CR 611.2c).
 * @property grantedKeywords the layer-6 keyword grants (CR 613.1f), as [Keyword] names.
 * @property powerMod the **already-snapshotted** layer-7c power modifier (CR 608.2h, CR 611.2d) —
 *   the number the pump actually is, not a formula.
 * @property toughnessMod the already-snapshotted layer-7c toughness modifier.
 * @property duration the effect's duration (CR 611.2), as its sealed member name.
 * @property timestamp the CR 613.7d timestamp, in the engine's single monotonic creation sequence.
 * @property createdOnTurn the turn number the effect was created on (CR 500).
 * @property source the resolving object's own id (CR 113.7c), or `null` where the engine had none.
 * @property sourceCard the printed name behind [source].
 */
@Serializable
data class TimedContinuousEffectDto(
    val affected: Long,
    val grantedKeywords: List<String>,
    val powerMod: Int,
    val toughnessMod: Int,
    val duration: String,
    val timestamp: Long,
    val createdOnTurn: Int,
    val source: Long?,
    val sourceCard: String,
)

/** The wire word for an [EffectDuration]; exhaustive so a new duration breaks compilation. */
private fun EffectDuration.wireName(): String =
    when (this) {
        EffectDuration.UntilEndOfTurn -> UNTIL_END_OF_TURN
    }

/** The [EffectDuration] a wire [word] names; an unknown word is version skew and fails loudly. */
private fun durationOf(word: String): EffectDuration =
    when (word) {
        UNTIL_END_OF_TURN -> EffectDuration.UntilEndOfTurn
        else -> error("unknown effect duration \"$word\" on the wire; this engine knows $UNTIL_END_OF_TURN")
    }

private const val UNTIL_END_OF_TURN: String = "UNTIL_END_OF_TURN"

/** [TimedContinuousEffect] to its wire form. */
fun TimedContinuousEffect.toDto(): TimedContinuousEffectDto =
    TimedContinuousEffectDto(
        affected = affected.value,
        grantedKeywords = modification.grantedKeywords.map { it.name },
        powerMod = modification.powerMod,
        toughnessMod = modification.toughnessMod,
        duration = duration.wireName(),
        timestamp = timestamp,
        createdOnTurn = createdOnTurn,
        source = source?.value,
        sourceCard = sourceCard.name,
    )

/** [TimedContinuousEffectDto] back to the engine value; an unknown keyword or duration fails loudly. */
fun TimedContinuousEffectDto.toDomain(): TimedContinuousEffect =
    TimedContinuousEffect(
        affected = ObjectId(affected),
        modification =
            ContinuousModification(
                grantedKeywords = grantedKeywords.map { parseVocabulary<Keyword>(it, "keyword") }.toPersistentSet(),
                powerMod = powerMod,
                toughnessMod = toughnessMod,
            ),
        duration = durationOf(duration),
        timestamp = timestamp,
        createdOnTurn = createdOnTurn,
        source = source?.let(::ObjectId),
        sourceCard = CardRef(sourceCard),
    )
