package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
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

/**
 * Appends [trigger] to the pending-trigger queue (CR 603.3), preserving fire order — unless its
 * CR 603.4 intervening-if clause is false, in which case the ability **does not trigger at all** and
 * the state is returned unchanged.
 *
 * The check lives here rather than in each of the half-dozen detectors so that a detector added later
 * cannot forget it (InterveningIfCheck.kt). An ability declaring no such clause is unaffected, which is
 * every ability in the pool but Goblin Bushwhacker's.
 */
internal fun enqueuePendingTrigger(
    state: GameState,
    trigger: PendingTrigger,
): GameState =
    if (!interveningIfHolds(state, trigger.ability, trigger.sourceId)) {
        state
    } else {
        state.copy(pendingTriggers = state.pendingTriggers.adding(trigger))
    }

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
 * Detects "when you draw your Nth card in a turn" triggers (CR 603.2) for [player], who has just
 * drawn — scanning [player]'s graveyard for [TriggerZoneScope.Graveyard]-scoped
 * [TriggerCondition.DrewNthCardThisTurn] abilities whose ordinal equals [player]'s post-draw
 * [dev.mtgplay.core.state.PlayerState.drawsThisTurn]. The threshold is checked for exact equality, so
 * the ability fires only on the draw that crosses it, never on a later draw the same turn. Sneaky
 * Snacker fires here; its fired trigger carries the graveyard object as both source and subject
 * (CR 603.10), the object its return-to-battlefield effect acts on. A no-op when the player controls
 * no such graveyard card at this count.
 */
internal fun detectDrawCountTriggers(
    state: GameState,
    player: PlayerId,
): GameState {
    val draws = state.player(player).drawsThisTurn
    return state.player(player).graveyard.fold(state) { current, graveyardCard ->
        val abilities =
            state.definitions[graveyardCard.card]
                ?.triggeredAbilities
                ?.filter {
                    it.zoneScope == TriggerZoneScope.Graveyard &&
                        it.condition is TriggerCondition.DrewNthCardThisTurn &&
                        (it.condition as TriggerCondition.DrewNthCardThisTurn).n == draws
                }.orEmpty()
        abilities.fold(current) { inner, ability ->
            enqueuePendingTrigger(
                inner,
                PendingTrigger(
                    sourceId = graveyardCard.id,
                    sourceCard = graveyardCard.card,
                    controller = graveyardCard.owner,
                    ability = ability,
                    subject = graveyardCard.id,
                ),
            )
        }
    }
}

/**
 * Detects enters-the-battlefield-self triggers (CR 603.6a) for the object [enteredId] that just
 * entered: each of its own [TriggerCondition.EnteredBattlefieldSelf] abilities fires, carrying the
 * entered object as its subject. Cartouche of Solidarity and Abundant Growth trigger here. A no-op if
 * the object is not on the battlefield or has no definition.
 *
 * [TriggerCondition.EnteredBattlefieldUntappedSelf] fires here too, and only when the object in fact
 * arrived untapped — Gingerbread Cabin's "when this land enters untapped, create a Food token". The
 * status is read off the entered object rather than recomputed, so whatever CR 614.1c replacement
 * applied as it entered is what the trigger sees; the two are decided at the same instant and can
 * never disagree. A land that entered tapped fires **nothing**, which is a different game from firing
 * an ability that resolves doing nothing (that one would use the stack).
 */
internal fun detectEnterBattlefieldTriggers(
    state: GameState,
    enteredId: ObjectId,
): GameState {
    val obj = state.sharedZones.battlefield.firstOrNull { it.id == enteredId } ?: return state
    val conditions =
        buildList {
            add(TriggerCondition.EnteredBattlefieldSelf)
            if (!obj.tapped) add(TriggerCondition.EnteredBattlefieldUntappedSelf)
        }
    return conditions
        .flatMap { condition -> battlefieldTriggersOf(state, obj.card, condition) }
        .fold(state) { current, ability ->
            enqueuePendingTrigger(
                current,
                PendingTrigger(obj.id, obj.card, obj.owner, ability, subject = obj.id),
            )
        }
}

/**
 * Announces that [battlefieldId] has entered the battlefield and fires its CR 603.6a
 * enters-the-battlefield triggers — **the one home every entry path shares**.
 *
 * The [announcement] is whichever [GameEvent] narrates this particular entry — a resolved permanent
 * spell's [GameEvent.PermanentEntered], a played land's [GameEvent.LandPlayed] (CR 305.1: a land is
 * played, not cast, and takes its own transition), a created token's [GameEvent.TokenCreated] — and
 * is emitted before the detector runs. Detection appends to [GameState.pendingTriggers] and emits
 * nothing itself, so the announcement stays the first word about the entry in the log.
 *
 * **Why this exists rather than four careful call sites.** Every path that puts an object onto the
 * battlefield has to remember two things: narrate the entry, and fire CR 603.6a. Two of the four
 * remembered both, and two — [dev.mtgplay.rules.engine.executePlayLand] and
 * [dev.mtgplay.rules.effect.createToken] — remembered only the first. The failure is *silent*: the
 * permanent arrives, the trigger is simply lost, and no invariant, no test and no crash notices,
 * because a trigger that never fired leaves no trace to check against. The gauntlet triage records
 * the land half as **T18**.
 *
 * Correcting the two call sites would have left the hazard exactly where it was, so the derivation
 * is given one home instead — the same move `P-MANASICK` and `FW-MANA` made for
 * [sourceClassKeyOf] after the identical "a future change that misses a call site fails the same
 * way" reasoning. Announcing an entry and firing its triggers are now a single indivisible step: a
 * new entry path cannot narrate an entry without also firing CR 603.6a, because there is no
 * remaining way to narrate one.
 *
 * The residual risk — a fifth path that adds to the battlefield and announces *nothing* — is what
 * `Invariant.ENTRY_TRIGGER_DETECTION` covers from the acceptance side.
 */
internal fun announceBattlefieldEntry(
    state: GameState,
    battlefieldId: ObjectId,
    announcement: GameEvent,
): GameState = detectEnterBattlefieldTriggers(state.emit(announcement), battlefieldId)

/**
 * Announces that [leftObject] has left the battlefield and fires the triggers that departure fires —
 * **the one home every departure path shares**, the mirror of [announceBattlefieldEntry].
 *
 * Two conditions watch a departure and they are not the same condition:
 * - [TriggerCondition.LeftBattlefieldSelf] (CR 603.6c) fires for **every** departure — to a graveyard,
 *   to exile, to a hand, to a library. Journey to Nowhere and Mesmeric Fiend print it.
 * - [TriggerCondition.PutIntoGraveyardFromBattlefieldSelf] (CR 603.6b) fires **only** when the
 *   destination is a graveyard, which is what [graveyardId] being non-null means. Rancor prints it.
 *
 * Both are matched against [leftObject]'s pre-departure last-known information (CR 603.10) — its card,
 * id, controller, and the [GameObject.linkedExiled] record a linked ability (CR 607.2) wrote on it while
 * it was on the battlefield. The graveyard trigger carries [graveyardId] as its subject, the fresh
 * graveyard object (CR 400.7) it acts on; the general one carries no subject, because the object it acts
 * on is in exile and is named by the linked record instead.
 *
 * **Why this exists rather than six careful call sites.** A permanent leaves the battlefield six ways —
 * destroyed, sacrificed, dying to lethal damage, falling off as an Aura, exiled, returned to hand — and
 * before this framework only the four that end in a graveyard fired anything at all. That was correct
 * while [TriggerCondition.PutIntoGraveyardFromBattlefieldSelf] was the only departure condition, and
 * became a silent trap the moment CR 603.6c was expressible: a new departure path, or an existing one
 * nobody thought to revisit, would drop a Journey to Nowhere's return and leave a creature exiled
 * forever, with no invariant, test, or crash to notice. This is the same "give it one home rather than
 * be careful six times" move [announceBattlefieldEntry] made for CR 603.6a after the identical failure
 * (T18), and it is made here *before* the second half of the pair goes wrong rather than after.
 *
 * @param graveyardId the fresh graveyard object when the destination is a graveyard, or `null` for a
 *   departure to any other zone.
 */
internal fun announceBattlefieldDeparture(
    state: GameState,
    leftObject: GameObject,
    graveyardId: ObjectId?,
): GameState {
    val general =
        battlefieldTriggersOf(state, leftObject.card, TriggerCondition.LeftBattlefieldSelf)
            .fold(state) { current, ability ->
                enqueuePendingTrigger(
                    current,
                    PendingTrigger(
                        sourceId = leftObject.id,
                        sourceCard = leftObject.card,
                        controller = leftObject.owner,
                        ability = ability,
                        linkedExiled = leftObject.linkedExiled,
                    ),
                )
            }
    if (graveyardId == null) return general
    return battlefieldTriggersOf(general, leftObject.card, TriggerCondition.PutIntoGraveyardFromBattlefieldSelf)
        .fold(general) { current, ability ->
            enqueuePendingTrigger(
                current,
                PendingTrigger(
                    sourceId = leftObject.id,
                    sourceCard = leftObject.card,
                    controller = leftObject.owner,
                    ability = ability,
                    subject = graveyardId,
                    linkedExiled = leftObject.linkedExiled,
                ),
            )
        }
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
 * Detects cast triggers (CR 603.2, CR 601.2i) when the spell [castEntry] finishes casting: each
 * battlefield permanent carrying a [TriggerCondition.SpellCast] ability whose filters match the cast
 * fires for its controller. Guttersnipe's "whenever you cast an instant or sorcery spell" and Kessig
 * Flamebreather's "whenever you cast a noncreature spell" both fire here. The three filters (P6.2a;
 * the exclusion added in P6.3):
 * - [TriggerCondition.SpellCast.spellTypes]: the cast spell's printed card types must include one of
 *   them (empty set = any spell);
 * - [TriggerCondition.SpellCast.excludedSpellTypes]: the cast spell's printed card types must include
 *   none of them (empty set = nothing excluded) — the "noncreature spell" shape;
 * - [TriggerCondition.SpellCast.controlledByYou]: the cast's controller must be the source's
 *   controller (control is ownership in the MVP pool).
 *
 * A source's fired trigger carries the source as last-known information; the cast spell itself is not
 * carried (Guttersnipe's effect deals damage to each opponent, needing only its own controller).
 */
internal fun detectCastTriggers(
    state: GameState,
    castEntry: dev.mtgplay.core.state.StackEntry.Spell,
): GameState {
    val castTypes = castEntry.definition.characteristics.cardTypes
    return state.sharedZones.battlefield.fold(state) { current, source ->
        val abilities =
            current.definitions[source.card]
                ?.triggeredAbilities
                ?.filter { ability ->
                    val condition = ability.condition
                    ability.zoneScope == TriggerZoneScope.Battlefield &&
                        condition is TriggerCondition.SpellCast &&
                        (condition.spellTypes.isEmpty() || condition.spellTypes.any { it in castTypes }) &&
                        condition.excludedSpellTypes.none { it in castTypes } &&
                        (!condition.controlledByYou || source.owner == castEntry.controller)
                }.orEmpty()
        abilities.fold(current) { inner, ability ->
            enqueuePendingTrigger(
                inner,
                PendingTrigger(source.id, source.card, source.owner, ability),
            )
        }
    }
}
