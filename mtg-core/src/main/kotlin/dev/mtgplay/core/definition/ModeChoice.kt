package dev.mtgplay.core.definition

/**
 * How many of a modal card's printed modes are chosen (CR 700.2, CR 601.2b) — "Choose one", "Choose up
 * to two", "Choose two". Card-definition data, additive and flagged core (`W9-B`).
 *
 * **Its own type rather than two `Int`s on [SpellDefinition]**, because the pair is a single printed
 * phrase and the two halves are meaningless apart: a card that said "choose at least one" without
 * saying how many at most does not exist, and an [EXACTLY_ONE] declaration that could drift into
 * `1..0` would be an unanswerable request. The `init` below is where "choose N of M" stops being
 * arithmetic and starts being a rule.
 *
 * **[minimum] may be zero, and that is a real line.** "Choose up to two" permits choosing *no* modes:
 * the spell is still cast, still costs its mana, still baits a counter, and resolves doing nothing.
 * Call Damage Control with an empty graveyard is exactly that, and it is occasionally the correct play
 * — so the engine offers it (ADR-005) rather than declaring the cast illegal.
 *
 * **What it deliberately cannot express.** A card that may choose the same mode twice ("Choose two.
 * You may choose the same mode more than once") is not this type: its answer is a *multiset* rather
 * than a subset, which changes the decision's shape and not merely its bounds. No card in the pool
 * prints it, and folding it in as a flag would make the subset case's distinctness rule conditional —
 * the sort of quiet widening [GraveyardCardRestriction] refuses for the same reason.
 *
 * @property minimum the fewest modes that must be chosen; zero for an "up to N" card.
 * @property maximum the most that may be chosen; at least one, and at least [minimum].
 */
data class ModeChoice(
    val minimum: Int,
    val maximum: Int,
) {
    init {
        require(minimum >= 0) { "CR 700.2: a mode count is non-negative, was $minimum" }
        require(maximum >= 1) { "CR 700.2: a modal card chooses at least one mode at most, was $maximum" }
        require(minimum <= maximum) {
            "CR 700.2: a mode count runs from its minimum up to its maximum, got $minimum..$maximum"
        }
    }

    companion object {
        /** "Choose one —" (CR 700.2a): the shape every modal card in the pool printed before `W9-B`. */
        val EXACTLY_ONE: ModeChoice = ModeChoice(minimum = 1, maximum = 1)

        /** "Choose up to [count]" (CR 700.2a): choosing none is legal, and the spell resolves anyway. */
        fun upTo(count: Int): ModeChoice = ModeChoice(minimum = 0, maximum = count)

        /** "Choose [count]" (CR 700.2a): exactly that many, no fewer. */
        fun exactly(count: Int): ModeChoice = ModeChoice(minimum = count, maximum = count)
    }
}
