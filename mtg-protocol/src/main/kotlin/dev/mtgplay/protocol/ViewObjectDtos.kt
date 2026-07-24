package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.HandView
import dev.mtgplay.rules.PlayerView
import dev.mtgplay.rules.StackEntryView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the object-shaped per-seat view nouns (ADR-007): the definition-free stack
 * projection, the hand filtered to own-contents-vs-count, and the public per-seat player view.
 */

/** Wire form of [StackEntryView] — the public facts of one stack entry (CR 405). */
@Serializable
sealed interface StackEntryViewDto {
    /** Wire form of [StackEntryView.SpellOnStack] (CR 112.1). */
    @Serializable
    @SerialName("spell_on_stack")
    data class SpellOnStack(
        val objectId: Long,
        val card: String,
        val controller: Int,
        val targets: List<TargetDto>,
    ) : StackEntryViewDto

    /** Wire form of [StackEntryView.TriggeredAbilityOnStack] (CR 113.3c). */
    @Serializable
    @SerialName("triggered_ability_on_stack")
    data class TriggeredAbilityOnStack(
        val sourceId: Long,
        val sourceCard: String,
        val controller: Int,
    ) : StackEntryViewDto

    /** Wire form of [StackEntryView.ActivatedAbilityOnStack] (CR 113.3b). */
    @Serializable
    @SerialName("activated_ability_on_stack")
    data class ActivatedAbilityOnStack(
        val sourceId: Long,
        val sourceCard: String,
        val controller: Int,
    ) : StackEntryViewDto
}

/** [StackEntryView] to its wire form. */
fun StackEntryView.toDto(): StackEntryViewDto =
    when (this) {
        is StackEntryView.SpellOnStack ->
            StackEntryViewDto.SpellOnStack(objectId.value, card.name, controller.seat, targets.map { it.toDto() })
        is StackEntryView.TriggeredAbilityOnStack ->
            StackEntryViewDto.TriggeredAbilityOnStack(sourceId.value, sourceCard.name, controller.seat)
        is StackEntryView.ActivatedAbilityOnStack ->
            StackEntryViewDto.ActivatedAbilityOnStack(sourceId.value, sourceCard.name, controller.seat)
    }

/** [StackEntryViewDto] back to the engine value. */
fun StackEntryViewDto.toDomain(): StackEntryView =
    when (this) {
        is StackEntryViewDto.SpellOnStack ->
            StackEntryView.SpellOnStack(
                ObjectId(objectId),
                CardRef(card),
                PlayerId(controller),
                targets.map { it.toDomain() },
            )
        is StackEntryViewDto.TriggeredAbilityOnStack ->
            StackEntryView.TriggeredAbilityOnStack(ObjectId(sourceId), CardRef(sourceCard), PlayerId(controller))
        is StackEntryViewDto.ActivatedAbilityOnStack ->
            StackEntryView.ActivatedAbilityOnStack(ObjectId(sourceId), CardRef(sourceCard), PlayerId(controller))
    }

/** Wire form of a [HandView] — the viewer's own hand in full, or an opponent's as a count only. */
@Serializable
sealed interface HandViewDto {
    /** The viewer's own hand contents (CR 402). */
    @Serializable
    @SerialName("revealed")
    data class Revealed(
        val cards: List<GameObjectDto>,
    ) : HandViewDto

    /** An opponent's hand as a count only (CR 402, ADR-007). */
    @Serializable
    @SerialName("concealed")
    data class Concealed(
        val count: Int,
    ) : HandViewDto
}

/** [HandView] to its wire form. */
fun HandView.toDto(): HandViewDto =
    when (this) {
        is HandView.Revealed -> HandViewDto.Revealed(cards.map { it.toDto() })
        is HandView.Concealed -> HandViewDto.Concealed(count)
    }

/** [HandViewDto] back to the engine value. */
fun HandViewDto.toDomain(): HandView =
    when (this) {
        is HandViewDto.Revealed -> HandView.Revealed(cards.map { it.toDomain() })
        is HandViewDto.Concealed -> HandView.Concealed(count)
    }

/**
 * Wire form of a [PlayerView] (ADR-007): every public per-seat fact, the hand filtered to
 * [HandViewDto], and the library as a [libraryCount] only.
 */
@Serializable
data class PlayerViewDto(
    val seat: Int,
    val life: Int,
    val hand: HandViewDto,
    val libraryCount: Int,
    val graveyard: List<GameObjectDto>,
    val manaPool: List<ManaTypeDto>,
    val priorityStatus: PriorityStatusDto,
    val attemptedDrawFromEmptyLibrary: Boolean,
    val decisionsAnswered: Int,
    val drawsThisTurn: Int,
)

/** [PlayerView] to its wire form. */
fun PlayerView.toDto(): PlayerViewDto =
    PlayerViewDto(
        seat = seat.seat,
        life = life,
        hand = hand.toDto(),
        libraryCount = libraryCount,
        graveyard = graveyard.map { it.toDto() },
        manaPool = manaPool.map { it.toDto() },
        priorityStatus = priorityStatus.toDto(),
        attemptedDrawFromEmptyLibrary = attemptedDrawFromEmptyLibrary,
        decisionsAnswered = decisionsAnswered,
        drawsThisTurn = drawsThisTurn,
    )

/** [PlayerViewDto] back to the engine value. */
fun PlayerViewDto.toDomain(): PlayerView =
    PlayerView(
        seat = PlayerId(seat),
        life = life,
        hand = hand.toDomain(),
        libraryCount = libraryCount,
        graveyard = graveyard.map { it.toDomain() },
        manaPool = manaPool.map { it.toDomain() },
        priorityStatus = priorityStatus.toDomain(),
        attemptedDrawFromEmptyLibrary = attemptedDrawFromEmptyLibrary,
        decisionsAnswered = decisionsAnswered,
        drawsThisTurn = drawsThisTurn,
    )
