package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.ChosenPowerSource
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.applyUntilEndOfTurn
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.effect.destroy
import dev.mtgplay.rules.effect.powerOfChosenSource
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The **non-consuming additional cost** (`W9-D`, CR 601.2b) and the CR 608.2h power read it feeds.
 * Fixture cards only; the `mtg-rules`-names-no-card rule holds.
 *
 * Three properties, and each of them is a way a plausible-looking encoding of this shape goes wrong:
 *
 * 1. **Nothing is consumed.** The named creature is still on the battlefield after the cast, and it may
 *    even have been tapped for mana to pay for the very spell that named it.
 * 2. **The pool spans two zones in one decision**, and the answer says which zone it came from — because
 *    the two are read back through different rules.
 * 3. **The value is calculated at resolution** (CR 608.2h): a pump between the cast and the resolution
 *    changes the answer, and a creature killed in response falls back to last known information rather
 *    than to zero or to a crash.
 */
class NonConsumingCostSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        /** alice: two Forests, the fixture in hand, a 3/3 on the battlefield, a 5/5 card in hand. */
        fun board(
            battlefield: List<String> = listOf("Fixture Forest", "Fixture Forest", "Fixture Bruiser"),
            hand: List<String> = listOf(NAMER, "Fixture Colossus"),
        ): GameState =
            fixtureState(
                SeatSetup(hand = hand, battlefield = battlefield),
                SeatSetup(battlefield = listOf("Fixture Bulwark")),
                definitions = namerDefinitions,
            )

        /** Casts the fixture at bob's Wall, answering the cost with option [optionIndex]. */
        fun castNaming(
            state: GameState,
            optionIndex: Int,
        ): GameState {
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            var current = engine.advance(state, castDecision(window, NAMER)).pausedState
            val targets = pausedRequestOf<DecisionRequest.ChooseTargets>(current)
            current =
                engine.advance(current, Decision.SingleSelect(targets.id, wallOption(current, targets))).pausedState
            val naming = pausedRequestOf<DecisionRequest.ChooseCostPowerSource>(current)
            current = engine.advance(current, Decision.MultiSelect(naming.id, listOf(optionIndex))).pausedState
            val plan = pausedRequestOf<DecisionRequest.ChoosePaymentPlan>(current)
            return engine.advance(current, planDecision(plan)).pausedState
        }

        "CR 601.2b: the pool is one list spanning two zones — battlefield creatures, then hand creatures" {
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(board())
            val cast = engine.advance(board(), castDecision(window, NAMER)).pausedState
            val targets = pausedRequestOf<DecisionRequest.ChooseTargets>(cast)
            val afterTarget =
                engine.advance(cast, Decision.SingleSelect(targets.id, wallOption(cast, targets))).pausedState

            val naming = pausedRequestOf<DecisionRequest.ChooseCostPowerSource>(afterTarget)
            naming.options.map { it.card.name } shouldContainExactly listOf("Fixture Bruiser", "Fixture Colossus")
            // The answer carries *which rule* reads it back, which an object id alone could not.
            naming.options[0].source.shouldBeChosenCreature()
            naming.options[1].source shouldBe ChosenPowerSource.RevealedCard(CardRef("Fixture Colossus"))
            // The displayed powers are the live layered one and the printed one respectively.
            naming.options.map { it.power } shouldContainExactly listOf(BRUISER_POWER, COLOSSUS_POWER)
            // The spell being cast is not offered as a card to reveal: it is no longer a card in hand.
            naming.options.map { it.card.name } shouldNotContain NAMER
            // Only alice's creatures — bob's Wall is a creature and is not in the pool.
            naming.options.map { it.card.name } shouldNotContain "Fixture Bulwark"
        }

        "CR 601.2b: naming a creature does not consume it — it is still on the battlefield afterwards" {
            val cast = castNaming(board(), optionIndex = 0)

            cast.sharedZones.battlefield.count { it.card == CardRef("Fixture Bruiser") } shouldBe 1
            cast.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldNotContain CardRef("Fixture Bruiser")
        }

        "CR 601.2b: revealing a card does not consume it — it is still in hand afterwards" {
            val cast = castNaming(board(), optionIndex = 1)

            cast.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly
                listOf(CardRef("Fixture Colossus"))
        }

        "CR 601.2b: the answer is fixed on the cast record as the spell goes on the stack" {
            val chosen = (castNaming(board(), 0).sharedZones.stack.single() as StackEntry.Spell).costPowerSource
            chosen.shouldBeChosenCreature()

            val revealed = (castNaming(board(), 1).sharedZones.stack.single() as StackEntry.Spell).costPowerSource
            revealed shouldBe ChosenPowerSource.RevealedCard(CardRef("Fixture Colossus"))
        }

        "CR 613: a named creature's power is the layered one, read at resolution" {
            val resolved = resolveTopOfStack(castNaming(board(), 0)).pausedState
            damageOnWall(resolved) shouldBe BRUISER_POWER
        }

        "CR 608.2h: a pump after the cast changes the damage, because the value is read at resolution" {
            val cast = castNaming(board(), 0)
            val bruiser =
                cast.sharedZones.battlefield
                    .single { it.card == CardRef("Fixture Bruiser") }
                    .id
            val pumped =
                applyUntilEndOfTurn(
                    cast,
                    bruiser,
                    ContinuousModification(powerMod = PUMP),
                    CardRef("Fixture Bloom"),
                )

            damageOnWall(resolveTopOfStack(pumped).pausedState) shouldBe BRUISER_POWER + PUMP
        }

        "CR 109.3: a revealed card's power is the printed one, which no continuous effect can change" {
            val resolved = resolveTopOfStack(castNaming(board(), 1)).pausedState
            damageOnWall(resolved) shouldBe COLOSSUS_POWER
        }

        "CR 113.7a: killing the named creature in response still deals its last known power" {
            val cast = castNaming(board(), 0)
            val bruiser =
                cast.sharedZones.battlefield
                    .single { it.card == CardRef("Fixture Bruiser") }
                    .id
            val killed = destroy(cast, bruiser)

            damageOnWall(resolveTopOfStack(killed).pausedState) shouldBe BRUISER_POWER
        }

        "CR 113.7a: shrinking the named creature *before* killing it is what actually blanks the spell" {
            val cast = castNaming(board(), 0)
            val bruiser =
                cast.sharedZones.battlefield
                    .single { it.card == CardRef("Fixture Bruiser") }
                    .id
            val shrunk =
                applyUntilEndOfTurn(
                    cast,
                    bruiser,
                    ContinuousModification(powerMod = -BRUISER_POWER),
                    CardRef("Fixture Bloom"),
                )
            val killed = destroy(shrunk, bruiser)

            // The last known power is the shrunken one, not the printed one — which is the whole reason
            // the value is captured at the departure rather than when the cost was paid.
            damageOnWall(resolveTopOfStack(killed).pausedState) shouldBe 0
        }

        "ADR-005: with nothing to name, the spell is not enumerated at all" {
            // No creature on alice's battlefield and no creature card in her hand.
            val barren =
                board(
                    battlefield = listOf("Fixture Forest", "Fixture Forest"),
                    hand = listOf(NAMER),
                )
            enumeratedCasts(pausedRequestOf<DecisionRequest.ChooseAction>(barren)) shouldNotContain NAMER
        }

        "CR 601.2b: a named mana creature may still be tapped for mana on the same cast" {
            // One Forest and one hasty Elf are the only sources, so paying {1}{G} *requires* tapping the
            // very creature the cost names. A sacrifice cost would have reserved it out of its own plans;
            // this one reserves nothing, because naming never spent it.
            val board =
                board(
                    battlefield = listOf("Fixture Forest", "Fixture Hasty Elf"),
                    hand = listOf(NAMER),
                )
            val cast = castNaming(board, optionIndex = 0)

            cast.sharedZones.battlefield
                .single { it.card == CardRef("Fixture Hasty Elf") }
                .tapped shouldBe true
            damageOnWall(resolveTopOfStack(cast).pausedState) shouldBe ELF_POWER
        }
    })

private fun ChosenPowerSource?.shouldBeChosenCreature() {
    (this is ChosenPowerSource.ChosenCreature) shouldBe true
}

/** The option index naming bob's Bulwark, which every scenario points the fixture at (CR 115.1b). */
private fun wallOption(
    state: GameState,
    request: DecisionRequest.ChooseTargets,
): Int {
    val wall =
        state.sharedZones.battlefield
            .single { it.card == CardRef("Fixture Bulwark") }
            .id
    val index = request.options.indexOfFirst { it == Target.Permanent(wall) }
    check(index >= 0) { "no Fixture Wall target in ${request.options}" }
    return index
}

/** The damage marked on bob's Bulwark, which every scenario points the fixture at. */
private fun damageOnWall(state: GameState): Int =
    state.sharedZones.battlefield
        .single { it.card == CardRef("Fixture Bulwark") }
        .damageMarked

private const val NAMER: String = "Fixture Namer"

private const val BRUISER_POWER: Int = 3

private const val COLOSSUS_POWER: Int = 6

private const val ELF_POWER: Int = 1

private const val PUMP: Int = 2

private const val BULWARK_TOUGHNESS: Int = 20

/** A vanilla creature fixture with a printed power, for the two halves of the naming pool. */
private fun vanilla(
    name: String,
    power: Int,
    toughness: Int,
): CardDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{2}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power = power, toughness = toughness),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/**
 * "Fixture Namer" — `{1}{G}` Sorcery, Monstrous Emergence's shape: a non-consuming additional cost, then
 * damage equal to the named source's power to target creature.
 */
private val fixtureNamer: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = NAMER,
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        override val additionalCost = AdditionalCost.ChooseCreatureOrRevealCreatureCard
        override val resolution =
            ResolutionEffect { state, context ->
                val named = context.costPowerSource ?: error("the cost named nothing")
                dealDamage(
                    state,
                    context.damageSource(),
                    Target.Permanent((context.targets.single() as Target.Permanent).id),
                    powerOfChosenSource(state, named).coerceAtLeast(0),
                )
            }
    }

private val namerDefinitions: Map<CardRef, CardDefinition> =
    fixtureDefinitions +
        listOf(
            fixtureNamer,
            vanilla("Fixture Bruiser", BRUISER_POWER, BRUISER_POWER),
            vanilla("Fixture Colossus", COLOSSUS_POWER, COLOSSUS_POWER),
            // The shared target: toughness far above anything named here, so no scenario's damage kills
            // it and every one is read off the same marked-damage counter (CR 120.3d).
            vanilla("Fixture Bulwark", 0, BULWARK_TOUGHNESS),
        ).associateBy { CardRef(it.characteristics.name) }
