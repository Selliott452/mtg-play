package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * The printed lines of the three cards `W9-C` unblocked, read off their definitions: Searing Blaze's two
 * targeting instances and its landfall clause, Gorilla Shaman's `{X}{X}{1}` ability and its X-dependent
 * restriction, and Weather the Storm's keyword.
 *
 * The *behaviour* each of them needs — the second target request depending on the first, the announcement
 * preceding CR 601.2c, the copies on the stack — is pinned against fixtures in `mtg-rules`
 * (`DependentTargetSpec`, `StormSpec`), where the engine lives. What is pinned here is that these three
 * cards really declare those shapes, which is the half a framework test cannot see.
 */
class DependentTargetCardsSpec :
    StringSpec({
        val alice = PlayerId(0)
        val bob = PlayerId(1)

        "CR 601.2c: Searing Blaze prints two instances of the word 'target', the second dependent" {
            with(searingBlaze.characteristics) {
                name shouldBe "Searing Blaze"
                manaCost?.render() shouldBe "{R}{R}"
                cardTypes shouldBe persistentSetOf(CardType.INSTANT)
            }
            searingBlaze.timing shouldBe TimingClass.INSTANT_SPEED
            // "target player or planeswalker" — target player, since Pauper prints no planeswalker.
            searingBlaze.targetSpec shouldBe TargetSpec.TargetPlayer(TargetCount.ONE)
            // "target creature that player ... controls" — one further line, reading the first's answer.
            searingBlaze.additionalTargetSpecs shouldContainExactly
                listOf(TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_CONTROLLED_BY_TARGETED_PLAYER))
        }

        "CR 702.135a: Searing Blaze deals 1 to each target without landfall and 3 with it" {
            val state = blazeBoard(alice, bob, landsEntered = 0)
            val creature =
                state.sharedZones.battlefield
                    .first()
                    .id
            val targets = persistentListOf<Target>(Target.Player(bob), Target.Permanent(creature))
            val blaze = CardRef("Searing Blaze")
            val withoutLandfall =
                searingBlaze.resolution.resolve(state, ResolutionContext(alice, targets, sourceCard = blaze))
            withoutLandfall.players.getValue(bob).life shouldBe STARTING_LIFE - SEARING_BLAZE_DAMAGE

            // The same board with a land already entered this turn: the *same* spell deals 3 instead,
            // read at resolution rather than at cast.
            val landfallState = blazeBoard(alice, bob, landsEntered = 1)
            val withLandfall =
                searingBlaze.resolution.resolve(landfallState, ResolutionContext(alice, targets, sourceCard = blaze))
            withLandfall.players.getValue(bob).life shouldBe STARTING_LIFE - SEARING_BLAZE_LANDFALL_DAMAGE
            SEARING_BLAZE_LANDFALL_DAMAGE shouldBe 3
        }

        "CR 608.2b: Searing Blaze with only its player target left still deals that player its damage" {
            // The partial-fizzle case, from the resolution's side: the creature has gone, so only the
            // player is in the target list, and the spell still does what it can.
            val state = blazeBoard(alice, bob, landsEntered = 0)
            val playerOnly = persistentListOf<Target>(Target.Player(bob))
            val blaze = CardRef("Searing Blaze")
            val resolved =
                searingBlaze.resolution.resolve(state, ResolutionContext(alice, playerOnly, sourceCard = blaze))
            resolved.players.getValue(bob).life shouldBe STARTING_LIFE - SEARING_BLAZE_DAMAGE
        }

        "CR 107.3: Gorilla Shaman's ability costs {X}{X}{1} and targets by the announced mana value" {
            with(gorillaShaman.characteristics) {
                name shouldBe "Gorilla Shaman"
                manaCost?.render() shouldBe "{R}"
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Ape"), Subtype("Shaman"))
                powerToughness shouldBe PrintedPowerToughness(1, 1)
            }
            val ability = gorillaShaman.activatedAbilities.single()
            // The oracle cost is {X}{X}{1}, not {X}{X}: at X = 2 the ability costs five mana, not four.
            (ability.cost.single() as AbilityCost.Mana).cost.render() shouldBe "{X}{X}{1}"
            ability.targetSpec shouldBe
                TargetSpec.TargetPermanent(PermanentRestriction.NONCREATURE_ARTIFACT_WITH_MANA_VALUE_X)
            // No {T} in the cost, so summoning sickness never restricts it (CR 302.6).
            ability.cost.none { it == AbilityCost.TapSelf } shouldBe true
        }

        "CR 702.40a: Weather the Storm is an untargeted instant with storm" {
            with(weatherTheStorm.characteristics) {
                name shouldBe "Weather the Storm"
                manaCost?.render() shouldBe "{1}{G}"
                cardTypes shouldBe persistentSetOf(CardType.INSTANT)
            }
            weatherTheStorm.timing shouldBe TimingClass.INSTANT_SPEED
            weatherTheStorm.storm shouldBe true
            // Untargeted, which is exactly what makes CR 702.40a's "you may choose new targets for any of
            // the copies" vacuous rather than approximated on this card.
            weatherTheStorm.targetSpec shouldBe TargetSpec.None
            WEATHER_THE_STORM_LIFE shouldBe 3
        }

        "CR 119.3: each Weather the Storm resolution gains its controller 3 life" {
            val state = blazeBoard(alice, bob, landsEntered = 0)
            val resolved = weatherTheStorm.resolution.resolve(state, ResolutionContext(alice, persistentListOf()))
            resolved.players.getValue(alice).life shouldBe STARTING_LIFE + WEATHER_THE_STORM_LIFE
        }
    })

/** The life both seats start on in these fixtures (CR 103.3). */
private const val STARTING_LIFE: Int = 20

/** A minimal board: one of [bob]'s creatures, and [alice]'s landfall tally set to [landsEntered]. */
private fun blazeBoard(
    alice: PlayerId,
    bob: PlayerId,
    landsEntered: Int,
): GameState {
    val creature = GameObject(ObjectId(0), CardRef("Grizzly Bears"), bob)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                        landsEnteredThisTurn = landsEntered,
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
        sharedZones = SharedZones(persistentListOf(creature), persistentListOf(), persistentListOf()),
        nextObjectId = 1,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
