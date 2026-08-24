package dev.mtgplay.core.definition

/**
 * A replacement effect (CR 614): "instead"/"as" — it watches for an event about to happen and
 * modifies it before it does. Card-definition *declaration*, additive and flagged core (P5.2);
 * `mtg-rules` owns detecting that an event matches (the interception points), ordering two or more
 * that apply to one event (CR 616.1), and performing the modified event exactly once per event
 * (CR 614.5).
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This is the vocabulary of *which*
 * modification a card's replacement makes; whether it applies to a given event, and the choice among
 * several, are the engine's.
 *
 * Sealed so the engine handles every replacement exhaustively and a new shape breaks compilation
 * rather than being silently mis-applied. The MVP pool needs exactly the two exile-instead members
 * the cast-from-elsewhere mechanics ride; CR 614's other shapes (prevention, redirection, "enters
 * with counters") have no MVP card and are the sealed extension point — a new member plus its
 * interception point, loud-gated until then. The one further shape the pool does print, "this land
 * enters tapped" (CR 614.1c), is deliberately **not** a member here: this list hangs off the castable
 * [SpellDefinition], and a land is played rather than cast (CR 305.1), so it is declared as
 * [CardDefinition.entersTapped] instead.
 */
sealed interface ReplacementEffect {
    /**
     * Madness (CR 702.35a): if a card with this replacement would be discarded, it is exiled instead.
     * Declared alongside a [CastingPermission.Madness] on the same definition — this is the CR 702.35a
     * replacement, the permission is the CR 702.35b reflexive cast. Intercepted at the discard event
     * (`mtg-rules`).
     */
    data object DiscardToExileInstead : ReplacementEffect

    /**
     * Flashback (CR 702.34e): if a spell cast with flashback would leave the stack, it is exiled
     * instead of going to a graveyard. Unlike [DiscardToExileInstead] this is not a static property of
     * the card — it applies only while the spell was *cast via flashback* — so it is carried on the
     * cast record ([CastingPermission.exilesOnLeaveStack]) rather than declared on the definition, and
     * is listed here only so the sealed replacement vocabulary is complete. Intercepted at the
     * stack-departure event, covering resolution, countering, and fizzling (`mtg-rules`).
     */
    data object LeaveStackToExileInstead : ReplacementEffect
}
