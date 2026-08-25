package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.arrangementsFor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * Surveil (CR 701.44a): a private look at the top of a library whose rest goes to the **graveyard**
 * rather than to the bottom of the library — the fourth destination docs/design/library-look.md §12
 * listed as a documented non-goal, and the one thing Conduit Pylons was still waiting on.
 *
 * The enumeration half is scry's ((N + 1)! arrangements, a free partition); everything that is new is on
 * the *destination*. Two properties are load-bearing and neither is true of a scry:
 * - a surveilled card **changes zones** (CR 400.7), so it is reborn under a fresh object id and narrated;
 * - the graveyard is **public** (CR 400.2), so that narration is the one place a private look becomes
 *   observable, while the cards kept on top stay silent (CR 701.14a).
 */
class SurveilSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 701.44a: surveil N admits exactly (N + 1)! arrangements, all distinct and all total" {
            listOf(1 to 2, 2 to 6, 3 to 24).forEach { (poolSize, expected) ->
                val options = arrangementsFor(LibraryLookMode.Surveil(poolSize), poolSize, matching = emptyList())
                options.size shouldBe expected
                options.distinct().size shouldBe expected
                options.all { it.isTotalOver(poolSize) } shouldBe true
            }
        }

        "CR 701.44a: every surveil arrangement partitions the pool between the graveyard and the top" {
            val options = arrangementsFor(LibraryLookMode.Surveil(2), 2, matching = emptyList())

            // Nothing ever goes to the hand or the bottom of the library — that is scry's destination.
            options.all { it.toHand.isEmpty() && it.toBottom.isEmpty() } shouldBe true
            val outcomes = options.map { it.toGraveyard to it.toTop }.toSet()
            outcomes shouldHaveSize options.size
            // "Any number" means both extremes are legal: bin everything, or keep everything.
            outcomes.contains(emptyList<Int>() to listOf(0, 1)) shouldBe true
            outcomes.contains(listOf(0, 1) to emptyList<Int>()) shouldBe true
        }

        "ADR-005: surveil 1 is exactly two options — keep it on top, or put it in the graveyard" {
            val options = arrangementsFor(LibraryLookMode.Surveil(1), 1, matching = emptyList())

            // The split walk runs from "bin none" upward, so keeping the card is index 0 and binning
            // it is index 1 — a fixed, seed-independent order (ADR-006).
            options.map { it.toTop to it.toGraveyard } shouldContainExactly
                listOf(listOf(0) to emptyList(), emptyList<Int>() to listOf(0))
        }

        "CR 701.44a: surveilling from an empty library is one forced no-op arrangement" {
            val options = arrangementsFor(LibraryLookMode.Surveil(1), 0, matching = emptyList())

            options shouldHaveSize 1
            options.single().isTotalOver(0) shouldBe true
        }

        "CR 701.44a and CR 400.7: the binned card leaves the library for the graveyard as a new object" {
            val (state, request) = surveilPause(engine)
            val looked = request.pool.single()

            val binIndex = request.options.indexOfFirst { it.toGraveyard.isNotEmpty() }
            val done = engine.advance(state, Decision.SingleSelect(request.id, binIndex)).pausedState

            val graveyard = done.players.getValue(alice).graveyard
            graveyard.single().card shouldBe looked.card
            // CR 400.7: a card that changes zones is a new object, so the looked-at id is gone.
            (graveyard.single().id == looked.objectId) shouldBe false
            done.players
                .getValue(alice)
                .library
                .none { it.id == looked.objectId } shouldBe true
            done.events.filterIsInstance<GameEvent.CardSurveilled>() shouldHaveSize 1
        }

        "CR 701.14a: the card kept on top never leaves the library, keeps its id, and is not narrated" {
            val (state, request) = surveilPause(engine)
            val looked = request.pool.single()

            val keepIndex = request.options.indexOfFirst { it.toTop.isNotEmpty() }
            val done = engine.advance(state, Decision.SingleSelect(request.id, keepIndex)).pausedState

            done.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            done.players
                .getValue(alice)
                .library
                .first()
                .id shouldBe looked.objectId
            // A card that only stayed put narrates nothing: its position is private to the decider.
            done.events.filterIsInstance<GameEvent.CardSurveilled>().shouldBeEmpty()
        }
    })

/**
 * Plays the surveil fixture land, passes both seats so its trigger resolves, and returns the state and
 * request of the arrangement pause it opens (CR 608.2c).
 */
private fun surveilPause(engine: GameEngine): Pair<GameState, DecisionRequest.ChooseLibraryArrangement> {
    val start =
        fixtureState(
            aliceSetup = SeatSetup(hand = listOf(SURVEIL_LAND)),
            bobSetup = SeatSetup(),
            definitions = fixtureDefinitions + surveilFixtures,
        )
    var current = engine.advance(start, playLandDecision(pausedRequestOf(start), SURVEIL_LAND))
    // Both seats pass, resolving the trigger on top of the stack (CR 608.1).
    repeat(2) { current = engine.advance(current.pausedState, respondTo(current.pending<DecisionRequest>())) }
    return current.pausedState to current.pending()
}

/** A land fixture printing "When this land enters, surveil 1." */
private const val SURVEIL_LAND: String = "Fixture Surveil Land"

private val surveilFixtures: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef(SURVEIL_LAND) to
            object : CardDefinition {
                override val characteristics =
                    PrintedCharacteristics(
                        name = SURVEIL_LAND,
                        manaCost = null,
                        supertypes = persistentSetOf(),
                        cardTypes = persistentSetOf(CardType.LAND),
                        subtypes = persistentSetOf(),
                        powerToughness = null,
                    )
                override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.COLORLESS)))
                override val triggeredAbilities =
                    persistentListOf(
                        TriggeredAbility(
                            condition = TriggerCondition.EnteredBattlefieldSelf,
                            effect = ResolutionEffect { state, _ -> state },
                            libraryLook = LibraryLook(LibraryLookMode.Surveil(1)),
                        ),
                    )
            },
    )
