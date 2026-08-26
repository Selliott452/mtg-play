package dev.mtgplay.rules.effect

import dev.mtgplay.core.definition.Dungeon
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.takeInitiative

/**
 * The published effect primitive for *"you take the initiative"* (CR 701.51a): [player] becomes the
 * initiative holder, and their venture ability triggers so that they venture into [dungeon] when it
 * resolves (CR 701.51b, CR 309.4). Added by `W10-A`.
 *
 * **A primitive rather than a fold left to the card**, for the reason ADR-003 asks the question: the
 * initiative is a *designation* with three entry points — this one, the initiative holder's upkeep, and
 * the combat-damage handover — and the other two are inside the engine, where no card can reach. A card
 * that set [dev.mtgplay.core.state.GameState.initiative] for itself would create a designation the
 * upkeep and the handover then maintained by rules the card never saw, and the first card to disagree
 * with them by a line would be undiagnosable. There is one way to take the initiative, and this is it.
 *
 * **[dungeon] is supplied by the card, and this is the only place it can be.** CR 701.51a fixes what
 * taking the initiative ventures into — Undercity — but ADR-003 forbids `mtg-rules` naming a card, so
 * the graph is a value declared in `mtg-cards` and handed in here. It is used only when the initiative
 * is first created; every later venture reads the dungeon already recorded in the game state, which is
 * why the engine's own two entry points need no dungeon at all.
 *
 * **Pure, and it does not venture.** The venture is a triggered ability that uses the stack (CR 603.3b),
 * so what this returns is a state with that trigger *pending*: the opponent gets a priority window
 * between the creature entering and the room's ability resolving. It performs no decision, which is why
 * it is a plain state transition a [dev.mtgplay.core.definition.ResolutionEffect] may call at all — the
 * CR 309.4 branch choice belongs to the venture, one resolution later, where the engine can pause for it
 * (ADR-004).
 */
fun takeTheInitiative(
    state: GameState,
    player: PlayerId,
    dungeon: Dungeon,
): GameState = takeInitiative(state, player, dungeon)
