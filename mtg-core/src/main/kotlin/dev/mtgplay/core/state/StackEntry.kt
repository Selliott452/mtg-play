package dev.mtgplay.core.state

import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * One object waiting on the stack (CR 405.2): the typed shape of
 * [SharedZones.stack]'s elements from P2.1 on.
 *
 * Sealed because the stack holds more than spells as the engine grows: triggered abilities join in
 * P5.1 as [Ability], and every consumer of the stack `when`s exhaustively, so their arrival breaks
 * compilation rather than falling through.
 *
 * **A spell has an underlying card object; an ability does not (CR 113).** A spell on the stack *is*
 * a card (CR 111.1); an ability on the stack is an object but not a card (CR 113.7a) — it never came
 * from a zone as a card and never goes to one. So there is no universal `obj` on this interface;
 * [cardObject] returns the spell's card object or `null` for an ability, which is what the
 * zone-residence, census, and object-id machinery consume (an ability contributes no zone residence
 * and no card to the conserved multiset).
 */
sealed interface StackEntry {
    /**
     * A spell on the stack (CR 112.1): the card object plus everything chosen while casting it
     * (CR 601.2) — its controller, its targets, and the definition it was cast from.
     *
     * The definition is captured at cast time so resolution (CR 608.2c) uses exactly what was
     * cast, with no registry lookup that could diverge; the cast record grows here in Phase 5
     * (chosen modes, linked cost information — docs/decklists.md).
     *
     * @property obj the card object on the stack; its id is fresh for this stack residence
     *   (CR 400.7) and dies with it — resolution puts a *new* object in the graveyard (CR 608.2m).
     * @property controller the player who cast the spell and controls it on the stack
     *   (CR 601.2, CR 108.4).
     * @property targets the chosen targets in the order chosen (CR 601.2c); empty for an
     *   untargeted spell.
     * @property definition the [SpellDefinition] the spell was cast from.
     * @property castVia the alternative permission the spell was cast with (CR 601.2f), or `null` for a
     *   normal cast. Part of the cast record (P5.2) because it governs how the spell leaves the stack:
     *   a spell cast via a permission with [CastingPermission.exilesOnLeaveStack] (flashback) is exiled
     *   instead of going to a graveyard (CR 702.34e), covering resolution, countering, and fizzling.
     * @property discardedForCost the printed identities of the cards discarded to an additional discard
     *   cost (CR 601.2b), in the order discarded; empty for a spell with no such cost. Additive, flagged
     *   core (P6.2a): the **linked information** the resolution reads (Grab the Prize's "if the discarded
     *   card wasn't a land card"), captured on the cast record because cost-payment results are part of
     *   the spell's record (docs/decklists.md).
     */
    data class Spell(
        val obj: GameObject,
        val controller: PlayerId,
        val targets: PersistentList<Target>,
        val definition: SpellDefinition,
        val castVia: CastingPermission? = null,
        val discardedForCost: PersistentList<CardRef> = persistentListOf(),
    ) : StackEntry

    /**
     * A triggered ability on the stack (CR 113.3c, CR 603.3): a fired [PendingTrigger] put on the
     * stack in APNAP order (CR 603.3b) and now waiting to resolve. Added in P5.1. It carries no card
     * object — an ability on the stack is not a card (CR 113.7a) — and on resolution it performs its
     * effect and simply ceases to exist (CR 113.7a): no card moves anywhere, unlike a spell's
     * CR 608.2m graveyard move. Everything the resolution needs (the source's last-known information,
     * the controller, and the trigger's linked information) rides in [trigger].
     *
     * @property trigger the fired triggered ability this stack entry resolves.
     */
    data class Ability(
        val trigger: PendingTrigger,
    ) : StackEntry

    /**
     * An activated ability on the stack (CR 602.2, CR 113.3b): an ability an active player put on the
     * stack by paying its cost, now waiting to resolve. Added in P6.2a. Like [Ability] it carries no card
     * object — an ability on the stack is not a card (CR 113.7a) — and on resolution it performs its
     * effect and ceases to exist (CR 113.7a). Everything the resolution needs rides here: the source's
     * last-known [sourceId]/[sourceCard], the [controller], and the [ability] itself.
     *
     * @property sourceId the source object's id when the ability was activated (CR 602.2, CR 113.7c LKI).
     * @property sourceCard the source's printed identity.
     * @property controller the player who activated and controls the ability (CR 602.2).
     * @property ability the activated ability itself (CR 602): its cost and its resolution effect.
     */
    data class ActivatedAbilityOnStack(
        val sourceId: ObjectId,
        val sourceCard: CardRef,
        val controller: PlayerId,
        val ability: ActivatedAbility,
    ) : StackEntry
}

/**
 * The card object a stack entry places on the stack, or `null` for an ability (CR 113.7a — an ability
 * on the stack is not a card). The zone-residence, card-census, and object-id-uniqueness machinery
 * consume this so an [StackEntry.Ability] contributes no card residence and no conserved card.
 */
val StackEntry.cardObject: GameObject?
    get() =
        when (this) {
            is StackEntry.Spell -> obj
            is StackEntry.Ability -> null
            is StackEntry.ActivatedAbilityOnStack -> null
        }
