package dev.mtgplay.rules.effect

import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.announceBattlefieldEntry
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
 *
 * A token is a permanent entering the battlefield, so its own enters-the-battlefield abilities
 * trigger (CR 603.6a) exactly as a resolved permanent spell's do — [TokenDefinition] carries
 * `triggeredAbilities` like any other definition, so this is expressible, not hypothetical. That
 * detection used to be missing here, the same silent gap the gauntlet triage records as **T18** for
 * the play-land path; both now share [announceBattlefieldEntry] so neither can drift again. No token
 * in the pool declares such a trigger today, so nothing observable changed when it was closed.
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
    return announceBattlefieldEntry(
        allocated.updateBattlefield { it.adding(created) },
        id,
        GameEvent.TokenCreated(controller, id, ref),
    )
}
