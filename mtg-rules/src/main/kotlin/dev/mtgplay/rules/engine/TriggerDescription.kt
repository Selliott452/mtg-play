package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TriggerCondition

/*
 * How a fired trigger is *named* for the CR 603.3b ordering decision (ADR-005) â€” one concept, one file.
 *
 * Split out of `TriggerPlacement.kt` by `W9-G`, which added cascade and pushed both that file's function
 * count and this description's branch count past their budgets. The seam is real rather than
 * budget-driven: placement decides *where* a trigger goes, and this decides what an agent is told it is.
 */

/**
 * A short human description of one trigger condition, for the ordering decision's display (ADR-005).
 *
 * Takes the bare condition rather than the whole [PendingTrigger] since `W8-C`, so
 * [TriggerCondition.AnyOf] can describe each pattern it names by recursing into itself. The recursion is
 * one level deep by construction â€” a disjunction is never nested ([TriggerCondition.AnyOf]'s `init`
 * refuses it).
 */
internal fun describeCondition(condition: TriggerCondition): String =
    when (condition) {
        TriggerCondition.EnteredBattlefieldSelf -> "enters-the-battlefield"
        TriggerCondition.EnteredBattlefieldUntappedSelf -> "enters-the-battlefield-untapped"
        TriggerCondition.PutIntoGraveyardFromBattlefieldSelf -> "put-into-graveyard-from-the-battlefield"
        TriggerCondition.LeftBattlefieldSelf -> "leaves-the-battlefield"
        TriggerCondition.EnchantedCreatureDealsDamage -> "enchanted-creature-deals-damage"
        TriggerCondition.EnchantedPermanentBecomesTapped -> "enchanted-permanent-becomes-tapped"
        TriggerCondition.EnchantedPermanentIsDealtDamage -> "enchanted-permanent-is-dealt-damage"
        TriggerCondition.DealtCombatDamageToPlayerSelf -> "deals-combat-damage-to-a-player"
        TriggerCondition.BecameTargetOfOpponentsSpellOrAbility -> "ward"
        is TriggerCondition.DrewNthCardThisTurn -> "drew-card-number-${condition.n}"
        // CR 603.2: a disjunctive condition is one ability, so it gets one description â€” the patterns it
        // watches, joined. Which of them actually fired is not recorded on the trigger and is not the
        // ordering decision's business; the description exists to tell two *abilities* apart (ADR-005).
        is TriggerCondition.AnyOf ->
            condition.conditions.joinToString(separator = "-or-", transform = ::describeCondition)
        // The cast-shaped conditions, described by the other half. Named individually rather than by an
        // `else`, so a new [TriggerCondition] member still breaks compilation here (CONVENTIONS.md).
        is TriggerCondition.SpellCast,
        TriggerCondition.MadnessCast,
        TriggerCondition.ReboundCast,
        TriggerCondition.CascadeCast,
        TriggerCondition.StormCast,
        -> describeCastCondition(condition)
    }

/**
 * The cast-shaped half of [describeCondition]: the conditions that watch a *spell* rather than an object
 * sitting in a zone â€” the general "whenever a spell is cast" (CR 603.2) and the three engine-synthesized
 * may-cast abilities (madness CR 702.35b, rebound CR 702.88b, cascade CR 702.85a).
 *
 * Split out because detekt's complexity budget fell here, and the split is at a real seam rather than an
 * arbitrary line: everything in the half above watches an object in a zone, everything here watches a
 * cast. **Both halves stay exhaustive and neither uses an `else`**, so a new [TriggerCondition] member
 * breaks compilation twice over rather than falling silently into a branch â€” which for this function
 * would mean a trigger the CR 603.3b ordering decision cannot name (ADR-005).
 */
private fun describeCastCondition(condition: TriggerCondition): String =
    when (condition) {
        is TriggerCondition.SpellCast -> "spell-cast"
        TriggerCondition.MadnessCast -> "madness-may-cast"
        TriggerCondition.ReboundCast -> "rebound-may-cast"
        TriggerCondition.CascadeCast -> "cascade"
        TriggerCondition.StormCast -> "storm"
        // Reachable only by calling this directly with a zone-shaped condition, which the one caller
        // above never does; listed rather than `else`d so the exhaustiveness check still bites.
        TriggerCondition.EnteredBattlefieldSelf,
        TriggerCondition.EnteredBattlefieldUntappedSelf,
        TriggerCondition.PutIntoGraveyardFromBattlefieldSelf,
        TriggerCondition.LeftBattlefieldSelf,
        TriggerCondition.EnchantedCreatureDealsDamage,
        TriggerCondition.EnchantedPermanentBecomesTapped,
        TriggerCondition.EnchantedPermanentIsDealtDamage,
        TriggerCondition.DealtCombatDamageToPlayerSelf,
        TriggerCondition.BecameTargetOfOpponentsSpellOrAbility,
        is TriggerCondition.DrewNthCardThisTurn,
        is TriggerCondition.AnyOf,
        -> error("CR 603.2: $condition is not a cast-shaped condition; describeCondition names it")
    }
