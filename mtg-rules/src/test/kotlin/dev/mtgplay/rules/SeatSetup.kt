package dev.mtgplay.rules

/**
 * One seat's zones and life for `fixtureState`; card names resolve via [fixtureDefinitions].
 * The default library keeps a few inert cards so mid-scenario draw steps never deck a player
 * out by accident.
 */
internal data class SeatSetup(
    val life: Int = STARTING_LIFE,
    val hand: List<String> = emptyList(),
    val battlefield: List<String> = emptyList(),
    val library: List<String> = listOf("Mountain", "Mountain", "Mountain"),
    /**
     * The seat's graveyard (CR 404), empty by default. Added by `FW-COST`: a cost reduction that
     * counts cards in a graveyard needs a board with one, and every prior fixture scenario left it
     * empty.
     */
    val graveyard: List<String> = emptyList(),
)
