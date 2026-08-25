package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.Color

/**
 * An "as this permanent enters the battlefield, choose a color" as-enters choice (CR 614.12) — Utopia
 * Sprawl's bare "As this Aura enters, choose a color", and the Gate cycle's "As this land enters, choose
 * a color other than white". Card-definition data, additive and flagged core (`W8-A`).
 *
 * **Not a triggered ability, and the difference is observable.** CR 614.12 makes this part of the
 * *entering event* itself: it happens as the permanent enters, so nothing goes on the stack, no player
 * receives priority, and no one can respond between the choice and the permanent's arrival. The engine
 * pauses for the choice **before** the object joins the battlefield and stores the answer on the
 * resulting object ([dev.mtgplay.core.state.GameObject.chosenColor]), where the card's mana abilities
 * read it later.
 *
 * **This replaced a `Boolean`.** [CardDefinition.asEntersColorChoice] was a flag and said only *whether* a
 * choice happened, which was exact while Utopia Sprawl — whose printed line restricts nothing — was the only
 * card in the pool that made one. The Gates print "choose a color **other than white**", a restriction
 * on the option list rather than on the choice's existence, and offering white would be an
 * enumerated-but-illegal action (ADR-005) rather than a cosmetic inaccuracy: the Gate would then tap for
 * `{W}{W}` in a deck that plays it precisely because it does not. Widening the type is how the option
 * list stopped being a constant.
 *
 * **Core/rules split (ADR-009).** This declares which colours the printed line admits; `mtg-rules` owns
 * surfacing the enumerated choice (ADR-005), applying it, and storing it on the entering object.
 *
 * @property excluding the one colour the printed line forbids ("choose a color other than white"), or
 *   `null` for an unrestricted "choose a color". A single colour rather than a set because that is what
 *   every printing of the template says; a wider exclusion is the extension point.
 */
data class AsEntersColorChoice(
    val excluding: Color? = null,
)
