package dev.mtgplay.core.definition

/**
 * *Whose* graveyard a [TargetSpec.CardInGraveyard] draws its legal choices from (CR 115.1, CR 404) —
 * the possessive half of "target creature card from **your** graveyard" versus "target creature or land
 * card from **a** graveyard". Additive, flagged core (`FW-ZONETGT`,
 * docs/design/graveyard-targeting.md §4).
 *
 * Its own axis rather than a member of [GraveyardCardRestriction] because the two are genuinely
 * independent: the gauntlet prints "your graveyard" with three different nouns and "a graveyard" with
 * two, and folding them together would multiply out into a member per pairing — the combinatorial shape
 * a closed restriction enum exists to avoid.
 *
 * **This makes the spec decider-relative**, the second spec after [TargetSpec.TargetOpponent] to depend
 * on who is choosing rather than only on the board. `mtg-rules` reads it against the deciding player:
 * the caster at CR 601.2c, the ability's controller at CR 603.3d, and — critically — the *same* player
 * again at the CR 608.2b re-check, so a spell cannot be cast targeting its controller's graveyard and
 * then re-checked against someone else's.
 *
 * It carries no visibility consequence. Both graveyards are public (CR 400.2), so [ANY] offers an
 * opponent's cards to a seat that could already read them off its own view; the ADR-007 ruling on
 * [dev.mtgplay.core.state.Target.CardInGraveyard] is what says so.
 */
enum class GraveyardScope {
    /** "…from **your** graveyard" (CR 404): only the deciding player's own graveyard. Archaeomancer. */
    YOURS,

    /** "…from **a** graveyard" (CR 404): either player's graveyard. Pulse of Murasa. */
    ANY,
}
