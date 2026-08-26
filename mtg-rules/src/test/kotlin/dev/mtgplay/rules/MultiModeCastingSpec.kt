package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.ModalSpell
import dev.mtgplay.core.definition.ModeChoice
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellMode
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * **Modal arity above one** (CR 700.2a, CR 601.2b, CR 115.3) — the `W9-B` framework, driven end to end
 * through the engine on a fixture that prints "Choose up to two."
 *
 * Four properties, and each of them is a place a narrower implementation would have gone quietly wrong:
 *
 * 1. the mode decision is a **subset** whose bounds are the card's printed count, clamped to what the
 *    board offers, with **zero** a legal answer;
 * 2. two chosen modes open **two** target requests, in chosen order, each enumerated against its own
 *    bullet's restriction rather than against some union of them;
 * 3. the **same** graveyard card may answer both, which CR 115.3 permits explicitly and which the flat
 *    "two targets, all distinct" reading forbids;
 * 4. the cast record keeps the **per-mode split**, so the CR 608.2b re-check and the resolution can hand
 *    each bullet the targets it actually chose.
 */
class MultiModeCastingSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 700.2a: an 'up to two' card offers a subset of its modes, clamped to what is choosable" {
            // Two graveyard cards: one artifact, one creature. Both bullets are live, so both are
            // offered and the range is the printed 0..2.
            val request = modeRequest(engine, retrievalBoard(graveyard = listOf("Fixture Relic", "Fixture Bear")))
            request.options.map { it.modeIndex } shouldContainExactly listOf(0, 1)
            request.minimumCount shouldBe 0
            request.maximumCount shouldBe 2
        }

        "CR 601.2c: a bullet with no legal target is not offered, and the maximum clamps with it" {
            // Only an artifact in the graveyard: the creature bullet would dead-end at CR 601.2c, so it
            // is left out — and the maximum drops to 1, because "up to two" cannot demand a second
            // choice that does not exist (ADR-005).
            val request = modeRequest(engine, retrievalBoard(graveyard = listOf("Fixture Relic")))
            request.options.map { it.modeIndex } shouldContainExactly listOf(0)
            request.maximumCount shouldBe 1
            // The printed index survives the filtering, so a replay log names the same bullet always.
            request.options.single().text shouldBe "Target artifact card."
        }

        "CR 700.2: choosing no modes is legal, and the cast goes straight to its payment" {
            val start = retrievalBoard(graveyard = listOf("Fixture Relic", "Fixture Bear"))
            val request = modeRequest(engine, start)
            val afterModes = engine.advance(pausedFor(engine, start), Decision.MultiSelect(request.id, emptyList()))

            // No mode means no targeting line at all, so the CR 601.2c stage settles empty rather than
            // asking, and the next question is the payment plan (CR 601.2g).
            afterModes.pending<DecisionRequest.ChoosePaymentPlan>()
            val cast = afterModes.pausedState.pendingCast.shouldNotBeNull()
            cast.chosenModes.shouldNotBeNull() shouldContainExactly emptyList()
            cast.chosenTargets.shouldNotBeNull() shouldContainExactly emptyList()
            cast.modeTargets shouldContainExactly emptyList()
        }

        "CR 115.3: two chosen modes open two target requests, each against its own bullet" {
            val start = retrievalBoard(graveyard = listOf("Fixture Relic", "Fixture Bear"))
            val request = modeRequest(engine, start)
            var result = engine.advance(pausedFor(engine, start), Decision.MultiSelect(request.id, listOf(0, 1)))

            // The first question is the *first chosen* bullet's — artifact — and it offers only the
            // artifact card. A union of the two bullets would have offered both.
            val first = result.pending<DecisionRequest.ChooseTargets>()
            first.options.map { graveyardCardName(result.pausedState, it) } shouldContainExactly
                listOf("Fixture Relic")
            result = engine.advance(result.pausedState, Decision.SingleSelect(first.id, 0))

            // Then the creature bullet's, and it offers only the creature card.
            val second = result.pending<DecisionRequest.ChooseTargets>()
            second.options.map { graveyardCardName(result.pausedState, it) } shouldContainExactly listOf("Fixture Bear")
            result = engine.advance(result.pausedState, Decision.SingleSelect(second.id, 0))

            // The split is on the record, one list per chosen mode, in chosen order.
            val cast = result.pausedState.pendingCast.shouldNotBeNull()
            cast.modeTargets.map { line -> line.map { graveyardCardName(result.pausedState, it) } } shouldContainExactly
                listOf(listOf("Fixture Relic"), listOf("Fixture Bear"))
            // …and the flat list every other reader uses is its concatenation.
            cast.chosenTargets.shouldNotBeNull().size shouldBe 2
        }

        "CR 115.3: the same card may be named by two different bullets — one instance of 'target' each" {
            // The rule the brief for this packet had backwards. An artifact *creature* card satisfies
            // both bullets, and CR 115.3 says the same object "can be chosen once for each instance of
            // the word 'target'". So the second request still offers it after the first named it, and
            // the cast completes.
            val start = retrievalBoard(graveyard = listOf("Fixture Golem"))
            val request = modeRequest(engine, start)
            var result = engine.advance(pausedFor(engine, start), Decision.MultiSelect(request.id, listOf(0, 1)))

            val first = result.pending<DecisionRequest.ChooseTargets>()
            first.options.map { graveyardCardName(result.pausedState, it) } shouldContainExactly listOf("Fixture Golem")
            result = engine.advance(result.pausedState, Decision.SingleSelect(first.id, 0))

            val second = result.pending<DecisionRequest.ChooseTargets>()
            second.options.map { graveyardCardName(result.pausedState, it) } shouldContainExactly
                listOf("Fixture Golem")
            result = engine.advance(result.pausedState, Decision.SingleSelect(second.id, 0))

            val cast = result.pausedState.pendingCast.shouldNotBeNull()
            cast.modeTargets.map { it.size } shouldContainExactly listOf(1, 1)
            // Both lines name the *same* object, which is the whole point of this test.
            cast.modeTargets[0].single() shouldBe cast.modeTargets[1].single()
        }
    })

// ---- fixtures ----

/**
 * A `{1}` "Choose up to two" fixture: one artifact bullet and one creature bullet, over your graveyard.
 * Priced at one generic so a single Fixture Island pays it — what this spec measures is the *modes*, and
 * a coloured cost would make the board about mana instead.
 */
private val fixtureRetrieval: ModalSpell =
    object : ModalSpell {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Retrieval",
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val modeChoice = ModeChoice.upTo(2)
        override val modes =
            persistentListOf(
                retrievalMode("artifact", GraveyardCardRestriction.ARTIFACT),
                retrievalMode("creature", GraveyardCardRestriction.CREATURE),
            )
    }

private fun retrievalMode(
    noun: String,
    restriction: GraveyardCardRestriction,
): SpellMode =
    SpellMode(
        text = "Target $noun card.",
        targetSpec = TargetSpec.CardInGraveyard(restriction = restriction, scope = GraveyardScope.YOURS),
        // The effect is irrelevant to what this spec measures, and deliberately inert: a mode that moved
        // its target would change the *second* bullet's option list mid-cast, which is a different
        // property (CR 608.2b's "does as much as it can") and belongs to a different test.
        resolution = ResolutionEffect { state, _ -> state },
    )

/** An artifact **creature** card — the object that satisfies both of [fixtureRetrieval]'s bullets. */
private val fixtureGolem: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Golem",
                manaCost = ManaCost.parse("{2}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
                subtypes = persistentSetOf(),
                // CR 208.1: a creature card has a power/toughness box, and PrintedCharacteristics
                // enforces the pairing — an artifact creature is a creature.
                powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
            )
    }

private val retrievalDefinitions: Map<CardRef, CardDefinition> =
    modalDefinitions +
        listOf(fixtureRetrieval, fixtureGolem).associateBy { CardRef(it.characteristics.name) }

/** Alice holds Fixture Retrieval and a Mountain to pay for it, with [graveyard] in her graveyard. */
private fun retrievalBoard(graveyard: List<String>): GameState =
    fixtureState(
        aliceSetup =
            SeatSetup(
                hand = listOf("Fixture Retrieval"),
                battlefield = listOf("Fixture Island"),
                graveyard = graveyard,
            ),
        bobSetup = SeatSetup(),
        definitions = retrievalDefinitions,
    )

/** Begins the Fixture Retrieval cast on [start] and returns the paused state it left behind. */
private fun pausedFor(
    engine: GameEngine,
    start: GameState,
): GameState {
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
    return engine.advance(start, castDecision(window, "Fixture Retrieval")).pausedState
}

/** The CR 601.2b mode request Fixture Retrieval surfaces on [start]. */
private fun modeRequest(
    engine: GameEngine,
    start: GameState,
): DecisionRequest.ChooseModes {
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
    return engine.advance(start, castDecision(window, "Fixture Retrieval")).pending()
}

/** The printed name of the graveyard card a [Target.CardInGraveyard] names, for readable assertions. */
private fun graveyardCardName(
    state: GameState,
    target: Target,
): String {
    val id = (target as Target.CardInGraveyard).id
    return state.players.values
        .flatMap { it.graveyard }
        .first { it.id == id }
        .card.name
}
