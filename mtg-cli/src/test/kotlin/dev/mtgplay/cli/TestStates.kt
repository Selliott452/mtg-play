package dev.mtgplay.cli

import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/*
 * Shared fixtures for the CLI render/menu specs: the two seats, a hand-built mid-game state over the
 * real [MvpCards] definitions (so effective P/T and keywords resolve through the layer system), and
 * the request-id helper.
 */

internal val viewerSeat = PlayerId(0)
internal val opponentSeat = PlayerId(1)
internal val testNames = mapOf(viewerSeat to "Mono-Red Madness", opponentSeat to "GW Bogles")

/** One zone object of card [name] owned by [owner]; not summoning sick, so no stray "sick" tag. */
private fun obj(
    id: Long,
    name: String,
    owner: PlayerId,
    tapped: Boolean = false,
    attachedTo: ObjectId? = null,
): GameObject =
    GameObject(
        id = ObjectId(id),
        card = CardRef(name),
        owner = owner,
        tapped = tapped,
        summoningSick = false,
        attachedTo = attachedTo,
    )

/**
 * A mid-game view for the viewer (seat 0): a Grizzly Bears enchanted by Rancor (so it renders 4/2
 * with trample, exercising the layer system), the opponent's tapped Mountain, the viewer's hand and
 * graveyard, and the opponent's hidden hand. Turn 3, precombat main.
 */
internal fun midGameView(): MatchView {
    val bears = obj(id = 1, name = "Grizzly Bears", owner = viewerSeat)
    val rancor = obj(id = 2, name = "Rancor", owner = viewerSeat, attachedTo = ObjectId(1))
    val oppMountain = obj(id = 3, name = "Mountain", owner = opponentSeat, tapped = true)
    val viewer =
        PlayerState(
            life = 18,
            library = List(20) { obj(id = 100L + it, name = "Mountain", owner = viewerSeat) }.toPersistentList(),
            hand = persistentListOf(obj(id = 4, name = "Lightning Bolt", owner = viewerSeat)),
            graveyard = persistentListOf(obj(id = 5, name = "Fiery Temper", owner = viewerSeat)),
        )
    val opponent =
        PlayerState(
            life = 20,
            library = List(25) { obj(id = 200L + it, name = "Forest", owner = opponentSeat) }.toPersistentList(),
            hand = persistentListOf(obj(id = 6, name = "Slippery Bogle", owner = opponentSeat)),
            graveyard = persistentListOf(),
        )
    val state =
        GameState(
            players = persistentMapOf(viewerSeat to viewer, opponentSeat to opponent),
            turn = Turn(activePlayer = viewerSeat, number = 3, phase = TurnPhase.PRECOMBAT_MAIN, step = null),
            sharedZones =
                SharedZones(
                    battlefield = persistentListOf(bears, rancor, oppMountain),
                    stack = persistentListOf(),
                    exile = persistentListOf(),
                ),
            nextObjectId = 1_000,
            rng = Rng(0),
            events = persistentListOf(),
            definitions = MvpCards.definitions.toPersistentMap(),
        )
    return MatchView(state, viewerSeat, testNames)
}
