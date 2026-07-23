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
}
