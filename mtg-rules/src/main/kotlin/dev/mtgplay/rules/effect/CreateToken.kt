package dev.mtgplay.rules.effect

import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updateBattlefield

/**
 * Effect primitive: [controller] creates a token from [token] on the battlefield (CR 111.4, CR 707.2)
 * — the published building block a token-making card resolution composes (ADR-003; Cartouche of
 * Solidarity's enters-the-battlefield trigger is the first client).
 *
 * The token enters as a **new** battlefield object (CR 400.7) under [controller]'s control (its owner
 * in the MVP pool), summoning sick (CR 302.6) and untapped, exactly like a resolved permanent spell.
 * Its printed characteristics ride in the state's definition registry: the [token]'s definition is
 * registered under its name-[CardRef] if not already present, so combat, the layer system, and the
 * state-based actions read the token's power/toughness and keywords through the same `definitions[card]`
 * path a real card uses — no new object field, and "this object is a token" is simply
 * `definitions[card] is TokenDefinition` (see [TokenDefinition]). Emits [GameEvent.TokenCreated].
 */
fun createToken(
    state: GameState,
    controller: PlayerId,
    token: TokenDefinition,
): GameState {
    val ref = CardRef(token.characteristics.name)
    val registered =
        if (state.definitions.containsKey(
                ref,
            )
        ) {
            state
        } else {
            state.copy(definitions = state.definitions.putting(ref, token))
        }
    val (id, allocated) = registered.allocateObjectId()
    val created = GameObject(id = id, card = ref, owner = controller, summoningSick = true)
    return allocated
        .updateBattlefield { it.adding(created) }
        .emit(GameEvent.TokenCreated(controller, id, ref))
}
