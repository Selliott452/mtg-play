package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The CR 614.1c "this permanent enters tapped" self-replacement ([CardDefinition.entersTapped]) at both
 * of the engine's battlefield-entry points: the play-land special action (CR 116.2a, CR 305.1) and a
 * resolving permanent spell (CR 608.3). Uses fixtures — engine tests never name a real card; the
 * gauntlet lands that print the clause are played end-to-end in the acceptance module.
 *
 * The property under test is that the permanent is *already* tapped when it arrives, not tapped
 * afterwards: a replacement modifies the entering event, so no tap event is generated, nothing happens
 * in between, and the land funds no mana until it untaps.
 */
class EntersTappedSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        fun windowOf(state: GameState) = pausedRequestOf<DecisionRequest.ChooseAction>(state)

        fun handOf(vararg cards: String) =
            fixtureState(
                aliceSetup = SeatSetup(hand = cards.toList()),
                bobSetup = SeatSetup(),
                definitions = fixtureDefinitions + entersTappedFixtures,
            )

        "CR 614.1c: a land whose card says it enters tapped is on the battlefield tapped when played" {
            val start = handOf(TAPLAND)
            val state = engine.advance(start, playLandDecision(windowOf(start), TAPLAND)).pausedState

            val land = state.sharedZones.battlefield.single()
            land.card shouldBe CardRef(TAPLAND)
            land.tapped.shouldBeTrue()
        }

        "CR 614.1c: entering tapped is a replacement, not a tap — no ObjectTapped event is generated" {
            val start = handOf(TAPLAND)
            val state = engine.advance(start, playLandDecision(windowOf(start), TAPLAND)).pausedState

            state.events.filterIsInstance<GameEvent.ObjectTapped>().shouldBeEmpty()
            state.events.filterIsInstance<GameEvent.LandPlayed>().size shouldBe 1
        }

        "CR 110.5a: a land without the replacement still enters untapped — the default is untouched" {
            val start = handOf(UNTAPPED_LAND)
            val state = engine.advance(start, playLandDecision(windowOf(start), UNTAPPED_LAND)).pausedState

            state.sharedZones.battlefield
                .single()
                .tapped
                .shouldBeFalse()
        }

        "CR 601.2g: a land that entered tapped funds no payment plan, so its caster's cast is unaffordable" {
            // Fixture Bloom costs {G/U}; each fixture land is the only source of either colour, so the
            // cast is enumerated exactly when the played land arrived untapped (ADR-005, both directions).
            val tappedStart = handOf(TAPLAND, "Fixture Bloom")
            val afterTapped = engine.advance(tappedStart, playLandDecision(windowOf(tappedStart), TAPLAND))
            enumeratedCasts(windowOf(afterTapped.pausedState)).shouldBeEmpty()

            val untappedStart = handOf(UNTAPPED_LAND, "Fixture Bloom")
            val afterUntapped =
                engine.advance(untappedStart, playLandDecision(windowOf(untappedStart), UNTAPPED_LAND))
            enumeratedCasts(windowOf(afterUntapped.pausedState)) shouldBe listOf("Fixture Bloom")
        }

        "CR 502.1: the land untaps in its controller's next untap step and then funds the cast" {
            val start = handOf(TAPLAND, "Fixture Bloom")
            var current = engine.advance(start, playLandDecision(windowOf(start), TAPLAND))
            var steps = 0
            while (current.pausedState.turn.number < ALICE_NEXT_TURN && steps < MAX_PASS_STEPS) {
                current = engine.advance(current.pausedState, respondTo(current.pending<DecisionRequest>()))
                steps += 1
            }
            current.pausedState.turn.number shouldBe ALICE_NEXT_TURN

            current.pausedState.sharedZones.battlefield
                .single { it.card == CardRef(TAPLAND) }
                .tapped
                .shouldBeFalse()
        }

        "CR 608.3 and CR 614.1c: a resolving permanent spell that enters tapped enters tapped" {
            val ref = CardRef(TAPPED_GOLEM)
            val base = handOf()
            val stackObject = GameObject(ObjectId(base.nextObjectId), ref, alice)
            val start =
                base.copy(
                    sharedZones =
                        base.sharedZones.copy(
                            stack =
                                persistentListOf(
                                    StackEntry.Spell(stackObject, alice, persistentListOf(), tappedGolem),
                                ),
                        ),
                    nextObjectId = base.nextObjectId + 1,
                )

            val entered = resolveTopOfStack(start).pausedState
            val permanent = entered.sharedZones.battlefield.single { it.card == ref }
            permanent.tapped.shouldBeTrue()
            // Entering tapped is not entering un-sick: CR 302.6 is untouched.
            permanent.summoningSick.shouldBeTrue()
        }
    })

/** A land fixture printing the CR 614.1c clause: "This land enters tapped. {T}: Add {G}." */
private const val TAPLAND: String = "Fixture Tapland"

/** The contrast fixture: "{T}: Add {U}" with no such clause, entering untapped by CR 110.5a. */
private const val UNTAPPED_LAND: String = "Fixture Untapped Land"

/** A creature-spell fixture that enters tapped — the permanent-resolution half of CR 614.1c. */
private const val TAPPED_GOLEM: String = "Fixture Tapped Golem"

/** The turn alice's next untap step falls on, starting from the turn-3 fixture state. */
private const val ALICE_NEXT_TURN: Int = 5

/** Runaway guard for the pass loop that walks to the next untap step. */
private const val MAX_PASS_STEPS: Int = 200

/** The tapped golem's printed power and toughness. */
private const val GOLEM_SIZE: Int = 2

/** A land fixture adding [produces], entering tapped exactly when [tapped]. */
private fun landFixture(
    name: String,
    produces: ManaType,
    tapped: Boolean,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(produces)))
        override val entersTapped = tapped
    }

/** A `{1}` 2/2 creature-spell fixture that enters tapped (CR 614.1c on a cast permanent). */
private val tappedGolem: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = TAPPED_GOLEM,
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(GOLEM_SIZE, GOLEM_SIZE),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val entersTapped = true
    }

/** The fixtures this spec registers, keyed by ref. */
private val entersTappedFixtures: Map<CardRef, CardDefinition> =
    listOf(
        landFixture(TAPLAND, ManaType.GREEN, tapped = true),
        landFixture(UNTAPPED_LAND, ManaType.BLUE, tapped = false),
        tappedGolem,
    ).associateBy { CardRef(it.characteristics.name) }
