package dev.mtgplay.protocol

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.PrintedCardView
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the public card-characteristics nouns carried by [SeatViewDto.cards] (ADR-007 +
 * ADR-008): the printed characteristics of the cards a seat's view names, and the token fact.
 *
 * The type-line vocabularies ([CardType], [Supertype], [Subtype], [Keyword], [Evasion]) are carried
 * as their **exact words** rather than as mirrored DTO enums — the same choice [CastingPermissionDto]
 * makes for [ManaCost] (its Scryfall brace string). These are flat, data-free vocabularies whose wire
 * form is the word itself, so a mirror would add no decision a reviewer must make, only a table to
 * keep in sync; and a word this schema does not know is rejected loudly on decode rather than
 * silently dropped. `PrintedCardDtoSpec` pins the round-trip over **every** member of each
 * vocabulary, so a new member is covered automatically instead of breaking a hand-written mapping.
 */

/** Wire form of [PrintedPowerToughness] (CR 208.1). */
@Serializable
data class PrintedPowerToughnessDto(
    val power: Int,
    val toughness: Int,
)

/**
 * Wire form of [PrintedCharacteristics] (CR 109.3) — printed values only.
 *
 * @property name the exact printed (oracle) name (CR 201).
 * @property manaCost the printed mana cost in Scryfall brace syntax (CR 202), or `null` for a card
 *   with no mana cost; absence is not `{0}`.
 * @property supertypes the printed supertypes (CR 205.4) as [Supertype] names.
 * @property cardTypes the printed card types (CR 205.2) as [CardType] names; never empty.
 * @property subtypes the printed subtypes (CR 205.3) as their exact printed words.
 * @property powerToughness the printed power/toughness box (CR 208.1); present exactly for creatures.
 * @property keywords the printed keyword abilities (CR 702) as [Keyword] names.
 * @property evasions the printed non-keyword evasion abilities (CR 509.1b) as [Evasion] names.
 * @property protections the printed protection abilities (CR 702.16), one [QualityDto] per quality.
 */
@Serializable
data class PrintedCharacteristicsDto(
    val name: String,
    val manaCost: String?,
    val supertypes: List<String>,
    val cardTypes: List<String>,
    val subtypes: List<String>,
    val powerToughness: PrintedPowerToughnessDto?,
    val keywords: List<String>,
    val evasions: List<String>,
    val protections: List<QualityDto>,
)

/**
 * Wire form of a [PrintedCardView] — the public half of one card's definition (ADR-007): its printed
 * characteristics plus whether it is a token (CR 111). Behaviour never reaches the wire.
 */
@Serializable
data class PrintedCardViewDto(
    val characteristics: PrintedCharacteristicsDto,
    val isToken: Boolean,
)

/** [PrintedPowerToughness] to its wire form. */
fun PrintedPowerToughness.toDto(): PrintedPowerToughnessDto = PrintedPowerToughnessDto(power, toughness)

/** [PrintedPowerToughnessDto] back to the engine value. */
fun PrintedPowerToughnessDto.toDomain(): PrintedPowerToughness = PrintedPowerToughness(power, toughness)

/** [PrintedCharacteristics] to its wire form. */
fun PrintedCharacteristics.toDto(): PrintedCharacteristicsDto =
    PrintedCharacteristicsDto(
        name = name,
        manaCost = manaCost?.render(),
        supertypes = supertypes.map { it.name },
        cardTypes = cardTypes.map { it.name },
        subtypes = subtypes.map { it.value },
        powerToughness = powerToughness?.toDto(),
        keywords = keywords.map { it.name },
        evasions = evasions.map { it.name },
        protections = protections.map { it.toDto() },
    )

/** [PrintedCharacteristicsDto] back to the engine value; an unknown vocabulary word fails loudly. */
fun PrintedCharacteristicsDto.toDomain(): PrintedCharacteristics =
    PrintedCharacteristics(
        name = name,
        manaCost = manaCost?.let(ManaCost::parse),
        supertypes = supertypes.map { parseVocabulary<Supertype>(it, "supertype") }.toPersistentSet(),
        cardTypes = cardTypes.map { parseVocabulary<CardType>(it, "card type") }.toPersistentSet(),
        subtypes = subtypes.map(::Subtype).toPersistentSet(),
        powerToughness = powerToughness?.toDomain(),
        keywords = keywords.map { parseVocabulary<Keyword>(it, "keyword") }.toPersistentSet(),
        evasions = evasions.map { parseVocabulary<Evasion>(it, "evasion") }.toPersistentSet(),
        protections = protections.map { it.toDomain() }.toPersistentSet(),
    )

/** [PrintedCardView] to its wire form. */
fun PrintedCardView.toDto(): PrintedCardViewDto = PrintedCardViewDto(characteristics.toDto(), isToken)

/** [PrintedCardViewDto] back to the engine value. */
fun PrintedCardViewDto.toDomain(): PrintedCardView = PrintedCardView(characteristics.toDomain(), isToken)

/**
 * The [E] member printed as [word], or a loud failure naming the vocabulary — a wire word this
 * schema's engine version does not know is a version skew, never something to drop silently.
 *
 * Internal rather than file-private since `FW-DURATION`: [TimedContinuousEffectDto] carries the same
 * [Keyword] vocabulary and must fail identically on an unknown word rather than with its own message.
 */
internal inline fun <reified E : Enum<E>> parseVocabulary(
    word: String,
    vocabulary: String,
): E =
    requireNotNull(enumValues<E>().firstOrNull { it.name == word }) {
        "unknown $vocabulary \"$word\" on the wire; this schema knows ${enumValues<E>().joinToString { it.name }}"
    }
