package dev.mtgplay.core.event

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaType
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
 * below are the P1.2 set (game lifecycle, turn structure, priority, zone moves) plus the P2.1
 * set (the CR 601 casting stages, mana, life, and CR 608 resolution) — enough for a readable
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
}
