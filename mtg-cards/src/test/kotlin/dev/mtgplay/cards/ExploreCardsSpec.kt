package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.Explore
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * Fanatical Offering and the Map token against their oracle text (CR 201–208) — including the two lines
 * that kept the card out of the pool: the additional cost, and the token whose ability explores.
 */
class ExploreCardsSpec :
    StringSpec({
        val alice = PlayerId(0)
        val bob = PlayerId(1)

        "CR 202/205: Fanatical Offering is a {1}{B} instant with no targets" {
            val printed = fanaticalOffering.characteristics
            printed.manaCost shouldBe ManaCost.parse("{1}{B}")
            printed.cardTypes shouldBe persistentSetOf(CardType.INSTANT)
            printed.powerToughness.shouldBeNull()
            fanaticalOffering.timing shouldBe TimingClass.INSTANT_SPEED
            fanaticalOffering.targetSpec shouldBe TargetSpec.None
        }

        "CR 601.2b: the additional cost sacrifices one artifact or creature" {
            fanaticalOffering.additionalCost shouldBe
                AdditionalCost.Sacrifice(
                    count = 1,
                    filter = SacrificeFilter(persistentSetOf(CardType.ARTIFACT, CardType.CREATURE)),
                )
        }

        "CR 120.1/111.1: resolving draws two cards and creates one Map token" {
            val library = List(3) { CardRef("Swamp") }
            val state = boardWithLibrary(alice, bob, library)
            val resolved =
                fanaticalOffering.resolution.resolve(state, ResolutionContext(alice, persistentListOf()))
            resolved.players.getValue(alice).hand shouldHaveSize FANATICAL_OFFERING_DRAW
            resolved.players.getValue(alice).library shouldHaveSize library.size - FANATICAL_OFFERING_DRAW
            resolved.sharedZones.battlefield.map { it.card } shouldBe listOf(CardRef.token("Map"))
        }

        "CR 111.1: the Map token is an artifact with the Map subtype and no mana cost" {
            val printed = mapToken.characteristics
            printed.manaCost.shouldBeNull()
            printed.cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
            printed.subtypes shouldBe persistentSetOf(Subtype("Map"))
            printed.powerToughness.shouldBeNull()
        }

        "CR 602.1/602.5d: its ability costs {1}, {T} and itself, targets a creature you control, sorcery-only" {
            val ability = mapToken.activatedAbilities.single()
            ability.cost shouldBe
                persistentListOf(
                    AbilityCost.Mana(ManaCost.parse("{1}")),
                    AbilityCost.TapSelf,
                    AbilityCost.SacrificeSelf,
                )
            ability.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_YOU_CONTROL)
            // "Activate only as a sorcery."
            ability.timing shouldBe TimingClass.SORCERY_SPEED
        }

        "CR 701.40a: the whole of the ability is its explore clause" {
            val ability = mapToken.activatedAbilities.single()
            ability.explore shouldBe Explore
            // The ordinary effect is deliberately empty: explore's last sentence is a decision, and
            // ADR-004 keeps decisions out of a ResolutionEffect. The engine runs the branch.
            val state = boardWithLibrary(alice, bob, emptyList())
            ability.effect.resolve(state, ResolutionContext(alice, persistentListOf())) shouldBe state
        }
    })

/** A two-seat state with [library] as the first seat's library and an empty battlefield. */
private fun boardWithLibrary(
    alice: PlayerId,
    bob: PlayerId,
    library: List<CardRef>,
): GameState {
    var nextId = 0L
    val cards = library.map { GameObject(ObjectId(nextId), it, alice).also { _ -> nextId += 1 } }

    fun seat(cardsIn: List<GameObject>) =
        PlayerState(
            life = 20,
            library = cardsIn.toPersistentList(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
        )
    return GameState(
        players = persistentMapOf(alice to seat(cards), bob to seat(emptyList())),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
