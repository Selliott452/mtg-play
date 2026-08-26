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
     * Exile the source permanent (CR 701.3a) — Relic of Progenitus' "{1}, Exile this artifact:".
     * Additive, flagged core (`W8-D`).
     *
     * **[SacrificeSelf]'s sibling, and where the permanent *goes* is the whole difference** — the same
     * argument [ReturnPermanentYouControl] makes against being folded into [Sacrifice]. A sacrificed
     * permanent lands in its owner's graveyard, where it is recurrable, counts for a graveyard-reading
     * cost, and can fire a dies trigger; an exiled one is gone. On this very card the distinction is
     * load-bearing in a way an approximation would hide: Relic of Progenitus' own ability exiles *all*
     * graveyards, so a Relic that sacrificed itself would put itself into a graveyard it is about to
     * empty — an ordering question that simply does not arise, because the printed cost exiles.
     *
     * The cost is paid at CR 602.2b, so the source is already in exile when the ability resolves. That is
     * observable: an ability of the source that reads the battlefield finds nothing there, and no
     * leaves-the-battlefield *dies* trigger fires, because the permanent did not go to a graveyard
     * (CR 603.6b). A CR 603.6c leaves-the-battlefield trigger would still fire; no card in the pool
     * prints one on an exiling source.
     */
    data object ExileSelf : AbilityCost

    /**
     * Discard the source card itself (CR 701.8) — the cost of a hand-functioning ability like Ash
     * Barrens' basic landcycling "{1}, Discard this card". The source leaves the hand for the graveyard
     * as the cost is paid, and the ability's effect then functions from having been so discarded.
     */
    data object DiscardSelf : AbilityCost

    /**
     * Exile the source card itself **from its owner's graveyard** (CR 701.3a) — the cost of a
     * graveyard-functioning ability like Bramble Wurm's "{2}{G}, Exile this card from your graveyard:
     * You gain 5 life." Additive, flagged core (`W8-E`). Pairs with [AbilityZoneScope.Graveyard].
     *
     * **The zone is in the member, not a parameter, because the zone is the whole rule.** CR 113.6b
     * only lets an ability function from a graveyard when it says so, and this cost is how Bramble
     * Wurm says so; a generic "exile the source" that worked from anywhere would be activatable off the
     * battlefield too, which the printed line does not permit. It is the graveyard sibling of
     * [DiscardSelf] (hand) and [SacrificeSelf] (battlefield), and like both of them it names its object
     * rather than choosing one, so it needs no selection while gathering.
     *
     * **It is a cost, so it is paid on activation and is not undone if the ability is countered**
     * (CR 601.2h via CR 602.2b) — and, critically, it makes the ability **once only**: the card leaves
     * the graveyard for exile as the cost is paid, so a second activation has no source. That is why
     * the printed line needs no "Activate only once" restriction and why [ActivatedAbility.oncePerTurn]
     * is not the mechanism here.
     */
    data object ExileSelfFromGraveyard : AbilityCost

    /**
     * Discard a chosen card from hand (CR 701.8) — Blood token's "Discard a card". The engine surfaces
     * the selection during activation and discards it through the CR 614/616 framework (so a discarded
     * madness card is exiled instead).
     */
    data object DiscardACard : AbilityCost

    /**
     * "Pay `{E}{E}`" (CR 107.16, CR 118.4): the ability's controller pays [amount] energy counters.
     * Additive, flagged core (`FW-EQUIP`) — Inventor's Axe's equip cost.
     *
     * **Not a [Mana] cost, and the difference is not cosmetic.** `{E}` is not a mana symbol: it is never
     * added to a mana pool, never produced by a mana ability, and never affected by cost reduction or by
     * anything that cares about coloured mana. It is paid from a running total the player carries
     * ([dev.mtgplay.core.state.PlayerState.energyCounters]), which is why it needs no payment plan and
     * surfaces no decision — there is exactly one way to pay it, so ADR-005's enumeration is a yes/no.
     *
     * The payability rule is the whole of it: the ability is enumerated only while the controller has at
     * least [amount] energy. A player who cannot pay never sees the option, rather than seeing it and
     * dead-ending.
     *
     * @property amount how many energy counters are paid; at least one.
     */
    data class Energy(
        val amount: Int,
    ) : AbilityCost {
        init {
            require(amount >= 1) { "CR 118.4: an energy cost pays at least one counter, was $amount" }
        }
    }

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

    /**
     * Return **one chosen permanent you control** matching [filter] to its owner's hand as a cost
     * (CR 602.1, CR 701.4a) — Quirion Ranger's "Return a Forest you control to its owner's hand:
     * Untap target creature." Additive, flagged core (`FW-TAPUNTAP`).
     *
     * The third cost component with a *chosen* object, after [DiscardACard] and [Sacrifice], and
     * deliberately their shape: the engine surfaces an enumerated selection during activation
     * (ADR-005) and performs the return during payment (CR 602.2b). Exactly one permanent, matching
     * [DiscardACard]'s "exactly one card" and [Sacrifice]'s "exactly one permanent" — Quirion Ranger
     * is the only printing in the gauntlet and it returns one — and a count is the extension point
     * for the same reason it is there.
     *
     * **Not a [Sacrifice] with a different verb.** Where the permanent *goes* is the whole difference,
     * and it is not cosmetic: a returned permanent is alive in its owner's hand and recastable, so a
     * card whose cost returns a land is a mana-neutral loop (Quirion Ranger untaps a creature, and the
     * Forest can be replayed), while a sacrifice is a real loss. Encoding one as the other would be a
     * plausible-looking wrong card (PLAN.md §7). It is also a *different filter type*: this one carries
     * [PermanentFilter], because "a **Forest** you control" names a subtype (CR 205.3) and
     * [SacrificeFilter] carries card types alone and cannot say it.
     *
     * **"You control" is in the filter, not the member name's promise.** [PermanentFilter.controlledByYou]
     * is what restricts the choice to the activator's own permanents; a printing that returned any
     * permanent would be the same member with the flag off, and nothing here would change.
     *
     * @property filter which permanents may be chosen to pay it (CR 602.1) — Quirion Ranger's is
     *   `PermanentFilter(subtype = Subtype("Forest"), controlledByYou = true)`.
     */
    data class ReturnPermanentYouControl(
        val filter: PermanentFilter,
    ) : AbilityCost

    /**
     * **Tap one chosen untapped permanent you control** matching [filter] (CR 602.1, CR 701.20a) —
     * Pinnacle Kill-Ship's Station, "Tap another creature you control". Additive, flagged core
     * (`W10-C`).
     *
     * The fourth cost component with a *chosen* object, after [DiscardACard], [Sacrifice] and
     * [ReturnPermanentYouControl], and deliberately their shape: the engine enumerates the candidates
     * and the activator picks by index (ADR-005), and the tap happens during payment (CR 602.2b).
     *
     * **Not [TapSelf] with a filter.** `{T}` is a symbol naming the source and nothing else, and the two
     * costs differ in every way that matters to the rules: `{T}` is subject to CR 302.6 summoning
     * sickness and this is not (CR 302.6 restricts the `{T}` *symbol* in an ability of that permanent, so
     * a creature that arrived this turn may be tapped to pay this — a real and frequently-correct line
     * of play that a summoning-sickness gate here would delete). Nor is it
     * [ReturnPermanentYouControl] with a different verb: a tapped permanent is alive, on the
     * battlefield, and untaps next turn, so the two are the difference between a loan and a loss.
     *
     * **Untapped is intrinsic, not a field**, exactly as [TapRequirement] argues for the cast-side
     * sibling: a tap cost can only be paid by an untapped permanent (CR 118.4 — tapping a tapped
     * permanent does nothing and pays nothing), so the printed word "untapped" is reminder text.
     *
     * **"You control" is likewise not optional.** CR 601.2h lets a player tap only permanents they
     * control to pay a cost, so it is a property of the cost rather than of the [filter] — the filter's
     * own `controlledByYou` axis is set for the same reason it is on [ReturnPermanentYouControl], and a
     * printing that tapped a permanent an opponent controlled would be a different cost entirely.
     *
     * @property filter which permanents may be chosen to pay it (CR 602.1) — Station's is
     *   `PermanentFilter(cardType = CardType.CREATURE, controlledByYou = true)`.
     * @property another whether the printed text says **another**, excluding the ability's own source
     *   from the choice (CR 109.5). `false` for a cost that names no such restriction.
     *
     *   **A flag on the cost rather than an axis of the filter**, and the reason is the argument
     *   [Sacrifice] already records from the other side. Krark-Clan Shaman's "Sacrifice an artifact"
     *   prints no "another" and its source is excluded by the filter alone, so an `another` restriction
     *   there would be *wrong*. Station prints the word, and its source is exactly the kind of permanent
     *   that would otherwise match: a Spacecraft with seven charge counters **is** a creature you
     *   control, so without this flag a fully-stationed Kill-Ship could tap itself to station itself —
     *   an enumerated-but-illegal action (ADR-005), and one that only appears at the moment the card
     *   starts working. Two printings, two answers, so the answer is data.
     */
    data class TapPermanentYouControl(
        val filter: PermanentFilter,
        val another: Boolean = false,
    ) : AbilityCost
}
