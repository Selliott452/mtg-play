package dev.mtgplay.protocol

import dev.mtgplay.core.definition.ExploreDestination
import dev.mtgplay.core.definition.LibraryPosition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The client-bound half of the resolution-clause requests — the mirror of `ResolutionClauseToDto.kt`,
 * split out of `SingleOptionRequestToDomain.kt` by `W10-D` when the explore converter pushed that file
 * past detekt's function budget.
 *
 * Everything here decodes a **closed vocabulary** that travels as an enum *name*: a library depth
 * (CR 401.1), an explore destination (CR 701.40a), a card type (CR 609.4). All three fail loudly on a
 * name they do not know rather than falling back to a default, because a silently substituted arm is a
 * different play than the one the deciding seat chose.
 */

/**
 * An owner's library-position choice (CR 401.1) back to the engine value. The depths travel as
 * [LibraryPosition] names; an unknown one fails loudly rather than silently becoming a different depth.
 */
internal fun libraryPositionToDomain(
    dto: DecisionRequestDto.ChooseLibraryPosition,
): DecisionRequest.ChooseLibraryPosition =
    DecisionRequest.ChooseLibraryPosition(
        dto.id.toDomain(),
        PlayerId(dto.controller),
        CardRef(dto.sourceCard),
        ObjectId(dto.permanent),
        CardRef(dto.permanentCard),
        dto.options.map { name ->
            LibraryPosition.entries.firstOrNull { it.name == name }
                ?: error("CR 401.1: unknown library position $name")
        },
    )

/**
 * An explorer's destination choice (CR 701.40a) back to the engine value. The destinations travel as
 * [ExploreDestination] names; an unknown one fails loudly rather than silently becoming the other arm.
 */
internal fun exploreDestinationToDomain(
    dto: DecisionRequestDto.ChooseExploreDestination,
): DecisionRequest.ChooseExploreDestination =
    DecisionRequest.ChooseExploreDestination(
        dto.id.toDomain(),
        PlayerId(dto.controller),
        CardRef(dto.sourceCard),
        ObjectId(dto.exploring),
        CardRef(dto.exploringCard),
        CardRef(dto.revealedCard),
        dto.options.map { name ->
            ExploreDestination.entries.firstOrNull { it.name == name }
                ?: error("CR 701.40a: unknown explore destination $name")
        },
    )
