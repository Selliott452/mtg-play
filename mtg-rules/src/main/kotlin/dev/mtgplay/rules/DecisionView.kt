package dev.mtgplay.rules

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.decision.DecisionRequest

/**
 * The decision a paused game is at, filtered for one seat (ADR-007): the deciding seat's full
 * request is part of *its* view context, while every other seat sees only who decides and the broad
 * kind — never another seat's private option contents.
 *
 * Two request kinds have options that are intrinsically secret from a non-deciding seat: a
 * library search ([DecisionRequestKind.CHOOSE_FROM_LIBRARY]) exposes matching library cards, which
 * are secret mid-search (CR 701.18), and a library arrangement
 * ([DecisionRequestKind.CHOOSE_LIBRARY_ARRANGEMENT]) exposes privately *looked at* cards, which no other
 * player ever sees (CR 701.14a). Several other kinds enumerate the deciding seat's own hand
 * (the cleanup/cost/resolution discards, mulligan bottoming) — equally private to an opponent. So
 * the general principle is encoded rather than special-casing library search: a non-deciding seat
 * receives no request options at all, only [Elsewhere]. No information is lost by this — the public
 * halves of every request (declared attackers, chosen targets, revealed cards) already reach the
 * seat through the public battlefield/stack/[SeatView.pendingReveal] state.
 */
sealed interface DecisionView {
    /**
     * The viewer is the deciding seat: it holds its full [DecisionRequest] with all enumerated
     * options (ADR-005), which it is entitled to see in order to answer.
     *
     * @property request the full request the viewer must answer.
     */
    data class ToDecide(
        val request: DecisionRequest,
    ) : DecisionView

    /**
     * Another seat is deciding: the viewer sees only who decides and the broad [kind], never the
     * options.
     *
     * @property seat the deciding seat.
     * @property kind the broad kind of the pending decision.
     */
    data class Elsewhere(
        val seat: PlayerId,
        val kind: DecisionRequestKind,
    ) : DecisionView
}

/**
 * The broad kind of a [DecisionRequest] — one value per request leaf (ADR-005) — surfaced to a
 * non-deciding seat in [DecisionView.Elsewhere] so it knows *what* choice is pending without seeing
 * the private options.
 *
 * Exhaustive with [DecisionRequest]'s 25 leaves: [kindOf] `when`s over every leaf, so a new request
 * kind breaks compilation here until it is classified.
 */
enum class DecisionRequestKind {
    /** [DecisionRequest.ChooseAction] — a priority window (CR 117). */
    CHOOSE_ACTION,

    /** [DecisionRequest.ChooseDiscards] — the cleanup discard (CR 514.1). */
    CHOOSE_DISCARDS,

    /** [DecisionRequest.ChooseModes] — a modal cast's mode choice (CR 601.2b, CR 700.2). */
    CHOOSE_MODES,

    /** [DecisionRequest.ChooseTargets] — a cast's target choice (CR 601.2c). */
    CHOOSE_TARGETS,

    /**
     * [DecisionRequest.ChooseMultipleTargets] — a multi-target choice (CR 601.2c): "up to two target
     * cards from graveyards", "two target creatures". Additive, flagged (`FW-MULTITGT`).
     */
    CHOOSE_MULTIPLE_TARGETS,

    /** [DecisionRequest.ChoosePaymentPlan] — a cast's payment choice (CR 601.2g). */
    CHOOSE_PAYMENT_PLAN,

    /** [DecisionRequest.DeclareAttackers] — the declare-attackers action (CR 508.1). */
    DECLARE_ATTACKERS,

    /** [DecisionRequest.DeclareBlockers] — the declare-blockers action (CR 509.1). */
    DECLARE_BLOCKERS,

    /** [DecisionRequest.OrderBlockers] — a multi-blocked attacker's damage order (CR 509.2). */
    ORDER_BLOCKERS,

    /** [DecisionRequest.AssignTrampleDamage] — a trample assignment (CR 702.19e). */
    ASSIGN_TRAMPLE_DAMAGE,

    /** [DecisionRequest.OrderTriggers] — ordering simultaneous triggers (CR 603.3b). */
    ORDER_TRIGGERS,

    /** [DecisionRequest.ChooseYesNo] — a yes/no choice (CR 601.3b, CR 702.35b). */
    CHOOSE_YES_NO,

    /** [DecisionRequest.ChooseCardsToExile] — an additional exile cost (CR 601.2b). */
    CHOOSE_CARDS_TO_EXILE,

    /** [DecisionRequest.ChooseSacrifices] — a non-mana sacrifice cost (CR 601.2h). */
    CHOOSE_SACRIFICES,

    /** [DecisionRequest.ChooseCardsToDiscardForCost] — an additional discard cost (CR 601.2b). */
    CHOOSE_CARDS_TO_DISCARD_FOR_COST,

    /** [DecisionRequest.ChooseSacrificesForCost] — an intrinsic sacrifice additional cost (CR 601.2b). */
    CHOOSE_SACRIFICES_FOR_COST,

    /** [DecisionRequest.ChooseAbilitySacrifice] — an activated ability's sacrifice cost (CR 602.1). */
    CHOOSE_ABILITY_SACRIFICE,

    /** [DecisionRequest.ChooseMulligan] — the keep-or-mulligan choice (CR 103.4). */
    CHOOSE_MULLIGAN,

    /** [DecisionRequest.ChooseCardsToBottom] — the mulligan bottoming choice (CR 103.5). */
    CHOOSE_CARDS_TO_BOTTOM,

    /** [DecisionRequest.ChooseAbilityDiscard] — an activated ability's discard cost (CR 602.2b). */
    CHOOSE_ABILITY_DISCARD,

    /** [DecisionRequest.ChooseColor] — an "as this enters, choose a colour" choice (CR 614.12). */
    CHOOSE_COLOR,

    /** [DecisionRequest.ChooseOptionalDiscard] — an optional discard-then-draw (CR 601.3b). */
    CHOOSE_OPTIONAL_DISCARD,

    /** [DecisionRequest.ChooseFromRevealed] — keep one of the revealed cards (CR 701.16). */
    CHOOSE_FROM_REVEALED,

    /** [DecisionRequest.ChooseReplacement] — the CR 616.1 replacement-ordering choice. */
    CHOOSE_REPLACEMENT,

    /** [DecisionRequest.ChooseCostMode] — an optional cost-then-draw mode choice (CR 601.3b). */
    CHOOSE_COST_MODE,

    /** [DecisionRequest.ChooseOptionalCostObject] — a chosen cost-mode's object (CR 601.3b). */
    CHOOSE_OPTIONAL_COST_OBJECT,

    /** [DecisionRequest.ChooseResolutionDiscards] — a mandatory resolution discard (CR 601.2c). */
    CHOOSE_RESOLUTION_DISCARDS,

    /** [DecisionRequest.ChooseFromLibrary] — find one from a library search (CR 701.18). */
    CHOOSE_FROM_LIBRARY,

    /** [DecisionRequest.ChooseLibraryArrangement] — arrange privately looked-at cards (CR 701.14a, CR 701.17a). */
    CHOOSE_LIBRARY_ARRANGEMENT,

    /** [DecisionRequest.ChooseCounterPayment] — a counter's "unless its controller pays" (CR 118.3a). */
    CHOOSE_COUNTER_PAYMENT,

    /** [DecisionRequest.ChooseRevealedHandCard] — pick a card from an opponent's revealed hand (CR 701.16a). */
    CHOOSE_REVEALED_HAND_CARD,

    /**
     * [DecisionRequest.ChooseOpponentDiscards] — an "each opponent discards a card" selection (CR 701.7a),
     * made by an opponent of the resolving object's controller over their own hand. The kind an opposing
     * seat may see; its **options** are never projected to anyone but the deciding seat (ADR-007).
     */
    CHOOSE_OPPONENT_DISCARDS,
}

/**
 * The broad [DecisionRequestKind] of [request]. Exhaustive over the hierarchy with no `else`, so a
 * new [DecisionRequest] kind forces a classification here or in one of the family helpers below.
 *
 * Dispatch is grouped by [DecisionRequest]'s sealed sub-interfaces (mirroring the engine's own
 * decision-application idiom), keeping this top-level `when` flat: the six families
 * ([DecisionRequest.SizedSelection], [DecisionRequest.RangedSelection],
 * [DecisionRequest.PermutationSelection], [DecisionRequest.ChoiceCountSelection],
 * [DecisionRequest.SingleOptionSelection], [DecisionRequest.MulliganRequest]) delegate to a helper, and
 * the standalone leaves map directly.
 */
fun kindOf(request: DecisionRequest): DecisionRequestKind =
    when (request) {
        is DecisionRequest.ChooseAction -> DecisionRequestKind.CHOOSE_ACTION
        is DecisionRequest.DeclareAttackers -> DecisionRequestKind.DECLARE_ATTACKERS
        is DecisionRequest.DeclareBlockers -> DecisionRequestKind.DECLARE_BLOCKERS
        is DecisionRequest.ChooseYesNo -> DecisionRequestKind.CHOOSE_YES_NO
        is DecisionRequest.SingleOptionSelection -> singleOptionSelectionKind(request)
        is DecisionRequest.SizedSelection -> sizedSelectionKind(request)
        is DecisionRequest.RangedSelection -> rangedSelectionKind(request)
        is DecisionRequest.PermutationSelection -> permutationSelectionKind(request)
        is DecisionRequest.ChoiceCountSelection -> choiceCountSelectionKind(request)
        is DecisionRequest.MulliganRequest -> mulliganRequestKind(request)
    }

/** The kind of one "pick exactly one option" request (CR 601.2c / 601.2g / 702.19e / 614.12 / 616.1 / 701.17a). */
private fun singleOptionSelectionKind(request: DecisionRequest.SingleOptionSelection): DecisionRequestKind =
    when (request) {
        is DecisionRequest.ChooseModes -> DecisionRequestKind.CHOOSE_MODES
        is DecisionRequest.ChooseTargets -> DecisionRequestKind.CHOOSE_TARGETS
        is DecisionRequest.ChoosePaymentPlan -> DecisionRequestKind.CHOOSE_PAYMENT_PLAN
        is DecisionRequest.AssignTrampleDamage -> DecisionRequestKind.ASSIGN_TRAMPLE_DAMAGE
        is DecisionRequest.ChooseColor -> DecisionRequestKind.CHOOSE_COLOR
        is DecisionRequest.ChooseReplacement -> DecisionRequestKind.CHOOSE_REPLACEMENT
        is DecisionRequest.ChooseLibraryArrangement -> DecisionRequestKind.CHOOSE_LIBRARY_ARRANGEMENT
        is DecisionRequest.ChooseCounterPayment -> DecisionRequestKind.CHOOSE_COUNTER_PAYMENT
        is DecisionRequest.ChooseRevealedHandCard -> DecisionRequestKind.CHOOSE_REVEALED_HAND_CARD
    }

/** The kind of one fixed-size subset selection (CR 514.1 / 601.2b/h / 602.2b). */
private fun sizedSelectionKind(request: DecisionRequest.SizedSelection): DecisionRequestKind =
    when (request) {
        is DecisionRequest.ChooseDiscards -> DecisionRequestKind.CHOOSE_DISCARDS
        is DecisionRequest.ChooseCardsToExile -> DecisionRequestKind.CHOOSE_CARDS_TO_EXILE
        is DecisionRequest.ChooseSacrifices -> DecisionRequestKind.CHOOSE_SACRIFICES
        is DecisionRequest.ChooseCardsToDiscardForCost -> DecisionRequestKind.CHOOSE_CARDS_TO_DISCARD_FOR_COST
        is DecisionRequest.ChooseSacrificesForCost -> DecisionRequestKind.CHOOSE_SACRIFICES_FOR_COST
        is DecisionRequest.ChooseAbilitySacrifice -> DecisionRequestKind.CHOOSE_ABILITY_SACRIFICE
        is DecisionRequest.ChooseAbilityDiscard -> DecisionRequestKind.CHOOSE_ABILITY_DISCARD
        is DecisionRequest.ChooseOptionalDiscard -> DecisionRequestKind.CHOOSE_OPTIONAL_DISCARD
        is DecisionRequest.ChooseOptionalCostObject -> DecisionRequestKind.CHOOSE_OPTIONAL_COST_OBJECT
        is DecisionRequest.ChooseResolutionDiscards -> DecisionRequestKind.CHOOSE_RESOLUTION_DISCARDS
        is DecisionRequest.ChooseOpponentDiscards -> DecisionRequestKind.CHOOSE_OPPONENT_DISCARDS
    }

/** The kind of one ranged subset selection (CR 601.2c) — a multi-target choice. */
private fun rangedSelectionKind(request: DecisionRequest.RangedSelection): DecisionRequestKind =
    when (request) {
        is DecisionRequest.ChooseMultipleTargets -> DecisionRequestKind.CHOOSE_MULTIPLE_TARGETS
    }

/** The kind of one full-ordering selection (CR 509.2 / 603.3b). */
private fun permutationSelectionKind(request: DecisionRequest.PermutationSelection): DecisionRequestKind =
    when (request) {
        is DecisionRequest.OrderBlockers -> DecisionRequestKind.ORDER_BLOCKERS
        is DecisionRequest.OrderTriggers -> DecisionRequestKind.ORDER_TRIGGERS
    }

/** The kind of one "choose one, or opt out" selection (CR 701.16 / 601.3b / 701.18). */
private fun choiceCountSelectionKind(request: DecisionRequest.ChoiceCountSelection): DecisionRequestKind =
    when (request) {
        is DecisionRequest.ChooseFromRevealed -> DecisionRequestKind.CHOOSE_FROM_REVEALED
        is DecisionRequest.ChooseCostMode -> DecisionRequestKind.CHOOSE_COST_MODE
        is DecisionRequest.ChooseFromLibrary -> DecisionRequestKind.CHOOSE_FROM_LIBRARY
    }

/** The kind of one pre-game mulligan decision (CR 103.4/103.5). */
private fun mulliganRequestKind(request: DecisionRequest.MulliganRequest): DecisionRequestKind =
    when (request) {
        is DecisionRequest.ChooseMulligan -> DecisionRequestKind.CHOOSE_MULLIGAN
        is DecisionRequest.ChooseCardsToBottom -> DecisionRequestKind.CHOOSE_CARDS_TO_BOTTOM
    }
