package dev.mtgplay.rules

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.Target

/**
 * One entry on the stack as any seat may see it (ADR-007): the fully public facts of a
 * [dev.mtgplay.core.state.StackEntry] — what object or ability it is, who controls it, and what it
 * targets — with the engine's captured *definition* deliberately dropped.
 *
 * The stack is public (CR 405), but a seat needs only the identity, controller, and targets to
 * reason about it; the [dev.mtgplay.core.definition.SpellDefinition] /
 * [dev.mtgplay.core.definition.TriggeredAbility] / [dev.mtgplay.core.definition.ActivatedAbility]
 * an entry carries is static card data an agent already holds by name (CR reference by
 * [CardRef]), so it never crosses the view boundary. Sealed to mirror
 * [dev.mtgplay.core.state.StackEntry] exhaustively: a new stack-entry kind breaks this projection.
 */
sealed interface StackEntryView {
    /** The controller of this stack object (CR 108.4): the player who put it on the stack. */
    val controller: PlayerId

    /**
     * A spell on the stack (CR 112.1): a public card object with its controller and chosen targets.
     *
     * @property objectId the spell's object id for its stack residence (CR 400.7).
     * @property card the spell's printed identity.
     * @property controller the player who cast and controls the spell (CR 601.2, CR 108.4).
     * @property targets the chosen targets in the order chosen (CR 601.2c); empty for an untargeted
     *   spell. Every target is a public object or player (CR 115.1).
     */
    data class SpellOnStack(
        val objectId: ObjectId,
        val card: CardRef,
        override val controller: PlayerId,
        val targets: List<Target>,
    ) : StackEntryView

    /**
     * A triggered ability on the stack (CR 113.3c, CR 603.3): an ability object with its source's
     * last-known identity and its controller.
     *
     * @property sourceId the source object's last-known id (CR 603.10).
     * @property sourceCard the source's printed identity.
     * @property controller the player who controls the ability (CR 603.3d).
     * @property targets the targets chosen as the ability was put on the stack (CR 603.3d); empty for an
     *   untargeted ability, and also for a targeting one whose controller had no legal choice. Public
     *   like a spell's (CR 115.1).
     */
    data class TriggeredAbilityOnStack(
        val sourceId: ObjectId,
        val sourceCard: CardRef,
        override val controller: PlayerId,
        val targets: List<Target>,
    ) : StackEntryView

    /**
     * An activated ability on the stack (CR 602.2, CR 113.3b): an ability object with its source's
     * last-known identity and its controller.
     *
     * @property sourceId the source object's id when the ability was activated (CR 602.2).
     * @property sourceCard the source's printed identity.
     * @property controller the player who activated and controls the ability (CR 602.2).
     * @property targets the targets chosen while activating (CR 602.2b); empty for an untargeted
     *   ability. Public like a spell's (CR 115.1).
     */
    data class ActivatedAbilityOnStack(
        val sourceId: ObjectId,
        val sourceCard: CardRef,
        override val controller: PlayerId,
        val targets: List<Target>,
    ) : StackEntryView
}
