package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.effect.exileAllGraveyards
import dev.mtgplay.rules.effect.exileTopCardsPlayableUntilEndOfYourNextTurn
import dev.mtgplay.rules.effect.returnFromGraveyardToBattlefield
import dev.mtgplay.rules.effect.sacrificePermanent
import dev.mtgplay.rules.engine.cleanupRemoveDamageAndEndEffects
import dev.mtgplay.rules.engine.interveningIfHolds
import dev.mtgplay.rules.engine.playGrantMarkerAllows
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The effect primitives and object markers `W8-D` added, exercised directly rather than through a card
 * (ADR-003: `mtg-rules` names no card). Each test pins the property that made the primitive a primitive
 * rather than a fold left to a card definition.
 */
class W8DPrimitivesSpec :
    StringSpec({

        "CR 701.17a: a sacrifice *effect* moves the permanent to its owner's graveyard" {
            val state = boardWith(battlefield = listOf(BEAR))
            val bear = state.sharedZones.battlefield.single()

            val done = sacrificePermanent(state, bear.id)

            done.sharedZones.battlefield.shouldBeEmpty()
            done.players
                .getValue(alice)
                .graveyard
                .map { it.card.name } shouldContainExactly listOf(BEAR)
            done.events.filterIsInstance<GameEvent.PermanentSacrificed>().size shouldBe 1
        }

        "CR 603.10: a sacrifice effect whose permanent has already left is a no-op, not a failure" {
            // The opposite contract from the *cost* path, which was checked before it was ever offered.
            // An evoke trigger resolving after its creature was killed in response reaches exactly this.
            val state = boardWith(battlefield = listOf(BEAR))

            sacrificePermanent(state, ObjectId(4242)) shouldBe state
        }

        "CR 110.5a: a graveyard creature returned to the battlefield arrives untapped and summoning sick" {
            val state = boardWith(graveyard = listOf(BEAR))
            val card =
                state.players
                    .getValue(alice)
                    .graveyard
                    .single()

            val done = returnFromGraveyardToBattlefield(state, card.id)

            val permanent = done.sharedZones.battlefield.single()
            permanent.card shouldBe CardRef(BEAR)
            // The whole difference from the tapped sibling, and three lines of play: it can block, pay a
            // `{T}` cost, and attack with haste.
            permanent.tapped shouldBe false
            permanent.summoningSick shouldBe true
            done.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
        }

        "CR 400.7: returning a card that has already left the graveyard does nothing" {
            val state = boardWith(graveyard = listOf(BEAR))
            returnFromGraveyardToBattlefield(state, ObjectId(4242)) shouldBe state
        }

        "CR 701.3a: exiling all graveyards includes the effect's own controller's" {
            val state = boardWith(graveyard = listOf(BEAR, BOLT), bobGraveyard = listOf(WASTE))

            val done = exileAllGraveyards(state)

            // The card names no player, so there is nobody to point it at — the symmetry is its real cost.
            done.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            done.players
                .getValue(bob)
                .graveyard
                .shouldBeEmpty()
            done.sharedZones.exile.map { it.card.name } shouldContainExactly listOf(BEAR, BOLT, WASTE)
        }

        "CR 701.3a: exiling all graveyards on an empty board changes nothing" {
            val state = boardWith()
            exileAllGraveyards(state) shouldBe state
        }

        "CR 118.5: exiling for play marks each exiled card with the turn the permission began" {
            val state = boardWith(library = listOf(BEAR, BOLT, WASTE))

            val done = exileTopCardsPlayableUntilEndOfYourNextTurn(state, alice, 2)

            done.sharedZones.exile.map { it.card.name } shouldContainExactly listOf(BEAR, BOLT)
            done.sharedZones.exile.all { it.playGrantedTurn == GRANT_TURN } shouldBe true
            done.players
                .getValue(alice)
                .library
                .map { it.card.name } shouldContainExactly listOf(WASTE)
            // Face up: the identities are public, so both seats can play around what is coming.
            done.events
                .filterIsInstance<GameEvent.CardsExiledFromLibrary>()
                .single()
                .cards
                .map { it.name } shouldContainExactly listOf(BEAR, BOLT)
        }

        "CR 701.3a: exiling for play from a shorter library exiles what there is" {
            val state = boardWith(library = listOf(BEAR))
            val done = exileTopCardsPlayableUntilEndOfYourNextTurn(state, alice, 2)
            done.sharedZones.exile.size shouldBe 1
        }

        "CR 118.5: the permission survives its own turn's cleanup and the opponent's" {
            val granted = exileTopCardsPlayableUntilEndOfYourNextTurn(boardWith(library = listOf(BEAR)), alice, 1)

            // Cleanup of the turn it was granted on: not "later than" the grant, so it survives.
            val afterOwnTurn = cleanupRemoveDamageAndEndEffects(granted)
            afterOwnTurn.sharedZones.exile
                .single()
                .playGrantedTurn shouldBe GRANT_TURN

            // Cleanup of the opponent's turn: not the owner's turn, so it survives that too.
            val opponentTurn =
                afterOwnTurn.copy(
                    turn = afterOwnTurn.turn.copy(activePlayer = bob, number = GRANT_TURN + 1),
                )
            cleanupRemoveDamageAndEndEffects(opponentTurn)
                .sharedZones.exile
                .single()
                .playGrantedTurn shouldBe GRANT_TURN
        }

        "CR 118.5: the permission ends at the cleanup of the owner's *next* turn" {
            val granted = exileTopCardsPlayableUntilEndOfYourNextTurn(boardWith(library = listOf(BEAR)), alice, 1)
            val ownNextTurn = granted.copy(turn = granted.turn.copy(activePlayer = alice, number = GRANT_TURN + 2))

            val expired = cleanupRemoveDamageAndEndEffects(ownNextTurn)

            expired.sharedZones.exile
                .single()
                .playGrantedTurn
                .shouldBeNull()
            // The enumeration agrees with the cleanup by construction: both read the same derivation.
            playGrantMarkerAllows(ownNextTurn, ownNextTurn.sharedZones.exile.single()) shouldBe false
            playGrantMarkerAllows(granted, granted.sharedZones.exile.single()) shouldBe true
        }

        "CR 702.74a: an evoke intervening-if reads the marker off the permanent, not the spell" {
            val evoked =
                boardWith(battlefield = listOf(BEAR)).let { state ->
                    val bear = state.sharedZones.battlefield.single()
                    state.copy(
                        sharedZones =
                            state.sharedZones.copy(
                                battlefield = persistentListOf(bear.copy(evokedWhenCast = true)),
                            ),
                    )
                }
            val hardCast = boardWith(battlefield = listOf(BEAR))
            val ability =
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = ResolutionEffect { s, _ -> s },
                    interveningIf = InterveningIf.SourceWasEvoked,
                )

            // CR 603.4's *first* check is where the whole observable difference lives: a hard-cast
            // permanent puts no ability on the stack at all, so nothing is ordered against its sibling.
            interveningIfHolds(
                evoked,
                ability,
                evoked.sharedZones.battlefield
                    .single()
                    .id,
                alice,
            ) shouldBe true
            interveningIfHolds(
                hardCast,
                ability,
                hardCast.sharedZones.battlefield
                    .single()
                    .id,
                alice,
            ) shouldBe false
            // A source that has left the battlefield answers false rather than throwing (CR 603.4).
            interveningIfHolds(evoked, ability, ObjectId(4242), alice) shouldBe false
        }
    })

private const val BEAR = "Prim Bear"
private const val BOLT = "Prim Bolt"
private const val WASTE = "Prim Waste"

/** The turn the fixture states sit on, and so the turn a granted play permission records. */
private const val GRANT_TURN = 3

/** A board with the named cards in each zone, all owned by Alice unless the zone says otherwise. */
private fun boardWith(
    battlefield: List<String> = emptyList(),
    graveyard: List<String> = emptyList(),
    library: List<String> = emptyList(),
    bobGraveyard: List<String> = emptyList(),
): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId++), CardRef(it), owner) }.toPersistentList()

    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = objects(library, alice),
                        hand = persistentListOf(),
                        graveyard = objects(graveyard, alice),
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = objects(bobGraveyard, bob),
                    ),
            ),
        turn = Turn(alice, GRANT_TURN, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(objects(battlefield, alice), persistentListOf(), persistentListOf()),
        nextObjectId = 500,
        rng = Rng(7),
        events = persistentListOf(),
        definitions = primitiveRegistry.toPersistentMap(),
    )
}

private val primitiveRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef(BEAR) to primitiveCard(BEAR, CardType.CREATURE),
        CardRef(BOLT) to primitiveCard(BOLT, CardType.INSTANT),
        CardRef(WASTE) to primitiveCard(WASTE, CardType.LAND),
    )

private fun primitiveCard(
    name: String,
    type: CardType,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(type),
                subtypes = persistentSetOf(),
                // CR 208.1: only a creature card has printed power/toughness.
                powerToughness = if (type == CardType.CREATURE) PrintedPowerToughness(1, 1) else null,
            )
    }
