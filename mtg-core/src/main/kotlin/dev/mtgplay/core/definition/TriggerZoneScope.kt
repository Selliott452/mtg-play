package dev.mtgplay.core.definition

/**
 * Which zone a [TriggeredAbility] functions from (CR 113.6, CR 603.6) — the "zone scope" that
 * decides whether the ability is even watching for its condition. Additive, flagged core (P5.1).
 *
 * Most abilities function only while their source is on the battlefield (CR 113.6a); some function
 * from the graveyard, hand, or exile (CR 113.6). The MVP mainboard's four triggered halves all
 * function from the battlefield — a leaves-the-battlefield trigger (Rancor) is still a
 * [Battlefield]-scoped ability, since CR 603.10 checks it against the state just before the object
 * left. Sealed so the rules detector `when`s over scope exhaustively; the graveyard/hand/exile
 * scopes that Sneaky Snacker, Ash Barrens landcycling, and madness/plot need (P6) are the extension
 * point — a new member breaks compilation rather than being silently mis-scoped.
 */
sealed interface TriggerZoneScope {
    /**
     * The ability functions while its source is on the battlefield (CR 113.6a) — the scope of every
     * MVP triggered half. A leaves-the-battlefield trigger is battlefield-scoped: it is watched from
     * the battlefield and evaluated against the pre-departure state (CR 603.10).
     */
    data object Battlefield : TriggerZoneScope

    /**
     * The ability functions from exile (CR 113.6, CR 406) — the P5.2 extension point the packet's
     * spec names. Madness's reflexive "you may cast this card" ability (CR 702.35b) functions from
     * exile: the card was exiled by the discard replacement (CR 702.35a) and its owner may cast it as
     * the reflexive trigger resolves. This is a *synthesized* ability the madness replacement creates
     * on the exiled card, not a printed one, so the battlefield trigger detector never matches it; it
     * is placed on the stack and resolved by the reflexive-cast path (`mtg-rules`).
     */
    data object Exile : TriggerZoneScope

    /**
     * The ability functions from the graveyard (CR 113.6, CR 404) — the P6.2a extension point. Sneaky
     * Snacker's "when you draw your third card in a turn, return this card from your graveyard to the
     * battlefield tapped" (CR 603.2) functions from the graveyard: the trigger is watched while the
     * card sits in its owner's graveyard, and `mtg-rules` fires it against the graveyard object as its
     * source and subject (CR 603.10). A graveyard-scoped trigger is never fired by the battlefield
     * detector; its own detection site scans graveyards.
     */
    data object Graveyard : TriggerZoneScope
}
