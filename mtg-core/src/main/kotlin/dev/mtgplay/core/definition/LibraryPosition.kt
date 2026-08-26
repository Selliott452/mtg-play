package dev.mtgplay.core.definition

/**
 * Where in a library an effect seats a card it puts there (CR 401.1 — index 0 is the top). Additive,
 * flagged core (`W9-F`) — Deem Inferior's *"puts it into their library **second from the top or on the
 * bottom**"*.
 *
 * **The first library position in the engine that is neither of the two ends**, and the reason this is a
 * named vocabulary rather than a `Boolean onTop`. Everything that reached a library before this packet
 * chose an end: [dev.mtgplay.core.event.GameEvent.CardPutOnLibrary] carries `onTop`, the CR 701.14a
 * arrangement machinery distributes into a top block and a bottom block, and a shuffle chooses no
 * position at all. "Second from the top" is a *depth*, and no boolean can say it.
 *
 * A closed enum rather than an integer depth, because the two members are what the card prints and the
 * gap between them is the whole decision: the top-adjacent option leaves the permanent one draw away and
 * the bottom option buries it for the game. An engine that accepted an arbitrary depth would have to
 * enumerate one option per library size (ADR-005), which is a choice no card offers.
 */
enum class LibraryPosition {
    /**
     * **Second from the top** (CR 401.1): under exactly one card, so its owner draws it on the draw after
     * next — or on their *next* draw if something takes the top card first.
     *
     * Deliberately not "on top": Deem Inferior is a tempo card, not a Time Ebb, and the one card of
     * insulation is what stops it from being a strict downgrade of a bounce spell against a creature the
     * opponent would happily recast. On an empty library it lands on top, which is the only reading of
     * "second from the top" that exists when there is no first — and an opponent whose library is empty
     * has larger problems (CR 704.5b).
     */
    SECOND_FROM_TOP,

    /**
     * **On the bottom** (CR 401.1): under every other card, which in practice removes the permanent from
     * the game. The same seat as a mulligan's bottomed cards.
     */
    BOTTOM,
}
