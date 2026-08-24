package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * One thing a spell, ability, or combat-damage assignment refers to (CR 115.1, CR 120): the
 * value recorded on a stack entry when targets are chosen (CR 601.2c) and re-checked on
 * resolution (CR 608.2b), and the recipient shape the damage primitive addresses.
 *
 * Sealed so both target-legality logic and the damage primitive handle every kind exhaustively.
 * [Player] and [Permanent] are the P3.1 pair: a player (CR 115.1a) and a battlefield permanent
 * referenced by [dev.mtgplay.core.identity.ObjectId] (the object-targeting member Phase 3 adds
 * alongside the battlefield state it refers to — additive, flagged, P3.1). Combat damage never
 * *targets* (CR 509.1 blocking is not targeting), but a combat-damage recipient and a targetable
 * battlefield object coincide in this engine's scope, so both reuse [Permanent]. Target-legality
 * enumeration still offers players only — nothing enumerates a permanent as a legal *target*
 * until a spell in a later pool needs it; adding the member does not by itself make permanents
 * targetable (that is `legalTargets`' concern, `mtg-rules`).
 *
 * **The hierarchy is one member per *kind of thing*, not one per zone.** [SpellOnStack] is the third
 * kind (`FW-COUNTER`, docs/design/countering-spells.md §4): an object on the stack, which is neither a
 * player nor a permanent. Every member names its referent by an id that is fresh for its **current
 * residence** (CR 400.7), which is what makes a stale target match nothing rather than following a card
 * into its next zone. A member for a card in another zone (`FW-ZONETGT` — "target card in a graveyard",
 * "target creature card in your graveyard") slots in the same way and reuses the same freshness rule;
 * what it additionally owes, and [SpellOnStack] does not, is the ADR-007 ruling `SeatView`'s KDoc
 * already reserves — a library or hand card's identity is not public, so an option list naming one is
 * per-seat information in a way a stack object never is.
 */
sealed interface Target {
    /** A player (CR 115.1a): a targeted player, or a player dealt damage (CR 120.3a). */
    data class Player(
        val id: PlayerId,
    ) : Target

    /**
     * A battlefield permanent, by its current-zone [id] (CR 115.1b, CR 120.3d). In P3.1 this is
     * a combat-damage recipient (an attacker or blocker taking marked damage); nothing yet
     * enumerates it as a legal spell target.
     */
    data class Permanent(
        val id: ObjectId,
    ) : Target

    /**
     * A spell on the stack (CR 115.1, CR 111.1 — a spell *is* an object), by its current **stack
     * residence** id. Additive, flagged core (`FW-COUNTER`, docs/design/countering-spells.md §4):
     * what a counter targets.
     *
     * The id is the one [dev.mtgplay.core.state.StackEntry.Spell.obj] carries, minted as the spell
     * was put on the stack (CR 601.2a, CR 400.7) and dying with that residence — resolution, a
     * counter, and a fizzle each rebirth the card in its next zone under a *different* id. So a
     * target naming a spell that has already left the stack matches nothing in any zone and can
     * never accidentally address the graveyard card; the CR 608.2b re-check then correctly fizzles
     * whatever was pointing at it, through the enumeration that already exists rather than through a
     * special case.
     *
     * **An ability on the stack is deliberately unnameable.** [StackEntry.Ability] and
     * [StackEntry.ActivatedAbilityOnStack] carry no card object and no residence id (CR 113.7a), so no
     * value of this type can refer to one. Countering an ability (Stifle) needs a stack-entry identity
     * distinct from [ObjectId]; until such a card exists the mistake is unrepresentable.
     */
    data class SpellOnStack(
        val id: ObjectId,
    ) : Target
}
