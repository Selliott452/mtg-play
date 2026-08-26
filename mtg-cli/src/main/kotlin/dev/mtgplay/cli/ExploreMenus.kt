package dev.mtgplay.cli

import dev.mtgplay.core.definition.ExploreDestination
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The menus of the CR 701.40 **explore** clause — a resolution-time reveal whose destination the
 * revealing player names (Fanatical Offering's Map token).
 *
 * Split out of `LaterSingleOptionMenus.kt` when that file reached detekt's per-file function budget.
 * The seam is real rather than arbitrary: explore is the only decision in the family answered about a
 * card the seat can see **while it is still in a library**, which is why it needed a view of its own
 * (`PendingExploreView`) and why its labels read differently from every other option list here.
 */

/**
 * The menu for the last sentence of an explore (CR 701.40a). The revealed card is named in the prompt
 * because CR 701.40a revealed it — a menu that hid it would be asking the player to place a card they had
 * not been shown.
 */
internal fun exploreDestinationMenu(request: DecisionRequest.ChooseExploreDestination): List<String> =
    listOf(
        "${request.exploringCard.name} explored for ${request.sourceCard.name} and revealed " +
            "${request.revealedCard.name} (CR 701.40a):",
    ) + numbered(request.options.map { destination -> exploreDestinationLabel(destination) }) + SINGLE_HINT

/** The printed wording of one explore destination (CR 701.40a). */
private fun exploreDestinationLabel(destination: ExploreDestination): String =
    when (destination) {
        ExploreDestination.LIBRARY_TOP -> "Back on top of your library"
        ExploreDestination.GRAVEYARD -> "Into your graveyard"
    }
