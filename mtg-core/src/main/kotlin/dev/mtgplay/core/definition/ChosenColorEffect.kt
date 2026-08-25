package dev.mtgplay.core.definition

/**
 * A **"choose a colour, then do something with it" resolution clause** (CR 609.4) — the post-resolution
 * clause a definition declares through [ResolutionClauses.chosenColorEffect]. Additive, flagged core
 * (`FW-PREVENT2`). Prismatic Strands' "the color of your choice".
 *
 * **A clause rather than a [ResolutionEffect], for the reason every member of [ResolutionClauses] is
 * one**: the colour is a *decision*, and ADR-004 forbids a callback out of an effect. The engine pauses
 * the resolution, surfaces the enumerated five-colour choice (ADR-005), applies the answer, and
 * completes the resolution.
 *
 * **Not [CardDefinition.choosesColorAsItEnters], which is a different rule at a different moment.**
 * That flag is CR 614.12 — "as this **permanent** enters, choose a colour" (Utopia Sprawl) — a
 * self-replacement on a permanent's entry, and the colour it yields is stored on the entering object
 * ([dev.mtgplay.core.state.GameObject.chosenColor]) for that permanent's own abilities to read. This is
 * CR 609.4: an instruction *inside* the resolution of a spell that never becomes a permanent, whose
 * colour is consumed immediately and stored nowhere on any object. The two flows share
 * [dev.mtgplay.rules.decision] machinery — the request is identical, five colours in WUBRG order — and
 * share nothing else, which is why this is a separate declaration rather than a reuse of that flag.
 *
 * Sealed, so `mtg-rules` dispatches on what to *do* with the chosen colour exhaustively: a card that
 * chooses a colour for a purpose the engine has not implemented breaks the `when` at compile time
 * rather than choosing a colour and silently discarding it.
 */
sealed interface ChosenColorEffect {
    /**
     * "Prevent all damage that sources of the chosen colour would deal this turn" (CR 615.1) —
     * Prismatic Strands, and the only thing anything in the gauntlet does with a chosen colour.
     *
     * A `data object` because the clause is parameterless: the colour is the *answer*, not a
     * declaration, and the duration and the breadth ("all damage", from every source, to everything)
     * are the printed card's and are fixed. A card preventing damage from the chosen colour to a
     * narrower set, or for a different duration, would be a different member — which is exactly the
     * sealed-set discipline that keeps such a card from being silently encoded as this one.
     */
    data object PreventDamageFromChosenColorThisTurn : ChosenColorEffect
}
