package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.DecisionRequestKind
import dev.mtgplay.rules.DecisionView
import dev.mtgplay.rules.PendingHandRevealView
import dev.mtgplay.rules.PendingRevealView
import dev.mtgplay.rules.PendingTriggerView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the trigger, reveal, and decision-context per-seat view nouns (ADR-007): the
 * definition-free pending-trigger projection, the publicly revealed cards (of a library reveal and
 * of a revealed hand alike), and the decision filtered to the deciding seat (others see only who
 * decides and the broad kind).
 */

/** Wire form of a [PendingTriggerView] — a fired trigger's public last-known information (CR 603.3). */
@Serializable
data class PendingTriggerViewDto(
    val sourceId: Long,
    val sourceCard: String,
    val controller: Int,
    val amount: Int,
    val subject: Long?,
)

/** [PendingTriggerView] to its wire form. */
fun PendingTriggerView.toDto(): PendingTriggerViewDto =
    PendingTriggerViewDto(sourceId.value, sourceCard.name, controller.seat, amount, subject?.value)

/** [PendingTriggerViewDto] back to the engine value. */
fun PendingTriggerViewDto.toDomain(): PendingTriggerView =
    PendingTriggerView(ObjectId(sourceId), CardRef(sourceCard), PlayerId(controller), amount, subject?.let(::ObjectId))

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
 * Wire form of a [PendingHandRevealView] (CR 701.16a) — an open "target opponent reveals their hand and
 * you choose a card from it". Added by `FW-HIDDENCHOICE`.
 *
 * **Carried in full to both seats**, and that is the rules-correct filtering rather than a shortcut:
 * CR 701.16a reveals the hand to *every* player, so this is the one pending record that makes a hidden
 * zone temporarily public. The engine has already made that ruling in [PendingHandRevealView]; this
 * layer is a straight structural translation of it and adds no redaction of its own.
 */
@Serializable
data class PendingHandRevealViewDto(
    val decider: Int,
    val revealer: Int,
    val revealed: List<GameObjectDto>,
    val outcome: RevealedCardOutcomeDto,
    val sourceCard: String,
)

/** [PendingHandRevealView] to its wire form. */
fun PendingHandRevealView.toDto(): PendingHandRevealViewDto =
    PendingHandRevealViewDto(
        decider.seat,
        revealer.seat,
        revealed.map { it.toDto() },
        outcome.toDto(),
        sourceCard.name,
    )

/** [PendingHandRevealViewDto] back to the engine value. */
fun PendingHandRevealViewDto.toDomain(): PendingHandRevealView =
    PendingHandRevealView(
        PlayerId(decider),
        PlayerId(revealer),
        revealed.map { it.toDomain() },
        outcome.toDomain(),
        CardRef(sourceCard),
    )

/** Wire form of the broad decision kind a non-deciding seat sees (ADR-007); names mirror [DecisionRequestKind]. */
@Serializable
enum class DecisionRequestKindDto {
    CHOOSE_ACTION,
    CHOOSE_DISCARDS,
    CHOOSE_MODES,
    CHOOSE_TARGETS,
    CHOOSE_MULTIPLE_TARGETS,
    CHOOSE_PAYMENT_PLAN,

    /** [dev.mtgplay.rules.DecisionRequestKind.CHOOSE_X_VALUE] — a CR 601.2b variable-cost announcement. */
    @SerialName("choose_x_value")
    CHOOSE_X_VALUE,
    DECLARE_ATTACKERS,
    DECLARE_BLOCKERS,
    ORDER_BLOCKERS,
    ASSIGN_TRAMPLE_DAMAGE,
    ORDER_TRIGGERS,
    CHOOSE_YES_NO,
    CHOOSE_CARDS_TO_EXILE,
    CHOOSE_SACRIFICES,
    CHOOSE_TAPS_FOR_COST,
    CHOOSE_OPTIONAL_COST_SACRIFICE,
    CHOOSE_CARDS_TO_DISCARD_FOR_COST,
    CHOOSE_SACRIFICES_FOR_COST,
    CHOOSE_ABILITY_SACRIFICE,
    CHOOSE_MULLIGAN,
    CHOOSE_CARDS_TO_BOTTOM,
    CHOOSE_ABILITY_DISCARD,
    CHOOSE_COLOR,
    CHOOSE_OPTIONAL_DISCARD,
    CHOOSE_FROM_REVEALED,
    CHOOSE_REPLACEMENT,
    CHOOSE_COST_MODE,
    CHOOSE_OPTIONAL_COST_OBJECT,
    CHOOSE_RESOLUTION_DISCARDS,
    CHOOSE_FROM_LIBRARY,
    CHOOSE_LIBRARY_ARRANGEMENT,

    /** A resolving counter's "unless its controller pays" (CR 118.3a). */
    CHOOSE_COUNTER_PAYMENT,

    /** Pick a card from an opponent's revealed hand (CR 701.16a). */
    CHOOSE_REVEALED_HAND_CARD,

    /** Decline, tap, or untap a clause's target (CR 608.2c). */
    CHOOSE_TAP_OR_UNTAP,

    /**
     * An "each opponent discards a card" selection (CR 701.7a), made by an opponent of the resolving
     * object's controller. The kind an opposing seat may see; its **options** never reach anyone but
     * the deciding seat (ADR-007).
     */
    CHOOSE_OPPONENT_DISCARDS,

    /** An activated ability's "return a permanent you control" cost (CR 602.1, CR 701.4a). */
    CHOOSE_ABILITY_RETURN,

    /**
     * An **untargeted** mid-resolution choice of battlefield permanents (CR 609.4) — Snap, Azorius
     * Chancery. Not a targeting request: see the `FW-TAPUNTAP` note in ProtocolVersion.kt.
     */
    CHOOSE_PERMANENTS_TO_AFFECT,

    /** An optional "you may pay {cost}; if you do, draw" clause (CR 601.3b) — Nihil Spellbomb. */
    CHOOSE_OPTIONAL_MANA_PAYMENT,

    /**
     * A "target player exiles a card from their graveyard" choice (CR 701.3a) — Relic of Progenitus —
     * made by the **targeted** player. The kind an opposing seat may see, options included: a graveyard
     * is a public zone (CR 400.2), so unlike [CHOOSE_OPPONENT_DISCARDS] nothing here is hidden.
     */
    CHOOSE_GRAVEYARD_CARD_TO_EXILE,

    /** A resolution-time "choose creature or land" (CR 609.4) — Winding Way. */
    CHOOSE_REVEALED_CARD_TYPE,
}

/**
 * [DecisionRequestKind] to its wire form. Mapped by name (the two enums are name-identical by
 * construction); the compile-time break on a new request kind is delivered by
 * [dev.mtgplay.rules.kindOf] and the full [DecisionRequestDto] mapping, and a round-trip test pins
 * every value.
 */
fun DecisionRequestKind.toDto(): DecisionRequestKindDto = DecisionRequestKindDto.valueOf(name)

/** [DecisionRequestKindDto] back to the engine value. */
fun DecisionRequestKindDto.toDomain(): DecisionRequestKind = DecisionRequestKind.valueOf(name)

/** Wire form of a [DecisionView] (ADR-007): the deciding seat's full request, or another seat's kind-only view. */
@Serializable
sealed interface DecisionViewDto {
    /** The viewer is the deciding seat: its full request (ADR-005). */
    @Serializable
    @SerialName("to_decide")
    data class ToDecide(
        val request: DecisionRequestDto,
    ) : DecisionViewDto

    /** Another seat is deciding: only who decides and the broad kind. */
    @Serializable
    @SerialName("elsewhere")
    data class Elsewhere(
        val seat: Int,
        val kind: DecisionRequestKindDto,
    ) : DecisionViewDto
}

/** [DecisionView] to its wire form. */
fun DecisionView.toDto(): DecisionViewDto =
    when (this) {
        is DecisionView.ToDecide -> DecisionViewDto.ToDecide(request.toDto())
        is DecisionView.Elsewhere -> DecisionViewDto.Elsewhere(seat.seat, kind.toDto())
    }

/** [DecisionViewDto] back to the engine value. */
fun DecisionViewDto.toDomain(): DecisionView =
    when (this) {
        is DecisionViewDto.ToDecide -> DecisionView.ToDecide(request.toDomain())
        is DecisionViewDto.Elsewhere -> DecisionView.Elsewhere(PlayerId(seat), kind.toDomain())
    }
