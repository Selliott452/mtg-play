package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * Writhing Chrysalis against its oracle text (CR 201–208), line by line — including the two lines whose
 * absence kept the card out of the pool for three waves.
 */
class CastTriggersSpec :
    StringSpec({
        val alice = PlayerId(0)
        val bob = PlayerId(1)

        "CR 202/205: a {2}{R}{G} Eldrazi Drone 2/3 with devoid and reach" {
            val printed = writhingChrysalis.characteristics
            printed.manaCost shouldBe ManaCost.parse("{2}{R}{G}")
            printed.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Eldrazi"), Subtype("Drone"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 2, toughness = 3)
            printed.keywords shouldBe persistentSetOf(Keyword.DEVOID, Keyword.REACH)
            writhingChrysalis.timing shouldBe TimingClass.SORCERY_SPEED
            writhingChrysalis.targetSpec shouldBe TargetSpec.None
        }

        "CR 702.114a: devoid makes it colourless despite the {R} and {G} in its cost" {
            // A characteristic-defining ability, so it holds in every zone — the reason this needs no
            // layer-5 effect and works while the card is in a hand or a graveyard.
            writhingChrysalis.characteristics.colors shouldBe emptySet()
        }

        "CR 603.2: the cast trigger is an ability of the spell, scoped to the stack" {
            val cast = writhingChrysalis.triggeredAbilities.first()
            cast.condition shouldBe TriggerCondition.CastSelf
            // The whole difference from an enters-the-battlefield trigger: this ability functions while
            // its source is a spell, so its tokens survive the spell being countered.
            cast.zoneScope shouldBe TriggerZoneScope.Stack
        }

        "CR 111.1: the cast trigger creates two Eldrazi Spawn tokens, each with its own mana ability" {
            val state = emptyBoard(alice, bob)
            val created =
                writhingChrysalis.triggeredAbilities
                    .first()
                    .effect
                    .resolve(state, ResolutionContext(alice, persistentListOf()))
            val spawn = created.sharedZones.battlefield.filter { it.card == CardRef.token("Eldrazi Spawn") }
            spawn shouldHaveSize WRITHING_CHRYSALIS_SPAWN
            // Two objects, not one with a count — each sacrifices separately for its own {C}.
            spawn.map { it.id }.distinct() shouldHaveSize WRITHING_CHRYSALIS_SPAWN
            eldraziSpawnToken.manaAbilities
                .single()
                .options shouldBe persistentListOf(ManaType.COLORLESS)
        }

        "CR 603.2: the sacrifice trigger watches another Eldrazi, from the battlefield" {
            val sacrifice = writhingChrysalis.triggeredAbilities[1]
            sacrifice.condition shouldBe TriggerCondition.YouSacrificedAnother(Subtype("Eldrazi"))
            sacrifice.zoneScope shouldBe TriggerZoneScope.Battlefield
        }

        "CR 122.1: the sacrifice trigger puts a +1/+1 counter on its own source" {
            val chrysalis = GameObject(ObjectId(0), CardRef("Writhing Chrysalis"), alice)
            val state = emptyBoard(alice, bob, chrysalis)
            val grown =
                writhingChrysalis.triggeredAbilities[1]
                    .effect
                    .resolve(state, ResolutionContext(alice, persistentListOf(), source = chrysalis.id))
            grown.sharedZones.battlefield
                .single()
                .counters[Counter.PLUS_ONE_PLUS_ONE] shouldBe 1
        }

        "CR 608.2b: a trigger whose source has already left the battlefield does nothing" {
            // The CR 603.10 case the "another" exclusion cannot cover: the trigger fired legally and the
            // Chrysalis died in response. Placing the counter is impossible, not partial.
            val departed = ObjectId(99)
            val state = emptyBoard(alice, bob)
            val unchanged =
                writhingChrysalis.triggeredAbilities[1]
                    .effect
                    .resolve(state, ResolutionContext(alice, persistentListOf(), source = departed))
            unchanged shouldBe state
        }
    })

/** A two-seat state whose battlefield holds [permanents] and whose definitions are the MVP registry. */
private fun emptyBoard(
    alice: PlayerId,
    bob: PlayerId,
    vararg permanents: GameObject,
): GameState {
    fun seat() =
        PlayerState(
            life = 20,
            library = persistentListOf(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
        )
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(*permanents),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = permanents.size.toLong(),
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
