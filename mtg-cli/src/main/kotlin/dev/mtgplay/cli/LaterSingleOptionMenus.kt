package dev.mtgplay.cli

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
 * A "target player exiles a card from their graveyard" choice (CR 701.3a) — Relic of Progenitus. The
 * header names the ability's controller, because the deciding seat is often the *opponent* of whoever
 * activated it and is choosing out of its own graveyard under someone else's instruction.
 */
internal fun graveyardExileMenu(request: DecisionRequest.ChooseGraveyardCardToExile): List<String> =
    listOf(
        "Exile a card from your graveyard for seat ${request.controller.seat}'s " +
            "${request.sourceCard.name} (CR 701.3a):",
    ) + numbered(request.options.map { it.card.name }) + SINGLE_HINT

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
