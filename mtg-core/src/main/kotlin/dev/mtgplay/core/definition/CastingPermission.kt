package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaCost

/**
 * One alternative way a card may be cast, beyond a normal cast from the hand — the unifying data
 * behind the four "cast-from-elsewhere" mechanics of the MVP pool (madness, flashback, plot, escape;
 * docs/decklists.md). Additive, flagged core (P5.2). Card-definition *declaration*; `mtg-rules` owns
 * when a permission is legal, enumerates it (ADR-005), and runs the cast pipeline from its
 * [source] zone.
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** A permission states its
 * alternative [cost] (which *replaces* the printed mana cost, CR 118.9 / CR 601.2f), its source
 * zone, its additional non-mana cost, and the replacement it rides (below); the engine decides
 * whether it can be used right now and executes it.
 *
 * Sealed so the enumerator and cast pipeline handle every permission exhaustively; the plot slot
 * (cast from exile for free at sorcery speed, CR 702.140) is the documented extension point — it adds
 * a member here and its enumeration case, without reshaping this contract (P6).
 *
 * @property cost the alternative mana cost this permission casts for (CR 118.9): it *replaces* the
 *   card's printed mana cost entirely, never adds to it.
 * @property source which zone the cast draws the card from (CR 601.2a).
 */
sealed interface CastingPermission {
    val cost: ManaCost
    val source: CastSource

    /**
     * How many *other* cards must be exiled from the [source] zone as an additional cost of casting
     * this way (CR 601.2b, CR 118.9) — escape's "exile N other cards from your graveyard"; `0` for a
     * permission with no such additional cost (madness, flashback). The engine surfaces the selection
     * decision and performs the exile during payment (CR 601.2h).
     */
    val additionalExileCount: Int get() = 0

    /**
     * Whether a spell cast via this permission is **exiled instead of** being put into a graveyard as
     * it leaves the stack (CR 702.34e / CR 614) — the flashback replacement, covering resolution,
     * countering, and fizzling. `false` for madness, escape, and plot: their spells leave the stack
     * by the ordinary rules.
     */
    val exilesOnLeaveStack: Boolean get() = false

    /**
     * Whether this permission is offered as a priority-window cast option (CR 117.1a) — `true` for
     * flashback, escape, and plot, each cast when the caster has priority. `false` for madness, whose
     * cast is offered only as its reflexive triggered ability resolves (CR 702.35b), never at a plain
     * priority window; see the reflexive-trigger flow in `mtg-rules`.
     */
    val offeredAtPriority: Boolean get() = true

    /**
     * Madness (CR 702.35): a card with madness that would be discarded is exiled instead
     * ([DiscardToExileInstead], the CR 702.35a replacement declared separately on the definition), and
     * its owner may then cast it from exile for its madness [cost] as a reflexive triggered ability
     * resolves (CR 702.35b). Cast from [CastSource.EXILE]; not offered at a plain priority window.
     */
    data class Madness(
        override val cost: ManaCost,
    ) : CastingPermission {
        override val source: CastSource = CastSource.EXILE
        override val offeredAtPriority: Boolean = false
    }

    /**
     * Flashback (CR 702.34): the card may be cast from the graveyard for its flashback [cost], and if
     * that spell would leave the stack it is exiled instead (CR 702.34e — [exilesOnLeaveStack]). Cast
     * from [CastSource.GRAVEYARD] at the card's own timing.
     */
    data class Flashback(
        override val cost: ManaCost,
    ) : CastingPermission {
        override val source: CastSource = CastSource.GRAVEYARD
        override val exilesOnLeaveStack: Boolean = true
    }

    /**
     * Escape (CR 702.139): the card may be cast from the graveyard for its escape [cost] plus an
     * additional cost of exiling [additionalExileCount] *other* cards from the graveyard. A permanent
     * cast this way resolves onto the battlefield normally (no leave-stack replacement) and behaves as
     * an ordinary permanent thereafter. Cast from [CastSource.GRAVEYARD] at the card's own timing.
     *
     * @property exileOthers how many other graveyard cards the additional cost exiles (CR 702.139a);
     *   Sentinel's Eyes is two (architect-verified).
     */
    data class Escape(
        override val cost: ManaCost,
        val exileOthers: Int,
    ) : CastingPermission {
        override val source: CastSource = CastSource.GRAVEYARD
        override val additionalExileCount: Int = exileOthers
    }
}
