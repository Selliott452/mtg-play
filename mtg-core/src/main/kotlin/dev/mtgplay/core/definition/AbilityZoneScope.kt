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

    /**
     * The ability is activatable while its source is a card in its owner's graveyard (CR 113.6b) —
     * Bramble Wurm's "{2}{G}, Exile this card from your graveyard: You gain 5 life." Additive, flagged
     * core (`W8-E`).
     *
     * **Not a variant of [Hand] with a different zone name.** A hand is that player's own hidden zone
     * (CR 400.2) and every card in it is a card they could be holding for any reason; a graveyard is a
     * **public** zone (CR 404.1, CR 400.2), so the option list this scope produces is information both
     * seats already have, and the enumeration hides nothing. That is why a graveyard-scoped ability may
     * be enumerated straight off the zone without any of the `library-look.md` §3 care a hidden-zone
     * option list needs.
     *
     * The CR 113.6b permission is what makes it work at all: an ability of a card in a graveyard
     * normally does nothing there, and only "abilities that specifically say they function from a
     * graveyard" do — Bramble Wurm's does, by naming the zone in its own cost.
     */
    data object Graveyard : AbilityZoneScope
}
