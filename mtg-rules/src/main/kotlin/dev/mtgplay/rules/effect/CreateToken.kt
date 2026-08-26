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
    // CR 111.1: a token is not a card, so its registry key is a *token* ref and never a card name
    // (`FW-COPYTOKEN`). This is the line that makes a copy token possible: an embalm token named
    // "Sacred Cat" used to key onto the entry the real card occupies, and register-if-absent then
    // silently gave the token the card's definition — castable, embalmable again, and invisible to the
    // CR 704.5d token-ceases state-based action.
    val ref = CardRef.token(token.characteristics.name)
    val existing = state.definitions[ref]
    val registered =
        if (existing != null) {
            // A second Sacred Cat's embalm token must find the *same* definition the first registered.
            // Two different token definitions under one name would mean the token's characteristics
            // depended on which card happened to create it first, which is a replay-order dependency.
            require(existing == token) {
                "CR 111.4: two different token definitions share the name \"${token.characteristics.name}\"; " +
                    "a token's characteristics are defined by the effect that creates it and must not " +
                    "depend on which effect ran first"
            }
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
