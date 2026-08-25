package dev.mtgplay.core.definition

/**
 * A "target opponent reveals their hand, **you** choose a card from it, then that card is discarded or
 * exiled" clause (CR 701.16, CR 701.7a / CR 701.3a) — Duress and Mesmeric Fiend. Card-definition data,
 * additive and flagged core (`FW-HIDDENCHOICE`, docs/design/exile-and-return.md §7).
 *
 * A [ResolutionClauses] member because it needs a mid-resolution pause the [ResolutionEffect] signature
 * cannot express: the chooser must be handed an enumerated option per revealed card (ADR-005).
 *
 * **The decider is the resolving object's controller, not the revealing player, and that is what the
 * oracle text says.** Both printed cards read "*You* choose a … card from it" — the revealing player
 * makes no choice at all, because revealing a whole hand is not a selection. This clause is therefore
 * *not* a non-controller decision (`FW-NONCTRLDEC`); it is a controller decision over information that
 * CR 701.16a has just made **public**. The upstream brief for this packet filed both cards under
 * non-controller decisions; the oracle text disagrees and the oracle text wins (docs/design/exile-and-return.md §7.1).
 *
 * The ADR-007 consequence is the interesting half and it runs the *opposite* way to the usual one: the
 * revealed hand stops being hidden. While the reveal is open, both seats see the revealed cards — the
 * treatment [LibraryReveal] already gets, for the same CR 701.16a reason — and the engine emits
 * [dev.mtgplay.core.event.GameEvent.CardsRevealed]. Nothing here widens what a seat may see beyond what
 * the printed card publishes.
 *
 * The revealing player is the clause's **target** (`TargetSpec.TargetOpponent`), not a field here: both
 * cards target, so who reveals is already recorded on the stack entry where every other target is, and
 * duplicating it would let the two disagree.
 *
 * @property restriction which revealed cards are legal choices (CR 701.16a); a hand containing none
 *   yields no choice and the clause does nothing, which is the CR-correct outcome for a hand of lands.
 * @property outcome what happens to the chosen card (CR 701.7a discard, or CR 701.3a exile).
 */
data class HandRevealChoice(
    val restriction: RevealedCardRestriction,
    val outcome: RevealedCardOutcome,
)

/**
 * Which cards in a revealed hand a [HandRevealChoice] may choose from (CR 701.16a) — the noun half of
 * "you choose a noncreature, nonland card from it". A closed enum for the reason
 * [PermanentRestriction] is one: the enumerator is the single source of legality truth (ADR-005) and a
 * new restriction must break the rules-side `when` rather than slip through. Members exist only where a
 * card in the pool prints them.
 */
enum class RevealedCardRestriction {
    /** "A nonland card" (CR 305) — Mesmeric Fiend. */
    NONLAND,

    /** "A noncreature, nonland card" (CR 302, CR 305) — Duress. */
    NONCREATURE_NONLAND,
}

/**
 * What a [HandRevealChoice] does with the card the controller chose. Closed for the same reason
 * [RevealedCardRestriction] is: each member is a different zone move with different downstream rules
 * consequences, and a new one must break the rules-side `when`.
 */
enum class RevealedCardOutcome {
    /**
     * "That player discards that card" (CR 701.7a) — Duress. The card's **owner** discards it, so it
     * routes through the CR 614/616 discard framework exactly as any other discard does and a madness
     * card discarded this way is exiled instead.
     */
    DISCARD,

    /**
     * "Exile that card" (CR 701.3a) — Mesmeric Fiend. The exiled object is recorded on the resolving
     * ability's source as [dev.mtgplay.core.state.GameObject.linkedExiled], because Mesmeric Fiend's
     * second ability is **linked** to this one (CR 607.2) and must return exactly this card.
     */
    EXILE_LINKED,
}
