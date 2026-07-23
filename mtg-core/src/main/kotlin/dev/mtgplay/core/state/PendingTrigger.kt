package dev.mtgplay.core.state

import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * A triggered ability that has fired (CR 603.3) but is not yet resolving — the load-bearing record of
 * a trigger that has been detected. Additive, flagged core (P5.1).
 *
 * A fired trigger lives here twice over one lifecycle, without duplication: first it accumulates in
 * [GameState.pendingTriggers] between the game event that fired it and the next time a player would
 * receive priority (CR 603.3b), then — put on the stack in APNAP order — it rides inside
 * [StackEntry.Ability] until it resolves. Keeping it in the state honours the ADR-004 "no hidden
 * position" rule: a paused game carries every fired-but-unresolved trigger explicitly, so the pending
 * decision (which player must order simultaneous triggers) is a pure function of the state.
 *
 * **Last-known information (CR 603.10).** The record captures what the ability needs at the moment it
 * fired, so it survives its source leaving the battlefield: the source's [sourceId]/[sourceCard] and
 * its [controller] (CR 603.3d — control is ownership in the MVP pool), plus the trigger's linked
 * information — [amount] ("that much", CR 118.9) and [subject] (the specific object the effect acts
 * on). A leaves-the-battlefield trigger (Rancor) carries, as its [subject], the fresh graveyard object
 * (CR 400.7) it will return; a damage trigger (Armadillo Cloak) carries the damage dealt as [amount].
 *
 * @property sourceId the source object's last-known id (CR 603.10); for a leaves-the-battlefield
 *   trigger, the battlefield id the object had before it left.
 * @property sourceCard the source's printed identity, conserved across the source's whole life.
 * @property controller the player who controls the ability and orders it among simultaneous triggers
 *   (CR 603.3b, CR 603.3d) — the source's controller when the trigger fired (ownership in the MVP).
 * @property ability the triggered ability itself (CR 603): its condition and its resolution effect.
 * @property amount the trigger's numeric linked information (CR 118.9), e.g. the damage the enchanted
 *   creature dealt for Armadillo Cloak's "gain that much life"; `0` when the trigger carries none.
 * @property subject the specific object the effect acts on beyond its controller (CR 603.10), e.g. the
 *   graveyard object Rancor returns to hand or the object that entered the battlefield; `null` when
 *   the trigger acts on no specific object.
 */
data class PendingTrigger(
    val sourceId: ObjectId,
    val sourceCard: CardRef,
    val controller: PlayerId,
    val ability: TriggeredAbility,
    val amount: Int = 0,
    val subject: ObjectId? = null,
)
