package dev.mtgplay.core.definition

/**
 * A "**target player** exiles a card from their graveyard" clause (CR 701.3a, CR 404, CR 115.1a) —
 * Relic of Progenitus' *"{T}: Target player exiles a card from their graveyard."* Additive, flagged core
 * (`W8-D`).
 *
 * **The clause exists because of *who chooses*, not because of what happens.** Exiling one graveyard
 * card is a published `mtg-rules` primitive and has been since `FW-MULTITGT`; what a plain
 * [ResolutionEffect] cannot do is ask the **targeted player** which card, and CR 701.3a puts that choice
 * on them — "target player exiles" means that player performs the action and therefore makes its
 * choices. The second clause in the engine whose decider is not the resolving object's controller, after
 * [EachOpponentDiscards], and the first decided by a player *named by a target* rather than by "each
 * opponent".
 *
 * **The card is not a target, and that distinction is load-bearing.** The *player* is the target
 * (CR 115.1a); the card is chosen on resolution, with no CR 608.2b re-check of its own. So a Relic of
 * Progenitus ability pointed at an opponent whose graveyard empties in response still resolves — it
 * simply exiles nothing — where a card-targeting ability would have fizzled. Encoding it as a
 * [TargetSpec.CardInGraveyard] would have made the *controller* pick the card and made the ability
 * fizzle, two errors for the price of one.
 *
 * **A data object, because there is nothing to vary.** The one printing exiles exactly one card and
 * restricts it to no type at all. A count and a [GraveyardCardRestriction] are the extension points, and
 * each becomes a property here rather than a second clause.
 *
 * **Core/rules split (ADR-009).** This declares that the clause exists; `mtg-rules` owns finding the
 * targeted player, enumerating their graveyard (a public zone, CR 400.2 — the ADR-007 ruling on
 * [dev.mtgplay.core.state.Target.CardInGraveyard] is what says so), surfacing the request to *them*, and
 * performing the exile. A targeted player with an empty graveyard is asked nothing and the clause does
 * nothing: a request with no options would be an enumerated decision with no legal answer (ADR-005).
 */
data object TargetPlayerExilesFromGraveyard
