package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggeredManaAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.manaSourceClasses
import dev.mtgplay.rules.engine.resolveTapForMana
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P6.2a as-enters colour choice (CR 614.12) and triggered mana ability (CR 605.1b), mirroring
 * Utopia Sprawl's "Enchant Forest. As this Aura enters, choose a colour. Whenever enchanted Forest is
 * tapped for mana, its controller adds an additional one mana of the chosen colour." The
 * `mtg-rules`-names-no-card rule holds.
 */
class TriggeredManaAbilitySpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val sprawl = CardRef("Fixture Sprawl")
        val forest = CardRef("Fixture Forest2")
        val mountain = CardRef("Fixture Mountain2")

        "CR 614.12: an Aura that chooses a colour as it enters pauses resolution for the choice" {
            val state = castSprawlState()
            var current = engine.advance(state, castDecision(pausedRequestOf(state), sprawl.name))
            // Target the Forest.
            val targets = current.pending<DecisionRequest.ChooseTargets>()
            val forestId =
                state.sharedZones.battlefield
                    .single { it.card == forest }
                    .id
            val forestTargetIndex = targets.options.indexOfFirst { it == Target.Permanent(forestId) }
            current = engine.advance(current.pausedState, Decision.SingleSelect(targets.id, forestTargetIndex))
            // Pay {1} with the Mountain.
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            // Both players pass; the Aura resolves and pauses for its colour choice.
            current = passUntilColorChoice(engine, current.pausedState)
            val colourRequest = current.pending<DecisionRequest.ChooseColor>()
            colourRequest.options shouldContainExactly Color.entries.toList()
            colourRequest.card shouldBe sprawl
        }

        "CR 614.12: the chosen colour is stored on the Aura as it enters the battlefield" {
            val state = castSprawlState()
            val entered = castSprawlChoosing(engine, state, Color.RED)
            val aura = entered.sharedZones.battlefield.single { it.card == sprawl }
            aura.chosenColor shouldBe Color.RED
            // It entered attached to the Forest.
            val forestId =
                entered.sharedZones.battlefield
                    .single { it.card == forest }
                    .id
            aura.attachedTo shouldBe forestId
        }

        "CR 605.1b: tapping the enchanted Forest for mana adds the chosen colour to the pool, no stack" {
            // An enchanted Forest (Sprawl chose RED) plus a bare Mountain; tap the Forest for {G}.
            val state = enchantedForestState(chosen = Color.RED)
            val forestClass =
                manaSourceClasses(state, alice).single {
                    it.key.card == forest
                }
            val tapped = resolveTapForMana(state, alice, forestClass.key, ManaType.GREEN)
            // Pool holds the primary green and the additional red — and the stack is untouched.
            tapped.players
                .getValue(alice)
                .manaPool
                .toList() shouldContainExactly listOf(ManaType.GREEN, ManaType.RED)
            tapped.sharedZones.stack.shouldHaveSize(0)
        }

        "CR 605.1b: an enchanted Forest is a distinct source class from a bare Forest" {
            val state = twoForestsState(chosen = Color.BLUE)
            val classes = manaSourceClasses(state, alice).filter { it.key.card == forest }
            // Two Forests, but two distinct classes: the bare one (no bonus) and the enchanted one (blue bonus).
            classes shouldHaveSize 2
            classes.map { it.key.bonus }.toSet() shouldBe setOf(emptyList(), listOf(ManaType.BLUE))
        }
    })

private fun passUntilColorChoice(
    engine: GameEngine,
    start: GameState,
): AdvanceResult {
    val startRequest =
        dev.mtgplay.rules.engine
            .pendingDecisionRequest(start) ?: error("start is not a paused state")
    var current: AdvanceResult = AdvanceResult.NeedsDecision(start, startRequest)
    while (true) {
        val paused = current as? AdvanceResult.NeedsDecision ?: return current
        val request = paused.request
        if (request !is DecisionRequest.ChooseAction) return current
        current = engine.advance(paused.state, passDecision(request))
    }
}

/** Casts the Sprawl targeting the Forest and chooses [color], returning the post-entry state. */
private fun castSprawlChoosing(
    engine: GameEngine,
    state: GameState,
    color: Color,
): GameState {
    var current = engine.advance(state, castDecision(pausedRequestOf(state), "Fixture Sprawl"))
    val targets = current.pending<DecisionRequest.ChooseTargets>()
    val forestId =
        state.sharedZones.battlefield
            .single { it.card == CardRef("Fixture Forest2") }
            .id
    val idx = targets.options.indexOfFirst { it == Target.Permanent(forestId) }
    current = engine.advance(current.pausedState, Decision.SingleSelect(targets.id, idx))
    current = engine.advance(current.pausedState, planDecision(current.pending()))
    current = passUntilColorChoice(engine, current.pausedState)
    val colourRequest = current.pending<DecisionRequest.ChooseColor>()
    val colourIndex = colourRequest.options.indexOf(color)
    return engine.advance(current.pausedState, Decision.SingleSelect(colourRequest.id, colourIndex)).pausedState
}

private val sprawlFixture: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Sprawl",
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(Subtype("Aura")),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.Enchantable(EnchantRestriction.FOREST)
        override val resolution = ResolutionEffect { state, _ -> state }
        override val choosesColorAsItEnters = true
        override val triggeredManaAbilities =
            persistentListOf<TriggeredManaAbility>(TriggeredManaAbility.AddChosenColor(1))
    }

private fun forestCard(): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Forest2",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(Subtype("Forest")),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.GREEN)))
    }

private fun mountainCard(): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Mountain2",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(Subtype("Mountain")),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.RED)))
    }

private val sprawlRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef("Fixture Sprawl") to sprawlFixture,
        CardRef("Fixture Forest2") to forestCard(),
        CardRef("Fixture Mountain2") to mountainCard(),
    )

/** Alice holding priority, Sprawl in hand, a Forest (enchant target) and a Mountain (mana) on battlefield. */
private fun castSprawlState(): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val field = objects(listOf("Fixture Forest2", "Fixture Mountain2"), alice)
    val hand = objects(listOf("Fixture Sprawl"), alice)
    return sprawlGameState(field, hand, nextId)
}

/** A battlefield with a Mountain and a Forest enchanted by a Sprawl that chose [chosen]. */
private fun enchantedForestState(chosen: Color): GameState {
    val forest = GameObject(ObjectId(0), CardRef("Fixture Forest2"), alice)
    val aura = GameObject(ObjectId(1), CardRef("Fixture Sprawl"), alice, attachedTo = forest.id, chosenColor = chosen)
    val mountain = GameObject(ObjectId(2), CardRef("Fixture Mountain2"), alice)
    return sprawlGameState(persistentListOf(forest, aura, mountain), persistentListOf(), 3)
}

/** A bare Forest plus a Forest enchanted by a Sprawl that chose [chosen]. */
private fun twoForestsState(chosen: Color): GameState {
    val bareForest = GameObject(ObjectId(0), CardRef("Fixture Forest2"), alice)
    val enchantedForest = GameObject(ObjectId(1), CardRef("Fixture Forest2"), alice)
    val aura =
        GameObject(ObjectId(2), CardRef("Fixture Sprawl"), alice, attachedTo = enchantedForest.id, chosenColor = chosen)
    return sprawlGameState(persistentListOf(bareForest, enchantedForest, aura), persistentListOf(), 3)
}

private fun sprawlGameState(
    battlefield: kotlinx.collections.immutable.PersistentList<GameObject>,
    aliceHand: kotlinx.collections.immutable.PersistentList<GameObject>,
    nextObjectId: Long,
): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = aliceHand,
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(battlefield, persistentListOf(), persistentListOf()),
        nextObjectId = nextObjectId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = sprawlRegistry.toPersistentMap(),
    )
