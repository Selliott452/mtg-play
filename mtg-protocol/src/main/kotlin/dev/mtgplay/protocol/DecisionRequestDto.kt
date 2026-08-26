package dev.mtgplay.protocol

import dev.mtgplay.rules.decision.DecisionRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire form of the full [DecisionRequest] hierarchy (ADR-004/ADR-005) — every request kind, each
 * carrying its stable [id] and its enumerated options. The mapping to and from the engine
 * ([toDto]/[toDomain]) is exhaustive in both directions, so a new request kind is a compile-time
 * schema break (ADR-008 amendment).
 *
 * The six family sub-interfaces mirror [DecisionRequest]'s own
 * ([DecisionRequest.SizedSelection], [DecisionRequest.RangedSelection],
 * [DecisionRequest.PermutationSelection], [DecisionRequest.ChoiceCountSelection],
 * [DecisionRequest.SingleOptionSelection], [DecisionRequest.MulliganRequest]), which lets the
 * mapping dispatch by family and keeps every `when` flat. They are wire-invisible: serialization
 * keys off each leaf's `@SerialName`, so grouping leaves under a family changes no encoded payload.
 */
@Serializable
sealed interface DecisionRequestDto {
    /** The request's stable identity (ADR-004). */
    val id: DecisionRequestIdDto

    /** A fixed-size subset selection (CR 514.1 / 601.2b/h / 602.2b). */
    @Serializable
    sealed interface SizedSelectionDto : DecisionRequestDto

    /** A ranged subset selection (CR 601.2c) — a multi-target choice. */
    @Serializable
    sealed interface RangedSelectionDto : DecisionRequestDto

    /**
     * A **summed-weight** subset selection (CR 601.2b / 701.60a) — collect evidence. Its own family
     * rather than a [SizedSelectionDto] or a [RangedSelectionDto] because its answer is bounded by the
     * chosen options' summed weights and not by their count at all.
     */
    @Serializable
    sealed interface SummedSelectionDto : DecisionRequestDto

    /** A full-ordering selection (CR 509.2 / 603.3b). */
    @Serializable
    sealed interface PermutationSelectionDto : DecisionRequestDto

    /** A "choose one, or opt out" selection (CR 701.16 / 601.3b / 701.18). */
    @Serializable
    sealed interface ChoiceCountSelectionDto : DecisionRequestDto

    /** A "pick exactly one of these options, no opt-out" selection (CR 601.2c/601.2g/702.19e/614.12/616.1/701.17a). */
    @Serializable
    sealed interface SingleOptionSelectionDto : DecisionRequestDto

    /** A pre-game mulligan decision (CR 103.4/103.5). */
    @Serializable
    sealed interface MulliganRequestDto : DecisionRequestDto

    /** Wire form of [DecisionRequest.ChooseAction] — a priority window (CR 117). */
    @Serializable
    @SerialName("choose_action")
    data class ChooseAction(
        override val id: DecisionRequestIdDto,
        val options: List<PriorityOptionDto>,
    ) : DecisionRequestDto

    /**
     * Wire form of [DecisionRequest.ChooseModes] — a modal cast's mode choice (CR 601.2b, CR 700.2).
     *
     * Sent server→client as an offered decision and answered client→server with a distinct index
     * subset whose size lies in [minimumCount]..[maximumCount] — a [RangedSelectionDto], since `W9-B`,
     * for "Choose one —" too, where the range is `1..1`. It arrives **before** that cast's target
     * requests, which is the CR 601.2b-before-CR 601.2c ordering a client can observe directly: the
     * target options it receives next depend on the modes it just sent back, and it receives **one
     * target request per chosen mode** (CR 115.3).
     *
     * [minimumCount] is the card's printed minimum, zero for "choose up to N" — and a client may
     * legitimately answer with no indices at all, casting the spell to resolve doing nothing.
     */
    @Serializable
    @SerialName("choose_modes")
    data class ChooseModes(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val options: List<ModeOptionDto>,
        val minimumCount: Int,
        val maximumCount: Int,
    ) : RangedSelectionDto

    /** Wire form of [DecisionRequest.ChooseTargets] — a cast's target choice (CR 601.2c). */
    @Serializable
    @SerialName("choose_targets")
    data class ChooseTargets(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val options: List<TargetDto>,
    ) : SingleOptionSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseMultipleTargets] — a multi-target choice (CR 601.2c):
     * "up to two target cards from graveyards", "two target creatures". Additive (`FW-MULTITGT`).
     *
     * [minimumCount] and [maximumCount] bound how many of [options] the answer names by distinct index;
     * the distinctness is CR 601.2c's same-object rule and the peer must honour it, since the engine
     * rejects a repeated index rather than silently deduplicating.
     */
    @Serializable
    @SerialName("choose_multiple_targets")
    data class ChooseMultipleTargets(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val options: List<TargetDto>,
        val minimumCount: Int,
        val maximumCount: Int,
    ) : RangedSelectionDto

    /**
     * Wire form of [DecisionRequest.ChoosePaymentPlan] — a cast's payment choice (CR 601.2g).
     *
     * [cost] is the **determined total cost** in Scryfall brace syntax (CR 601.2f,
     * docs/design/cost-modification.md), which since `FW-COST` is no longer inferable from [card]: an
     * affinity spell's printed `{7}` may be a `{3}` by the time it is paid. Display and audit only —
     * the option set is unaffected, so no enumerated index moves (ADR-005).
     */
    @Serializable
    @SerialName("choose_payment_plan")
    data class ChoosePaymentPlan(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val cost: String,
        val options: List<PaymentPlanDto>,
    ) : SingleOptionSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseXValue] — the CR 601.2b announcement of a variable cost
     * (CR 107.3b). Additive (`FW-X`).
     *
     * [values] carries the announceable **numbers**, not a count, and a peer must answer with the index
     * of the value it wants rather than with the value itself. The two coincide on every ordinary board
     * (the options run `0, 1, 2, …`) and would diverge on a board where a middle value is unpayable, so
     * a client that assumes they are equal is wrong exactly where the bound is interesting.
     */
    @Serializable
    @SerialName("choose_x_value")
    data class ChooseXValue(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val values: List<Int>,
    ) : SingleOptionSelectionDto

    /** Wire form of [DecisionRequest.DeclareAttackers] (CR 508.1). */
    @Serializable
    @SerialName("declare_attackers")
    data class DeclareAttackers(
        override val id: DecisionRequestIdDto,
        val options: List<AttackerOptionDto>,
    ) : DecisionRequestDto

    /** Wire form of [DecisionRequest.DeclareBlockers] (CR 509.1). */
    @Serializable
    @SerialName("declare_blockers")
    data class DeclareBlockers(
        override val id: DecisionRequestIdDto,
        val options: List<BlockerOptionDto>,
        val minimumBlockers: List<BlockerMinimumDto> = emptyList(),
    ) : DecisionRequestDto

    /** Wire form of [DecisionRequest.AssignTrampleDamage] (CR 702.19e). */
    @Serializable
    @SerialName("assign_trample_damage")
    data class AssignTrampleDamage(
        override val id: DecisionRequestIdDto,
        val attacker: Long,
        val attackerCard: String,
        val defendingPlayer: Int,
        val options: List<Int>,
    ) : SingleOptionSelectionDto

    /** Wire form of [DecisionRequest.ChooseYesNo] (CR 601.3b, CR 702.35b). */
    @Serializable
    @SerialName("choose_yes_no")
    data class ChooseYesNo(
        override val id: DecisionRequestIdDto,
        val prompt: String,
        val cardObjectId: Long,
        val card: String,
    ) : DecisionRequestDto

    /** Wire form of [DecisionRequest.ChooseColor] (CR 614.12). */
    @Serializable
    @SerialName("choose_color")
    data class ChooseColor(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val options: List<ColorDto>,
    ) : SingleOptionSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseLibraryArrangement] (CR 701.14a, CR 701.17a). Reaches the
     * deciding seat only: the pool is privately looked-at cards, and every other seat receives
     * `DecisionView.Elsewhere` with no request at all.
     */
    @Serializable
    @SerialName("choose_library_arrangement")
    data class ChooseLibraryArrangement(
        override val id: DecisionRequestIdDto,
        val prompt: String,
        val pool: List<CardObjectOptionDto>,
        val options: List<LibraryArrangementDto>,
    ) : SingleOptionSelectionDto

    /** Wire form of [DecisionRequest.ChooseReplacement] (CR 616.1). */
    @Serializable
    @SerialName("choose_replacement")
    data class ChooseReplacement(
        override val id: DecisionRequestIdDto,
        val options: List<ReplacementOptionDto>,
    ) : SingleOptionSelectionDto

    /** Wire form of [DecisionRequest.ChooseDiscards] — the cleanup discard (CR 514.1). */
    @Serializable
    @SerialName("choose_discards")
    data class ChooseDiscards(
        override val id: DecisionRequestIdDto,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /** Wire form of [DecisionRequest.ChooseCardsToExile] (CR 601.2b). */
    @Serializable
    @SerialName("choose_cards_to_exile")
    data class ChooseCardsToExile(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /** Wire form of [DecisionRequest.ChooseSacrifices] (CR 601.2h). */
    @Serializable
    @SerialName("choose_sacrifices")
    data class ChooseSacrifices(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /** Wire form of [DecisionRequest.ChooseOptionalCostSacrifice] (CR 601.2b, CR 702.166a) — `FW-BARGAIN`. */
    @Serializable
    @SerialName("choose_optional_cost_sacrifice")
    data class ChooseOptionalCostSacrifice(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseCostPowerSource] (CR 601.2b) — `W9-D`, Monstrous Emergence.
     *
     * The **first cast-cost selection whose options are not all object ids**, and the reason it does not
     * reuse [CardObjectOptionDto]: half the pool is a battlefield object and half is a card in hand that
     * is not moving, so each option carries a `kind` word plus whichever half applies. A `kind` this
     * schema's engine version does not know fails loudly on decode, the discipline every flattened
     * sealed payload on this wire uses.
     *
     * No `count` field: exactly one thing is named, always, so a count would be a constant the two sides
     * could disagree about.
     */
    @Serializable
    @SerialName("choose_cost_power_source")
    data class ChooseCostPowerSource(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val options: List<PowerSourceOptionDto>,
    ) : SizedSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseEvidence] (CR 601.2b, CR 701.60a) — `W9-B`. An announced
     * collect evidence: any distinct subset of [options] whose weights sum to at least [requiredTotal].
     */
    @Serializable
    @SerialName("choose_evidence")
    data class ChooseEvidence(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val options: List<WeightedCardOptionDto>,
        val requiredTotal: Int,
    ) : SummedSelectionDto

    /** Wire form of [DecisionRequest.ChooseTapsForCost] (CR 601.2h, CR 702.34c) — `FW-PREVENT2`. */
    @Serializable
    @SerialName("choose_taps_for_cost")
    data class ChooseTapsForCost(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /** Wire form of [DecisionRequest.ChooseCardsToDiscardForCost] (CR 601.2b). */
    @Serializable
    @SerialName("choose_cards_to_discard_for_cost")
    data class ChooseCardsToDiscardForCost(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /** Wire form of [DecisionRequest.ChooseSacrificesForCost] (CR 601.2b) — `FW-ADDSAC`. */
    @Serializable
    @SerialName("choose_sacrifices_for_cost")
    data class ChooseSacrificesForCost(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /** Wire form of [DecisionRequest.ChooseAbilitySacrifice] (CR 602.1) — `FW-ADDSAC`. */
    @Serializable
    @SerialName("choose_ability_sacrifice")
    data class ChooseAbilitySacrifice(
        override val id: DecisionRequestIdDto,
        val sourceObjectId: Long,
        val card: String,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /** Wire form of [DecisionRequest.ChooseAbilityDiscard] (CR 602.2b). */
    @Serializable
    @SerialName("choose_ability_discard")
    data class ChooseAbilityDiscard(
        override val id: DecisionRequestIdDto,
        val sourceObjectId: Long,
        val card: String,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseAbilityReturn] (CR 602.1, CR 701.4a) — an activated ability's
     * "Return a Forest you control to its owner's hand" cost. Additive (`FW-TAPUNTAP`).
     */
    @Serializable
    @SerialName("choose_ability_return")
    data class ChooseAbilityReturn(
        override val id: DecisionRequestIdDto,
        val sourceObjectId: Long,
        val card: String,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /**
     * Wire form of [DecisionRequest.ChoosePermanentsToAffect] (CR 609.4) — an **untargeted**
     * mid-resolution choice of battlefield permanents to untap or return to their owners' hands (Snap,
     * Azorius Chancery). Additive (`FW-TAPUNTAP`).
     *
     * [minimumCount] and [maximumCount] bound how many of [options] the answer names by distinct index;
     * both arrive already clamped to the board, so a peer never has to reconcile them with what it can
     * see. [prompt] says which action the chosen permanents receive, since the wire form carries no
     * clause declaration.
     *
     * There is no `cardObjectId`: an ability on the stack is not a card (CR 113.7a) and the choice
     * belongs to the resolving object as a whole, so [sourceCard] alone identifies it for display —
     * the shape [ChooseOpponentDiscards] already uses.
     */
    @Serializable
    @SerialName("choose_permanents_to_affect")
    data class ChoosePermanentsToAffect(
        override val id: DecisionRequestIdDto,
        val sourceCard: String,
        val prompt: String,
        val options: List<CardObjectOptionDto>,
        val minimumCount: Int,
        val maximumCount: Int,
    ) : RangedSelectionDto

    /** Wire form of [DecisionRequest.ChooseOptionalDiscard] (CR 601.3b). */
    @Serializable
    @SerialName("choose_optional_discard")
    data class ChooseOptionalDiscard(
        override val id: DecisionRequestIdDto,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /** Wire form of [DecisionRequest.ChooseOptionalCostObject] (CR 601.3b). */
    @Serializable
    @SerialName("choose_optional_cost_object")
    data class ChooseOptionalCostObject(
        override val id: DecisionRequestIdDto,
        val options: List<CardObjectOptionDto>,
    ) : SizedSelectionDto

    /** Wire form of [DecisionRequest.ChooseResolutionDiscards] (CR 601.2c). */
    @Serializable
    @SerialName("choose_resolution_discards")
    data class ChooseResolutionDiscards(
        override val id: DecisionRequestIdDto,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /** Wire form of [DecisionRequest.OrderBlockers] (CR 509.2). */
    @Serializable
    @SerialName("order_blockers")
    data class OrderBlockers(
        override val id: DecisionRequestIdDto,
        val attacker: Long,
        val options: List<CardObjectOptionDto>,
    ) : PermutationSelectionDto

    /** Wire form of [DecisionRequest.OrderTriggers] (CR 603.3b). */
    @Serializable
    @SerialName("order_triggers")
    data class OrderTriggers(
        override val id: DecisionRequestIdDto,
        val options: List<TriggerOptionDto>,
    ) : PermutationSelectionDto

    /** Wire form of [DecisionRequest.ChooseFromRevealed] (CR 701.16). */
    @Serializable
    @SerialName("choose_from_revealed")
    data class ChooseFromRevealed(
        override val id: DecisionRequestIdDto,
        val options: List<CardObjectOptionDto>,
    ) : ChoiceCountSelectionDto

    /** Wire form of [DecisionRequest.ChooseCostMode] (CR 601.3b). */
    @Serializable
    @SerialName("choose_cost_mode")
    data class ChooseCostMode(
        override val id: DecisionRequestIdDto,
        val prompt: String,
        val options: List<OptionalCostModeDto>,
    ) : ChoiceCountSelectionDto

    /** Wire form of [DecisionRequest.ChooseFromLibrary] (CR 701.18). */
    @Serializable
    @SerialName("choose_from_library")
    data class ChooseFromLibrary(
        override val id: DecisionRequestIdDto,
        val options: List<CardObjectOptionDto>,
        val optionalSearch: Boolean = false,
    ) : ChoiceCountSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseCounterPayment] (CR 118.3a) — a resolving counter's
     * "unless its controller pays". [cost] is the Scryfall brace string, as every mana cost on the wire
     * is. Index 0 of [options] is always the decline; the rest pay.
     */
    @Serializable
    @SerialName("choose_counter_payment")
    data class ChooseCounterPayment(
        override val id: DecisionRequestIdDto,
        val card: String,
        val cost: String,
        val options: List<CounterPaymentOptionDto>,
    ) : SingleOptionSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseRevealedHandCard] (CR 701.16a) — pick one card from the
     * opponent's revealed hand. [revealer] is the opponent whose hand was revealed, never the deciding
     * seat; the option cards are public because CR 701.16a revealed them to every player, which is why
     * this request carries names at all. Added by `FW-HIDDENCHOICE`.
     */
    @Serializable
    @SerialName("choose_revealed_hand_card")
    data class ChooseRevealedHandCard(
        override val id: DecisionRequestIdDto,
        val revealer: Int,
        val sourceCard: String,
        val options: List<CardObjectOptionDto>,
    ) : SingleOptionSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseTapOrUntap] (CR 608.2c) — decline, tap, or untap the clause's
     * target. [cardObjectId] is the clause's source as last known (CR 113.7c) and may name nothing on
     * the battlefield: Sewer-veillance Cam's second trigger fires *because* the artifact left it. Added
     * by `W8-G`.
     */
    @Serializable
    @SerialName("choose_tap_or_untap")
    data class ChooseTapOrUntap(
        override val id: DecisionRequestIdDto,
        val cardObjectId: Long,
        val card: String,
        val targetId: Long,
        val options: List<TapOrUntapChoiceDto>,
    ) : SingleOptionSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseOpponentDiscards] (CR 701.7a) — an "each opponent discards a
     * card" selection made by an opponent of the resolving object's controller over their **own** hand.
     * [controller] is carried for display only; a request reaches the deciding seat alone (ADR-007), so
     * the options never travel to the controller, whose seat view carries the count-only
     * [SeatViewDto.pendingOpponentDiscard] instead. Added by `FW-NONCTRLDEC`.
     */
    @Serializable
    @SerialName("choose_opponent_discards")
    data class ChooseOpponentDiscards(
        override val id: DecisionRequestIdDto,
        val controller: Int,
        val sourceCard: String,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : SizedSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseOpponentSacrifice] (CR 701.17a) — an "each opponent sacrifices
     * a permanent of their choice" selection made by an opponent of the resolving object's controller
     * over their **own** battlefield. Added by `W9-B`.
     *
     * The sibling of [ChooseOpponentDiscards] with nothing hidden: its options are battlefield permanents
     * (CR 400.2), so no count-only seat-view projection accompanies it. [greatestPowerOnly] says the
     * options have already been narrowed to the greatest-power permanents — the engine does the
     * narrowing, and the flag is there so a client can *say* why the list is short.
     */
    @Serializable
    @SerialName("choose_opponent_sacrifice")
    data class ChooseOpponentSacrifice(
        override val id: DecisionRequestIdDto,
        val controller: Int,
        val sourceCard: String,
        val greatestPowerOnly: Boolean,
        val options: List<CardObjectOptionDto>,
    ) : SizedSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseOptionalManaPayment] (CR 601.3b) — an optional "you may pay
     * {cost}; if you do, draw" clause. [cost] is the Scryfall brace string, as every mana cost on the
     * wire is. Index 0 of [options] is always the decline; the rest pay. Added by `W8-D`.
     *
     * It carries its own discriminator rather than reusing [ChooseCounterPayment]'s, whose payload names
     * the spell that would be countered — a spell this request does not have.
     */
    @Serializable
    @SerialName("choose_optional_mana_payment")
    data class ChooseOptionalManaPayment(
        override val id: DecisionRequestIdDto,
        val sourceCard: String,
        val cost: String,
        val drawCount: Int,
        val options: List<CounterPaymentOptionDto>,
    ) : SingleOptionSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseGraveyardCardToExile] (CR 701.3a) — a "target player exiles a
     * card from their graveyard" choice, made by the **targeted** player. [controller] is the ability's
     * controller, carried for display; unlike [ChooseOpponentDiscards] the options are public, because a
     * graveyard is a public zone (CR 400.2). Added by `W8-D`.
     *
     * [optionalExile] (`W9-F`) is the "**you may** exile" of Masked Vandal's enters trigger: it adds one
     * selectable index after [options], meaning "exile nothing", on which the clause's gated "if you do"
     * half does not happen. `false` for Relic of Progenitus, whose targeted player must exile.
     */
    @Serializable
    @SerialName("choose_graveyard_card_to_exile")
    data class ChooseGraveyardCardToExile(
        override val id: DecisionRequestIdDto,
        val controller: Int,
        val sourceCard: String,
        val options: List<CardObjectOptionDto>,
        val optionalExile: Boolean = false,
    ) : SingleOptionSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseLibraryPosition] (CR 401.1) — a "second from the top or on the
     * bottom" choice, made by the targeted permanent's **owner**. [controller] is the resolving spell's
     * controller, carried for display, and is normally the deciding seat's opponent. The options are
     * [LibraryPosition] names, read through the shared vocabulary reader so an unknown name is a loud
     * decode failure rather than a silently dropped depth. Added by `W9-F`.
     */
    @Serializable
    @SerialName("choose_library_position")
    data class ChooseLibraryPosition(
        override val id: DecisionRequestIdDto,
        val controller: Int,
        val sourceCard: String,
        val permanent: Long,
        val permanentCard: String,
        val options: List<String>,
    ) : SingleOptionSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseExploreDestination] (CR 701.40a) — the last sentence of an
     * explore, answered by the exploring permanent's controller. [revealedCard] is on the wire to both
     * seats deliberately: CR 701.40a revealed it. The options are
     * [dev.mtgplay.core.definition.ExploreDestination] names, read through the shared vocabulary reader
     * so an unknown name is a loud decode failure. Added by `W10-D`.
     */
    @Serializable
    @SerialName("choose_explore_destination")
    data class ChooseExploreDestination(
        override val id: DecisionRequestIdDto,
        val controller: Int,
        val sourceCard: String,
        val exploring: Long,
        val exploringCard: String,
        val revealedCard: String,
        val options: List<String>,
    ) : SingleOptionSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseRevealedCardType] (CR 609.4) — Winding Way's resolution-time
     * "choose creature or land", answered before anything is revealed. The options ride as
     * [dev.mtgplay.core.definition.RevealedCardFilter] names. Added by `W8-D`.
     */
    @Serializable
    @SerialName("choose_revealed_card_type")
    data class ChooseRevealedCardType(
        override val id: DecisionRequestIdDto,
        val sourceCard: String,
        val revealCount: Int,
        val options: List<String>,
    ) : SingleOptionSelectionDto

    /**
     * Wire form of [DecisionRequest.ChooseDungeonRoom] (CR 309.4) — a venturing player's branch choice in
     * a dungeon. Added by `W10-A`.
     *
     * The options carry both the room's **index** in the dungeon's room list and its printed name: the
     * index is the engine's handle, so a driver never matches a room by name, and the name is what a
     * player reads off the card. Both are public — a dungeon card is face up in a command zone
     * (CR 309.2).
     */
    @Serializable
    @SerialName("choose_dungeon_room")
    data class ChooseDungeonRoom(
        override val id: DecisionRequestIdDto,
        val dungeon: String,
        val fromRoom: String,
        val options: List<DungeonRoomOptionDto>,
    ) : SingleOptionSelectionDto

    /** Wire form of [DecisionRequest.ChooseMulligan] (CR 103.4). */
    @Serializable
    @SerialName("choose_mulligan")
    data class ChooseMulligan(
        override val id: DecisionRequestIdDto,
        val mulligansTaken: Int,
    ) : MulliganRequestDto

    /** Wire form of [DecisionRequest.ChooseCardsToBottom] (CR 103.5). */
    @Serializable
    @SerialName("choose_cards_to_bottom")
    data class ChooseCardsToBottom(
        override val id: DecisionRequestIdDto,
        val options: List<CardObjectOptionDto>,
        val count: Int,
    ) : MulliganRequestDto
}
