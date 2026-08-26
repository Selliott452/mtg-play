package dev.mtgplay.core.state

import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ResolutionClauses
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
     * @property sacrificedForCost the printed identities of the permanents sacrificed to a sacrifice
     *   additional cost (CR 601.2b/h), in the order sacrificed; empty for a spell with no such cost.
     *   Additive, flagged core (`FW-ADDSAC`): the **linked information** the resolution reads
     *   (Reckoner's Bargain's "the sacrificed permanent's mana value"), on the cast record for the same
     *   reason [discardedForCost] is. The permanents are gone by the time the spell resolves, so this
     *   *is* the last-known information the CR makes the value readable from (CR 608.2h).
     * @property chosenModes the **printed** indices of the modes chosen for a modal spell as it was put
     *   onto the stack (CR 601.2b, CR 700.2), in the order chosen; empty for a card with no modes.
     *   Additive, flagged core (`FW-MODAL`, docs/design/countering-spells.md §8).
     *
     *   Part of the cast record for the same reason [castVia] is: it is fixed when the spell is put on
     *   the stack and cannot change afterwards (CR 700.2c — "the modes can't be changed later"), and
     *   everything downstream depends on it. The CR 608.2b re-check reads the *chosen mode's* target
     *   spec through it, and resolution runs the *chosen mode's* effect — so a copy of this entry with
     *   the modes dropped would fizzle-check and resolve as a different card.
     * @property kicked whether this spell's kicker cost was paid (CR 702.33a), announced at CR 601.2b
     *   and fixed here as the spell was put on the stack; `false` for a card without kicker and for one
     *   whose kicker was declined. Additive, flagged core (`FW-OPTCOST`).
     *
     *   The **linked information** CR 702.33f makes readable -- "the spell was kicked" is a fact about
     *   *this cast*, not about the card -- and it is on the cast record for exactly the reason
     *   [discardedForCost] and [chosenModes] are: it is settled while casting, it can never change
     *   afterwards, and the resolution depends on it. Prohibit reads it to decide whether it counters a
     *   mana value of 2 or of 4; a permanent spell carries it onward to the entering object
     *   ([GameObject.kickedWhenCast]), because the permanent is a different object (CR 400.7) and would
     *   otherwise have no way to know.
     * @property chosenX the value announced for the variable symbol as this spell was put on the stack
     *   (CR 107.3, CR 601.2b); `0` for a spell whose cost carries none. Additive, flagged core (`FW-X`).
     *
     *   **This is what CR 202.3b means by "while a spell is on the stack".** The printed mana cost keeps
     *   an unvalued [dev.mtgplay.core.mana.ManaSymbol.X] whose mana value is zero, which is correct for
     *   the card in every other zone; the announced value lives here, on the one object for which the
     *   rule says X is not zero. Keeping them apart is what stops a card in a graveyard from claiming a
     *   mana value it only had on the stack.
     */
    data class Spell(
        val obj: GameObject,
        val controller: PlayerId,
        val targets: PersistentList<Target>,
        val definition: SpellDefinition,
        val castVia: CastingPermission? = null,
        val discardedForCost: PersistentList<CardRef> = persistentListOf(),
        val sacrificedForCost: PersistentList<CardRef> = persistentListOf(),
        val chosenModes: PersistentList<Int> = persistentListOf(),
        val kicked: Boolean = false,
        val optionalCostPaid: Boolean = false,
        val chosenX: Int = 0,
    ) : StackEntry {
        init {
            require(chosenX >= 0) { "CR 601.2b: an announced value of X is non-negative, was $chosenX" }
        }
    }

    /**
     * A triggered ability on the stack (CR 113.3c, CR 603.3): a fired [PendingTrigger] put on the
     * stack in APNAP order (CR 603.3b) and now waiting to resolve. Added in P5.1. It carries no card
     * object — an ability on the stack is not a card (CR 113.7a) — and on resolution it performs its
     * effect and simply ceases to exist (CR 113.7a): no card moves anywhere, unlike a spell's
     * CR 608.2m graveyard move. Everything the resolution needs (the source's last-known information,
     * the controller, and the trigger's linked information) rides in [trigger].
     *
     * @property trigger the fired triggered ability this stack entry resolves.
     * @property targets the targets chosen **as the ability was put on the stack** (CR 603.3d), in the
     *   order chosen; empty for an untargeted ability *and* for a targeting one whose controller had no
     *   legal choice at placement — the latter still goes on the stack and then does nothing (CR 608.2b).
     *   Additive, flagged core (`FW-ABILTGT`, docs/design/targeted-abilities.md).
     * @property entryId this ability's identity **as an object on the stack** (CR 111.1: an ability on the
     *   stack is an object), fresh for this stack residence and dying with it, exactly as a spell's
     *   [Spell.obj] id is. Additive, flagged core (`FW-WARD`).
     *
     *   **The stack-entry identity docs/design/countering-spells.md §4 flagged as missing and did not
     *   build.** Its argument for leaving it out was that "abilities are structurally untargetable" and
     *   nothing in the pool countered one. Ward (CR 702.21a) counters *"that spell or ability"*, so an
     *   ability now has to be nameable while it sits on the stack, and neither of the facts already on
     *   the record can name it: [PendingTrigger.sourceId] names the *source permanent*, of which one
     *   object can put several abilities on the stack at once, and structural equality would confuse two
     *   identical triggers from two identical sources.
     *
     *   It is still **not** a target: [Target.SpellOnStack] names a spell and nothing constructs a target
     *   from this. It is the linked information a ward trigger carries about what to counter.
     *
     *   **Nullable, and never null in a real game.** Every production placement path allocates one; the
     *   `null` is for an entry a test fixture built by hand, and it is modelled in the type rather than
     *   as a sentinel so that "counter the object with no identity" cannot silently match an arbitrary
     *   ability on the stack.
     */
    data class Ability(
        val trigger: PendingTrigger,
        val targets: PersistentList<Target> = persistentListOf(),
        val entryId: ObjectId? = null,
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
     * @property targets the targets chosen while activating (CR 602.2b, following CR 601.2c), in the order
     *   chosen; empty for an untargeted ability. Never short: an ability with no legal target cannot be
     *   activated (CR 601.2c) and is not enumerated. Additive, flagged core (`FW-ABILTGT`,
     *   docs/design/targeted-abilities.md).
     * @property entryId this ability's identity as an object on the stack — the exact sibling of
     *   [Ability.entryId], for the exact reason, and distinct from [sourceId], which names the permanent
     *   whose ability this is and stays the same across two activations of it.
     */
    data class ActivatedAbilityOnStack(
        val sourceId: ObjectId,
        val sourceCard: CardRef,
        val controller: PlayerId,
        val ability: ActivatedAbility,
        val targets: PersistentList<Target> = persistentListOf(),
        val entryId: ObjectId? = null,
    ) : StackEntry
}

/**
 * This stack object's own identity for its stack residence (CR 400.7, CR 111.1) — a spell's card-object
 * id, an ability's [StackEntry.Ability.entryId] / [StackEntry.ActivatedAbilityOnStack.entryId].
 *
 * The projection that lets "counter it" (CR 701.5, CR 702.21a) name its victim without asking which kind
 * of object it is. Deliberately **not** [resolutionSourceId], which for an ability names the *source
 * permanent* and not the ability: countering an ability by its source's id would counter the wrong
 * object whenever a permanent has two abilities on the stack at once.
 *
 * `null` only for a fixture-built ability entry that was never given an identity; a spell always has one,
 * and so does every ability a production path put on the stack.
 */
val StackEntry.stackObjectId: ObjectId?
    get() =
        when (this) {
            is StackEntry.Spell -> obj.id
            is StackEntry.Ability -> entryId
            is StackEntry.ActivatedAbilityOnStack -> entryId
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

/**
 * The post-resolution clauses this stack object carries (CR 608.2c) — a spell's from the definition it
 * was cast from, an ability's from the ability itself. The one projection that makes the clause hook
 * uniform across the three resolution paths (`FW-CLAUSEHOOK`,
 * docs/design/resolution-clause-hook.md): the orchestration in `mtg-rules` reads clauses through this
 * and never asks which kind of object is resolving.
 */
val StackEntry.resolutionClauses: ResolutionClauses
    get() =
        when (this) {
            is StackEntry.Spell -> definition
            is StackEntry.Ability -> trigger.ability
            is StackEntry.ActivatedAbilityOnStack -> ability
        }

/**
 * The player who controls this stack object and therefore makes its resolution decisions
 * (CR 108.4, CR 603.3d, CR 602.2) — the decider of every mid-resolution pause its clauses open.
 */
val StackEntry.resolutionController: PlayerId
    get() =
        when (this) {
            is StackEntry.Spell -> controller
            is StackEntry.Ability -> trigger.controller
            is StackEntry.ActivatedAbilityOnStack -> controller
        }

/**
 * The object id this stack object names as its source, for a decision request that must point at
 * something the deciding seat can see: a spell's own card object on the stack (CR 111.1), or an
 * ability's source as last known when it went on the stack (CR 113.7c LKI). Never null — an ability is
 * not a card (CR 113.7a) but it always has a source, which is why this is not [cardObject].
 */
val StackEntry.resolutionSourceId: ObjectId
    get() =
        when (this) {
            is StackEntry.Spell -> obj.id
            is StackEntry.Ability -> trigger.sourceId
            is StackEntry.ActivatedAbilityOnStack -> sourceId
        }

/**
 * The targets this stack object chose, in the order chosen (CR 601.2c, CR 602.2b, CR 603.3d) — empty for
 * an untargeted one. The projection that lets a mid-resolution clause read its object's targets without
 * knowing which of the three kinds it is, exactly as [resolutionController] does for the decider.
 *
 * Its first client is the hand-reveal clause (`FW-HIDDENCHOICE`), which reveals *the targeted opponent's*
 * hand and so must reach the target from inside an orchestrator that takes a plain [StackEntry]: Duress
 * declares the clause on a spell and Mesmeric Fiend on a triggered ability, and the clause is written
 * once.
 */
val StackEntry.resolutionTargets: PersistentList<Target>
    get() =
        when (this) {
            is StackEntry.Spell -> targets
            is StackEntry.Ability -> targets
            is StackEntry.ActivatedAbilityOnStack -> targets
        }

/** The printed identity of this stack object's source (CR 201) — the counterpart of [resolutionSourceId]. */
val StackEntry.resolutionSourceCard: CardRef
    get() =
        when (this) {
            is StackEntry.Spell -> obj.card
            is StackEntry.Ability -> trigger.sourceCard
            is StackEntry.ActivatedAbilityOnStack -> sourceCard
        }
