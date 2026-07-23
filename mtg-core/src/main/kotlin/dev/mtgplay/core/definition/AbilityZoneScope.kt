package dev.mtgplay.core.definition

/**
 * Which zone an [ActivatedAbility] functions from (CR 113.6, CR 602.5) — the zone scope that decides
 * where the source must be for the ability to be activatable. Additive, flagged core (P6.2a).
 *
 * Most activated abilities function only on the battlefield (CR 113.6a); some function from the hand
 * (CR 113.6c). Sealed so the activation engine `when`s over scope exhaustively; the graveyard/exile
 * scopes some cards use are the extension point.
 */
sealed interface AbilityZoneScope {
    /** The ability is activatable while its source is a battlefield permanent (CR 602.5a) — Blood, Moxite. */
    data object Battlefield : AbilityZoneScope

    /**
     * The ability is activatable while its source is a card in its owner's hand (CR 113.6c) — Ash
     * Barrens' basic landcycling "{1}, Discard this card: Search your library …". The cost discards the
     * source from hand and the effect functions from there.
     */
    data object Hand : AbilityZoneScope
}
