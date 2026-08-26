package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updateBattlefield

/*
 * **Goad** (CR 701.38) — the Undercity's Arena, *"Goad target creature."* Added by `W11`.
 *
 * CR 701.38a: *"to goad a creature means to say 'Until your next turn, that creature attacks each
 * combat if able and attacks a player other than you if able.'"* Both halves are **requirements**
 * (CR 508.1d) on a declaration the goading player will not be making, which is what makes goad
 * unlike every other effect primitive in this package: it changes no characteristic, deals nothing,
 * moves nothing, and its whole observable consequence is that the declare-attackers option list
 * stops being a free subset (`AttackRequirements.kt`, ADR-005).
 */

/**
 * Effect primitive: [goader] goads the battlefield creature [objectId] (CR 701.38a) — until
 * [goader]'s next turn it attacks each combat if able, and attacks a player other than [goader] if
 * able. The published verb the Undercity's Arena composes (ADR-003).
 *
 * **A primitive rather than a fold left to the card, because goad is a rules effect with no payload.**
 * There is nothing for a card to compose it *out of*: the two halves are constraints the engine
 * applies when it enumerates the CR 508.1 declaration, and a card definition cannot reach that
 * enumeration at all. What a card can do is name a target and say the word, which is this function.
 *
 * **Both halves are recorded, and the second is satisfied trivially in a two-player game — which is
 * why the goading player is stored rather than dropped.** "Attacks a player other than you if able"
 * narrows *whom* a goaded creature may attack. In a two-player game the defending player is the sole
 * opponent of whoever is attacking, so the requirement is satisfied by the only declaration there is
 * when the creature's controller is not [goader], and is impossible — hence waived by its own "if
 * able" — when it is. Either way it constrains nothing here, and it is left recorded rather than
 * quietly discarded so that the first multiplayer game does not have to rediscover that goad has a
 * second half. The half that *does* bite in two players is "attacks each combat if able", which
 * `attackRequirementsFor` enforces.
 *
 * **The duration rides on the permanent** ([GameObject.goadedBy], [GameObject.goadedOnTurn]) rather
 * than in a continuous-effect store: goad is not a characteristic, so it classifies into no CR 613
 * layer, and it must end when the object stops being that object (CR 400.7) — a goaded creature that
 * dies and is returned comes back ungoaded, which a field on the permanent gets for free.
 *
 * **Goading again re-starts the clock rather than adding a second record.** A creature goaded by the
 * same player on a later turn is goaded until *that* player's next turn counted from the later goad,
 * which is exactly overwriting [GameObject.goadedOnTurn]. Two different goading players cannot be told
 * apart by one record, which costs nothing in a two-player game for the reason above and is the point
 * at which this field becomes a map.
 *
 * Fails loudly if [objectId] is not a battlefield creature's: every caller arrives after the CR 608.2b
 * re-check has confirmed its target (ADR-005), so a missing one is an engine defect.
 */
fun goad(
    state: GameState,
    objectId: ObjectId,
    goader: PlayerId,
): GameState {
    val index = state.sharedZones.battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) { "CR 701.38a: cannot goad $objectId; only a battlefield permanent can be goaded" }
    val obj = state.sharedZones.battlefield[index]
    val goaded = obj.copy(goadedBy = goader, goadedOnTurn = state.turn.number)
    return state
        .updateBattlefield { battlefield ->
            // Battlefield order is the engine's determinism spine (CR 613.7 timestamps derive from
            // entry order), so the marker replaces in place and never reorders the zone.
            battlefield.removingAt(index).addingAt(index, goaded)
        }.emit(GameEvent.CreatureGoaded(objectId, obj.card, goader))
}
