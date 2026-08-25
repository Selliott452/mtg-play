package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Who may legitimately hold floating mana at an observed pause — the `W8-B` half of
 * [Invariant.MANA_POOL_EMPTY_AT_PAUSE]'s declared exceptions (CR 500.4).
 *
 * Its own file rather than a member of [InvariantChecker] purely for room: that object and its file
 * both sit on detekt's function budget, and this reads nothing but its argument. The CR 605.1b Aura
 * half of the exemption stays inline beside the check, because it is expressed entirely in terms of
 * the battlefield the check is already walking.
 */

/**
 * The seats that own a card, **in any zone**, whose definition declares a mana-adding triggered ability
 * ([dev.mtgplay.core.definition.TriggeredAbility.addsMana]) — Burning-Tree Emissary's "When this
 * creature enters, add `{R}{G}`".
 *
 * Such an ability is *not* a CR 605.1b mana ability: it triggers off a permanent entering the
 * battlefield, so it uses the stack, and its mana arrives in the priority window the resolution hands
 * back rather than inside a payment. Floating across a pause is therefore the card working, not a
 * remainder — and unlike the P8.3 narrowing of the Aura bonus, nothing can consume it early, because no
 * payment is in progress.
 *
 * **Any zone, deliberately.** The trigger is independent of the permanent that generated it (CR 603.3),
 * so killing the Emissary in response to its own trigger does not stop the mana arriving: the source is
 * in a graveyard by the time the checker sees the pause. A battlefield-keyed exemption would report that
 * entirely correct game as engine wrongness — the same shape of false positive
 * [InvariantChecker.checkManaPoolEmptiness]'s KDoc records the Aura half once cost 7,920 times over.
 * Scanning the whole game also keeps the verdict a property of the *deck* rather than of the moment.
 */
internal fun seatsOwningAManaAddingTrigger(state: GameState): Set<PlayerId> {
    val floats = { obj: GameObject ->
        state.definitions[obj.card]
            ?.triggeredAbilities
            .orEmpty()
            .any { it.addsMana.isNotEmpty() }
    }
    val shared = (state.sharedZones.battlefield + state.sharedZones.exile).filter(floats).map { it.owner }
    val hidden =
        state.players
            .filterValues { player -> (player.library + player.hand + player.graveyard).any(floats) }
            .keys
    return shared.toSet() + hidden
}
