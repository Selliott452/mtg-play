package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.engine.untapStepTurnBasedActions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/**
 * "Untap this permanent during each other player's untap step" (CR 502.2, CR 613.11) — Bender's
 * Waterskin's printed first line, declared as
 * [CardDefinition.untapsInEachOtherPlayersUntapStep] and read by the untap step's turn-based action.
 *
 * A **rules-modifying** static ability, not a continuous effect on any characteristic: it changes *which
 * permanents this step untaps*, and the CR 613 layer system has no layer for that. What the card buys is
 * a mana source that is untapped on both turns of a round, which is the whole reason it is played.
 *
 * The interaction with the CR 502.2 "doesn't untap" marker is asymmetric and that asymmetry is the CR's:
 * Sleep of the Dead names "its **controller's** next untap step", so it has nothing to say about an
 * untap step belonging to somebody else.
 */
class OtherPlayersUntapStepSpec :
    StringSpec({

        /** A board with [owner]'s tapped [card], stepped into [active]'s untap step. */
        fun untapStepWith(
            card: String,
            owner: PlayerId,
            active: PlayerId,
            skipsNextUntap: Boolean = false,
        ): GameState {
            val board =
                fixtureState(
                    aliceSetup = SeatSetup(battlefield = if (owner == alice) listOf(card) else emptyList()),
                    bobSetup = SeatSetup(battlefield = if (owner == bob) listOf(card) else emptyList()),
                    turn = Turn(active, 3, TurnPhase.BEGINNING, TurnStep.UNTAP),
                    holder = active,
                    definitions = fixtureDefinitions + untapFixtures,
                )
            val tapped =
                board.sharedZones.battlefield.map { obj ->
                    obj.copy(tapped = true, skipsNextUntapStep = skipsNextUntap)
                }
            return board.copy(
                sharedZones = board.sharedZones.copy(battlefield = tapped.toPersistentBattlefield()),
            )
        }

        fun untappedAfterStep(state: GameState): Boolean =
            untapStepTurnBasedActions(state)
                .sharedZones.battlefield
                .single()
                .tapped
                .not()

        "CR 502.2: an ordinary permanent stays tapped through another player's untap step" {
            untappedAfterStep(untapStepWith(PLAIN_ROCK, owner = alice, active = bob)) shouldBe false
        }

        "CR 502.2: an ordinary permanent untaps in its own controller's untap step" {
            untappedAfterStep(untapStepWith(PLAIN_ROCK, owner = alice, active = alice)) shouldBe true
        }

        "CR 613.11: a permanent that untaps in each other player's untap step does so" {
            val stepped = untapStepTurnBasedActions(untapStepWith(WATERSKIN, owner = alice, active = bob))

            stepped.sharedZones.battlefield
                .single()
                .tapped shouldBe false
            // The status change is narrated exactly as an ordinary untap is.
            stepped.events.filterIsInstance<GameEvent.ObjectUntapped>() shouldHaveSize 1
        }

        "CR 502.2: it also untaps normally in its controller's own untap step — the two are not exclusive" {
            untappedAfterStep(untapStepWith(WATERSKIN, owner = alice, active = alice)) shouldBe true
        }

        "CR 502.2: a 'doesn't untap' marker holds it down in its controller's step only" {
            val held = untapStepWith(WATERSKIN, owner = alice, active = alice, skipsNextUntap = true)
            untappedAfterStep(held) shouldBe false
            // The marker names its controller's step, so it neither applies to nor is spent by another's.
            val elsewhere = untapStepWith(WATERSKIN, owner = alice, active = bob, skipsNextUntap = true)
            untappedAfterStep(elsewhere) shouldBe true
            untapStepTurnBasedActions(elsewhere)
                .sharedZones.battlefield
                .single()
                .skipsNextUntapStep shouldBe true
        }

        "CR 502.2: an already-untapped permanent is unaffected and narrates nothing" {
            val board = untapStepWith(WATERSKIN, owner = alice, active = bob)
            val untapped =
                board.copy(
                    sharedZones =
                        board.sharedZones.copy(
                            battlefield =
                                board.sharedZones.battlefield
                                    .map { it.copy(tapped = false) }
                                    .toPersistentBattlefield(),
                        ),
                )

            untapStepTurnBasedActions(untapped)
                .events
                .filterIsInstance<GameEvent.ObjectUntapped>()
                .shouldHaveSize(0)
        }
    })

private fun List<GameObject>.toPersistentBattlefield() = toPersistentList()

/** The Bender's-Waterskin-shaped fixture. */
private const val WATERSKIN: String = "Fixture Waterskin"

/** The contrast: an artifact with no such static ability. */
private const val PLAIN_ROCK: String = "Fixture Plain Rock"

private fun artifactFixture(
    name: String,
    untapsElsewhere: Boolean,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{3}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val untapsInEachOtherPlayersUntapStep = untapsElsewhere
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.COLORLESS)))
    }

private val untapFixtures: Map<CardRef, CardDefinition> =
    listOf(
        artifactFixture(WATERSKIN, untapsElsewhere = true),
        artifactFixture(PLAIN_ROCK, untapsElsewhere = false),
    ).associateBy { CardRef(it.characteristics.name) }
