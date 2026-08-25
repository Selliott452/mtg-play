package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * Lotleth Giant, the `FW-ABILTGT` demonstration card (docs/design/targeted-abilities.md §7): the
 * printed box against the oracle card (CR 201–208), and the first *ability* in the pool that targets
 * (CR 603.3d) — including that the target sits on the triggered ability, not on the creature spell.
 */
class LotlethGiantSpec :
    StringSpec({
        "CR 201-208: Lotleth Giant is a {6}{B} 6/5 Zombie Giant that targets nothing as a spell" {
            val printed = lotlethGiant.characteristics
            printed.name shouldBe "Lotleth Giant"
            printed.manaCost shouldBe ManaCost.parse("{6}{B}")
            printed.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Zombie"), Subtype("Giant"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 6, toughness = 5)
            // No printed keyword: the oracle card has neither trample nor any other (CR 702).
            printed.keywords.shouldBeEmpty()
            // CR 302.1: the creature *spell* is sorcery-speed and untargeted.
            lotlethGiant.timing shouldBe TimingClass.SORCERY_SPEED
            lotlethGiant.targetSpec shouldBe TargetSpec.None
        }

        "CR 603.3d: the enters-the-battlefield trigger is the half that targets, and it targets an opponent" {
            val trigger = lotlethGiant.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            // CR 115.1a/102.1: "target opponent", not "target player" — the controller is not a choice.
            trigger.targetSpec shouldBe TargetSpec.TargetOpponent
        }

        "CR 120.3a: the trigger deals one damage per creature card in its controller's graveyard" {
            // alice's graveyard: two creature cards (Grizzly Bears, Hill Giant) and one noncreature
            // (Lightning Bolt), so the trigger deals 2 to the targeted opponent.
            val state =
                graveyardState(
                    listOf(CardRef("Grizzly Bears"), CardRef("Lightning Bolt"), CardRef("Hill Giant")),
                )
            val resolved =
                lotlethGiant.triggeredAbilities
                    .single()
                    .effect
                    .resolve(
                        state,
                        // CR 120.1 + CR 113.7c: the trigger's damage source is its source object.
                        ResolutionContext(
                            alice,
                            persistentListOf(Target.Player(bob)),
                            sourceCard = CardRef("Lotleth Giant"),
                        ),
                    )
            resolved.players.getValue(bob).life shouldBe STARTING_LIFE - 2
            resolved.players.getValue(alice).life shouldBe STARTING_LIFE
        }

        "CR 120.8: with no creature card in the graveyard the trigger deals no damage at all" {
            val state = graveyardState(listOf(CardRef("Lightning Bolt")))
            val resolved =
                lotlethGiant.triggeredAbilities
                    .single()
                    .effect
                    .resolve(
                        state,
                        // CR 120.1 + CR 113.7c: the trigger's damage source is its source object.
                        ResolutionContext(
                            alice,
                            persistentListOf(Target.Player(bob)),
                            sourceCard = CardRef("Lotleth Giant"),
                        ),
                    )
            resolved shouldBe state
        }
    })

private val alice = PlayerId(0)
private val bob = PlayerId(1)
private const val STARTING_LIFE: Int = 20

/** A two-player state with the named cards in alice's graveyard, defined by the real registry. */
private fun graveyardState(graveyard: List<CardRef>): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard =
                            graveyard
                                .mapIndexed { index, card -> GameObject(ObjectId(index.toLong()), card, alice) }
                                .toPersistentList(),
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
            ),
        turn = Turn(alice, 5, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf()),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
