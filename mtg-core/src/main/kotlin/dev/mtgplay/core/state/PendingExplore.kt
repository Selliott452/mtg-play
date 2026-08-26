package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * An explore in progress (CR 701.40a), paused on its last sentence: the top card of [decider]'s library
 * has been **revealed**, the `+1/+1` counter is already on [exploring], and the engine is waiting to be
 * told whether the revealed card goes back on top or into the graveyard. Additive, flagged core
 * (`W10-D`) — the Map token's *"Target creature you control explores."* Non-null only at that pause,
 * where the resolving object is still the top of the stack.
 *
 * **Open only on the nonland branch.** A revealed *land* card is put into the hand and the resolution
 * ends with no pause, so this record never exists for it; an **empty** library reveals nothing, so the
 * "otherwise" arm runs, the counter is placed and this record still never exists. The only state that
 * reaches here is "a nonland card was revealed and has not yet been placed" — see
 * [dev.mtgplay.core.definition.Explore].
 *
 * **[revealed] is a card in a library that every seat may see**, and it is the only such card in the
 * engine besides CR 701.16's reveal selection. The card has not moved: it is still the physical top of
 * [decider]'s library, and it goes back there or to a graveyard when the answer arrives. Its identity is
 * public because CR 701.40a said *reveal*, which is why `dev.mtgplay.rules.PendingExploreView` exists —
 * a seat view that hid it would leave the opponent unable to see a card the card just showed them
 * (ADR-007).
 *
 * @property decider the exploring permanent's controller (CR 701.40a), whose library was revealed and who
 *   chooses the destination. Read from the permanent rather than from the resolving object's controller,
 *   because the CR names the permanent's controller and the two need not be the same seat.
 * @property exploring the permanent that explored — the object the `+1/+1` counter has already gone on.
 * @property revealed the revealed card, still the top object of [decider]'s library. Public to both seats.
 * @property sourceCard the printed identity of the object that issued the instruction, for display.
 */
data class PendingExplore(
    val decider: PlayerId,
    val exploring: ObjectId,
    val revealed: ObjectId,
    val sourceCard: CardRef,
)
