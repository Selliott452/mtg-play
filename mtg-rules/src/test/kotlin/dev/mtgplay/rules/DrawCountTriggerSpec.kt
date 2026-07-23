package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.effect.returnFromGraveyardToBattlefieldTapped
import dev.mtgplay.rules.engine.drawCard
import dev.mtgplay.rules.engine.player
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P6.2a per-turn draw-counting and graveyard-scoped trigger machinery (CR 603.2, CR 113.6)
 * exercised with a fixture card mirroring Sneaky Snacker's "when you draw your third card in a turn,
 * return this card from your graveyard to the battlefield tapped" — the `mtg-rules`-names-no-card rule
 * holds; the P6.2b Sneaky Snacker encoding reuses exactly this shape.
 */
class DrawCountTriggerSpec :
    StringSpec({
        val snacker = CardRef("Fixture Snacker")

        "CR 603.2: drawing the third card in a turn fires the graveyard-scoped draw trigger" {
            val state = snackerState(drawsSoFar = 2)
            val drawn = drawCard(state, alice)
            drawn.player(alice).drawsThisTurn shouldBe 3
            val fired = drawn.pendingTriggers.single()
            fired.sourceCard shouldBe snacker
            fired.controller shouldBe alice
            // CR 603.10: the graveyard object is captured as the trigger's source and subject.
            fired.sourceId shouldBe SNACKER_GRAVEYARD_ID
            fired.subject shouldBe SNACKER_GRAVEYARD_ID
        }

        "CR 603.2: the first and second draws of the turn do not fire the third-card trigger" {
            val first = drawCard(snackerState(drawsSoFar = 0), alice)
            first.pendingTriggers.shouldBeEmpty()
            val second = drawCard(first, alice)
            second.player(alice).drawsThisTurn shouldBe 2
            second.pendingTriggers.shouldBeEmpty()
        }

        "CR 603.2: a fourth draw does not re-fire the third-card trigger" {
            val fourth = drawCard(snackerState(drawsSoFar = 3), alice)
            fourth.player(alice).drawsThisTurn shouldBe 4
            fourth.pendingTriggers.shouldBeEmpty()
        }

        "CR 400.7: the resolved trigger returns the card to the battlefield tapped as a new object" {
            val state = snackerState(drawsSoFar = 2)
            // Resolve the return effect directly against the fired trigger's subject.
            val returned = returnFromGraveyardToBattlefieldTapped(state, SNACKER_GRAVEYARD_ID)
            // The graveyard object is gone; a new battlefield object with a fresh id is tapped.
            returned.player(alice).graveyard.shouldBeEmpty()
            val onBattlefield = returned.sharedZones.battlefield.filter { it.card == snacker }
            onBattlefield shouldHaveSize 1
            val reborn = onBattlefield.single()
            reborn.id shouldBe ObjectId(state.nextObjectId) // CR 400.7: a fresh id from the counter.
            reborn.tapped shouldBe true
        }

        "CR 603.2: 'in a turn' is per player — a draw for one seat does not count for the other" {
            val state = snackerState(drawsSoFar = 2)
            // Bob draws; his own count rises, alice's is untouched, and no alice trigger fires.
            val bobDrew = drawCard(state.updatePlayerLibrary(bob, listOf(snacker)), bob)
            bobDrew.player(bob).drawsThisTurn shouldBe 1
            bobDrew.player(alice).drawsThisTurn shouldBe 2
            bobDrew.pendingTriggers.shouldBeEmpty()
        }
    })

/** The stable id of the fixture Snacker sitting in alice's graveyard across these scenarios. */
private val SNACKER_GRAVEYARD_ID = ObjectId(0)

/** A fixture card carrying Sneaky Snacker's graveyard-scoped third-draw trigger. */
private fun snackerCard(name: CardRef): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name.name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness =
                    dev.mtgplay.core.card
                        .PrintedPowerToughness(1, 1),
            )
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.DrewNthCardThisTurn(3),
                    effect =
                        ResolutionEffect { state, context ->
                            returnFromGraveyardToBattlefieldTapped(
                                state,
                                context.subject ?: error("draw trigger carries the graveyard object as its subject"),
                            )
                        },
                    zoneScope = TriggerZoneScope.Graveyard,
                ),
            )
    }

/**
 * A two-player state where alice has a fixture Snacker in her graveyard, a two-card library to draw
 * from, and [drawsSoFar] draws already made this turn.
 */
private fun snackerState(drawsSoFar: Int): GameState {
    val snacker = CardRef("Fixture Snacker")
    val library =
        listOf(GameObject(ObjectId(1), CardRef("Mountain"), alice), GameObject(ObjectId(2), CardRef("Mountain"), alice))
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = library.toPersistentList(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(GameObject(SNACKER_GRAVEYARD_ID, snacker, alice)),
                        drawsThisTurn = drawsSoFar,
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf()),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = mapOf(snacker to snackerCard(snacker)).toPersistentMap(),
    )
}

/** Replaces [seat]'s library with fresh objects of [cards] — for handcrafting a draw source. */
private fun GameState.updatePlayerLibrary(
    seat: dev.mtgplay.core.identity.PlayerId,
    cards: List<CardRef>,
): GameState {
    val objects = cards.mapIndexed { i, ref -> GameObject(ObjectId(50L + i), ref, seat) }.toPersistentList()
    return copy(players = players.putting(seat, players.getValue(seat).copy(library = objects)))
}
