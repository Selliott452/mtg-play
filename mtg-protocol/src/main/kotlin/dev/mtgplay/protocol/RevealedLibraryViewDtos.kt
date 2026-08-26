package dev.mtgplay.protocol

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.PendingExploreView
import dev.mtgplay.rules.PendingRevealView
import kotlinx.serialization.Serializable

/*
 * The two seat-view payloads that name a card **inside a library** — CR 701.16's reveal selection and
 * CR 701.40a's explore. Split out of `ViewDecisionDtos.kt` by `W10-D`, when the second one pushed that
 * file past detekt's function budget.
 *
 * The seam is the sharpest one in the whole view codec. A library is the one zone [SeatViewDto] never
 * discloses; these two payloads exist because a printed card said *reveal*, which makes one card in it
 * public to every seat for exactly as long as a pause is open. Anything added beside them has to make the
 * same argument from the same word, which is easier to notice in a file whose whole subject is that.
 */

/**
 * Wire form of a [PendingRevealView] — the revealed cards and the keeps gathered so far, both public
 * to both seats (CR 701.16). [kept] is non-empty only part-way through a multi-keep clause
 * (Kruphix's Insight's "up to three"), so it defaults to empty on the wire.
 */
@Serializable
data class PendingRevealViewDto(
    val decider: Int,
    val revealed: List<GameObjectDto>,
    val kept: List<GameObjectDto> = emptyList(),
)

/** [PendingRevealView] to its wire form. */
fun PendingRevealView.toDto(): PendingRevealViewDto =
    PendingRevealViewDto(decider.seat, revealed.map { it.toDto() }, kept.map { it.toDto() })

/** [PendingRevealViewDto] back to the engine value. */
fun PendingRevealViewDto.toDomain(): PendingRevealView =
    PendingRevealView(PlayerId(decider), revealed.map { it.toDomain() }, kept.map { it.toDomain() })

/**
 * Wire form of a [PendingExploreView] (CR 701.40a) — an explore paused on its last sentence: the
 * deciding seat, the permanent that explored, and the **revealed** card, which is still in a library and
 * is public to both seats because CR 701.40a revealed it. Added by `W10-D`; the second thing on this wire
 * that names a library card, after [PendingRevealViewDto].
 */
@Serializable
data class PendingExploreViewDto(
    val decider: Int,
    val exploring: GameObjectDto,
    val revealed: GameObjectDto,
)

/** [PendingExploreView] to its wire form. */
fun PendingExploreView.toDto(): PendingExploreViewDto =
    PendingExploreViewDto(decider.seat, exploring.toDto(), revealed.toDto())

/** [PendingExploreViewDto] back to the engine value. */
fun PendingExploreViewDto.toDomain(): PendingExploreView =
    PendingExploreView(PlayerId(decider), exploring.toDomain(), revealed.toDomain())
