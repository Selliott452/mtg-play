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

    /**
     * Sacrifice **one chosen permanent** matching [filter] (CR 602.1, CR 701.17) — Krark-Clan Shaman's
     * "Sacrifice an artifact", Makeshift Munitions' "Sacrifice an artifact or creature". Additive,
     * flagged core (`FW-ADDSAC`).
     *
     * The sibling of [SacrificeSelf] and a genuinely different shape: [SacrificeSelf] names its object
     * (the source, no choice, always payable from the battlefield), while this one has a *chosen*
     * object, so the engine surfaces an enumerated selection during activation exactly as
     * [DiscardACard] does — the same shape [AdditionalCost.Sacrifice] takes on the cast side.
     *
     * **Not "sacrifice another".** Neither pool card prints the word: Krark-Clan Shaman reads
     * "Sacrifice an artifact" and Makeshift Munitions "Sacrifice an artifact or creature". The source
     * is excluded from its own cost by the [filter] alone — the Shaman is a Goblin Shaman, the
     * Munitions an Enchantment, so neither matches its own filter — and encoding an `another`
     * restriction instead would be *wrong* for a source that does match (an artifact printing
     * "Sacrifice an artifact:" may sacrifice itself, CR 701.17). See [SacrificeFilter].
     *
     * **Exactly one permanent**, matching [DiscardACard]'s "exactly one card": every printed instance
     * in the pool sacrifices one. A count is the extension point, and it belongs here rather than on
     * [SacrificeFilter] for the reason [AdditionalCost.Sacrifice] carries its own count.
     *
     * @property filter which permanents may be chosen to pay it (CR 602.1).
     */
    data class Sacrifice(
        val filter: SacrificeFilter,
    ) : AbilityCost
}
