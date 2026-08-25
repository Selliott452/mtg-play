package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.effect.loseLife
import dev.mtgplay.rules.engine.opponentOf
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The `FW-ADDSAC` sacrifice-as-a-cost framework: an intrinsic
 * [AdditionalCost.Sacrifice] on a spell (CR 601.2b, paid at CR 601.2h) and an
 * [AbilityCost.Sacrifice] component of an activated ability (CR 602.1, paid at CR 602.2b). Both pick
 * their permanent from an **engine-enumerated** option list (ADR-005), and both interact with the mana
 * payment enumerated beside them (docs/design/mana-payment.md §2.2).
 *
 * The interaction is where the value of this spec is. Two properties are asserted against each other,
 * and getting either wrong is invisible without the other:
 * - **Sacrificing a permanent you tapped for mana is legal** (CR 601.2g precedes CR 601.2h), so a plan
 *   that taps the very land or artifact the cost then consumes must stay enumerated. Over-reserving
 *   here would delete a legal play from the action space silently, which is worse than crashing.
 * - **A permanent that produces mana by being _sacrificed_ cannot do both**, so it — and only it — is
 *   reserved out of the plans of the cost that chose it.
 *
 * `mtg-rules` names no card (PLAN.md §3), so every card here is a fixture; the linked-information
 * effect uses the opponent's life as an observable proxy for a resolution reading the cost's result.
 */
class SacrificeCostFrameworkSpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val trinket = CardRef("Fixture Trinket")
        val mountain = CardRef("Fixture Mountain")
        val spawn = CardRef("Fixture Mana Spawn")
        val prism = CardRef("Fixture Prism")

        // ---------------------------------------------------------------- the cast side (CR 601.2b)

        "CR 601.2b: an additional sacrifice cost surfaces an enumerated selection of only matching permanents" {
            val state = sacState(battlefield = listOf(mountain.name, trinket.name), hand = listOf(SAC_DRAW))
            val current = engine.advance(state, castDecision(pausedRequestOf(state), SAC_DRAW))
            val request = current.pending<DecisionRequest.ChooseSacrificesForCost>()
            request.count shouldBe 1
            // "An artifact or creature": the land on the battlefield is not an option.
            request.options.map { it.card } shouldBe listOf(trinket)
        }

        "CR 601.2b: a spell whose sacrifice cost cannot be paid is not enumerated at all (ADR-005)" {
            // Only a land on the battlefield, and the cost demands an artifact or creature.
            val state = sacState(battlefield = listOf(mountain.name), hand = listOf(SAC_DRAW))
            enumeratedCasts(pausedRequestOf(state)).contains(SAC_DRAW) shouldBe false
        }

        "CR 601.2g before CR 601.2h: a land tapped for mana may still be sacrificed to the same cast's cost" {
            // One land, which is both the only way to pay {R} and the only thing the cost can eat.
            // Over-reserving would make this legal play vanish from the action space.
            val state = sacState(battlefield = listOf(mountain.name), hand = listOf(LAND_SAC))
            var current = engine.advance(state, castDecision(pausedRequestOf(state), LAND_SAC))
            val request = current.pending<DecisionRequest.ChooseSacrificesForCost>()
            request.options.map { it.card } shouldBe listOf(mountain)
            current = engine.advance(current.pausedState, Decision.MultiSelect(request.id, listOf(0)))
            // The plan that taps the doomed land is still offered, and it executes.
            val payment = current.pending<DecisionRequest.ChoosePaymentPlan>()
            payment.options.isNotEmpty() shouldBe true
            val paid = engine.advance(current.pausedState, planDecision(payment)).pausedState
            // CR 701.17: the land is in its owner's graveyard, and the spell reached the stack.
            paid.sharedZones.battlefield
                .none { it.card == mountain } shouldBe true
            paid.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldBe listOf(mountain)
            paid.sharedZones.stack.size shouldBe 1
        }

        "CR 605.1a: a sacrifice-cost mana source chosen for the cost cannot also fund it, so the cast is absent" {
            // The Spawn's only mana ability is "Sacrifice this: Add {C}". It is the only creature the
            // cost can take *and* the only mana, so no (selection, plan) pair exists — ADR-005 says the
            // cast must be absent rather than offered and then thrown out of.
            val state = sacState(battlefield = listOf(spawn.name), hand = listOf(SPAWN_EATER))
            enumeratedCasts(pausedRequestOf(state)).contains(SPAWN_EATER) shouldBe false
        }

        "CR 601.2g: with other mana available the same cast is offered, and no plan spends the doomed Spawn" {
            val state = sacState(battlefield = listOf(spawn.name, mountain.name), hand = listOf(SPAWN_EATER))
            var current = engine.advance(state, castDecision(pausedRequestOf(state), SPAWN_EATER))
            val request = current.pending<DecisionRequest.ChooseSacrificesForCost>()
            request.options.map { it.card } shouldBe listOf(spawn)
            current = engine.advance(current.pausedState, Decision.MultiSelect(request.id, listOf(0)))
            val payment = current.pending<DecisionRequest.ChoosePaymentPlan>()
            payment.options.isNotEmpty() shouldBe true
            payment.options.forEach { plan -> plan.activations.none { it.sourceClass.card == spawn } shouldBe true }
            // ADR-005: every plan offered is one execution can carry out.
            payment.options.indices.forEach { choice ->
                val paid = engine.advance(current.pausedState, Decision.SingleSelect(payment.id, choice))
                paid.pausedState.sharedZones.stack.size shouldBe 1
            }
        }

        "CR 608.2h: the sacrificed permanent's identity reaches resolution as linked information" {
            // The fixture's resolution drains the opponent for the sacrificed card's mana value; the
            // permanent is gone by then, so only last-known information can answer.
            val state = sacState(battlefield = listOf(mountain.name, trinket.name), hand = listOf(SAC_DRAW))
            var current = engine.advance(state, castDecision(pausedRequestOf(state), SAC_DRAW))
            val request = current.pending<DecisionRequest.ChooseSacrificesForCost>()
            current = engine.advance(current.pausedState, Decision.MultiSelect(request.id, listOf(0)))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            val resolved = resolveTopOfStack(engine, current)
            (STARTING_LIFE - resolved.players.getValue(bob).life) shouldBe TRINKET_MANA_VALUE
        }

        // ----------------------------------------------------------- the ability side (CR 602.1)

        "CR 602.1: an ability's chosen-sacrifice cost offers only matching permanents, never its own source" {
            // The Bomber is a creature, so it does not match its own "Sacrifice an artifact" filter —
            // which is how the printed text excludes it, no 'another' restriction being printed.
            val state = sacState(battlefield = listOf(BOMBER, trinket.name, mountain.name), hand = emptyList())
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val index =
                window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == CardRef(BOMBER) }
            val current = engine.advance(state, Decision.SingleSelect(window.id, index))
            val request = current.pending<DecisionRequest.ChooseAbilitySacrifice>()
            request.count shouldBe 1
            request.options.map { it.card } shouldBe listOf(trinket)
        }

        "CR 602.1: an ability whose sacrifice cost cannot be paid is not enumerated (ADR-005)" {
            val state = sacState(battlefield = listOf(BOMBER, mountain.name), hand = emptyList())
            pausedRequestOf<DecisionRequest.ChooseAction>(state)
                .options
                .none { it is PriorityOption.ActivateAbility && it.card == CardRef(BOMBER) } shouldBe true
        }

        "CR 602.2b with CR 701.17: an artifact tapped for the ability's mana may then be sacrificed to it" {
            // The Prism is the only artifact and the only mana source, so the Cannon's "{1}, Sacrifice an
            // artifact" must tap it and then eat it. The ability-side twin of the land case above.
            val state = sacState(battlefield = listOf(CANNON, prism.name), hand = emptyList())
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val index =
                window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == CardRef(CANNON) }
            var current = engine.advance(state, Decision.SingleSelect(window.id, index))
            val request = current.pending<DecisionRequest.ChooseAbilitySacrifice>()
            request.options.map { it.card } shouldBe listOf(prism)
            current = engine.advance(current.pausedState, Decision.MultiSelect(request.id, listOf(0)))
            val payment = current.pending<DecisionRequest.ChoosePaymentPlan>()
            payment.options.isNotEmpty() shouldBe true
            val paid = engine.advance(current.pausedState, planDecision(payment)).pausedState
            paid.sharedZones.battlefield
                .none { it.card == prism } shouldBe true
            paid.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldBe listOf(prism)
            paid.sharedZones.stack.size shouldBe 1
        }

        "CR 605.1a: a sacrifice-cost mana source is not offered for the cost it would have to fund" {
            // The Spawn is the only creature and the only mana: reserving it leaves the {1} unpayable,
            // so it is not a candidate — and with no other candidate the ability is absent entirely.
            val state = sacState(battlefield = listOf(EATER_CANNON, spawn.name), hand = emptyList())
            pausedRequestOf<DecisionRequest.ChooseAction>(state)
                .options
                .none { it is PriorityOption.ActivateAbility && it.card == CardRef(EATER_CANNON) } shouldBe true
        }

        "CR 605.1a: once other mana exists the same Spawn becomes a legal sacrifice again" {
            val state = sacState(battlefield = listOf(EATER_CANNON, spawn.name, mountain.name), hand = emptyList())
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val index =
                window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == CardRef(EATER_CANNON) }
            (index >= 0) shouldBe true
            var current = engine.advance(state, Decision.SingleSelect(window.id, index))
            val request = current.pending<DecisionRequest.ChooseAbilitySacrifice>()
            request.options.map { it.card } shouldBe listOf(spawn)
            current = engine.advance(current.pausedState, Decision.MultiSelect(request.id, listOf(0)))
            val payment = current.pending<DecisionRequest.ChoosePaymentPlan>()
            payment.options.forEach { plan -> plan.activations.none { it.sourceClass.card == spawn } shouldBe true }
            val paid = engine.advance(current.pausedState, planDecision(payment)).pausedState
            paid.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldBe listOf(spawn)
        }
    })

private const val SAC_DRAW = "Fixture Sac Drain"
private const val LAND_SAC = "Fixture Land Sac"
private const val SPAWN_EATER = "Fixture Spawn Eater"
private const val BOMBER = "Fixture Bomber"
private const val CANNON = "Fixture Cannon"
private const val EATER_CANNON = "Fixture Eater Cannon"

/** The Fixture Trinket's printed mana value (CR 202.3) — what the linked-information drain reads. */
private const val TRINKET_MANA_VALUE: Int = 2

/** How much life the two ability fixtures drain on resolution. */
private const val ABILITY_DRAIN: Int = 1

/** "An artifact or creature" (CR 300.1). */
private val artifactOrCreature = SacrificeFilter(persistentSetOf(CardType.ARTIFACT, CardType.CREATURE))

/** Passes priority until the top of the stack has resolved; returns the resulting state. */
private fun resolveTopOfStack(
    engine: GameEngine,
    from: AdvanceResult,
): GameState {
    var current = from
    var request = pendingPassableAction(current)
    while (request != null) {
        current = engine.advance(current.pausedState, passDecision(request))
        request = pendingPassableAction(current)
    }
    return current.pausedState
}

/** The priority request [result] is paused on while something is still on the stack, else `null`. */
private fun pendingPassableAction(result: AdvanceResult): DecisionRequest.ChooseAction? {
    val paused = result as? AdvanceResult.NeedsDecision ?: return null
    val stack = paused.state.sharedZones.stack
    return if (stack.isEmpty()) null else paused.request as? DecisionRequest.ChooseAction
}

/** A sorcery fixture with an intrinsic sacrifice additional cost and no resolution of its own. */
private fun sacrificeSpell(
    name: String,
    manaCost: String,
    filter: SacrificeFilter,
    resolution: ResolutionEffect = ResolutionEffect { state, _ -> state },
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse(manaCost),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val additionalCost = AdditionalCost.Sacrifice(count = 1, filter = filter)
        override val resolution = resolution
    }

/** A permanent fixture carrying one activated ability, with no mana ability of its own. */
private fun permanentWithAbility(
    name: String,
    type: CardType,
    ability: ActivatedAbility,
): CardDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(type),
                subtypes = persistentSetOf(),
                powerToughness = if (type == CardType.CREATURE) PrintedPowerToughness(1, 1) else null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val activatedAbilities = persistentListOf(ability)
    }

/** The drain every ability fixture performs on resolution — an observable, untargeted effect. */
private val abilityDrain =
    ResolutionEffect { state, context -> loseLife(state, state.opponentOf(context.controller), ABILITY_DRAIN) }

private val sacrificeFixtures: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef("Fixture Trinket") to
            object : SpellDefinition {
                override val characteristics =
                    PrintedCharacteristics(
                        name = "Fixture Trinket",
                        manaCost = ManaCost.parse("{2}"),
                        supertypes = persistentSetOf(),
                        cardTypes = persistentSetOf(CardType.ARTIFACT),
                        subtypes = persistentSetOf(),
                        powerToughness = null,
                    )
                override val timing = TimingClass.SORCERY_SPEED
                override val targetSpec = TargetSpec.None
                override val resolution = ResolutionEffect { state, _ -> state }
            },
        CardRef(SAC_DRAW) to
            sacrificeSpell(SAC_DRAW, "{R}", artifactOrCreature) { state, context ->
                // CR 608.2h: the sacrificed permanent is gone; its mana value is last-known information.
                val drain =
                    context.sacrificedForCost.sumOf { ref ->
                        state.definitions[ref]
                            ?.characteristics
                            ?.manaCost
                            ?.manaValue ?: 0
                    }
                loseLife(state, state.opponentOf(context.controller), drain)
            },
        CardRef(LAND_SAC) to sacrificeSpell(LAND_SAC, "{R}", SacrificeFilter(persistentSetOf(CardType.LAND))),
        CardRef(SPAWN_EATER) to
            sacrificeSpell(SPAWN_EATER, "{1}", SacrificeFilter(persistentSetOf(CardType.CREATURE))),
        CardRef(BOMBER) to
            permanentWithAbility(
                BOMBER,
                CardType.CREATURE,
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.Sacrifice(SacrificeFilter(persistentSetOf(CardType.ARTIFACT)))),
                    effect = abilityDrain,
                ),
            ),
        CardRef(CANNON) to
            permanentWithAbility(
                CANNON,
                CardType.ENCHANTMENT,
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{1}")),
                            AbilityCost.Sacrifice(SacrificeFilter(persistentSetOf(CardType.ARTIFACT))),
                        ),
                    effect = abilityDrain,
                ),
            ),
        CardRef(EATER_CANNON) to
            permanentWithAbility(
                EATER_CANNON,
                CardType.ENCHANTMENT,
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{1}")),
                            AbilityCost.Sacrifice(SacrificeFilter(persistentSetOf(CardType.CREATURE))),
                        ),
                    effect = abilityDrain,
                ),
            ),
    )

/** Alice holding priority in her own precombat main, with the given battlefield and hand. */
private fun sacState(
    battlefield: List<String>,
    hand: List<String>,
): GameState {
    var nextId = 0L

    fun objects(names: List<String>) =
        names.map { GameObject(ObjectId(nextId), CardRef(it), alice).also { _ -> nextId += 1 } }.toPersistentList()

    val field = objects(battlefield)
    val handObjects = objects(hand)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = handObjects,
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
        sharedZones = SharedZones(field, persistentListOf(), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = (fixtureDefinitions + sacrificeFixtures).toPersistentMap(),
    )
}
