package dev.mtgplay.rules

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject

/**
 * An explore paused on its last sentence (CR 701.40a) as any seat may see it (ADR-007): the deciding
 * seat, the permanent that explored, and the **revealed** card waiting to be placed. Additive (`W10-D`).
 *
 * **The second place a library card crosses the view boundary to a non-owning seat**, after
 * [PendingRevealView], and correct for the same reason: CR 701.40a says *reveal*, so the card's identity
 * is public to every player even though the rest of the library stays secret. The difference from its
 * precedent is only in how long the disclosure lasts — a CR 701.16 reveal ends with every revealed card
 * moved out of the library, while an explore may put this one **back on top**, at which point it stops
 * being public and this view is gone. Both seats saw it; only one of them will draw it.
 *
 * Withholding it is the failure this type exists to prevent, and it is a *silent* one: the deciding seat
 * would answer a request naming a card the opposing seat could not see, so an agent trained on the
 * opposing view would be reasoning about a decision with an invisible subject (ADR-005 and ADR-007
 * pulling the same way).
 *
 * [viewFor] resolves the revealed object id against the deciding seat's library to expose the actual
 * [GameObject] here — the card has not moved and is still that library's top.
 *
 * @property decider the exploring permanent's controller (CR 701.40a), who places the revealed card.
 * @property exploring the permanent that explored; its `+1/+1` counter is already on it.
 * @property revealed the revealed card, still in [decider]'s library. Public to both seats.
 */
data class PendingExploreView(
    val decider: PlayerId,
    val exploring: GameObject,
    val revealed: GameObject,
)
