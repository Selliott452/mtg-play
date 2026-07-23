package dev.mtgplay.core.definition

/**
 * One triggered ability of a card (CR 603): a [condition] that fires it, the zone it functions from
 * ([zoneScope]), and the [effect] it performs on resolution. Card-definition data, additive and
 * flagged core (P5.1).
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This declares *what* the ability
 * watches for and does; `mtg-rules` owns *detecting* the trigger (CR 603.3), *ordering* simultaneous
 * triggers in APNAP order (CR 603.3b), putting the ability on the stack, and resolving it (CR 608.2).
 * A triggered ability "uses the stack" and resolves like a spell (CR 603.3), except no card moves on
 * resolution — the ability simply performs [effect] and ceases to exist (CR 113.7a).
 *
 * The [effect] reuses the [ResolutionEffect] shape (P5.1 deliverable): the rules engine hands it a
 * [ResolutionContext] carrying the ability's controller plus the trigger's linked information —
 * [ResolutionContext.amount] (a damage-dealt "that much", CR 118.9) and [ResolutionContext.subject]
 * (a leaves-the-battlefield trigger's LKI object, CR 603.10). None of the four MVP halves targets
 * (CR 603.3d), so triggered-ability targets are not modeled here; they are the extension point for
 * P5.2/P6, alongside intervening-if conditions (CR 603.4).
 *
 * @property condition the event pattern that fires this ability (CR 603.2).
 * @property effect what the ability does when it resolves (CR 608.2); reuses [ResolutionEffect].
 * @property zoneScope the zone the ability functions from (CR 113.6); [TriggerZoneScope.Battlefield]
 *   for every MVP triggered half.
 */
data class TriggeredAbility(
    val condition: TriggerCondition,
    val effect: ResolutionEffect,
    val zoneScope: TriggerZoneScope = TriggerZoneScope.Battlefield,
)
