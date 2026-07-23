package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.engine.SbaOutcome
import dev.mtgplay.rules.engine.performStateBasedActions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * Token machinery (P5.1, CR 111 / CR 704.5d) at the rules level with a fixture token: creation onto
 * the battlefield as a summoning-sick new object (CR 111.4, CR 400.7), and the CR 704.5d cessation of
 * a token in any zone other than the battlefield.
 */
class TokenSpec :
    StringSpec({

        "CR 111.4: create-token puts a summoning-sick token on the battlefield and registers its definition" {
            val state = tokenTestState()
            val created = createToken(state, alice, fixtureToken)

            val token = created.sharedZones.battlefield.single()
            token.card shouldBe CardRef("Fixture Beast")
            token.owner shouldBe alice
            // CR 302.6: a token enters summoning sick; CR 110.5a: untapped.
            token.summoningSick shouldBe true
            token.tapped shouldBe false
            // The token's characteristics ride in the registry, so it is a real creature the engine reads.
            created.definitions[CardRef("Fixture Beast")].shouldBeInstanceOf<TokenDefinition>()
            created.events.filterIsInstance<GameEvent.TokenCreated>() shouldHaveSize 1
        }

        "CR 704.5d: a token in a graveyard ceases to exist as a state-based action" {
            // A token already sitting in alice's graveyard (as a token would be for one check after it
            // died) — the CR 704.5d state-based action removes it entirely.
            val tokenInGraveyard = GameObject(ObjectId(0), CardRef("Fixture Beast"), alice)
            val state =
                tokenTestState(definitions = mapOf(CardRef("Fixture Beast") to fixtureToken)).copy(
                    players =
                        persistentMapOf(
                            alice to
                                PlayerState(
                                    life = STARTING_LIFE,
                                    library = persistentListOf(),
                                    hand = persistentListOf(),
                                    graveyard = persistentListOf(tokenInGraveyard),
                                ),
                            bob to emptySeat(),
                        ),
                )
            val outcome = performStateBasedActions(state).shouldBeInstanceOf<SbaOutcome.Continued>()
            // The token is gone from the graveyard, put nowhere (a token is not conserved).
            outcome.state.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            outcome.state.events.filterIsInstance<GameEvent.TokenCeasedToExist>() shouldHaveSize 1
        }
    })

/** A fixture 2/2 creature token with vigilance — the token machinery is card-agnostic. */
private val fixtureToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Fixture Beast",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
                keywords = persistentSetOf(Keyword.VIGILANCE),
            ),
    )

private fun emptySeat(): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )

private fun tokenTestState(
    battlefield: List<GameObject> = emptyList(),
    definitions: Map<CardRef, CardDefinition> = emptyMap(),
): GameState =
    GameState(
        players = persistentMapOf(alice to emptySeat(), bob to emptySeat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(battlefield.toPersistentList(), persistentListOf(), persistentListOf()),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = definitions.toPersistentMap(),
    )
