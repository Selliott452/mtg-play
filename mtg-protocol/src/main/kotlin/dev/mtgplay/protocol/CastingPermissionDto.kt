package dev.mtgplay.protocol

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CastCondition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.SacrificeRequirement
import dev.mtgplay.core.definition.TapRequirement
import dev.mtgplay.core.mana.Color
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

/**
 * Wire form of a non-mana [TapRequirement] (CR 601.2h, CR 702.34c): tap [count] untapped permanents of
 * [cardType] and [color] the caster controls. Additive (`FW-PREVENT2`).
 *
 * The two axes ride as their vocabulary words, the choice [PrintedCharacteristicsDto] makes for the
 * same enums, so a colour or card type this schema's engine version does not know fails loudly on
 * decode rather than silently matching nothing.
 *
 * @property count how many permanents must be tapped.
 * @property color the required colour, as a [Color] name.
 * @property cardType the required card type, as a [CardType] name.
 */
@Serializable
data class TapRequirementDto(
    val count: Int,
    val color: String,
    val cardType: String,
)

/** [TapRequirement] to its wire form. */
fun TapRequirement.toDto(): TapRequirementDto = TapRequirementDto(count, color.name, cardType.name)

/** [TapRequirementDto] back to the engine value; an unknown colour or card type fails loudly. */
fun TapRequirementDto.toDomain(): TapRequirement =
    TapRequirement(count, parseVocabulary<Color>(color, "color"), parseVocabulary<CardType>(cardType, "card type"))

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

    /**
     * Flashback (CR 702.34): cast from the graveyard for [cost] plus an optional [sacrifice] or [tap].
     *
     * [tap] arrived with `FW-PREVENT2` — Prismatic Strands' "Flashback—Tap an untapped white creature
     * you control" — and is independent of [sacrifice] for the reason CR 702.34c gives: a flashback
     * cost may include more than mana, and the gauntlet prints both non-mana shapes.
     */
    @Serializable
    @SerialName("flashback")
    data class Flashback(
        val cost: String,
        val sacrifice: SacrificeRequirementDto?,
        val tap: TapRequirementDto?,
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

    /**
     * Prototype (CR 702.160, CR 718): cast from the hand for the prototyped [cost], with the spell and
     * the permanent it becomes taking the card's alternative [power]/[toughness] and the colours of
     * [cost] (CR 718.3b). Added by `W9-G`.
     *
     * **The only permission whose payload is not purely a cost**, because it is the only one that
     * changes what the object is rather than what it costs. A peer that rendered only [cost] would show
     * a `{3}{G}` Boulderbranch Golem still reading 6/5, which is not a card.
     */
    @Serializable
    @SerialName("prototype")
    data class Prototype(
        val cost: String,
        val power: Int,
        val toughness: Int,
    ) : CastingPermissionDto

    /**
     * Cascade (CR 702.85): a free cast from exile offered while the cascade ability resolves, never at a
     * plain priority window. Carries no payload — the cost is fixed at `{0}` and the source at exile —
     * so it is a `data object` and rides the wire as its bare discriminator, exactly as [Rebound] does.
     * Added by `W9-G`.
     */
    @Serializable
    @SerialName("cascade")
    data object Cascade : CastingPermissionDto

    /**
     * Adventure (CR 715.3): cast from the hand as the card's inset-frame face — an instant or sorcery
     * named [faceName], for [cost] instead of the card's printed one. Added by `W10-B`.
     *
     * **[faceName] is what makes the option legible, and the card ref cannot supply it.** A
     * [dev.mtgplay.core.identity.CardRef] is the *card's* name in every zone (CR 715.2c — an adventurer
     * card is one card), so a peer offered two options for the same ref would otherwise see "Cast Fang
     * Dragon" twice and have no way to tell the `{5}{R}{R}` 6/3 creature from the `{1}{R}` sweeper.
     *
     * **The face's rules text is deliberately absent.** A face is a whole second definition —
     * resolution effects and targeting specs, which are function values — and the wire carries static
     * card data by name throughout (`PrintedCardView`, `StackEntryView`). Sending the name is what lets
     * a peer round-trip the permission unchanged; sending the definition would be a payload nothing
     * could reconstruct.
     */
    @Serializable
    @SerialName("adventure")
    data class Adventure(
        val cost: String,
        val faceName: String,
    ) : CastingPermissionDto

    /**
     * Omen (CR 720.3): cast from the hand as the card's inset-frame face — an instant or sorcery named
     * [faceName], for [cost] instead of the card's printed one. Added by `W10-B`.
     *
     * [Adventure]'s twin on the wire and its opposite in play: an Omen spell that resolves is shuffled
     * into its owner's library (CR 720.3d) rather than exiled for a later cast, so the discriminator is
     * the one thing telling a peer whether choosing this option banks the card or spends it.
     */
    @Serializable
    @SerialName("omen")
    data class Omen(
        val cost: String,
        val faceName: String,
    ) : CastingPermissionDto

    /**
     * Bestow (CR 702.103): cast from the hand for the bestow [cost], **as an Aura spell with enchant
     * creature**. Added by `W10-C`.
     *
     * [Prototype]'s sibling in the one way that matters on the wire: both change what the object *is*
     * and not only what it costs, so a peer that rendered the cost alone would be showing the wrong
     * card. Here the payload is nonetheless just the cost, and that is not an omission — everything
     * else bestow does is fixed by CR 702.103b (the spell is an Aura, its enchant restriction is
     * "creature") or is a static ability the card declares for itself (the type change while attached).
     * A restriction field would carry the same value on every bestow card ever printed.
     */
    @Serializable
    @SerialName("bestow")
    data class Bestow(
        val cost: String,
    ) : CastingPermissionDto
}

/** [CastingPermission] to its wire form. */
fun CastingPermission.toDto(): CastingPermissionDto =
    when (this) {
        is CastingPermission.Madness -> CastingPermissionDto.Madness(cost.render())
        is CastingPermission.Flashback ->
            CastingPermissionDto.Flashback(cost.render(), sacrifice?.toDto(), tap?.toDto())
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
        is CastingPermission.Prototype -> CastingPermissionDto.Prototype(cost.render(), power, toughness)
        is CastingPermission.Cascade -> CastingPermissionDto.Cascade
        is CastingPermission.Adventure -> CastingPermissionDto.Adventure(cost.render(), faceName)
        is CastingPermission.Omen -> CastingPermissionDto.Omen(cost.render(), faceName)
        is CastingPermission.Bestow -> CastingPermissionDto.Bestow(cost.render())
    }

/** [CastingPermissionDto] back to the engine value. */
fun CastingPermissionDto.toDomain(): CastingPermission =
    when (this) {
        is CastingPermissionDto.Madness -> CastingPermission.Madness(ManaCost.parse(cost))
        is CastingPermissionDto.Flashback ->
            CastingPermission.Flashback(ManaCost.parse(cost), sacrifice?.toDomain(), tap?.toDomain())
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
        is CastingPermissionDto.Prototype -> CastingPermission.Prototype(ManaCost.parse(cost), power, toughness)
        is CastingPermissionDto.Cascade -> CastingPermission.Cascade
        is CastingPermissionDto.Adventure -> CastingPermission.Adventure(ManaCost.parse(cost), faceName)
        is CastingPermissionDto.Omen -> CastingPermission.Omen(ManaCost.parse(cost), faceName)
        is CastingPermissionDto.Bestow -> CastingPermission.Bestow(ManaCost.parse(cost))
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
