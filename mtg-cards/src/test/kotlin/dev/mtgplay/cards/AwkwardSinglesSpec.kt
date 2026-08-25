package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.OptionalTapOrUntap
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TargetingRequirement
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * The printed half of `W8-G`'s three one-of-a-kind cards against the oracle cards (CR 201–208): each
 * type line and mana cost, the shape of every ability printed, and — for the two whose earlier drop
 * turned on a rules reading — the reading itself, asserted where the card declares it.
 *
 * Their *behaviour* is exercised against the real engine in `mtg-rules` (the skip framework, the
 * targeting requirement, and the tap-or-untap clause each have their own spec there). Nothing here
 * plays a game.
 */
class AwkwardSinglesSpec :
    StringSpec({

        "CR 201-208: Stonehorn Dignitary is a {3}{W} 1/4 Rhino Soldier that targets nothing as a spell" {
            with(stonehornDignitary.characteristics) {
                name shouldBe "Stonehorn Dignitary"
                manaCost shouldBe ManaCost.parse("{3}{W}")
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Rhino"), Subtype("Soldier"))
                powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 4)
                keywords.shouldBeEmpty()
            }
            // CR 302.1: the creature spell is sorcery-speed and untargeted; the *ability* targets.
            stonehornDignitary.timing shouldBe TimingClass.SORCERY_SPEED
            stonehornDignitary.targetSpec shouldBe TargetSpec.None
            stonehornDignitary.activatedAbilities.shouldBeEmpty()
            stonehornDignitary.manaAbilities.shouldBeEmpty()
        }

        "CR 603.6a / CR 115.1a: the enters trigger targets an opponent, never a player generally" {
            val trigger = stonehornDignitary.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            // "target opponent", not "target player": the controller is never a legal choice.
            trigger.targetSpec shouldBe TargetSpec.TargetOpponent
            trigger.zoneScope shouldBe TriggerZoneScope.Battlefield
        }

        "CR 500.10: resolving the trigger schedules one skip, and a second resolution schedules two" {
            val once = resolveStonehorn(twoPlayerState(), target = bob)
            once.players.getValue(bob).combatPhasesToSkip shouldBe 1
            // The scheduled skips *stack*: this is the Ephemerate line, and a boolean marker would
            // have absorbed the second one silently.
            val twice = resolveStonehorn(once, target = bob)
            twice.players.getValue(bob).combatPhasesToSkip shouldBe 2
            // Nothing is written about the controller.
            twice.players.getValue(alice).combatPhasesToSkip shouldBe 0
        }

        "CR 201-208: Standard Bearer is a {1}{W} 1/1 Human Flagbearer with no ability but its static one" {
            with(standardBearer.characteristics) {
                name shouldBe "Standard Bearer"
                manaCost shouldBe ManaCost.parse("{1}{W}")
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Human"), Subtype("Flagbearer"))
                powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 1)
                keywords.shouldBeEmpty()
            }
            standardBearer.triggeredAbilities.shouldBeEmpty()
            standardBearer.activatedAbilities.shouldBeEmpty()
            // CR 613.11: a rules-modifying continuous effect, so *not* a layer-system declaration.
            standardBearer.staticContinuousEffects.shouldBeEmpty()
        }

        "CR 601.2c: Standard Bearer's static ability is a targeting requirement naming the Flagbearer type" {
            standardBearer.targetingRequirements shouldContainExactly
                listOf(TargetingRequirement(Subtype("Flagbearer")))
            // It requires *a Flagbearer*, not itself — which is why a second one adds no constraint and
            // why a changeling satisfies it (CR 702.73a).
            standardBearer.targetingRequirements.single().subtype shouldBe
                standardBearer.characteristics.subtypes.first { it == Subtype("Flagbearer") }
        }

        "CR 201 / CR 702.8a: Sewer-veillance Cam is a {U} artifact castable at instant speed" {
            with(sewerVeillanceCam.characteristics) {
                name shouldBe "Sewer-veillance Cam"
                manaCost shouldBe ManaCost.parse("{U}")
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
                subtypes.shouldBeEmpty()
                powerToughness.shouldBeNull()
                // Flash is a timing permission, not a printed keyword — the pool's settled spelling.
                keywords.shouldBeEmpty()
            }
            sewerVeillanceCam.timing shouldBe TimingClass.INSTANT_SPEED
            sewerVeillanceCam.targetSpec shouldBe TargetSpec.None
        }

        "CR 603.6a / CR 603.6c: one printed ability, two conditions — entering and *leaving*" {
            sewerVeillanceCam.triggeredAbilities.map { it.condition } shouldContainExactly
                listOf(
                    TriggerCondition.EnteredBattlefieldSelf,
                    // The wider condition, not PutIntoGraveyardFromBattlefieldSelf: the Cam's own
                    // ability sacrifices it, and an affinity board also loses artifacts to exile.
                    TriggerCondition.LeftBattlefieldSelf,
                )
            sewerVeillanceCam.triggeredAbilities.map { it.zoneScope } shouldContainExactly
                listOf(TriggerZoneScope.Battlefield, TriggerZoneScope.Battlefield)
        }

        "CR 700.2: the tap-or-untap choice is a resolution clause, not a mode — both halves carry it" {
            sewerVeillanceCam.triggeredAbilities.forEach { trigger ->
                trigger.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
                trigger.optionalTapOrUntap shouldBe OptionalTapOrUntap
            }
            // The card prints no bulleted list and no "choose one", so it declares no modes at all —
            // the correction to `FW-TAPUNTAP`'s drop, asserted where it can regress.
            sewerVeillanceCam.modes.shouldBeEmpty()
        }

        "CR 602.1: the sacrifice ability costs {3}{U} plus sacrificing the Cam, and draws two" {
            val ability = sewerVeillanceCam.activatedAbilities.single()
            ability.cost shouldContainExactly
                listOf(AbilityCost.Mana(ManaCost.parse("{3}{U}")), AbilityCost.SacrificeSelf)
            ability.targetSpec shouldBe TargetSpec.None
            SEWER_VEILLANCE_CAM_DRAW shouldBe 2
        }
    })

private val alice = PlayerId(0)
private val bob = PlayerId(1)
private const val STARTING_LIFE: Int = 20

/** Resolves Stonehorn Dignitary's enters trigger against [target] (CR 603.6a). */
private fun resolveStonehorn(
    state: GameState,
    target: PlayerId,
): GameState =
    stonehornDignitary.triggeredAbilities
        .single()
        .effect
        .resolve(
            state,
            ResolutionContext(
                alice,
                persistentListOf(Target.Player(target)),
                sourceCard = CardRef("Stonehorn Dignitary"),
            ),
        )

/** A bare two-player state with empty zones; nothing asserted here needs a board. */
private fun twoPlayerState(): GameState =
    GameState(
        players = persistentMapOf(alice to emptySeat(), bob to emptySeat()),
        turn = Turn(alice, 5, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf()),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )

private fun emptySeat(): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )
