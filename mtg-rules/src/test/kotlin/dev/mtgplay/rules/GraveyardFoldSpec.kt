package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.event.GameEvent
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
import dev.mtgplay.rules.effect.exileGraveyard
import dev.mtgplay.rules.effect.returnRandomCardFromGraveyardToHand
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The two whole-graveyard effect primitives: `exileGraveyard` (CR 701.3a, CR 404 — "exile target player's
 * graveyard") and `returnRandomCardFromGraveyardToHand` (CR 400.7, CR 701.15 — "return a creature card at
 * random from your graveyard to your hand").
 *
 * The second is where ADR-006 is load-bearing: the pick is seeded, so these tests pin the seed and assert
 * the card, which is the only way a random effect can be a *contract* rather than a coin flip. The
 * `mtg-rules`-names-no-card rule holds — these are fixture cards.
 */
class GraveyardFoldSpec :
    StringSpec({
        "CR 701.3a: exiling a graveyard moves every card in it to exile, in graveyard order" {
            val exiled = exileGraveyard(foldState(aliceGraveyard = FULL_GRAVEYARD), alice)

            exiled.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            exiled.sharedZones.exile
                .map { it.card.name } shouldContainExactly FULL_GRAVEYARD
        }

        "CR 400.7: each card a graveyard exile takes becomes a new object and is narrated on its own" {
            val start = foldState(aliceGraveyard = FULL_GRAVEYARD)
            val graveyardIds =
                start.players
                    .getValue(alice)
                    .graveyard
                    .map { it.id }
            val exiled = exileGraveyard(start, alice)

            exiled.sharedZones.exile.none { it.id in graveyardIds } shouldBe true
            // One event per card, not one per zone: a card exiled this way is indistinguishable in the log
            // from one exiled singly.
            exiled.events
                .filterIsInstance<GameEvent.GraveyardCardExiled>()
                .map { it.card.name } shouldContainExactly FULL_GRAVEYARD
        }

        "CR 404: a graveyard exile touches only the named player's graveyard" {
            val start = foldState(aliceGraveyard = FULL_GRAVEYARD, bobGraveyard = listOf(FIXTURE_BEAR))
            val exiled = exileGraveyard(start, alice)

            exiled.players
                .getValue(bob)
                .graveyard
                .map { it.card.name } shouldContainExactly listOf(FIXTURE_BEAR)
        }

        "CR 115.1a: exiling an empty graveyard is legal and does nothing — the target is a player" {
            val start = foldState(aliceGraveyard = emptyList())

            // Not an error and not a special case: a player is always a legal target, so the effect resolves
            // and finds nothing to move.
            exileGraveyard(start, alice) shouldBeSameInstanceAs start
        }

        "ADR-006: a random graveyard return draws from the match PRNG — the seed decides the card" {
            // Three eligible creatures, so three seeds that reduce to three different indices pin the draw
            // against the frozen splitmix64 contract rather than against 'some card came back'.
            returnedCreature(seed = 3) shouldBe FIXTURE_BEAR
            returnedCreature(seed = 0) shouldBe FIXTURE_WALL
            returnedCreature(seed = 1) shouldBe FIXTURE_GIANT
        }

        "ADR-006: a random graveyard return advances the generator, so a replay reproduces it" {
            val start = foldState(aliceGraveyard = FULL_GRAVEYARD, seed = 3)
            val returned = returnRandomCardFromGraveyardToHand(start, alice, GraveyardCardRestriction.CREATURE)

            (returned.rng == start.rng) shouldBe false
            // Pure per ADR-002: the same input state always yields the same successor.
            returnRandomCardFromGraveyardToHand(start, alice, GraveyardCardRestriction.CREATURE) shouldBe returned
        }

        "CR 400.7: the returned card leaves the graveyard for the hand as a new object" {
            val start = foldState(aliceGraveyard = FULL_GRAVEYARD, seed = 3)
            val graveyardIds =
                start.players
                    .getValue(alice)
                    .graveyard
                    .map { it.id }
            val returned = returnRandomCardFromGraveyardToHand(start, alice, GraveyardCardRestriction.CREATURE)

            val hand = returned.players.getValue(alice).hand
            hand.single().card.name shouldBe FIXTURE_BEAR
            (hand.single().id in graveyardIds) shouldBe false
            // Only the chosen card left; the non-creature cards and the other creatures stay put.
            returned.players
                .getValue(alice)
                .graveyard
                .map { it.card.name } shouldContainExactly FULL_GRAVEYARD.filterNot { it == FIXTURE_BEAR }
        }

        "CR 115.1: a random return never picks a card the restriction excludes, at any seed" {
            // Sweeping the seeds is how a filter that silently admitted the instant gets caught rather than
            // hidden behind one lucky draw — and it pins that every creature really is reachable.
            val drawn = (0L until SEED_SWEEP).map { returnedCreature(it) }.toSet()

            drawn shouldBe setOf(FIXTURE_BEAR, FIXTURE_WALL, FIXTURE_GIANT)
        }

        "CR 608.2: a return with no eligible card does nothing and draws no entropy" {
            val start = foldState(aliceGraveyard = listOf(FIXTURE_BOLT), seed = 3)
            val returned = returnRandomCardFromGraveyardToHand(start, alice, GraveyardCardRestriction.CREATURE)

            // Deliberately the *same* state, generator included: an empty-handed activation must not
            // desynchronise a replay from one where the ability found a card.
            returned shouldBeSameInstanceAs start
        }
    })

/** The card a creature-restricted random return brings back from [FULL_GRAVEYARD] under [seed]. */
private fun returnedCreature(seed: Long): String =
    returnRandomCardFromGraveyardToHand(
        foldState(aliceGraveyard = FULL_GRAVEYARD, seed = seed),
        alice,
        GraveyardCardRestriction.CREATURE,
    ).players
        .getValue(alice)
        .hand
        .single()
        .card.name

private const val FIXTURE_BEAR = "Fold Bear"
private const val FIXTURE_WALL = "Fold Wall"
private const val FIXTURE_GIANT = "Fold Giant"
private const val FIXTURE_BOLT = "Fold Bolt"

/** Three creature cards and one instant, so a creature-restricted return has three candidates of four. */
private val FULL_GRAVEYARD = listOf(FIXTURE_BEAR, FIXTURE_BOLT, FIXTURE_WALL, FIXTURE_GIANT)

/** How many seeds the restriction sweep walks; enough that a mis-scoped filter cannot hide behind luck. */
private const val SEED_SWEEP = 40L

private fun foldCard(
    name: String,
    type: CardType,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(type),
                subtypes = persistentSetOf(),
                powerToughness = if (type == CardType.CREATURE) PrintedPowerToughness(2, 2) else null,
            )
    }

private val foldRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef(FIXTURE_BEAR) to foldCard(FIXTURE_BEAR, CardType.CREATURE),
        CardRef(FIXTURE_WALL) to foldCard(FIXTURE_WALL, CardType.CREATURE),
        CardRef(FIXTURE_GIANT) to foldCard(FIXTURE_GIANT, CardType.CREATURE),
        CardRef(FIXTURE_BOLT) to foldCard(FIXTURE_BOLT, CardType.INSTANT),
    )

/** A two-player state whose only interesting contents are the two graveyards. */
private fun foldState(
    aliceGraveyard: List<String>,
    bobGraveyard: List<String> = emptyList(),
    seed: Long = 0,
): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val aliceZone = objects(aliceGraveyard, alice)
    val bobZone = objects(bobGraveyard, bob)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = aliceZone,
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = bobZone,
                    ),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(seed),
        events = persistentListOf(),
        definitions = foldRegistry.toPersistentMap(),
    )
}
