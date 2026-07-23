package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaCost

/**
 * One component of an activated ability's composite cost (CR 602.1, CR 118) — the sealed cost
 * vocabulary an [ActivatedAbility] is built from. Additive, flagged core (P6.2a). Card-definition
 * *declaration*; `mtg-rules` owns whether a component is payable, surfaces any selection it needs, and
 * performs it during activation (CR 602.2b).
 *
 * A composite cost is a list of these (Blood token's "{1}, {T}, Discard a card, Sacrifice this token"
 * is [Mana]`+`[TapSelf]`+`[DiscardACard]`+`[SacrificeSelf]). Sealed so the activation pipeline handles
 * every component exhaustively; new cost shapes (pay life, remove a counter) are the extension point.
 */
sealed interface AbilityCost {
    /** A mana cost component (CR 118), paid with a payment plan (Blood's `{1}`, Melded Moxite's `{3}`). */
    data class Mana(
        val cost: ManaCost,
    ) : AbilityCost

    /** Tap the source permanent (the `{T}` symbol, CR 602.2a) — payable only while it is untapped. */
    data object TapSelf : AbilityCost

    /** Sacrifice the source permanent (CR 701.17) — Blood's "Sacrifice this token", Melded Moxite's. */
    data object SacrificeSelf : AbilityCost

    /**
     * Discard the source card itself (CR 701.8) — the cost of a hand-functioning ability like Ash
     * Barrens' basic landcycling "{1}, Discard this card". The source leaves the hand for the graveyard
     * as the cost is paid, and the ability's effect then functions from having been so discarded.
     */
    data object DiscardSelf : AbilityCost

    /**
     * Discard a chosen card from hand (CR 701.8) — Blood token's "Discard a card". The engine surfaces
     * the selection during activation and discards it through the CR 614/616 framework (so a discarded
     * madness card is exiled instead).
     */
    data object DiscardACard : AbilityCost
}
