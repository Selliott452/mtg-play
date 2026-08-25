package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The two `FW-ZONETGT` cards (docs/design/graveyard-targeting.md §1): the printed box against the oracle
 * card (CR 201–208), and — the point of the packet — that each states the right pairing of
 * [GraveyardCardRestriction] noun and [GraveyardScope] possessive for the line it actually prints.
 *
 * The scope half is the easiest thing in the family to get wrong, because "your graveyard" and "a
 * graveyard" read almost identically and differ only in a possessive; a card encoded with the wrong one
 * plays perfectly on a board where only one graveyard has anything in it.
 */
class GraveyardTargetsSpec :
    StringSpec({
        "CR 201-208: Archaeomancer is a {2}{U}{U} 1/2 Human Wizard that targets nothing as a spell" {
            val printed = archaeomancer.characteristics
            printed.name shouldBe "Archaeomancer"
            printed.manaCost shouldBe ManaCost.parse("{2}{U}{U}")
            printed.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Human"), Subtype("Wizard"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 2)
            printed.keywords.shouldBeEmpty()
            // CR 302.1: the creature *spell* is sorcery-speed and untargeted — the ability targets.
            archaeomancer.timing shouldBe TimingClass.SORCERY_SPEED
            archaeomancer.targetSpec shouldBe TargetSpec.None
        }

        "CR 603.3d/404: Archaeomancer's ETB trigger targets an instant or sorcery in *your* graveyard" {
            val trigger = archaeomancer.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            trigger.targetSpec shouldBe
                TargetSpec.CardInGraveyard(
                    restriction = GraveyardCardRestriction.INSTANT_OR_SORCERY,
                    scope = GraveyardScope.YOURS,
                )
        }

        "CR 201-208/404: Pulse of Murasa is a {2}{G} instant targeting a creature or land card in *a* graveyard" {
            val printed = pulseOfMurasa.characteristics
            printed.name shouldBe "Pulse of Murasa"
            printed.manaCost shouldBe ManaCost.parse("{2}{G}")
            printed.cardTypes shouldBe persistentSetOf(CardType.INSTANT)
            printed.powerToughness shouldBe null
            pulseOfMurasa.timing shouldBe TimingClass.INSTANT_SPEED
            // "a graveyard", not "your graveyard" — the possessive the oracle text actually prints.
            pulseOfMurasa.targetSpec shouldBe
                TargetSpec.CardInGraveyard(
                    restriction = GraveyardCardRestriction.CREATURE_OR_LAND,
                    scope = GraveyardScope.ANY,
                )
            // The spell itself has no triggered abilities; the whole card is its resolution.
            pulseOfMurasa.triggeredAbilities shouldBe persistentListOf()
        }

        "CR 119.3: Pulse of Murasa's lifegain is the printed 6" {
            PULSE_OF_MURASA_LIFEGAIN shouldBe 6
        }

        "ADR-005: a resolution handed the wrong target shape fails loudly rather than guessing" {
            // The CR 608.2b re-check has already run by resolution time, so a non-graveyard target here
            // is an engine defect. The card must say so rather than silently doing nothing.
            val thrown =
                shouldThrow<IllegalStateException> {
                    pulseOfMurasa.resolution.resolve(
                        graveyardlessState(),
                        ResolutionContext(
                            controller = PlayerId(0),
                            targets = persistentListOf(Target.Player(PlayerId(0))),
                        ),
                    )
                }
            thrown.message.orEmpty() shouldContain "CR 115.1"
            thrown.message.orEmpty() shouldContain "Pulse of Murasa"
        }
    })

/** A minimal two-seat state with empty graveyards — enough for the loud-failure case, which never reads it. */
private fun graveyardlessState(): GameState =
    GameState(
        players = persistentMapOf(PlayerId(0) to emptySeat(), PlayerId(1) to emptySeat()),
        turn = Turn(PlayerId(0), 1, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 1,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = persistentMapOf(),
    )

/** A seated player with every zone empty (CR 103.1 starting life). */
private fun emptySeat(): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )

/** CR 103.3: the starting life total of a two-player game. */
private const val STARTING_LIFE: Int = 20
