package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastCondition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState

/*
 * The state condition a casting permission may carry (`FW-ALTCOST`, CR 118.9), split from
 * CastLegality.kt so that file stays within its function budget.
 *
 * **The first thing about a permission that is not a property of the card's own residence.** Madness,
 * flashback, escape, plot and rebound are each gated on where the card is and how it got there — a
 * marker the engine reads off the object itself. Land Grant's is gated on the rest of the game state and
 * can flip between one priority window and the next without the card moving at all.
 */

/**
 * Whether [permission]'s own state condition holds for [seat] right now (CR 118.9) — Land Grant's "If
 * you have no land cards in hand". Trivially true for a permission with no condition, which is every
 * one that predates `FW-ALTCOST`.
 *
 * **Evaluated at enumeration time, and re-evaluated at nothing.** A permission whose condition is false
 * is simply not an option (ADR-005), so a seat can never choose it and then find the cost unpayable.
 * The engine deliberately does *not* re-check it during the cast pipeline: CR 601.2 runs atomically in
 * the transition that receives the final decision, and nothing between enumeration and execution can
 * change the caster's hand — the card being cast leaves the hand at CR 601.2a, which is why
 * [noLandCardsInHand] counts the hand *including* the card being cast rather than excluding it (see
 * [dev.mtgplay.core.definition.CastCondition.NoLandCardsInHand], and note that no land card has an
 * alternative cost, so a land can never be the card being cast this way).
 *
 * The condition reads the caster's own hidden hand (CR 400.2), which discloses nothing: cast options
 * are enumerated only for the seat holding priority, over cards that seat can already see (ADR-007).
 */
internal fun castConditionHolds(
    state: GameState,
    seat: PlayerId,
    permission: CastingPermission,
): Boolean =
    when (permission.condition) {
        null -> true
        CastCondition.NoLandCardsInHand -> noLandCardsInHand(state, seat)
    }

/**
 * Whether [seat]'s hand contains no land card (CR 205.2, CR 305) — the whole of
 * [CastCondition.NoLandCardsInHand]. A card with no definition is inert and is not a land
 * ([isLand] answers `false` for it), matching how every other land test in the engine reads.
 */
private fun noLandCardsInHand(
    state: GameState,
    seat: PlayerId,
): Boolean = state.player(seat).hand.none { state.definitions[it.card].isLand() }
