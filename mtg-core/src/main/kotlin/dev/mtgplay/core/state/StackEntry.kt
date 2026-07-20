package dev.mtgplay.core.state

import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList

/**
 * One object waiting on the stack (CR 405.2): the typed shape of
 * [SharedZones.stack]'s elements from P2.1 on.
 *
 * Sealed because the stack holds more than spells as the engine grows: triggered and activated
 * abilities join in Phase 5 as new members, and every consumer of the stack `when`s
 * exhaustively, so their arrival breaks compilation rather than falling through.
 *
 * @property obj the card object on the stack; its id is fresh for this stack residence
 *   (CR 400.7) and dies with it — resolution puts a *new* object in the graveyard (CR 608.2m).
 */
sealed interface StackEntry {
    val obj: GameObject

    /**
     * A spell on the stack (CR 112.1): the card object plus everything chosen while casting it
     * (CR 601.2) — its controller, its targets, and the definition it was cast from.
     *
     * The definition is captured at cast time so resolution (CR 608.2c) uses exactly what was
     * cast, with no registry lookup that could diverge; the cast record grows here in Phase 5
     * (chosen modes, linked cost information — docs/decklists.md).
     *
     * @property controller the player who cast the spell and controls it on the stack
     *   (CR 601.2, CR 108.4).
     * @property targets the chosen targets in the order chosen (CR 601.2c); empty for an
     *   untargeted spell.
     * @property definition the [SpellDefinition] the spell was cast from.
     */
    data class Spell(
        override val obj: GameObject,
        val controller: PlayerId,
        val targets: PersistentList<Target>,
        val definition: SpellDefinition,
    ) : StackEntry
}
