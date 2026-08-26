package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.PreventionEffect
import dev.mtgplay.core.state.TimedPreventionEffect
import kotlinx.serialization.Serializable

/*
 * Wire mirror of a running **global** prevention effect (CR 615) carried by
 * [SeatViewDto.preventionEffects] (`FW-PREVENT2`). Public in full, for the reason
 * [TimedContinuousEffectDto] is and one sharper: an agent that cannot see which colour is shielded
 * cannot evaluate a single attack or burn spell for the rest of the turn.
 */

/**
 * Wire form of [TimedPreventionEffect].
 *
 * The sealed payload is flattened into a [kind] word plus an optional [color], rather than carried as
 * a polymorphic member, because the two members share no field at all: one is a colour and one is
 * nothing. A `kind` this schema's engine version does not know fails loudly on decode rather than
 * being read as the wrong prevention — the discipline [TimedContinuousEffectDto] applies to its
 * duration word.
 *
 * @property kind which prevention effect this is: `PREVENT_DAMAGE_FROM_COLOR` (CR 615.1, Prismatic
 *   Strands) or `DAMAGE_CANT_BE_PREVENTED` (CR 615.9, Flaring Pain).
 * @property color the shielded colour's [Color] name for `PREVENT_DAMAGE_FROM_COLOR`, and `null` for
 *   `DAMAGE_CANT_BE_PREVENTED`, which names none.
 * @property duration the effect's duration (CR 611.2), as its sealed member name.
 * @property createdOnTurn the turn number the effect was created on (CR 500).
 * @property source the resolving object's own id (CR 113.7c), or `null` where the engine had none.
 * @property sourceCard the printed name behind [source].
 */
@Serializable
data class TimedPreventionEffectDto(
    val kind: String,
    val color: String?,
    val duration: String,
    val createdOnTurn: Int,
    val source: Long?,
    val sourceCard: String,
)

/** The wire word for the kind of a [PreventionEffect]; exhaustive so a new member breaks compilation. */
private fun PreventionEffect.wireKind(): String =
    when (this) {
        is PreventionEffect.PreventDamageFromColor -> PREVENT_DAMAGE_FROM_COLOR
        PreventionEffect.DamageCantBePrevented -> DAMAGE_CANT_BE_PREVENTED
    }

/** The shielded colour of a [PreventionEffect], or `null` for a member that names none. */
private fun PreventionEffect.wireColor(): String? =
    when (this) {
        is PreventionEffect.PreventDamageFromColor -> color.name
        PreventionEffect.DamageCantBePrevented -> null
    }

/** The [PreventionEffect] a wire [kind] and [color] name; version skew fails loudly. */
private fun preventionEffectOf(
    kind: String,
    color: String?,
): PreventionEffect =
    when (kind) {
        PREVENT_DAMAGE_FROM_COLOR ->
            PreventionEffect.PreventDamageFromColor(
                parseVocabulary<Color>(
                    requireNotNull(color) { "CR 615.1: a colour shield on the wire must name its colour" },
                    "color",
                ),
            )
        DAMAGE_CANT_BE_PREVENTED -> PreventionEffect.DamageCantBePrevented
        else ->
            error(
                "unknown prevention effect \"$kind\" on the wire; this engine knows " +
                    "$PREVENT_DAMAGE_FROM_COLOR and $DAMAGE_CANT_BE_PREVENTED",
            )
    }

/**
 * The wire word for an [EffectDuration]; exhaustive so a new duration breaks compilation.
 *
 * [EffectDuration.Indefinite] (CR 611.2b) is a **real** duration this schema refuses to write for a
 * *prevention* effect, and the refusal is the point: no card in the pool prints a durationless shield,
 * so a stored one would mean a prevention effect that never wears off — the worst-behaved bug this
 * framework can have (`FW-PREVENT2`) and one that leaves no other trace. Erroring here means it is
 * caught the first time such a state is serialised rather than never.
 */
private fun EffectDuration.preventionWireName(): String =
    when (this) {
        EffectDuration.UntilEndOfTurn -> PREVENTION_UNTIL_END_OF_TURN
        EffectDuration.Indefinite ->
            error(
                "CR 611.2b: no prevention effect in this pool is durationless, so an indefinite one " +
                    "in the store is an engine defect rather than a wire-format gap",
            )
    }

/** The [EffectDuration] a wire [word] names; an unknown word is version skew and fails loudly. */
private fun preventionDurationOf(word: String): EffectDuration =
    when (word) {
        PREVENTION_UNTIL_END_OF_TURN -> EffectDuration.UntilEndOfTurn
        else ->
            error(
                "unknown effect duration \"$word\" on the wire; this engine knows " +
                    PREVENTION_UNTIL_END_OF_TURN,
            )
    }

private const val PREVENT_DAMAGE_FROM_COLOR: String = "PREVENT_DAMAGE_FROM_COLOR"

private const val DAMAGE_CANT_BE_PREVENTED: String = "DAMAGE_CANT_BE_PREVENTED"

private const val PREVENTION_UNTIL_END_OF_TURN: String = "UNTIL_END_OF_TURN"

/** [TimedPreventionEffect] to its wire form. */
fun TimedPreventionEffect.toDto(): TimedPreventionEffectDto =
    TimedPreventionEffectDto(
        kind = effect.wireKind(),
        color = effect.wireColor(),
        duration = duration.preventionWireName(),
        createdOnTurn = createdOnTurn,
        source = source?.value,
        sourceCard = sourceCard.name,
    )

/** [TimedPreventionEffectDto] back to the engine value; an unknown kind, colour or duration fails loudly. */
fun TimedPreventionEffectDto.toDomain(): TimedPreventionEffect =
    TimedPreventionEffect(
        effect = preventionEffectOf(kind, color),
        duration = preventionDurationOf(duration),
        createdOnTurn = createdOnTurn,
        source = source?.let(::ObjectId),
        sourceCard = CardRef(sourceCard),
    )
