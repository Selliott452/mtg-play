package dev.mtgplay.protocol

import dev.mtgplay.core.card.Quality
import dev.mtgplay.core.mana.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire form of a protection [Quality] (CR 702.16a) — what a protection ability is protection *from*.
 * Additive, flagged (`FW-PROTECT`).
 *
 * A sealed hierarchy on the wire for the same reason it is one in the engine: the quality "is
 * usually a color … but can be any characteristic value" (CR 702.16a), so a bare colour string
 * carries Mask of Law and Grace and cannot express Guardian of the Guildpact's *monocolored*. A
 * peer that met a flat string would have to guess which kind it held.
 */
@Serializable
sealed interface QualityDto {
    /** Protection from one colour (CR 702.16a), the colour named by its [Color] enum constant. */
    @Serializable
    @SerialName("color")
    data class OfColor(
        val color: String,
    ) : QualityDto

    /** Protection from **monocolored** — a source that is exactly one colour (CR 105.4). */
    @Serializable
    @SerialName("monocolored")
    data object Monocolored : QualityDto
}

/** A [Quality] to its wire form. */
fun Quality.toDto(): QualityDto =
    when (this) {
        is Quality.OfColor -> QualityDto.OfColor(color.name)
        Quality.Monocolored -> QualityDto.Monocolored
    }

/** A [QualityDto] back to the engine value; an unknown colour fails loudly. */
fun QualityDto.toDomain(): Quality =
    when (this) {
        is QualityDto.OfColor -> Quality.OfColor(parseVocabulary<Color>(color, "color"))
        QualityDto.Monocolored -> Quality.Monocolored
    }
