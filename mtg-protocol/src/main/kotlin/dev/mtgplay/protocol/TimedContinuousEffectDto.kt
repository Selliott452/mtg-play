package dev.mtgplay.protocol

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
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
 * @property grantedEvasions the layer-6 block-legality grants (CR 509.1b), as [Evasion] names.
 *   **This field closes a silent wire hole rather than adding a feature**: `ContinuousModification`
 *   gained `grantedEvasions` with the keyword-tail packet and this mirror was not extended, so a
 *   Gingerbrute that had made itself unblockable for the turn crossed the wire as an ordinary
 *   creature and a remote seat's reconstructed state disagreed with the engine's about a combat
 *   legality. Nothing caught it because no round-trip test carried an evasion grant.
 * @property addedCardTypes the layer-4 card-type additions (CR 613.1d), as [CardType] names
 *   (`FW-TYPECHANGE`).
 * @property addedSubtypes the layer-4 subtype additions (CR 613.1d) as their exact printed words —
 *   the same free-text vocabulary [PrintedCharacteristicsDto] carries subtypes in, because CR 205.3's
 *   word list is open.
 * @property setPower the layer-7b power the effect *sets* (CR 613.4b), or `null` if it sets none.
 *   Distinct from [powerMod] on the wire for the reason it is distinct in the engine: 7b and 7c are
 *   different sublayers and collapsing them would reorder the result.
 * @property setToughness the layer-7b toughness the effect sets (CR 613.4b), or `null`.
 * @property duration the effect's duration (CR 611.2), as its sealed member name.
 * @property timestamp the CR 613.7d timestamp, in the engine's single monotonic creation sequence.
 * @property createdOnTurn the turn number the effect was created on (CR 500).
 * @property source the resolving object's own id (CR 113.7c), or `null` where the engine had none.
 * @property sourceCard the printed name behind [source].
 * @property durationPlayer the seat an "until your next turn" [duration] names (CR 611.2, `W11`), or
 *   `null` for the two durations that name none. Defaulted so a peer on the older schema, which wrote
 *   no such field and could not have carried that duration, still decodes.
 */
@Serializable
data class TimedContinuousEffectDto(
    val affected: Long,
    val grantedKeywords: List<String>,
    val powerMod: Int,
    val toughnessMod: Int,
    val grantedEvasions: List<String>,
    val addedCardTypes: List<String>,
    val addedSubtypes: List<String>,
    val setPower: Int?,
    val setToughness: Int?,
    val duration: String,
    val timestamp: Long,
    val createdOnTurn: Int,
    val source: Long?,
    val sourceCard: String,
    val durationPlayer: Int? = null,
)

/** The wire word for an [EffectDuration]; exhaustive so a new duration breaks compilation. */
private fun EffectDuration.wireName(): String =
    when (this) {
        EffectDuration.UntilEndOfTurn -> UNTIL_END_OF_TURN
        EffectDuration.Indefinite -> INDEFINITE
        is EffectDuration.UntilYourNextTurn -> UNTIL_YOUR_NEXT_TURN
    }

/**
 * The seat [EffectDuration.UntilYourNextTurn] names (CR 611.2), or `null` for the two durations that
 * name none — the second half of the duration's wire form, in the shape the prevention wire gives a
 * colour shield: one word plus one nullable payload, rather than a word that encodes a seat number
 * inside itself and has to be parsed back out.
 */
private fun EffectDuration.wirePlayer(): Int? =
    when (this) {
        EffectDuration.UntilEndOfTurn, EffectDuration.Indefinite -> null
        is EffectDuration.UntilYourNextTurn -> player.seat
    }

/** The [EffectDuration] a wire [word] and [player] name; an unknown word is version skew and fails loudly. */
private fun durationOf(
    word: String,
    player: Int?,
): EffectDuration =
    when (word) {
        UNTIL_END_OF_TURN -> EffectDuration.UntilEndOfTurn
        INDEFINITE -> EffectDuration.Indefinite
        UNTIL_YOUR_NEXT_TURN ->
            EffectDuration.UntilYourNextTurn(
                PlayerId(
                    requireNotNull(player) {
                        "CR 611.2: an \"until your next turn\" duration on the wire must name whose turn ends it"
                    },
                ),
            )
        else ->
            error(
                "unknown effect duration \"$word\" on the wire; this engine knows " +
                    "$UNTIL_END_OF_TURN, $INDEFINITE and $UNTIL_YOUR_NEXT_TURN",
            )
    }

private const val UNTIL_END_OF_TURN: String = "UNTIL_END_OF_TURN"

/** The wire word for [EffectDuration.Indefinite] (CR 611.2b) — an effect that lasts until the game ends. */
private const val INDEFINITE: String = "INDEFINITE"

/**
 * The wire word for [EffectDuration.UntilYourNextTurn] (CR 611.2, `W11`) — an effect that ends as the
 * named seat's next turn begins. The only duration whose wire form carries a payload, which is why
 * `durationPlayer` exists alongside it rather than being folded into the word.
 */
private const val UNTIL_YOUR_NEXT_TURN: String = "UNTIL_YOUR_NEXT_TURN"

/** [TimedContinuousEffect] to its wire form. */
fun TimedContinuousEffect.toDto(): TimedContinuousEffectDto =
    TimedContinuousEffectDto(
        affected = affected.value,
        grantedKeywords = modification.grantedKeywords.map { it.name },
        powerMod = modification.powerMod,
        toughnessMod = modification.toughnessMod,
        grantedEvasions = modification.grantedEvasions.map { it.name },
        addedCardTypes = modification.addedCardTypes.map { it.name },
        addedSubtypes = modification.addedSubtypes.map { it.value },
        setPower = modification.setPower,
        setToughness = modification.setToughness,
        duration = duration.wireName(),
        durationPlayer = duration.wirePlayer(),
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
                grantedEvasions = grantedEvasions.map { parseVocabulary<Evasion>(it, "evasion") }.toPersistentSet(),
                addedCardTypes =
                    addedCardTypes.map { parseVocabulary<CardType>(it, "card type") }.toPersistentSet(),
                addedSubtypes = addedSubtypes.map(::Subtype).toPersistentSet(),
                setPower = setPower,
                setToughness = setToughness,
            ),
        duration = durationOf(duration, durationPlayer),
        timestamp = timestamp,
        createdOnTurn = createdOnTurn,
        source = source?.let(::ObjectId),
        sourceCard = CardRef(sourceCard),
    )
