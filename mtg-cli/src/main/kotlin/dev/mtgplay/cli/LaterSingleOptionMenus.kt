package dev.mtgplay.cli

import dev.mtgplay.core.definition.LibraryPosition
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The menus of the "pick exactly one of these options" requests added by `W8-D` — an optional
 * pay-then-draw (CR 601.3b), a targeted player's graveyard exile (CR 701.3a), and a resolution-time card
 * type (CR 609.4).
 *
 * Their own file for the reason `mtg-protocol` split `SingleOptionRequestToDomain.kt` off
 * `DecisionRequestToDomain.kt`: this is the family that grows — almost every decision a new engine
 * framework adds turns out to be "pick one of these" — and SingleOptionMenus.kt was at detekt's
 * per-file function budget. The dispatch stays there; only the leaves move.
 */

/**
 * An optional "you may pay {cost}; if you do, draw" choice (CR 601.3b) — Nihil Spellbomb. The header
 * names the source and the reward, so a decline reads as a real option rather than as a mistake.
 */
internal fun optionalManaPaymentMenu(request: DecisionRequest.ChooseOptionalManaPayment): List<String> =
    listOf(
        "You may pay ${request.cost.render()} for ${request.sourceCard.name}; " +
            "if you do, draw ${request.drawCount} (CR 601.3b):",
    ) +
        numbered(
            request.options.map { option ->
                when (option) {
                    DecisionRequest.ChooseOptionalManaPayment.Option.Decline -> "Do not pay — draw nothing"
                    is DecisionRequest.ChooseOptionalManaPayment.Option.Pay -> "Pay: ${paymentPlanLabel(option.plan)}"
                }
            },
        ) + SINGLE_HINT

/**
 * A "exile a card from your graveyard" choice (CR 701.3a, CR 601.3b) — Relic of Progenitus' mandatory
 * one and Masked Vandal's "you may". The header names the ability's controller, because the deciding
 * seat is often the *opponent* of whoever activated it and is choosing out of its own graveyard under
 * someone else's instruction; the decline line is listed only when the printed text offers it.
 */
internal fun graveyardExileMenu(request: DecisionRequest.ChooseGraveyardCardToExile): List<String> =
    listOf(
        "Exile a card from your graveyard for seat ${request.controller.seat}'s " +
            "${request.sourceCard.name} (CR 701.3a):",
    ) + numbered(request.options.map { it.card.name } + declineExileLine(request)) + SINGLE_HINT

/** The "exile nothing" line of a "you may exile" (CR 601.3b), or nothing at all for a mandatory one. */
private fun declineExileLine(request: DecisionRequest.ChooseGraveyardCardToExile): List<String> =
    if (request.optionalExile) listOf("Exile nothing") else emptyList()

/**
 * A resolution-time "choose creature or land" (CR 609.4) — Winding Way. The header says how many cards
 * *will* be revealed, in the future tense, because nothing has been revealed yet: the choice is made
 * blind, and a menu implying otherwise would misdescribe the decision.
 */
internal fun revealedCardTypeMenu(request: DecisionRequest.ChooseRevealedCardType): List<String> =
    listOf(
        "Choose a card type for ${request.sourceCard.name}, then reveal the top " +
            "${request.revealCount} (CR 609.4):",
    ) + numbered(request.options.map { revealedCardTypeLabel(it) }) + SINGLE_HINT

/** The printed noun a [RevealedCardFilter] stands for, for a resolution-time type choice (CR 609.4). */
private fun revealedCardTypeLabel(filter: RevealedCardFilter): String =
    when (filter) {
        RevealedCardFilter.PERMANENT_CARD -> "permanent"
        RevealedCardFilter.ENCHANTMENT_CARD -> "enchantment"
        RevealedCardFilter.COLORLESS_CARD -> "colorless"
        RevealedCardFilter.INSTANT_OR_SORCERY_CARD -> "instant or sorcery"
        RevealedCardFilter.CREATURE_CARD -> "creature"
        RevealedCardFilter.LAND_CARD -> "land"
    }

/**
 * A "second from the top or on the bottom" choice (CR 401.1) — Deem Inferior. The header names both the
 * permanent and the spell's controller, because the deciding seat is the permanent's **owner** and is
 * normally that controller's opponent, watching their own permanent leave under someone else's
 * instruction.
 */
internal fun libraryPositionMenu(request: DecisionRequest.ChooseLibraryPosition): List<String> =
    listOf(
        "Put ${request.permanentCard.name} into your library for seat ${request.controller.seat}'s " +
            "${request.sourceCard.name} (CR 401.1):",
    ) + numbered(request.options.map { position -> libraryPositionLabel(position) }) + SINGLE_HINT

/** The printed wording of one library depth (CR 401.1). */
private fun libraryPositionLabel(position: LibraryPosition): String =
    when (position) {
        LibraryPosition.SECOND_FROM_TOP -> "Second from the top"
        LibraryPosition.BOTTOM -> "On the bottom"
    }

/**
 * The tail of [singleOptionMenu]: the menus of the clauses a *resolving* object opens — a tap-or-untap
 * choice (CR 608.2c), an optional mana payment (CR 601.3b), a graveyard exile (CR 701.3a), an owner's
 * library position (CR 401.1), and a revealed-card type choice.
 *
 * Split out only so the dispatch stays inside detekt's complexity budget, the same reason and the same
 * shape as the split in `PendingDecision.kt`. There is no seam in the rules here: these are simply
 * the arms that arrived last. The `else` is exhaustive by construction — every other member is matched
 * above — and fails loudly rather than rendering a blank menu, because a menu an agent cannot read is
 * an option it cannot take (ADR-005).
 */
internal fun resolutionClauseMenu(
    view: MatchView,
    request: DecisionRequest.SingleOptionSelection,
): List<String> =
    when (request) {
        is DecisionRequest.ChooseTapOrUntap -> tapOrUntapMenu(view, request)
        is DecisionRequest.ChooseOptionalManaPayment -> optionalManaPaymentMenu(request)
        is DecisionRequest.ChooseGraveyardCardToExile -> graveyardExileMenu(request)
        is DecisionRequest.ChooseLibraryPosition -> libraryPositionMenu(request)
        is DecisionRequest.ChooseRevealedCardType -> revealedCardTypeMenu(request)
        else -> error("no menu for ${request::class.simpleName}; every request must render one")
    }
