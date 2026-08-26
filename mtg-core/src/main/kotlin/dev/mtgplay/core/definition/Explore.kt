package dev.mtgplay.core.definition

/**
 * The **explore** keyword action (CR 701.40a), as a resolution clause: *"Reveal the top card of your
 * library. Put that card into your hand if it's a land card. Otherwise, put a `+1/+1` counter on the
 * exploring permanent, then put the card back on top of your library or into your graveyard."* Additive,
 * flagged core (`W10-D`) — the Map token's *"Target creature you control explores."*
 *
 * **A clause and not a [ResolutionEffect]**, for the ordinary ADR-004 reason and one that is specific to
 * this keyword.
 *
 * The ordinary reason is the pause: the last sentence is a genuine choice, made after the reveal, by the
 * exploring permanent's controller. A resolution effect cannot ask.
 *
 * The specific reason is *when* the pause happens. Explore's decision exists on exactly one of its two
 * branches, so the clause is a branch and not a question:
 *
 * - **A land card is revealed** → it goes to the hand and the resolution is over. There is **no pause at
 *   all**, because there is nothing to decide: surfacing a decision with one legal answer is the other
 *   half of ADR-005's rule, and an engine that asked "top or graveyard?" about a card already in a hand
 *   would be enumerating an illegal action.
 * - **Anything else is revealed, or nothing is** → the counter goes on first (the CR's order), and only
 *   then does the pause open. An **empty library** reveals no card, so no *land* card is revealed, so the
 *   "otherwise" arm is the one that runs: the permanent still gets its counter, and there is still no
 *   pause, because there is no revealed card to place. That reading is CR 701.40a taken literally and is
 *   the one an engine gets wrong by treating "reveal" as a precondition rather than as an action that
 *   may reveal nothing.
 *
 * **The reveal is the reason this clause needed a disclosure and its siblings did not.** CR 701.40a says
 * *reveal*, so the card's identity is public to every player (CR 701.16 is the same word) — and between
 * the reveal and the answer it is sitting **in a library**, the one zone a seat view never discloses.
 * `PendingExploreView` is what makes the opponent able to see the card this clause just told them about;
 * see `dev.mtgplay.core.state.PendingExplore` for the state it is derived from.
 *
 * A `data object` rather than a data class: CR 701.40a is one fixed procedure with no parameters. The
 * exploring permanent is the declaring ability's *target*, not a field here, so nothing about the
 * instruction varies between the cards that print it.
 */
data object Explore

/**
 * Where the explorer puts a revealed nonland card once the `+1/+1` counter is on (CR 701.40a) — *"put the
 * card back on top of their library or into their graveyard"*. Additive, flagged core (`W10-D`).
 *
 * A closed two-member enum, and both members are always legal: a library always accepts its own top card
 * back and a graveyard always accepts a card, so this is the second request in the engine with no "cannot
 * be done" branch after [LibraryPosition]'s. The choice is real in both directions — leaving the card on
 * top keeps a known draw, and binning it digs one card deeper *and* fuels a graveyard the same deck is
 * usually filling on purpose.
 */
enum class ExploreDestination {
    /**
     * **Back on top of the library** (CR 701.40a): the card returns to exactly where it was revealed
     * from, so the explorer's next draw is a card they have now seen. Its identity stops being public the
     * moment the clause finishes — CR 701.40a reveals it, it does not turn it face up permanently.
     */
    LIBRARY_TOP,

    /**
     * **Into the graveyard** (CR 701.40a): the explorer digs one card deeper and puts a card in a public
     * zone, which is the arm a deck built around its own graveyard is usually taking.
     */
    GRAVEYARD,
}
