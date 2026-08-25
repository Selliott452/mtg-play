package dev.mtgplay.protocol

import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.LibraryLookSource
import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.core.definition.RevealedCardOutcome
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the card-definition enums, plus the two data-free sealed families
 * ([AbilityZoneScope], [OptionalCostMode]) that serialize as enum values. Every mapping is an
 * exhaustive `when` (no `else`). The match-outcome enum lives with the result it explains, in
 * MatchResultDto.kt.
 */

/** Wire form of [CastSource] (CR 601.2a). */
@Serializable
enum class CastSourceDto { HAND, GRAVEYARD, EXILE }

/** Wire form of [AbilityZoneScope] (CR 113.6) — a data-free sealed family, so an enum on the wire. */
@Serializable
enum class AbilityZoneScopeDto { BATTLEFIELD, HAND }

/** Wire form of [OptionalCostMode] (CR 601.3b) — a data-free sealed family, so an enum on the wire. */
@Serializable
enum class OptionalCostModeDto { DISCARD_CARD, SACRIFICE_LAND }

/** Wire form of [LibraryLookSource] (CR 701.14a) — which zone a private look's pool came from. */
@Serializable
enum class LibraryLookSourceDto { TOP_OF_LIBRARY, HAND }

/**
 * Wire form of [RevealedCardOutcome] (CR 701.16a) — what happens to the card chosen from a revealed
 * hand: the owner discards it (CR 701.7a, Duress) or it is exiled and recorded as the choosing
 * source's linked exile (CR 701.3a/CR 607.2, Mesmeric Fiend). Added by `FW-HIDDENCHOICE`.
 */
@Serializable
enum class RevealedCardOutcomeDto { DISCARD, EXILE_LINKED }

/*
 * [PermanentSelectionAction]'s wire form is deliberately **not** here despite being a card-definition
 * enum: it belongs with the pending record that is its only carrier, in PermanentSelectionDtos.kt, and
 * this file is at its function budget. The grouping is by flow rather than by kind, which is the same
 * call PendingHiddenChoiceDtos.kt makes.
 */

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

/** [RevealedCardOutcome] to its wire form. */
fun RevealedCardOutcome.toDto(): RevealedCardOutcomeDto =
    when (this) {
        RevealedCardOutcome.DISCARD -> RevealedCardOutcomeDto.DISCARD
        RevealedCardOutcome.EXILE_LINKED -> RevealedCardOutcomeDto.EXILE_LINKED
    }

/** [RevealedCardOutcomeDto] back to the engine value. */
fun RevealedCardOutcomeDto.toDomain(): RevealedCardOutcome =
    when (this) {
        RevealedCardOutcomeDto.DISCARD -> RevealedCardOutcome.DISCARD
        RevealedCardOutcomeDto.EXILE_LINKED -> RevealedCardOutcome.EXILE_LINKED
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
