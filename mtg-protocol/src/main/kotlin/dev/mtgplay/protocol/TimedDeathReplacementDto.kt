package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.DeathReplacement
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.TimedDeathReplacement
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.Serializable

/*
 * Wire mirror of a running **delayed death replacement** (CR 614.1a) carried by
 * [SeatViewDto.deathReplacements] (`W9-D`). Public in full, for the reason [TimedPreventionEffectDto]
 * is: which permanents will be exiled rather than die decides every blocking, sacrifice, and graveyard
 * line for the rest of the turn, and the affected ids name battlefield permanents both seats already
 * see (CR 400.2).
 */

/**
 * Wire form of [TimedDeathReplacement].
 *
 * The sealed payload is flattened into a [kind] word, for [TimedPreventionEffectDto]'s reason and more
 * simply: the one member carries no fields at all. A `kind` this schema's engine version does not know
 * fails loudly on decode rather than being read as the wrong replacement.
 *
 * @property kind which death replacement this is: `EXILE_INSTEAD` (CR 614.1a, Torch the Tower).
 * @property affected the watched permanents' object ids (CR 400.7), in a **sorted** order. The engine
 *   value is a set, which has no order to preserve; sorting makes the wire form of one replacement
 *   byte-identical across encodes, which is what a replay comparison needs.
 * @property duration the replacement's duration (CR 611.2), as its sealed member name.
 * @property createdOnTurn the turn number the replacement was created on (CR 500).
 * @property source the resolving object's own id (CR 113.7c), or `null` where the engine had none.
 * @property sourceCard the printed name behind [source].
 */
@Serializable
data class TimedDeathReplacementDto(
    val kind: String,
    val affected: List<Long>,
    val duration: String,
    val createdOnTurn: Int,
    val source: Long?,
    val sourceCard: String,
)

/** The wire word for a [DeathReplacement]; exhaustive so a new member breaks compilation. */
private fun DeathReplacement.wireKind(): String =
    when (this) {
        DeathReplacement.ExileInstead -> EXILE_INSTEAD
    }

/** The [DeathReplacement] a wire [kind] names; version skew fails loudly. */
private fun deathReplacementOf(kind: String): DeathReplacement =
    when (kind) {
        EXILE_INSTEAD -> DeathReplacement.ExileInstead
        else -> error("unknown death replacement \"$kind\" on the wire; this engine knows $EXILE_INSTEAD")
    }

/** The wire word for an [EffectDuration]; exhaustive so a new duration breaks compilation. */
private fun EffectDuration.deathReplacementWireName(): String =
    when (this) {
        EffectDuration.UntilEndOfTurn -> DEATH_REPLACEMENT_UNTIL_END_OF_TURN
        // CR 611.2b: no pool card prints a durationless death replacement, but the duration type is
        // shared with the continuous-effect and prevention stores, so the wire has to name every member
        // or a future one would be silently unrepresentable here alone.
        EffectDuration.Indefinite -> DEATH_REPLACEMENT_INDEFINITE
    }

/** The [EffectDuration] a wire [word] names; an unknown word is version skew and fails loudly. */
private fun deathReplacementDurationOf(word: String): EffectDuration =
    when (word) {
        DEATH_REPLACEMENT_UNTIL_END_OF_TURN -> EffectDuration.UntilEndOfTurn
        DEATH_REPLACEMENT_INDEFINITE -> EffectDuration.Indefinite
        else ->
            error(
                "unknown effect duration \"$word\" on the wire; this engine knows " +
                    DEATH_REPLACEMENT_UNTIL_END_OF_TURN,
            )
    }

private const val EXILE_INSTEAD: String = "EXILE_INSTEAD"

private const val DEATH_REPLACEMENT_UNTIL_END_OF_TURN: String = "UNTIL_END_OF_TURN"

/** The wire word for a durationless death replacement (CR 611.2b). */
private const val DEATH_REPLACEMENT_INDEFINITE: String = "indefinite"

/** [TimedDeathReplacement] to its wire form. */
fun TimedDeathReplacement.toDto(): TimedDeathReplacementDto =
    TimedDeathReplacementDto(
        kind = effect.wireKind(),
        affected = affected.map { it.value }.sorted(),
        duration = duration.deathReplacementWireName(),
        createdOnTurn = createdOnTurn,
        source = source?.value,
        sourceCard = sourceCard.name,
    )

/** [TimedDeathReplacementDto] back to the engine value; an unknown kind or duration fails loudly. */
fun TimedDeathReplacementDto.toDomain(): TimedDeathReplacement =
    TimedDeathReplacement(
        effect = deathReplacementOf(kind),
        affected = affected.map(::ObjectId).toPersistentSet(),
        duration = deathReplacementDurationOf(duration),
        createdOnTurn = createdOnTurn,
        source = source?.let(::ObjectId),
        sourceCard = CardRef(sourceCard),
    )
