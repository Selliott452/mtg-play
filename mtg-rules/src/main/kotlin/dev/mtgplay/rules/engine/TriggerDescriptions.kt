package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TriggerCondition

/*
 * A short human description of a trigger condition, for the CR 603.3b ordering decision's display
 * (ADR-005). Split out of TriggerPlacement.kt by `W9-C`, when storm's condition took the single `when`
 * past detekt's complexity budget and the file past its function budget.
 *
 * The description exists to tell two *abilities* apart when a seat must order simultaneous triggers — not
 * to explain what fired — so it is a stable label per condition and nothing reads it back.
 */

/**
 * A short human description of one trigger condition (CR 603.2).
 *
 * Takes the bare condition rather than the whole [dev.mtgplay.core.state.PendingTrigger] since `W8-C`, so
 * [TriggerCondition.AnyOf] can describe each pattern it names by recursing into itself. The recursion is
 * one level deep by construction — a disjunction is never nested ([TriggerCondition.AnyOf]'s `init`
 * refuses it).
 */
internal fun describeCondition(condition: TriggerCondition): String =
    when (condition) {
        // CR 603.2: a disjunctive condition is one ability, so it gets one description — the patterns it
        // watches, joined. Which of them actually fired is not recorded on the trigger and is not the
        // ordering decision's business; the description exists to tell two *abilities* apart (ADR-005).
        is TriggerCondition.AnyOf ->
            condition.conditions.joinToString(separator = "-or-", transform = ::describeCondition)
        else -> describeSimpleCondition(condition)
    }

/**
 * The non-disjunctive conditions (CR 603.2). **Exhaustive on purpose**: a new [TriggerCondition] member
 * fails to compile here rather than quietly acquiring a generic label, which is the property the split had
 * to preserve. The three attachment conditions are delegated as a *group arm* rather than through an
 * `else`, so the exhaustiveness is real and the `when` stays inside detekt's complexity budget.
 */
private fun describeSimpleCondition(condition: TriggerCondition): String =
    when (condition) {
        TriggerCondition.EnteredBattlefieldSelf -> "enters-the-battlefield"
        TriggerCondition.EnteredBattlefieldUntappedSelf -> "enters-the-battlefield-untapped"
        TriggerCondition.PutIntoGraveyardFromBattlefieldSelf -> "put-into-graveyard-from-the-battlefield"
        TriggerCondition.LeftBattlefieldSelf -> "leaves-the-battlefield"
        TriggerCondition.DealtCombatDamageToPlayerSelf -> "deals-combat-damage-to-a-player"
        TriggerCondition.ReboundCast -> "rebound-may-cast"
        TriggerCondition.MadnessCast -> "madness-may-cast"
        // CR 702.40a (`W9-C`): a cast trigger of the spell itself, functioning on the stack.
        TriggerCondition.StormCast -> "storm"
        is TriggerCondition.SpellCast -> "spell-cast"
        is TriggerCondition.DrewNthCardThisTurn -> "drew-card-number-${condition.n}"
        TriggerCondition.EnchantedCreatureDealsDamage,
        TriggerCondition.EnchantedPermanentBecomesTapped,
        TriggerCondition.EnchantedPermanentIsDealtDamage,
        is TriggerCondition.AnyOf,
        -> describeAttachmentCondition(condition)
    }

/**
 * The three conditions an Aura watches on the permanent it is attached to (CR 603.2, CR 303.4), plus the
 * disjunction [describeCondition] has already handled. Reached only from [describeSimpleCondition]'s
 * grouped arm, so its `else` is unreachable rather than permissive — exhaustiveness is enforced one level
 * up, where a new member has to be classified before it can reach here at all.
 */
private fun describeAttachmentCondition(condition: TriggerCondition): String =
    when (condition) {
        TriggerCondition.EnchantedCreatureDealsDamage -> "enchanted-creature-deals-damage"
        TriggerCondition.EnchantedPermanentBecomesTapped -> "enchanted-permanent-becomes-tapped"
        TriggerCondition.EnchantedPermanentIsDealtDamage -> "enchanted-permanent-is-dealt-damage"
        is TriggerCondition.AnyOf -> describeCondition(condition)
        else -> error("CR 603.2: $condition is not an attachment condition; describeSimpleCondition owns it")
    }
