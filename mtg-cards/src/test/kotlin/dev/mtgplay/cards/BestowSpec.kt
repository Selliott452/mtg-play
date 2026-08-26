package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.CounterAmount
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.StaticCondition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * Nyxborn Hydra checked against its Oracle text (CR 201–208, CR 702.103, CR 614.1c). Every assertion
 * reads a printed line; how bestow is *cast*, attaches, and comes off is rules behaviour and is tested
 * in `mtg-rules` (`BestowSpec`) against fixtures.
 *
 * The two things driven through the engine here are the two the card owns rather than the framework: the
 * bonus is "+1/+1 **for each `+1/+1` counter on this Aura**", so the count is read off the Aura and not
 * off its host, and the Hydra's own printed reach and trample are a different ability from the reach and
 * trample it *grants*.
 */
class BestowSpec :
    StringSpec({

        "CR 201-208: Nyxborn Hydra is a {X}{G} Enchantment Creature - Hydra, a 0/1 with reach and trample" {
            val printed = nyxbornHydra.characteristics
            printed.name shouldBe "Nyxborn Hydra"
            printed.manaCost shouldBe ManaCost.parse("{X}{G}")
            printed.supertypes.shouldBeEmpty()
            printed.cardTypes shouldBe persistentSetOf(CardType.ENCHANTMENT, CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Hydra"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 0, toughness = 1)
            printed.keywords shouldBe persistentSetOf(Keyword.REACH, Keyword.TRAMPLE)
        }

        "CR 702.103: \"Bestow {X}{G}{G}\" is the card's one casting permission" {
            nyxbornHydra.castingPermissions shouldBe
                persistentListOf(CastingPermission.Bestow(ManaCost.parse("{X}{G}{G}")))
            // CR 302.1: the *creature* spell is sorcery-speed and untargeted; the bestow cast's Aura
            // targeting line is put in force by the permission, not declared on the card.
            nyxbornHydra.timing shouldBe TimingClass.SORCERY_SPEED
            nyxbornHydra.targetSpec shouldBe TargetSpec.None
        }

        "CR 614.1c: \"This permanent enters with X +1/+1 counters on it\"" {
            val clause = nyxbornHydra.entersWithCounters.shouldNotBeNull()
            clause.counter shouldBe Counter.PLUS_ONE_PLUS_ONE
            // The X announced for whichever cost the spell was cast for (CR 107.3), never a constant.
            clause.amount shouldBe CounterAmount.AnnouncedX
        }

        "CR 702.103a: while attached to a creature it is an Aura enchantment and not a creature" {
            val typeChange = nyxbornHydra.staticContinuousEffects[0]
            typeChange.affects shouldBe AffectedSet.Self
            typeChange.condition shouldBe StaticCondition.AttachedToCreature
            typeChange.addedSubtypes shouldBe persistentSetOf(Subtype("Aura"))
            // The removal is the half that keeps it out of combat; without it this is a 0/1 creature
            // that can attack and block while enchanting something (ADR-005).
            typeChange.removedCardTypes shouldBe persistentSetOf(CardType.CREATURE)
        }

        "the printed Aura line grants reach and trample and is unconditional on its affected set" {
            val auraLine = nyxbornHydra.staticContinuousEffects[1]
            auraLine.affects shouldBe AffectedSet.Enchanted
            auraLine.grantedKeywords shouldBe persistentSetOf(Keyword.REACH, Keyword.TRAMPLE)
            // No condition: [AffectedSet.Enchanted] is empty for a permanent attached to nothing, which
            // is the whole gate. A second one would be a second answer to the same question.
            auraLine.condition shouldBe null
        }

        "CR 613.3c: the bonus counts +1/+1 counters on the Aura itself, not on the creature it enchants" {
            val bonus = nyxbornHydra.staticContinuousEffects[1].powerMod
            // Two counters on the Aura and none on the host: the bonus is +2/+2. Reading the host's
            // counters instead would be a different and much stronger card.
            evaluate(bonus, auraOnHost(auraCounters = 2, hostCounters = 5), AURA_ID) shouldBe 2
            // The mirror: five counters on the host contribute nothing.
            nyxbornHydra.staticContinuousEffects[1].toughnessMod shouldBe bonus
        }

        "CR 613: a bestowed Hydra makes its host bigger and is not itself a creature" {
            val state = auraOnHost(auraCounters = 3, hostCounters = 0)
            val host = layeredCharacteristics(state, HOST_ID)
            host.power shouldBe HOST_SIZE + 3
            host.toughness shouldBe HOST_SIZE + 3
            host.keywords shouldBe persistentSetOf(Keyword.REACH, Keyword.TRAMPLE)

            val aura = layeredCharacteristics(state, AURA_ID)
            aura.cardTypes shouldBe persistentSetOf(CardType.ENCHANTMENT)
            aura.subtypes shouldBe persistentSetOf(Subtype("Hydra"), Subtype("Aura"))
        }
    })

private const val BESTOW_LIFE: Int = 20

private const val HOST_SIZE: Int = 2

private val HYDRA = CardRef("Nyxborn Hydra")

private val AURA_ID = ObjectId(0)

private val HOST_ID = ObjectId(1)

private val bestowAlice = PlayerId(0)

private val bestowBob = PlayerId(1)

/** The value of a [Magnitude] in [state] for the effect generated by [source] (CR 613.3c). */
private fun evaluate(
    magnitude: Magnitude,
    state: GameState,
    source: ObjectId,
): Int = (magnitude as Magnitude.Dynamic).evaluate(state, source)

/** A board with a bestowed Hydra attached to a 2/2, each carrying the given `+1/+1` counters. */
private fun auraOnHost(
    auraCounters: Int,
    hostCounters: Int,
): GameState =
    GameState(
        players = persistentMapOf(bestowAlice to bestowSeat(), bestowBob to bestowSeat()),
        turn = Turn(bestowAlice, 5, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield =
                    persistentListOf(
                        GameObject(
                            AURA_ID,
                            HYDRA,
                            bestowAlice,
                            attachedTo = HOST_ID,
                            counters = plusOneCounters(auraCounters),
                        ),
                        GameObject(
                            HOST_ID,
                            CardRef("Grizzly Bears"),
                            bestowAlice,
                            counters = plusOneCounters(hostCounters),
                        ),
                    ),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 2,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )

private fun plusOneCounters(count: Int) =
    if (count == 0) persistentMapOf() else persistentMapOf<Counter, Int>(Counter.PLUS_ONE_PLUS_ONE to count)

private fun bestowSeat(): PlayerState =
    PlayerState(
        life = BESTOW_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )
