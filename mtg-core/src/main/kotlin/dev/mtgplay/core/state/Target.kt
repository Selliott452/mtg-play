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
 * player nor a permanent. [CardInGraveyard] is the fourth (`FW-ZONETGT`). Every member names its
 * referent by an id that is fresh for its **current residence** (CR 400.7), which is what makes a stale
 * target match nothing rather than following a card into its next zone.
 *
 * **The ADR-007 ruling this hierarchy carries (`FW-ZONETGT`).** Visibility is decided by the *zone*, not
 * by the fact of targeting: CR 400.2 makes the graveyard a **public** zone and the library and hand
 * **hidden** ones. So a member naming a graveyard card discloses nothing, and a member naming a library
 * or hand card would be per-seat information in a way no member here has ever been. That distinction is
 * kept **structural rather than reviewed**: [CardInGraveyard] names its zone in its type, and there is
 * deliberately no zone-parameterised `CardInZone(id, zone)` member that could carry a public and a
 * hidden referent in one shape. The consequence is a guarantee the view layer can rely on — *no value of
 * any member of this hierarchy can name a card in a hidden zone* — so ADR-005's enumeration and
 * ADR-007's filter agree by construction. A future "target card in a library" is a **new member**, and
 * adding one breaks every `when` in the codebase, view-side ones included; that break is the review
 * moment ADR-007 wants, and it is not skippable.
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

    /**
     * A card in a **graveyard** (CR 115.1, CR 404), by its current **graveyard residence** id.
     * Additive, flagged core (`FW-ZONETGT`, docs/design/graveyard-targeting.md): what Archaeomancer's
     * trigger and Pulse of Murasa target.
     *
     * The id is the one the [GameObject] in a player's [PlayerState.graveyard] carries, minted as the
     * card arrived there (CR 400.7) and dying with that residence — returning it to a hand or to the
     * battlefield rebirths it under a *different* id. So a target naming a card that has already left
     * the graveyard matches nothing in any zone, and the CR 608.2b re-check fizzles whatever was
     * pointing at it through the enumeration that already exists, exactly as it does for
     * [SpellOnStack]. Which graveyard the card is in is not recorded: an object id is unique across the
     * game, so the id alone identifies it, and both graveyards are public.
     *
     * **This member is safe to enumerate to either seat, and that is a property of the zone.** A
     * graveyard is public (CR 400.2), and `visibleCardRefs` already feeds *both* seats' graveyards into
     * `SeatView.cards`, so an option list naming one of these cards tells a seat nothing it could not
     * read off its own view. The `SeatView.pendingTriggerTargets` KDoc reserved a revisit for the
     * arrival of this member; the revisit is this paragraph, and its answer is that no filtering rule is
     * added, because the type cannot name a hidden-zone card at all. See the hierarchy KDoc above for
     * why that is structural.
     */
    data class CardInGraveyard(
        val id: ObjectId,
    ) : Target
}
