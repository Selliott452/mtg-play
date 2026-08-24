package dev.mtgplay.protocol

import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.LibraryLookSource
import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.core.event.LossReason
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the card-definition/outcome enums, plus the two data-free sealed families
 * ([AbilityZoneScope], [OptionalCostMode]) that serialize as enum values. Every mapping is an
 * exhaustive `when` (no `else`).
 */

/** Wire form of [CastSource] (CR 601.2a). */
@Serializable
enum class CastSourceDto { HAND, GRAVEYARD, EXILE }

/** Wire form of [LossReason] (CR 104.3). */
@Serializable
enum class LossReasonDto { LIFE_TOTAL_ZERO_OR_LESS, ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY }

/** Wire form of [AbilityZoneScope] (CR 113.6) — a data-free sealed family, so an enum on the wire. */
@Serializable
enum class AbilityZoneScopeDto { BATTLEFIELD, HAND }

/** Wire form of [OptionalCostMode] (CR 601.3b) — a data-free sealed family, so an enum on the wire. */
@Serializable
enum class OptionalCostModeDto { DISCARD_CARD, SACRIFICE_LAND }

/** Wire form of [LibraryLookSource] (CR 701.14a) — which zone a private look's pool came from. */
@Serializable
enum class LibraryLookSourceDto { TOP_OF_LIBRARY, HAND }

/** [LibraryLookSource] to its wire form. */
fun LibraryLookSource.toDto(): LibraryLookSourceDto =
    when (this) {
        LibraryLookSource.TOP_OF_LIBRARY -> LibraryLookSourceDto.TOP_OF_LIBRARY
        LibraryLookSource.HAND -> LibraryLookSourceDto.HAND
    }

/** [LibraryLookSourceDto] back to the engine value. */
fun LibraryLookSourceDto.toDomain(): LibraryLookSource =
    when (this) {
        LibraryLookSourceDto.TOP_OF_LIBRARY -> LibraryLookSource.TOP_OF_LIBRARY
        LibraryLookSourceDto.HAND -> LibraryLookSource.HAND
    }

/** [CastSource] to its wire form. */
fun CastSource.toDto(): CastSourceDto =
    when (this) {
        CastSource.HAND -> CastSourceDto.HAND
        CastSource.GRAVEYARD -> CastSourceDto.GRAVEYARD
        CastSource.EXILE -> CastSourceDto.EXILE
    }

/** [CastSourceDto] back to the engine value. */
fun CastSourceDto.toDomain(): CastSource =
    when (this) {
        CastSourceDto.HAND -> CastSource.HAND
        CastSourceDto.GRAVEYARD -> CastSource.GRAVEYARD
        CastSourceDto.EXILE -> CastSource.EXILE
    }

/** [LossReason] to its wire form. */
fun LossReason.toDto(): LossReasonDto =
    when (this) {
        LossReason.LIFE_TOTAL_ZERO_OR_LESS -> LossReasonDto.LIFE_TOTAL_ZERO_OR_LESS
        LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY -> LossReasonDto.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY
    }

/** [LossReasonDto] back to the engine value. */
fun LossReasonDto.toDomain(): LossReason =
    when (this) {
        LossReasonDto.LIFE_TOTAL_ZERO_OR_LESS -> LossReason.LIFE_TOTAL_ZERO_OR_LESS
        LossReasonDto.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY -> LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY
    }

/** [AbilityZoneScope] to its wire form. */
fun AbilityZoneScope.toDto(): AbilityZoneScopeDto =
    when (this) {
        AbilityZoneScope.Battlefield -> AbilityZoneScopeDto.BATTLEFIELD
        AbilityZoneScope.Hand -> AbilityZoneScopeDto.HAND
    }

/** [AbilityZoneScopeDto] back to the engine value. */
fun AbilityZoneScopeDto.toDomain(): AbilityZoneScope =
    when (this) {
        AbilityZoneScopeDto.BATTLEFIELD -> AbilityZoneScope.Battlefield
        AbilityZoneScopeDto.HAND -> AbilityZoneScope.Hand
    }

/** [OptionalCostMode] to its wire form. */
fun OptionalCostMode.toDto(): OptionalCostModeDto =
    when (this) {
        OptionalCostMode.DiscardCard -> OptionalCostModeDto.DISCARD_CARD
        OptionalCostMode.SacrificeLand -> OptionalCostModeDto.SACRIFICE_LAND
    }

/** [OptionalCostModeDto] back to the engine value. */
fun OptionalCostModeDto.toDomain(): OptionalCostMode =
    when (this) {
        OptionalCostModeDto.DISCARD_CARD -> OptionalCostMode.DiscardCard
        OptionalCostModeDto.SACRIFICE_LAND -> OptionalCostMode.SacrificeLand
    }
