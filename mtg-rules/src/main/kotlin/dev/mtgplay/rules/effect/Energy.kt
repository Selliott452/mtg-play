package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updatePlayer

/*
 * Energy counters (CR 107.16, CR 122.1) — the counter this engine puts on a **player** rather than on an
 * object. Added by `FW-EQUIP` for Inventor's Axe, the pool's only energy card.
 *
 * Two verbs and no third: a player *gets* energy from a resolving effect and *pays* it as a cost. There
 * is no "lose energy" — nothing in the gauntlet removes it except payment — and no cap, because CR 107.16
 * gives one neither.
 */

/**
 * Effect primitive: [player] gets [amount] energy counters (CR 107.16) — Inventor's Axe's "you get
 * `{E}{E}`". Emits [GameEvent.EnergyCountersGained].
 *
 * **Energy does not expire**, which is what makes the Axe's equip cost repeatable but finite: two
 * counters buy exactly one re-equip, whenever the controller wants it, and nothing gives them back. An
 * engine that cleared energy at end of turn would turn a card about a small permanent resource into a
 * card about a one-shot, and would do it silently.
 */
fun gainEnergy(
    state: GameState,
    player: PlayerId,
    amount: Int,
): GameState {
    require(amount > 0) { "CR 107.16: getting energy gets at least one counter, was $amount" }
    return state
        .updatePlayer(player) { it.copy(energyCounters = it.energyCounters + amount) }
        .emit(GameEvent.EnergyCountersGained(player, amount))
}

/**
 * Pays [amount] of [player]'s energy counters (CR 118.4) — the cost half, called from the activation
 * pipeline rather than from a card. Emits [GameEvent.EnergyCountersPaid].
 *
 * Fails loudly if the player has fewer than [amount]. That is ADR-005 restated as an assertion rather
 * than defensiveness: an ability whose cost cannot be paid is never enumerated, so reaching here without
 * the counters means the payability check and the payment disagreed — the exact class of defect the
 * "enumerated and payable are one predicate" discipline exists to prevent.
 */
internal fun payEnergy(
    state: GameState,
    player: PlayerId,
    amount: Int,
): GameState {
    require(amount > 0) { "CR 118.4: paying energy pays at least one counter, was $amount" }
    val held = state.players.getValue(player).energyCounters
    require(held >= amount) {
        "CR 118.4: $player must pay $amount energy but has $held; an unpayable cost must never have " +
            "been enumerated (ADR-005)"
    }
    return state
        .updatePlayer(player) { it.copy(energyCounters = it.energyCounters - amount) }
        .emit(GameEvent.EnergyCountersPaid(player, amount))
}
