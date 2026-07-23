package dev.mtgplay.core.definition

/**
 * The event pattern that fires a [TriggeredAbility] (CR 603.2) — the "when/whenever/at" condition,
 * expressed as card-definition data. Additive, flagged core (P5.1).
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This is the *declaration* of
 * what the ability watches for; `mtg-rules` owns *detecting* that the condition matched a game event
 * (CR 603.3) and *queueing* the ability. Core says "watch for this creature dealing damage"; rules
 * decides, at each transition, whether that happened.
 *
 * Sealed so the rules detector handles every pattern exhaustively and a new pattern breaks
 * compilation rather than going silently undetected. The MVP pool exercises exactly the patterns the
 * four deferred Bogles halves need plus the cast-trigger seam; targeted triggers (CR 603.3d) and
 * intervening-if conditions (CR 603.4) are not needed by any MVP card and are the sealed extension
 * point (P5.2/P6).
 */
sealed interface TriggerCondition {
    /**
     * "When this permanent enters the battlefield" (CR 603.6a) — a self-referential enters-the-
     * battlefield trigger. Cartouche of Solidarity (create a token) and Abundant Growth (draw a
     * card) trigger on their own entry. The trigger carries the entered object as its subject.
     */
    data object EnteredBattlefieldSelf : TriggerCondition

    /**
     * "When this permanent is put into a graveyard from the battlefield" (CR 603.6b, CR 603.10) — a
     * leaves-the-battlefield trigger. Rancor's "return this to its owner's hand" fires as the Aura
     * arrives in the graveyard (most often via the CR 704.5m fall-off when its creature dies). Per
     * CR 603.10 it is checked against the game state just before the object left; the fired trigger
     * carries, as its subject, the fresh graveyard object (CR 400.7) the ability then acts on.
     */
    data object PutIntoGraveyardFromBattlefieldSelf : TriggerCondition

    /**
     * "Whenever enchanted creature deals damage" (CR 603.2) — the Aura watches the object it is
     * attached to (CR 611.2c) and fires when that creature deals damage, combat or noncombat.
     * Armadillo Cloak's "you gain that much life" fires here; the fired trigger carries the amount
     * of damage dealt (CR 118.9 "that much") and the enchanted creature as its subject. Only combat
     * damage occurs in the MVP pool (the enchanted creatures are vanilla), but the condition is not
     * combat-restricted — a noncombat damage source that is itself an enchanted creature would fire
     * it identically, from the same detection seam (`mtg-rules`).
     */
    data object EnchantedCreatureDealsDamage : TriggerCondition

    /**
     * "Whenever a spell is cast" (CR 603.2, CR 601.2i) — the cast-trigger seam. No MVP mainboard card
     * uses it; it exists so the [dev.mtgplay.core.definition.CardDefinition] SPI can express the
     * cast-trigger shape Guttersnipe needs in P6, and so the `completeCast` hook (`mtg-rules`) has a
     * pattern to detect against. A card carrying this trigger fires it as any spell finishes casting.
     * Refinements Guttersnipe needs — "an instant or sorcery spell you control" (CR 603.2e) — are the
     * sealed extension point (P6); this bare form fires on every cast.
     */
    data object SpellCast : TriggerCondition

    /**
     * Madness's reflexive "when this card is discarded this way, its owner may cast it" ability
     * (CR 702.35b) — the condition of the ability the madness replacement synthesizes on a card it
     * exiles instead of discarding (CR 702.35a). Added in P5.2. Unlike the four battlefield conditions
     * this is never *detected* against a game event: the madness replacement creates the fired trigger
     * directly (functioning from [TriggerZoneScope.Exile]), so the trigger detector never produces it.
     * On resolution the reflexive-cast path offers the owner a yes/no cast for the card's madness cost
     * and, if declined or impossible, puts the card into its owner's graveyard (`mtg-rules`); the
     * ability's [TriggeredAbility.effect] is unused because the may-cast is the engine's, not a
     * [ResolutionEffect].
     */
    data object MadnessCast : TriggerCondition
}
