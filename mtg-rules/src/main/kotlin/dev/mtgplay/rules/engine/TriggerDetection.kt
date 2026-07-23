package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger

/*
 * Trigger detection (CR 603.3): the honest watch mechanism for the immutable-transition engine.
 *
 * There is no event-log scan — events are derived observability and rules logic must not read them
 * (ADR-006). Instead, each atomic engine step that *is* a trigger event calls the matching detector
 * here at the moment it happens, when it still has the information a fired trigger needs (the damage
 * source, the object that left the battlefield, its controller). A detector matches the event against
 * the active trigger sources' declared conditions (CR 603.2) and appends any fired trigger to
 * [GameState.pendingTriggers] as last-known information (CR 603.10). The pending triggers then wait in
 * the state until a player would receive priority, when they are put on the stack in APNAP order
 * (TriggerPlacement.kt, CR 603.3b). Accumulating in the state, not in a side channel, keeps the paused
 * game a complete record (ADR-004 no-hidden-position).
 *
 * Every MVP triggered ability functions from the battlefield ([TriggerZoneScope.Battlefield]); the
 * scope is checked so a graveyard/hand/exile-scoped ability (P6) is not fired from the wrong zone.
 */

/** Appends [trigger] to the pending-trigger queue (CR 603.3), preserving fire order. */
internal fun enqueuePendingTrigger(
    state: GameState,
    trigger: PendingTrigger,
): GameState = state.copy(pendingTriggers = state.pendingTriggers.adding(trigger))

/** The battlefield-scoped triggered abilities of [card]'s definition matching [condition]. */
private fun battlefieldTriggersOf(
    state: GameState,
    card: dev.mtgplay.core.identity.CardRef,
    condition: TriggerCondition,
): List<TriggeredAbility> =
    state.definitions[card]
        ?.triggeredAbilities
        ?.filter { it.zoneScope == TriggerZoneScope.Battlefield && it.condition == condition }
        .orEmpty()

/**
 * Detects enters-the-battlefield-self triggers (CR 603.6a) for the object [enteredId] that just
 * entered: each of its own [TriggerCondition.EnteredBattlefieldSelf] abilities fires, carrying the
 * entered object as its subject. Cartouche of Solidarity and Abundant Growth trigger here. A no-op if
 * the object is not on the battlefield or has no definition.
 */
internal fun detectEnterBattlefieldTriggers(
    state: GameState,
    enteredId: ObjectId,
): GameState {
    val obj = state.sharedZones.battlefield.firstOrNull { it.id == enteredId } ?: return state
    return battlefieldTriggersOf(state, obj.card, TriggerCondition.EnteredBattlefieldSelf)
        .fold(state) { current, ability ->
            enqueuePendingTrigger(
                current,
                PendingTrigger(obj.id, obj.card, obj.owner, ability, subject = obj.id),
            )
        }
}

/**
 * Detects put-into-graveyard-from-battlefield-self triggers (CR 603.6b, CR 603.10) for [leftObject],
 * the battlefield object that just moved to the graveyard as the new object [graveyardId]. Matched
 * against [leftObject]'s pre-departure last-known information (its card, id, and controller); each
 * fired trigger carries [graveyardId] as its subject — the fresh graveyard object (CR 400.7) the
 * ability acts on. Rancor's "return this to its owner's hand" fires here. A no-op if [leftObject] has
 * no definition or no such trigger (every creature and non-Rancor Aura in the MVP pool).
 */
internal fun detectPutIntoGraveyardTriggers(
    state: GameState,
    leftObject: GameObject,
    graveyardId: ObjectId,
): GameState =
    battlefieldTriggersOf(state, leftObject.card, TriggerCondition.PutIntoGraveyardFromBattlefieldSelf)
        .fold(state) { current, ability ->
            enqueuePendingTrigger(
                current,
                PendingTrigger(leftObject.id, leftObject.card, leftObject.owner, ability, subject = graveyardId),
            )
        }

/**
 * Detects enchanted-creature-deals-damage triggers (CR 603.2) when the creature [damagerId] dealt
 * [amount] damage: each Aura attached to it carrying a [TriggerCondition.EnchantedCreatureDealsDamage]
 * ability fires for its controller (the Aura's owner in the MVP pool), carrying the damage as the
 * trigger's amount ("that much", CR 118.9). Armadillo Cloak's lifegain fires here. Zero damage is not
 * dealt (CR 120.8), so it fires nothing. The [amount] is the total the creature dealt in the event,
 * so one damage event fires the trigger once (a blocked attacker splitting damage among two blockers
 * still deals damage once, CR 510.2).
 */
internal fun fireEnchantedDamageTriggers(
    state: GameState,
    damagerId: ObjectId,
    amount: Int,
): GameState {
    if (amount <= 0) return state
    return state.sharedZones.battlefield
        .filter { it.attachedTo == damagerId }
        .fold(state) { current, aura ->
            battlefieldTriggersOf(current, aura.card, TriggerCondition.EnchantedCreatureDealsDamage)
                .fold(current) { inner, ability ->
                    enqueuePendingTrigger(
                        inner,
                        PendingTrigger(aura.id, aura.card, aura.owner, ability, amount = amount, subject = damagerId),
                    )
                }
        }
}

/**
 * Detects cast triggers (CR 603.2, CR 601.2i) when a spell finishes casting: each battlefield
 * permanent carrying a [TriggerCondition.SpellCast] ability fires for its controller. The cast-trigger
 * seam (P5.1): no MVP mainboard card carries this condition, so this fires nothing in real games; it
 * is the hook Guttersnipe's "whenever you cast an instant or sorcery" refines in P6. The bare form
 * here fires on every cast, watching every battlefield source.
 */
internal fun detectCastTriggers(state: GameState): GameState =
    state.sharedZones.battlefield.fold(state) { current, source ->
        battlefieldTriggersOf(current, source.card, TriggerCondition.SpellCast)
            .fold(current) { inner, ability ->
                enqueuePendingTrigger(
                    inner,
                    PendingTrigger(source.id, source.card, source.owner, ability),
                )
            }
    }
