package dev.mtgplay.core.definition

/**
 * An **untargeted** mid-resolution choice of battlefield permanents, and what is then done to them
 * (CR 609.4, CR 701.21a, CR 701.4a) — Snap's "Untap up to two lands", Azorius Chancery's "return a land
 * you control to its owner's hand". Card-definition data, additive and flagged core (`FW-TAPUNTAP`).
 *
 * **Not targeting, and the difference is the whole reason this type exists.** Neither printing says
 * "target": Snap reads "Untap up to two lands" and the Chancery "return a land you control", so the
 * permanents are chosen as the object *resolves* (CR 609.4) rather than as it is put on the stack
 * (CR 601.2c / CR 603.3d). Three consequences follow, and every one of them is observable:
 * - **Hexproof and shroud do not subtract from the option list** (CR 702.11a, CR 702.18a — both speak of
 *   targeting alone), so Snap untaps an opponent's hexproof land as happily as any other.
 * - **There is no CR 608.2b re-check and no fizzle.** A chosen permanent cannot become an illegal
 *   choice, because the choice is made inside the resolution rather than before it.
 * - **Nothing is announced in advance**, so an opponent cannot respond to the choice.
 * Encoding either card as a [TargetSpec] would be a plausible-looking wrong card (PLAN.md §7) on all
 * three counts.
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This declares which permanents may
 * be chosen, how many, and what happens to them; `mtg-rules` owns enumerating the legal choices
 * (ADR-005), surfacing the decision, and performing [action]. It is a [ResolutionClauses] member
 * because that is precisely the property the carrier is about — a mid-resolution decision a pure
 * [ResolutionEffect] may not make (ADR-004).
 *
 * **The count is a range, and the two cards use both ends of it.** Snap's "up to two" is `0..2` and the
 * Chancery's "a land" is `1..1`. `mtg-rules` clamps the range to what the board actually offers, so a
 * Chancery whose controller has been left with no land at all returns nothing rather than demanding a
 * permanent that does not exist — the same clamp [TargetCount] describes for a targeting line.
 *
 * @property filter which battlefield permanents may be chosen (CR 609.4). Reuses [PermanentFilter]
 *   rather than introducing a fourth filter type: its subtype/card-type/controller axes are exactly
 *   what these two lines say (Snap's "lands" is `cardType = LAND` with **no** controller restriction —
 *   any player's land — and the Chancery's is the same with `controlledByYou = true`), and no axis is
 *   being added, so the `SourceClassKey` structural equality that type also serves is untouched.
 * @property minimum the fewest permanents that must be chosen — 0 for "up to N"; clamped downward by
 *   `mtg-rules` to what the board offers.
 * @property maximum the most that may be chosen; at least [minimum] and at least 1.
 * @property action what is done to each chosen permanent when the selection is answered.
 */
data class PermanentSelection(
    val filter: PermanentFilter,
    val minimum: Int,
    val maximum: Int,
    val action: PermanentSelectionAction,
) {
    init {
        require(minimum >= 0) { "CR 609.4: a selection's minimum is non-negative, was $minimum" }
        require(maximum >= minimum) {
            "CR 609.4: a selection's range runs from its minimum up to its maximum, got $minimum..$maximum"
        }
        require(maximum >= 1) {
            "CR 609.4: a selection that can never choose anything is not a clause, got $minimum..$maximum"
        }
    }
}

/**
 * What a [PermanentSelection] does to each permanent chosen (CR 609.4). A closed enum for the reason
 * [PermanentRestriction] is one: the rules-side performer must handle every member exhaustively, and a
 * new one must break that `when` rather than slip through. Members exist only where a card in the pool
 * prints them.
 */
enum class PermanentSelectionAction {
    /**
     * Untap each chosen permanent (CR 701.21a) — Snap's "Untap up to two lands". A permanent that is
     * already untapped is a legal choice and simply stays untapped; the CR draws no distinction, and
     * filtering the option list by tapped status would be the engine inventing a restriction the card
     * does not print (it also matters: choosing an untapped land is how a player declines the second
     * half of "up to two" without declining the first).
     */
    UNTAP,

    /**
     * Return each chosen permanent to its owner's hand (CR 701.4a) — Azorius Chancery's "return a land
     * you control to its owner's hand". **Owner's**, not controller's, which is the printed word and
     * coincides with the controller in the current pool (no control-changing effect exists).
     *
     * The Chancery is itself a legal choice for its own trigger, and that is the card: bouncing itself
     * is the standard play when no other land can be spared, and excluding the source would silently
     * delete it.
     */
    RETURN_TO_OWNERS_HAND,
}
