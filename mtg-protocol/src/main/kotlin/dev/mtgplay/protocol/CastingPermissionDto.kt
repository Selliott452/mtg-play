package dev.mtgplay.protocol

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CastCondition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.SacrificeRequirement
import dev.mtgplay.core.mana.ManaCost
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire form of a [SacrificeFilter] (CR 601.2h): which permanents a sacrifice cost may be paid with.
 *
 * The two axes are conjunctive and either may be absent, exactly as the engine value states — an empty
 * [anyOfCardTypes] names no card type and a `null` [subtype] names no subtype, and at least one of them
 * is always present.
 *
 * @property anyOfCardTypes the [CardType] names a permanent may have to match (CR 300.1); a permanent
 *   matches when it has at least one of them, or when the list is empty.
 * @property subtype the subtype name every matching permanent must have (CR 205.3), or `null`.
 */
@Serializable
data class SacrificeFilterDto(
    val anyOfCardTypes: List<String> = emptyList(),
    val subtype: String? = null,
)

/** [SacrificeFilter] to its wire form. */
fun SacrificeFilter.toDto(): SacrificeFilterDto =
    SacrificeFilterDto(anyOfCardTypes.map { it.name }.sorted(), subtype?.value)

/** [SacrificeFilterDto] back to the engine value. */
fun SacrificeFilterDto.toDomain(): SacrificeFilter =
    SacrificeFilter(
        anyOfCardTypes = anyOfCardTypes.map { parseVocabulary<CardType>(it, "card type") }.toPersistentSet(),
        subtype = subtype?.let { Subtype(it) },
    )

/**
 * Wire form of a non-mana [SacrificeRequirement] (CR 601.2h): sacrifice [count] permanents matching
 * [filter].
 *
 * **[filter] replaced a bare `subtype` string in the `W8-D` wire revision**, when Dread Return's
 * "Sacrifice three creatures" showed that a permission-side sacrifice cost can name a card type; see
 * [SacrificeRequirement].
 *
 * @property count how many permanents must be sacrificed.
 * @property filter which permanents may be chosen to pay it.
 */
@Serializable
data class SacrificeRequirementDto(
    val count: Int,
    val filter: SacrificeFilterDto,
)

/** [SacrificeRequirement] to its wire form. */
fun SacrificeRequirement.toDto(): SacrificeRequirementDto = SacrificeRequirementDto(count, filter.toDto())

/** [SacrificeRequirementDto] back to the engine value. */
fun SacrificeRequirementDto.toDomain(): SacrificeRequirement = SacrificeRequirement(count, filter.toDomain())

/**
 * Wire form of a [CastingPermission] (CR 118.9): one alternative way to cast a card. The mana cost
 * is carried as its Scryfall brace-syntax string ([ManaCost.render]/[ManaCost.parse] round-trip it
 * exactly), so no separate mana-cost DTO tree is needed. Sealed to mirror [CastingPermission]'s
 * members exhaustively.
 */
@Serializable
sealed interface CastingPermissionDto {
    /** Madness (CR 702.35): cast from exile for the madness [cost]. */
    @Serializable
    @SerialName("madness")
    data class Madness(
        val cost: String,
    ) : CastingPermissionDto

    /** Flashback (CR 702.34): cast from the graveyard for [cost] plus an optional [sacrifice]. */
    @Serializable
    @SerialName("flashback")
    data class Flashback(
        val cost: String,
        val sacrifice: SacrificeRequirementDto?,
    ) : CastingPermissionDto

    /**
     * A generic alternative cost cast from hand (CR 118.9): [cost] plus an optional [sacrifice] or
     * [revealsHand] hand reveal, available only while [condition] holds.
     *
     * [condition] and [revealsHand] arrived with `FW-ALTCOST` and are independent: a permission may be
     * gated without revealing or reveal without a gate. A `null` [condition] is the always-available
     * permission every pre-`FW-ALTCOST` card has.
     */
    @Serializable
    @SerialName("alternative_cost")
    data class AlternativeCost(
        val cost: String,
        val sacrifice: SacrificeRequirementDto?,
        val condition: CastConditionDto?,
        val revealsHand: Boolean,
    ) : CastingPermissionDto

    /**
     * Evoke (CR 702.74): cast from the hand for the evoke [cost], with the resulting permanent
     * sacrificed as it enters. Added by `W8-D`.
     */
    @Serializable
    @SerialName("evoke")
    data class Evoke(
        val cost: String,
    ) : CastingPermissionDto

    /** Escape (CR 702.139): cast from the graveyard for [cost] plus exiling [exileOthers] others. */
    @Serializable
    @SerialName("escape")
    data class Escape(
        val cost: String,
        val exileOthers: Int,
    ) : CastingPermissionDto

    /** Plot (CR 702.140): a free cast from exile; [plotCost] is the cost paid when the card was plotted. */
    @Serializable
    @SerialName("plot")
    data class Plot(
        val plotCost: String,
    ) : CastingPermissionDto

    /**
     * Rebound (CR 702.88b): a free cast from exile offered by the delayed ability, never at a plain
     * priority window. Carries no payload at all — the cost is fixed at `{0}` and the source at exile —
     * so it is a `data object` and rides the wire as its bare discriminator. Added by `FW-BLINK`.
     */
    @Serializable
    @SerialName("rebound")
    data object Rebound : CastingPermissionDto
}

/** [CastingPermission] to its wire form. */
fun CastingPermission.toDto(): CastingPermissionDto =
    when (this) {
        is CastingPermission.Madness -> CastingPermissionDto.Madness(cost.render())
        is CastingPermission.Flashback -> CastingPermissionDto.Flashback(cost.render(), sacrifice?.toDto())
        is CastingPermission.AlternativeCost ->
            CastingPermissionDto.AlternativeCost(
                cost.render(),
                sacrifice?.toDto(),
                condition?.toDto(),
                revealsHand,
            )
        is CastingPermission.Evoke -> CastingPermissionDto.Evoke(cost.render())
        is CastingPermission.Escape -> CastingPermissionDto.Escape(cost.render(), exileOthers)
        is CastingPermission.Plot -> CastingPermissionDto.Plot(plotCost.render())
        is CastingPermission.Rebound -> CastingPermissionDto.Rebound
    }

/** [CastingPermissionDto] back to the engine value. */
fun CastingPermissionDto.toDomain(): CastingPermission =
    when (this) {
        is CastingPermissionDto.Madness -> CastingPermission.Madness(ManaCost.parse(cost))
        is CastingPermissionDto.Flashback -> CastingPermission.Flashback(ManaCost.parse(cost), sacrifice?.toDomain())
        is CastingPermissionDto.AlternativeCost ->
            CastingPermission.AlternativeCost(
                ManaCost.parse(cost),
                sacrifice?.toDomain(),
                condition?.toDomain(),
                revealsHand,
            )
        is CastingPermissionDto.Evoke -> CastingPermission.Evoke(ManaCost.parse(cost))
        is CastingPermissionDto.Escape -> CastingPermission.Escape(ManaCost.parse(cost), exileOthers)
        is CastingPermissionDto.Plot -> CastingPermission.Plot(ManaCost.parse(plotCost))
        is CastingPermissionDto.Rebound -> CastingPermission.Rebound
    }

/**
 * Wire form of [dev.mtgplay.core.definition.CastCondition] (CR 118.9) — the state condition gating a
 * [CastingPermissionDto.AlternativeCost]. Additive (`FW-ALTCOST`).
 *
 * An enum rather than a sealed hierarchy because the one condition the pool prints carries no payload;
 * a condition that needs one becomes a sealed hierarchy here, which is a wire break and should be.
 */
@Serializable
enum class CastConditionDto {
    /** "If you have no land cards in hand" (CR 305) — Land Grant. */
    @SerialName("no_land_cards_in_hand")
    NO_LAND_CARDS_IN_HAND,
}

/** [dev.mtgplay.core.definition.CastCondition] to its wire form. */
fun CastCondition.toDto(): CastConditionDto =
    when (this) {
        CastCondition.NoLandCardsInHand -> CastConditionDto.NO_LAND_CARDS_IN_HAND
    }

/** [CastConditionDto] back to the engine value. */
fun CastConditionDto.toDomain(): CastCondition =
    when (this) {
        CastConditionDto.NO_LAND_CARDS_IN_HAND -> CastCondition.NoLandCardsInHand
    }
