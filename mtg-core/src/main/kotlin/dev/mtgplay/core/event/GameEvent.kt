package dev.mtgplay.core.event

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.AttackerAssignment
import dev.mtgplay.core.state.BlockAssignment
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep

/**
 * The root of the typed game-event hierarchy.
 *
 * Events are the engine's observability channel (ADR-006): every transition appends the events
 * describing what happened to [dev.mtgplay.core.state.GameState.events], for replay display,
 * debugging, and drivers. They are **derived, never load-bearing**: rules logic must not read
 * them, and replay reconstructs state from `(MatchConfig, List<Decision>)`, never from events.
 *
 * Later packets grow this hierarchy *in this file* — events are nouns, so they live in core
 * even though the engine in `mtg-rules` emits them. Growth is strictly additive. The members
 * below are the P1.2 set (game lifecycle, turn structure, priority, zone moves), the P2.1
 * set (the CR 601 casting stages, mana, life, and CR 608 resolution), and the P2.2 set
 * ([LandPlayed], [ObjectTapped], [ObjectUntapped], [DamageDealt]) — enough for a readable
 * game log, nothing speculative.
 */
sealed interface GameEvent {
    /**
     * The game began: libraries shuffled and opening hands drawn (CR 103.1), with
     * [startingPlayer] set to take the first turn.
     */
    data class GameStarted(
        val startingPlayer: PlayerId,
    ) : GameEvent

    /**
     * [player] took a mulligan (CR 103.4): they shuffled their hand into their library and drew a
     * fresh hand, which was [mulliganNumber] mulligans so far this game. The redraw is narrated by
     * the [CardDrawn] events that follow. Added in P6.1.
     */
    data class MulliganTaken(
        val player: PlayerId,
        val mulliganNumber: Int,
    ) : GameEvent

    /**
     * [player] kept their hand after [mulligansTaken] mulligans (CR 103.5): they will put
     * [mulligansTaken] cards (capped at their hand size) on the bottom of their library, each
     * narrated by a following [CardBottomed]. Added in P6.1.
     */
    data class HandKept(
        val player: PlayerId,
        val mulligansTaken: Int,
    ) : GameEvent

    /**
     * [player] put [card] on the bottom of their library after a mulligan (CR 103.5): the hand
     * object moved to the bottom of the library, keeping its id (a within-pre-game reshuffle, like
     * the opening shuffle, not a CR 400.7 zone-change rebirth). Added in P6.1. Emitted in the
     * player's chosen bottoming order.
     */
    data class CardBottomed(
        val player: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /** [activePlayer]'s turn, number [turnNumber], began (CR 500.1). */
    data class TurnBegan(
        val activePlayer: PlayerId,
        val turnNumber: Int,
    ) : GameEvent

    /** The [phase] began (CR 500.1); emitted before the phase's first step begins. */
    data class PhaseBegan(
        val phase: TurnPhase,
    ) : GameEvent

    /**
     * The [step] began (CR 500.1). A step that repeats emits again each time — the repeated
     * cleanup step of CR 514.3a is the P1.2 case.
     */
    data class StepBegan(
        val step: TurnStep,
    ) : GameEvent

    /** [player] passed priority (CR 117.4). */
    data class PriorityPassed(
        val player: PlayerId,
    ) : GameEvent

    /**
     * [player] drew a card: the top card of their library was put into their hand, becoming the
     * new object [objectId] there (CR 400.7). [card] is the printed identity it carries.
     */
    data class CardDrawn(
        val player: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /**
     * [player] discarded [card]: it moved from their hand to their graveyard, becoming the new
     * object [objectId] there (CR 400.7). In P1.2 the only discard is the cleanup-step discard
     * down to maximum hand size (CR 402.2, CR 514.1).
     */
    data class CardDiscarded(
        val player: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /**
     * [caster] proposed casting [card] (CR 601.2a): the hand card moved to the stack, becoming
     * the new stack object [objectId] there (CR 400.7). The first stage of the CR 601 pipeline;
     * the cast is complete only at [SpellCast].
     */
    data class SpellProposed(
        val caster: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /**
     * [caster] chose the [targets] of the spell [objectId] on the stack (CR 601.2c); empty
     * target lists are not announced, so this only appears for a spell that targets.
     */
    data class TargetsChosen(
        val caster: PlayerId,
        val objectId: ObjectId,
        val targets: List<Target>,
    ) : GameEvent

    /**
     * [player] activated a mana ability of the battlefield source [sourceId] (CR 605.3): the
     * source was tapped and the ability resolved immediately — no stack, no priority round
     * (CR 605.3a–b). The mana it added follows as [ManaAdded].
     */
    data class ManaAbilityActivated(
        val player: PlayerId,
        val sourceId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /** One [mana] was added to [player]'s mana pool (CR 106.4). */
    data class ManaAdded(
        val player: PlayerId,
        val mana: ManaType,
    ) : GameEvent

    /**
     * [player]'s mana pool emptied because a step or phase ended (CR 500.4). Emitted only when
     * the pool actually held mana — unspent mana is the exception, not the rule.
     */
    data class ManaPoolEmptied(
        val player: PlayerId,
    ) : GameEvent

    /**
     * [player]'s life total changed by [change] (negative for a loss) to [newTotal]
     * (CR 119.3–4): a cost paid with life (CR 107.4-Phyrexian), a lose-life effect, or later
     * phases' damage results (CR 120.3).
     */
    data class LifeChanged(
        val player: PlayerId,
        val change: Int,
        val newTotal: Int,
    ) : GameEvent

    /**
     * [caster] finished casting the spell [objectId] (CR 601.2i): every stage of the CR 601
     * pipeline completed, costs fully paid, and the spell now waits on the stack. This is the
     * moment "when a player casts a spell" triggers care about (Phase 5's cast-trigger hook).
     */
    data class SpellCast(
        val caster: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /**
     * The spell [objectId], controlled by [controller], resolved (CR 608.2): its instructions
     * were performed, and the card was put into its owner's graveyard as the new object
     * [graveyardObjectId] (CR 608.2m, CR 400.7).
     */
    data class SpellResolved(
        val controller: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
        val graveyardObjectId: ObjectId,
    ) : GameEvent

    /**
     * The spell [objectId], controlled by [controller], did not resolve because all of its
     * targets were illegal when it tried to (CR 608.2b): none of its instructions were
     * performed, and the card was put into its owner's graveyard as the new object
     * [graveyardObjectId] (CR 608.2m, CR 400.7).
     */
    data class SpellFizzled(
        val controller: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
        val graveyardObjectId: ObjectId,
    ) : GameEvent

    /**
     * The spell [objectId], controlled by [controller], was **countered** (CR 701.5a) by
     * [counteredBy]: it was removed from the stack so that it does not resolve, none of its
     * instructions were performed, and the card was put into its owner's graveyard as the new object
     * [graveyardObjectId] (CR 400.7) — or into exile, narrated by [SpellExiledInsteadOfGraveyard], if
     * it was cast via flashback (CR 702.34e). Additive, flagged (`FW-COUNTER`,
     * docs/design/countering-spells.md §3). Its costs stay paid (CR 701.5a): nothing is refunded, and
     * the "whenever a player casts a spell" triggers that fired at CR 601.2i are unaffected.
     *
     * **Deliberately not [SpellFizzled], and deliberately not a flag on it.** The two produce the same
     * state transition for entirely different reasons, and modern CR 608.2b drops the older "is
     * countered" wording on purpose: a card that watches for "whenever a spell is countered" does not
     * see a fizzle, and a spell that "can't be countered" still fizzles for want of a legal target.
     * Nothing in this pool observes the difference — which is precisely the condition under which a
     * wrong merge survives review and ships as silently-wrong-but-plausible behaviour.
     *
     * @property controller the countered spell's controller (CR 108.4), not the countering player.
     * @property counteredBy the object id of the spell whose resolution countered it — the event log is
     *   the narration surface, and "Counterspell countered Lightning Bolt" needs both halves.
     */
    data class SpellCountered(
        val controller: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
        val graveyardObjectId: ObjectId,
        val counteredBy: ObjectId,
    ) : GameEvent

    /**
     * The permanent spell [objectId], controlled by [controller], resolved and entered the
     * battlefield (CR 608.3, CR 400.7): the resolving spell becomes a permanent under its
     * controller's control as the new battlefield object [battlefieldObjectId], summoning sick
     * (CR 302.6), untapped, with no marked damage. Added in P3.2 — the first permanent spells are
     * creatures; a permanent spell has no CR 608.2c resolution effect of its own in the MVP pool
     * (enters-the-battlefield triggers are Phase 5).
     */
    data class PermanentEntered(
        val controller: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
        val battlefieldObjectId: ObjectId,
    ) : GameEvent

    /**
     * [player] played the land [card] (CR 115.2a, CR 305.1) — the CR 116.2a special action, not
     * a cast: no stack, and the player retains priority afterward (CR 116.4). The hand card
     * moved to the battlefield, becoming the new object [objectId] there (CR 400.7), untapped
     * (CR 110.5a).
     */
    data class LandPlayed(
        val player: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /**
     * The battlefield object [objectId] became tapped (CR 701.21a). In P2.2 the only tapping is
     * a mana ability's `{T}` cost (CR 605.1a), emitted between [ManaAbilityActivated] and
     * [ManaAdded]; combat and other activated abilities add emission sites in later phases.
     */
    data class ObjectTapped(
        val objectId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /**
     * The battlefield object [objectId] became untapped (CR 701.21a). In P2.2 the only
     * untapping is the untap step's turn-based action (CR 502.2); the simultaneous untap emits
     * one event per object, in battlefield order, for a deterministic log.
     */
    data class ObjectUntapped(
        val objectId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /**
     * [amount] damage was dealt to [recipient] (CR 120). For a [Target.Player] recipient the
     * damage's result is losing that much life (CR 120.3a), narrated by the [LifeChanged] that
     * follows — the two events together are what distinguish damage from pure life loss
     * (CR 119.3c), a distinction later phases' cards care about. For a [Target.Permanent]
     * recipient (P3.1) the result is marked damage (CR 120.3d): no [LifeChanged] follows,
     * because a permanent has no life total — the damage sits on the object until cleanup
     * (CR 514.2) or a lethal-damage state-based action (CR 704.5g, P3.2) acts on it.
     */
    data class DamageDealt(
        val recipient: Target,
        val amount: Int,
    ) : GameEvent

    /**
     * The active player declared [attackers] as the declare-attackers step's turn-based action
     * (CR 508.1). Additive, flagged (P3.1); possibly empty (CR 508.8 then skips the later combat
     * steps). Each combat-damage point an attacker deals is narrated by its own [DamageDealt];
     * this event records only the structural declaration.
     */
    data class AttackersDeclared(
        val attackers: List<AttackerAssignment>,
    ) : GameEvent

    /**
     * A defending player declared [blocks] as the declare-blockers step's turn-based action
     * (CR 509.1). Additive, flagged (P3.1); possibly empty (blockers were declared and none
     * chosen — distinct from the step being skipped).
     */
    data class BlockersDeclared(
        val blocks: List<BlockAssignment>,
    ) : GameEvent

    /**
     * The attacking player chose the damage-assignment [order] of [attacker]'s blockers
     * (CR 509.2). Additive, flagged (P3.1); emitted once per attacker blocked by two or more
     * creatures, and [order] is a permutation of that attacker's blockers.
     */
    data class BlockerOrderChosen(
        val attacker: ObjectId,
        val order: List<ObjectId>,
    ) : GameEvent

    /**
     * The creature [objectId] died (CR 700.4): a state-based action put it from the battlefield
     * into its owner's graveyard as the new object [graveyardObjectId] (CR 400.7) — either because
     * it had lethal marked damage and was destroyed (CR 704.5g) or because its toughness was 0 or
     * less (CR 704.5f). Added in P3.2. The two causes are one event here (both are "dies"); the
     * distinction that matters for regeneration (CR 704.5g is destruction, CR 704.5f is not) is
     * carried by the state-based action in `mtg-rules`, not the observability log.
     */
    data class CreatureDied(
        val objectId: ObjectId,
        val card: CardRef,
        val graveyardObjectId: ObjectId,
    ) : GameEvent

    /**
     * The Aura [aura] entered the battlefield attached to [attachedTo] (CR 303.4f): a permanent
     * spell resolved and became attached to the object it targeted while on the stack (CR 601.2c).
     * Added in P4.1. Follows the [PermanentEntered] of the same resolution.
     */
    data class AuraAttached(
        val aura: ObjectId,
        val attachedTo: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /**
     * The Aura [objectId] fell off (CR 704.5m): a state-based action put it from the battlefield
     * into its owner's graveyard as the new object [graveyardObjectId] (CR 400.7), because it was
     * attached to an illegal object or to nothing — most often its enchanted creature had just
     * died (CR 700.4). Added in P4.1.
     */
    data class AuraFellOff(
        val objectId: ObjectId,
        val card: CardRef,
        val graveyardObjectId: ObjectId,
    ) : GameEvent

    /**
     * A triggered ability was put on the stack (CR 603.3): [sourceCard], controlled by [controller],
     * fired and its ability now waits on the stack as a [dev.mtgplay.core.state.StackEntry.Ability].
     * Added in P5.1. When several triggers are placed at once they emit in the APNAP order they were
     * put on the stack (CR 603.3b) — the active player's first.
     */
    data class TriggeredAbilityPutOnStack(
        val controller: PlayerId,
        val sourceCard: CardRef,
    ) : GameEvent

    /**
     * A triggered ability resolved (CR 608.2, CR 113.7a): [sourceCard]'s ability, controlled by
     * [controller], performed its effect and ceased to exist — no card moved, unlike a spell's
     * CR 608.2m graveyard move. Added in P5.1. Any zone changes the effect made are narrated by their
     * own events (a token created, a card drawn, a permanent returned to hand).
     */
    data class TriggeredAbilityResolved(
        val controller: PlayerId,
        val sourceCard: CardRef,
    ) : GameEvent

    /**
     * A token was created on the battlefield (CR 111.4, CR 707): [controller]'s effect created the
     * token [name] as the new battlefield object [objectId] (CR 400.7), summoning sick (CR 302.6) and
     * untapped. Added in P5.1 — Cartouche of Solidarity's enters-the-battlefield trigger creates a
     * 1/1 white Warrior token with vigilance.
     */
    data class TokenCreated(
        val controller: PlayerId,
        val objectId: ObjectId,
        val name: CardRef,
    ) : GameEvent

    /**
     * A token ceased to exist (CR 704.5d): the token [objectId] ([name]) was in a zone other than the
     * battlefield and was removed by the state-based action. Added in P5.1 — a token creature put
     * into a graveyard by a death (CR 704.5f/g) is there for only the moment between two state-based-
     * action checks before this fires.
     */
    data class TokenCeasedToExist(
        val objectId: ObjectId,
        val name: CardRef,
    ) : GameEvent

    /**
     * A card was returned from a graveyard to its owner's hand (CR 400.7): [card], owned by [player],
     * moved from their graveyard to their hand as the new hand object [objectId]. Added in P5.1 —
     * Rancor's leaves-the-battlefield trigger returns it to hand after it arrives in the graveyard.
     */
    data class CardReturnedToHand(
        val player: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /**
     * A card with madness was exiled instead of being discarded (CR 702.35a): the discard→exile
     * replacement moved [card] from [player]'s hand to exile as the new object [objectId] (CR 400.7),
     * where it waits ([dev.mtgplay.core.state.GameObject.awaitingMadness]) on its reflexive cast
     * trigger. Added in P5.2.
     */
    data class CardExiledByMadness(
        val player: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /**
     * A madness card was put into its owner's graveyard because it was not cast (CR 702.35b): the
     * reflexive trigger resolved and [player] declined the cast, or it was impossible, so [card] moved
     * from exile to their graveyard as the new object [graveyardObjectId] (CR 400.7). Added in P5.2.
     */
    data class MadnessCardPutIntoGraveyard(
        val player: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
        val graveyardObjectId: ObjectId,
    ) : GameEvent

    /**
     * A spell was exiled instead of being put into a graveyard as it left the stack (CR 702.34e): the
     * flashback leave-stack replacement moved [card], controlled by [controller], to exile as the new
     * object [exileObjectId] (CR 400.7) rather than to its owner's graveyard — on resolution, a
     * counter, or a fizzle. Added in P5.2. Follows the [SpellResolved]/[SpellFizzled] of the same
     * departure, whose object id is this same exile object.
     */
    data class SpellExiledInsteadOfGraveyard(
        val controller: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
        val exileObjectId: ObjectId,
    ) : GameEvent

    /**
     * Cards were exiled to pay an additional cost of casting a spell (CR 601.2h, CR 702.139a): escape's
     * "exile N other cards from your graveyard". [player] exiled the [exiled] objects (their new exile
     * ids, CR 400.7) while casting. Added in P5.2. The mana part of the cost is narrated by the usual
     * mana events; this records the non-mana exile.
     */
    data class CardsExiledForCost(
        val player: PlayerId,
        val exiled: List<ObjectId>,
    ) : GameEvent

    /**
     * An activated ability was put on the stack (CR 602.2): [controller] paid the cost of [sourceCard]'s
     * activated ability, which now waits on the stack as a
     * [dev.mtgplay.core.state.StackEntry.ActivatedAbilityOnStack]. Added in P6.2a.
     */
    data class AbilityActivated(
        val controller: PlayerId,
        val sourceId: ObjectId,
        val sourceCard: CardRef,
    ) : GameEvent

    /**
     * An activated ability resolved (CR 608.2, CR 113.7a): [sourceCard]'s activated ability, controlled
     * by [controller], performed its effect and ceased to exist — no card moved. Added in P6.2a. Any zone
     * changes the effect made are narrated by their own events.
     */
    data class AbilityResolved(
        val controller: PlayerId,
        val sourceCard: CardRef,
    ) : GameEvent

    /**
     * An ability did not resolve because every one of its targets was illegal (CR 608.2b):
     * [sourceCard]'s ability, controlled by [controller], was removed from the stack and **none of its
     * instructions were performed**. Added with `FW-ABILTGT` (docs/design/targeted-abilities.md).
     *
     * The ability counterpart of [SpellFizzled], and deliberately a distinct event: a fizzled spell's
     * card is put into a graveyard or exile as a new object (CR 608.2m, CR 702.34e), while an ability
     * simply ceases to exist (CR 113.7a) — there is no card and no final object id to report. Emitted
     * for a triggered ability (CR 603.3d) and an activated one (CR 602.2b) alike; [triggered] says
     * which, since the two have different narration in a log ("its trigger fizzled" vs "its ability
     * fizzled").
     */
    data class AbilityFizzled(
        val controller: PlayerId,
        val sourceCard: CardRef,
        val triggered: Boolean,
    ) : GameEvent

    /**
     * Cards were revealed from the top of a library (CR 701.16): [player] revealed [cards] (their
     * printed identities, top-first) as part of a resolving effect — Malevolent Rumble's "reveal the top
     * four cards". Added in P6.2a. Public information: the revealed identities are recorded here (they are
     * shown to all players), unlike a hidden library. The subsequent moves to hand/graveyard are narrated
     * by their own events.
     */
    data class CardsRevealed(
        val player: PlayerId,
        val cards: List<CardRef>,
    ) : GameEvent

    /**
     * [player] **looked at** [count] cards privately as part of a resolving effect (CR 701.14a) — a scry,
     * Ponder's top three, Impulse's top four. Added with `FW-LIBLOOK` (docs/design/library-look.md). The
     * deliberate counterpart of [CardsRevealed]: a look is seen by its controller and *no other player*, so
     * only the count is recorded here, never the identities. That a look happened and over how many cards
     * is publicly observable at the table; what was seen is not.
     */
    data class CardsLookedAt(
        val player: PlayerId,
        val count: Int,
    ) : GameEvent

    /**
     * [player] put [card] onto their library from another zone as the new object [objectId] (CR 400.7) —
     * Brainstorm's "put two cards from your hand on top of your library". Added with `FW-LIBLOOK`. Emitted
     * once per card, in the order placed. [onTop] distinguishes the top of the library from the bottom.
     *
     * Only a **zone change** is narrated: a card the same effect merely reorders *within* the library (a
     * scry's top and bottom groups) has not moved zones, keeps its object id, and is deliberately silent,
     * since its new position is private to the player who chose it (CR 701.14a).
     */
    data class CardPutOnLibrary(
        val player: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
        val onTop: Boolean,
    ) : GameEvent

    /**
     * A card was plotted (CR 702.140): [player] paid its plot cost and exiled [card] from their hand as
     * the new exile object [objectId] (CR 400.7), marked plotted this turn
     * ([dev.mtgplay.core.state.GameObject.plottedTurn]). Added in P6.2a — the card may be cast for free
     * on a later turn. The plot cost's mana payment is narrated by the usual mana events.
     */
    data class CardPlotted(
        val player: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /**
     * A permanent was sacrificed to pay a cost (CR 601.2h, CR 701.17): [player] sacrificed the
     * battlefield object [objectId] ([card]) while casting a spell — Fireblast's two Mountains, Lava
     * Dart's Mountain — and it went to its owner's graveyard as the new object [graveyardObjectId]
     * (CR 400.7). Added in P6.2a. One event per sacrificed permanent.
     */
    data class PermanentSacrificed(
        val player: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
        val graveyardObjectId: ObjectId,
    ) : GameEvent

    /**
     * A permanent was **destroyed** (CR 701.7a): a destruction effect moved the battlefield object
     * [objectId] ([card]) to its owner's graveyard as the new object [graveyardObjectId] (CR 400.7)
     * — Terminate, Cast Down, Ancient Grudge. Added by the removal-and-destruction packet.
     *
     * Deliberately *not* the same event as [CreatureDied]: that one narrates the CR 704.5f/g
     * state-based actions, which the game performs on its own, one of which (zero toughness) is not
     * destruction at all. A destruction effect that finds an indestructible permanent (CR 702.12b)
     * destroys nothing and emits nothing — the absence of this event is how the log records that.
     */
    data class PermanentDestroyed(
        val objectId: ObjectId,
        val card: CardRef,
        val graveyardObjectId: ObjectId,
    ) : GameEvent

    /**
     * A permanent was **exiled** from the battlefield (CR 701.3a): an exile effect moved the
     * battlefield object [objectId] ([card]) to the exile zone as the new object [exileObjectId]
     * (CR 400.7) — Scour from Existence, Last Breath. Added by the removal-and-destruction packet.
     *
     * Distinct from every other exile event in this log because it names a *battlefield* departure:
     * [CardExiledByMadness] replaces a discard, [SpellExiledInsteadOfGraveyard] replaces a
     * leave-the-stack move, and [CardsExiledForCost] pays a cost from a graveyard. Exiling is not
     * destroying: indestructible (CR 702.12b) does not stop it, and nothing that watches for a
     * permanent being put into a graveyard (CR 603.6b) sees it.
     */
    data class PermanentExiled(
        val objectId: ObjectId,
        val card: CardRef,
        val exileObjectId: ObjectId,
    ) : GameEvent

    /**
     * [player] milled [card] (CR 701.13a): the top card of their library moved to their graveyard,
     * becoming the new object [objectId] there (CR 400.7). One event per milled card, in the order
     * milled (top-first). Distinct from [CardDiscarded] on purpose — a mill is not a discard, so
     * nothing that watches discards (madness's CR 702.35a replacement, a discard trigger) may see a
     * mill. Milling from an empty library mills nothing and emits nothing (CR 701.13b) — unlike a
     * draw, it is not a loss condition.
     */
    data class CardMilled(
        val player: PlayerId,
        val objectId: ObjectId,
        val card: CardRef,
    ) : GameEvent

    /**
     * [amount] counters of kind [counter] were **put on** the permanent [objectId] ([card])
     * (CR 122.1) — Unexpected Fangs' `+1/+1` and lifelink counters. Added by `FW-COUNTERS`. One event
     * per kind, so a spell that places two different kinds emits two events in the order it places
     * them. [amount] is always positive; removal is [CountersRemoved].
     */
    data class CountersPlaced(
        val objectId: ObjectId,
        val card: CardRef,
        val counter: Counter,
        val amount: Int,
    ) : GameEvent

    /**
     * [amount] counters of kind [counter] were **removed from** the permanent [objectId] ([card])
     * (CR 122.1). Added by `FW-COUNTERS`. The reachable emitter today is the CR 704.5q state-based
     * action, which removes equal numbers of `+1/+1` and `-1/-1` counters and so emits this twice —
     * once per kind. [amount] is always positive.
     */
    data class CountersRemoved(
        val objectId: ObjectId,
        val card: CardRef,
        val counter: Counter,
        val amount: Int,
    ) : GameEvent

    /** [player] lost the game (CR 104.3) for [reason]. */
    data class PlayerLost(
        val player: PlayerId,
        val reason: LossReason,
    ) : GameEvent

    /** The game ended: [loser]'s only opponent, [winner], won the game (CR 104.2a). */
    data class GameEnded(
        val winner: PlayerId,
        val loser: PlayerId,
    ) : GameEvent

    /**
     * A resolving spell or ability created a continuous effect with a duration (CR 611.2): [sourceCard]'s
     * resolution modified [affected] until the effect expires. Added with `FW-DURATION`
     * (docs/design/duration.md).
     *
     * The modifiers are reported as the **already-snapshotted** integers the effect stores (CR 608.2h,
     * CR 611.2d), which is what makes the log readable: "+3/+3" is what the pump actually is for the rest
     * of the turn, not a formula whose value a reader would have to recompute. No event narrates the
     * CR 514.2 wear-off — like the untap step's status change it is silent bookkeeping, and the acceptance
     * invariant confirms no effect survives its turn.
     */
    data class ContinuousEffectCreated(
        val sourceCard: CardRef,
        val affected: ObjectId,
        val powerMod: Int,
        val toughnessMod: Int,
    ) : GameEvent
}
