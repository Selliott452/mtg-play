package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.StaticCondition
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.engine.layeredCharacteristics
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * Pinnacle Kill-Ship checked against its Oracle text (CR 201–208, CR 602, CR 604.3). Every assertion
 * here reads a printed line; how the tap cost is gathered and paid is rules behaviour and is tested in
 * `mtg-rules` (`AbilityTapCostSpec`) against fixtures.
 *
 * The one thing this suite does drive through the engine is the **threshold**, because "it's an artifact
 * creature at 7+" is the printed line and the number seven is the card's, not the framework's.
 */
class SpacecraftSpec :
    StringSpec({

        "CR 201-208.1b: Pinnacle Kill-Ship is a {7} Artifact - Spacecraft printed 7/7 and not a creature" {
            val printed = pinnacleKillShip.characteristics
            printed.name shouldBe "Pinnacle Kill-Ship"
            printed.manaCost shouldBe ManaCost.parse("{7}")
            printed.supertypes.shouldBeEmpty()
            // CR 208.1b: the P/T box is printed on a card that is not a creature card.
            printed.cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
            printed.subtypes shouldBe persistentSetOf(Subtype("Spacecraft"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 7, toughness = 7)
            printed.keywords.shouldBeEmpty()
        }

        "CR 301.1: the artifact spell itself is sorcery-speed and untargeted" {
            pinnacleKillShip.timing shouldBe TimingClass.SORCERY_SPEED
            pinnacleKillShip.targetSpec shouldBe TargetSpec.None
        }

        "CR 603.6a: \"When this Spacecraft enters, it deals 10 damage to up to one target creature\"" {
            val trigger = pinnacleKillShip.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            trigger.targetSpec shouldBe
                TargetSpec.TargetPermanent(
                    restriction = PermanentRestriction.CREATURE,
                    count = TargetCount.UpTo(1),
                )
            PINNACLE_KILL_SHIP_DAMAGE shouldBe 10
        }

        "CR 602.1: Station's whole cost is \"Tap another creature you control\" - no mana and no {T}" {
            val station = pinnacleKillShip.activatedAbilities.single()
            station.cost shouldBe
                persistentListOf(
                    AbilityCost.TapPermanentYouControl(
                        filter = PermanentFilter(cardType = CardType.CREATURE, controlledByYou = true),
                        another = true,
                    ),
                )
            // The absence of a {T} component is a printed fact: a Kill-Ship may be stationed the turn
            // it lands, and stationed again every turn after.
            station.cost.none { it == AbilityCost.TapSelf } shouldBe true
        }

        "CR 602.5d: \"Station only as a sorcery\"" {
            pinnacleKillShip.activatedAbilities.single().timing shouldBe TimingClass.SORCERY_SPEED
        }

        "CR 604.3: both static abilities are conditioned on seven charge counters on the Spacecraft" {
            val statics = pinnacleKillShip.staticContinuousEffects
            statics.size shouldBe 2
            val threshold = StaticCondition.CountersOnSelf(Counter.Charge, PINNACLE_KILL_SHIP_STATION_THRESHOLD)
            statics.forEach {
                it.affects shouldBe AffectedSet.Self
                it.condition shouldBe threshold
            }
            // "It's an artifact creature at 7+" — a type *addition* (CR 205.1b), never a replacement.
            statics[0].addedCardTypes shouldBe persistentSetOf(CardType.CREATURE)
            // The separate printed "7+ | Flying" ability line.
            statics[1].grantedKeywords shouldBe persistentSetOf(Keyword.FLYING)
            PINNACLE_KILL_SHIP_STATION_THRESHOLD shouldBe 7
        }

        "CR 613: a Kill-Ship is an artifact with a 7/7 box below seven counters and a flier at seven" {
            val six = layeredCharacteristics(killShipState(counters = 6), KILL_SHIP_ID)
            six.cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
            six.keywords.contains(Keyword.FLYING) shouldBe false
            // CR 208.1b: the printed numbers are there all along; only the type arrives later.
            six.power shouldBe 7
            six.toughness shouldBe 7

            val seven = layeredCharacteristics(killShipState(counters = 7), KILL_SHIP_ID)
            seven.cardTypes shouldBe persistentSetOf(CardType.ARTIFACT, CardType.CREATURE)
            seven.keywords.contains(Keyword.FLYING) shouldBe true
            seven.power shouldBe 7
            seven.toughness shouldBe 7
        }
    })

private const val SPACECRAFT_LIFE: Int = 20

private val KILL_SHIP = CardRef("Pinnacle Kill-Ship")

private val KILL_SHIP_ID = ObjectId(0)

private val spacecraftAlice = PlayerId(0)

private val spacecraftBob = PlayerId(1)

/** A board with one Kill-Ship Alice controls, carrying [counters] charge counters. */
private fun killShipState(counters: Int): GameState =
    GameState(
        players =
            persistentMapOf(
                spacecraftAlice to spacecraftSeat(),
                spacecraftBob to spacecraftSeat(),
            ),
        turn = Turn(spacecraftAlice, 7, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield =
                    persistentListOf(
                        GameObject(
                            KILL_SHIP_ID,
                            KILL_SHIP,
                            spacecraftAlice,
                            counters = persistentMapOf<Counter, Int>(Counter.Charge to counters),
                        ),
                    ),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 1,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )

private fun spacecraftSeat(): PlayerState =
    PlayerState(
        life = SPACECRAFT_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )
