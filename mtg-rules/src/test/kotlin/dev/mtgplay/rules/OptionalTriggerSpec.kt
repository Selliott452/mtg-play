package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.putGraveyardCardOnTopOfOwnersLibrary
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The "you may" that wraps a whole triggered ability (CR 603.2, CR 601.3b —
 * [TriggeredAbility.optional]), and the CR 400.7 move it performs on acceptance
 * ([putGraveyardCardOnTopOfOwnersLibrary]). Mortuary Mire is the shape; the fixtures below are its
 * skeleton, because an engine test never names a real card.
 *
 * **The property that matters is the *ordering*.** A target is chosen as the ability goes on the stack
 * (CR 603.3d) and the "may" is answered when it resolves (CR 608.2c), so the two are a whole priority
 * round apart. Collapsing them into "up to one target" would settle both at CR 603.3d, deleting the
 * later decision and with it every line where the board changed in between (ADR-005).
 */
class OptionalTriggerSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        fun boardWithBody(): GameState =
            fixtureState(
                aliceSetup = SeatSetup(hand = listOf(MAY_LAND), graveyard = listOf(BODY)),
                bobSetup = SeatSetup(),
                definitions = fixtureDefinitions + optionalTriggerFixtures,
            )

        /** Plays the fixture land and answers its CR 603.3d target choice, stopping at the "may". */
        fun playAndTarget(start: GameState): AdvanceResult {
            var current = engine.advance(start, playLandDecision(pausedRequestOf(start), MAY_LAND))
            val targets = current.pending<DecisionRequest.ChooseTargets>()
            current = engine.advance(current.pausedState, Decision.SingleSelect(targets.id, 0))
            // Both seats pass, so the trigger resolves and reaches its "you may" (CR 608.2c).
            repeat(2) { current = engine.advance(current.pausedState, respondTo(current.pending())) }
            return current
        }

        "CR 603.3d: the target is chosen as the trigger is put on the stack, before any 'you may'" {
            val start = boardWithBody()
            val afterPlay = engine.advance(start, playLandDecision(pausedRequestOf(start), MAY_LAND))

            val request = afterPlay.pending<DecisionRequest.ChooseTargets>()
            request.options shouldHaveSize 1
            (request.options.single() is Target.CardInGraveyard) shouldBe true
            // Nothing is being asked about the "may" yet — that is a whole priority round away.
            afterPlay.pausedState.pendingOptionalTrigger shouldBe null
        }

        "CR 603.2: an optional ability pauses for its controller's yes/no when it resolves" {
            val paused = playAndTarget(boardWithBody())

            val pending = paused.pausedState.pendingOptionalTrigger
            pending.shouldNotBeNull()
            pending.decider shouldBe alice
            pending.sourceCard shouldBe CardRef(MAY_LAND)
            paused.pending<DecisionRequest.ChooseYesNo>().card shouldBe CardRef(MAY_LAND)
        }

        "CR 603.2: accepting performs the effect — the creature card goes on top of the library" {
            val paused = playAndTarget(boardWithBody())
            val request = paused.pending<DecisionRequest.ChooseYesNo>()
            val done =
                engine
                    .advance(paused.pausedState, Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.ACCEPT))
                    .pausedState

            done.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            done.players
                .getValue(alice)
                .library
                .first()
                .card shouldBe CardRef(BODY)
            done.pendingOptionalTrigger shouldBe null
            // Accepted or declined, the ability resolved and ceased to exist (CR 113.7a).
            done.events.filterIsInstance<GameEvent.TriggeredAbilityResolved>() shouldHaveSize 1
        }

        "CR 603.2: declining performs nothing at all, and is still a resolution rather than a fizzle" {
            val paused = playAndTarget(boardWithBody())
            val request = paused.pending<DecisionRequest.ChooseYesNo>()
            val done =
                engine
                    .advance(paused.pausedState, Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.DECLINE))
                    .pausedState

            done.players
                .getValue(alice)
                .graveyard
                .single()
                .card shouldBe CardRef(BODY)
            done.pendingOptionalTrigger shouldBe null
            done.events.filterIsInstance<GameEvent.TriggeredAbilityResolved>() shouldHaveSize 1
            done.events.filterIsInstance<GameEvent.AbilityFizzled>().shouldBeEmpty()
        }

        "CR 608.2b precedes CR 608.2c: a trigger with no legal target is never asked its 'you may'" {
            val start =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf(MAY_LAND)),
                    bobSetup = SeatSetup(),
                    definitions = fixtureDefinitions + optionalTriggerFixtures,
                )
            var current = engine.advance(start, playLandDecision(pausedRequestOf(start), MAY_LAND))
            repeat(2) { current = engine.advance(current.pausedState, respondTo(current.pending())) }

            current.pausedState.pendingOptionalTrigger shouldBe null
            current.pausedState.events.filterIsInstance<GameEvent.AbilityFizzled>() shouldHaveSize 1
        }

        "CR 400.7: the moved card is reborn on the library, and a card no longer there is a no-op" {
            val board = boardWithBody()
            val card =
                board.players
                    .getValue(alice)
                    .graveyard
                    .single()

            val moved = putGraveyardCardOnTopOfOwnersLibrary(board, card.id)
            moved.players
                .getValue(alice)
                .library
                .first()
                .card shouldBe CardRef(BODY)
            (
                moved.players
                    .getValue(alice)
                    .library
                    .first()
                    .id == card.id
            ) shouldBe false
            moved.events
                .filterIsInstance<GameEvent.CardPutOnLibrary>()
                .single()
                .onTop shouldBe true

            // CR 603.10: the same id a second time names nothing, and the effect honestly does nothing.
            putGraveyardCardOnTopOfOwnersLibrary(moved, card.id) shouldBe moved
        }
    })

/** A land fixture printing "When this land enters, you may put target creature card … on top of your library." */
private const val MAY_LAND: String = "Fixture May Land"

/** The creature card the fixture recovers. */
private const val BODY: String = "Fixture Recoverable Body"

/** The fixture creature's toughness (CR 208.2). */
private const val BODY_TOUGHNESS: Int = 2

private val optionalTriggerFixtures: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef(MAY_LAND) to
            object : CardDefinition {
                override val characteristics =
                    PrintedCharacteristics(
                        name = MAY_LAND,
                        manaCost = null,
                        supertypes = persistentSetOf(),
                        cardTypes = persistentSetOf(CardType.LAND),
                        subtypes = persistentSetOf(),
                        powerToughness = null,
                    )
                override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.BLACK)))
                override val triggeredAbilities =
                    persistentListOf(
                        TriggeredAbility(
                            condition = TriggerCondition.EnteredBattlefieldSelf,
                            optional = true,
                            targetSpec =
                                TargetSpec.CardInGraveyard(
                                    restriction = GraveyardCardRestriction.CREATURE,
                                    scope = GraveyardScope.YOURS,
                                ),
                            effect =
                                ResolutionEffect { state, context ->
                                    val target =
                                        context.targets.single() as? Target.CardInGraveyard
                                            ?: error("CR 115.1b: expected a graveyard card, got ${context.targets}")
                                    putGraveyardCardOnTopOfOwnersLibrary(state, target.id)
                                },
                        ),
                    )
            },
        CardRef(BODY) to
            object : SpellDefinition {
                override val characteristics =
                    PrintedCharacteristics(
                        name = BODY,
                        manaCost = ManaCost.parse("{1}{B}"),
                        supertypes = persistentSetOf(),
                        cardTypes = persistentSetOf(CardType.CREATURE),
                        subtypes = persistentSetOf(),
                        powerToughness = PrintedPowerToughness(power = 2, toughness = BODY_TOUGHNESS),
                    )
                override val timing = TimingClass.SORCERY_SPEED
                override val targetSpec = TargetSpec.None
                override val resolution = ResolutionEffect { state, _ -> state }
            },
    )
