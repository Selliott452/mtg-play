package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TriggerCondition

/*
 * Human-readable names for trigger conditions, for the CR 603.3b ordering decision's display (ADR-005).
 *
 * Its own file since `W9-A`, when ward's condition took `TriggerPlacement.kt` past detekt's
 * functions-per-file budget. Splitting the *display* concern out rather than splitting the placement
 * logic is the cheaper cut: nothing here decides anything about the game, and the two halves of the
 * dispatch below stay jointly exhaustive so a new condition still breaks compilation.
 */

/**
 * A short human description of one trigger condition, for the ordering decision's display (ADR-005).
 *
 * Takes the bare condition rather than the whole [PendingTrigger] since `W8-C`, so
 * [TriggerCondition.AnyOf] can describe each pattern it names by recursing into itself. The recursion is
 * one level deep by construction — a disjunction is never nested ([TriggerCondition.AnyOf]'s `init`
 * refuses it).
 */
internal fun describeCondition(condition: TriggerCondition): String =
    when (condition) {
        TriggerCondition.EnteredBattlefieldSelf -> "enters-the-battlefield"
        TriggerCondition.EnteredBattlefieldUntappedSelf -> "enters-the-battlefield-untapped"
        TriggerCondition.PutIntoGraveyardFromBattlefieldSelf -> "put-into-graveyard-from-the-battlefield"
        TriggerCondition.LeftBattlefieldSelf -> "leaves-the-battlefield"
        TriggerCondition.ReboundCast -> "rebound-may-cast"
        TriggerCondition.EnchantedCreatureDealsDamage -> "enchanted-creature-deals-damage"
        is TriggerCondition.SpellCast -> "spell-cast"
        TriggerCondition.MadnessCast -> "madness-may-cast"
        else -> describeRemainingCondition(condition)
    }

/**
 * The tail of [describeCondition]'s dispatch, split off only so each half stays inside detekt's
 * complexity budget — the same split `PendingDecision.kt`, `DecisionView.kt` and the CLI menu family
 * already make. The two halves are one `when` and must stay jointly exhaustive: the `else` above reaches
 * here, and here there is no `else`, so a new [TriggerCondition] member still breaks compilation.
 */
private fun describeRemainingCondition(condition: TriggerCondition): String =
    when (condition) {
        is TriggerCondition.DrewNthCardThisTurn -> "drew-card-number-${condition.n}"
        TriggerCondition.DealtCombatDamageToPlayerSelf -> "deals-combat-damage-to-a-player"
        TriggerCondition.EnchantedPermanentBecomesTapped -> "enchanted-permanent-becomes-tapped"
        TriggerCondition.EnchantedPermanentIsDealtDamage -> "enchanted-permanent-is-dealt-damage"
        TriggerCondition.BecameTargetOfOpponentsSpellOrAbility -> "ward"
        // CR 603.2: a disjunctive condition is one ability, so it gets one description — the patterns it
        // watches, joined. Which of them actually fired is not recorded on the trigger and is not the
        // ordering decision's business; the description exists to tell two *abilities* apart (ADR-005).
        is TriggerCondition.AnyOf ->
            condition.conditions.joinToString(separator = "-or-", transform = ::describeCondition)
        // The eight patterns [describeCondition] answers itself. Naming them keeps this half exhaustive
        // over the sealed hierarchy — so a new member still breaks compilation here — and reaching one is
        // an engine defect rather than a description to guess at.
        TriggerCondition.EnteredBattlefieldSelf,
        TriggerCondition.EnteredBattlefieldUntappedSelf,
        TriggerCondition.PutIntoGraveyardFromBattlefieldSelf,
        TriggerCondition.LeftBattlefieldSelf,
        TriggerCondition.ReboundCast,
        TriggerCondition.EnchantedCreatureDealsDamage,
        is TriggerCondition.SpellCast,
        TriggerCondition.MadnessCast,
        -> error("$condition is described by describeCondition and cannot reach its tail")
    }
