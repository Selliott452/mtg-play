package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.engine.manaSourceClasses
import dev.mtgplay.rules.engine.manaSourcesReservedBy
import dev.mtgplay.rules.engine.resolveTapForMana
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P6.2a general activated-ability framework (CR 602): composite costs (mana, tap self, sacrifice
 * self, discard a card), the hand zone scope (landcycling), and resolution as a stack ability. Fixtures
 * mirror Blood token's, Melded Moxite's, and Ash Barrens' shapes (the `mtg-rules`-names-no-card rule holds).
 */
class ActivatedAbilitySpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val blood = CardRef("Fixture Blood")
        val moxite = CardRef("Fixture Moxite")
        val cycler = CardRef("Fixture Cycler")
        val ornament = CardRef("Fixture Ornament")
        val mountain = CardRef("Ability Mountain")
        val robot = CardRef("Robot")

        "CR 602.2: a composite-cost ability ({1},{T},discard,sac) is activated, gathering discard then payment" {
            // Blood: {1}, {T}, Discard a card, Sacrifice this: Draw a card.
            val state =
                abilityState(
                    battlefield = listOf(blood.name, mountain.name, mountain.name),
                    hand = listOf("Ability Filler"),
                    library = 2,
                )
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val activateIndex =
                window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == blood }
            var current = engine.advance(state, Decision.SingleSelect(window.id, activateIndex))
            // Cost gathering: discard a card first.
            val discard = current.pending<DecisionRequest.ChooseAbilityDiscard>()
            discard.count shouldBe 1
            current = engine.advance(current.pausedState, Decision.MultiSelect(discard.id, listOf(0)))
            // Then the {1} payment.
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            // The ability is on the stack; the activator has priority to respond.
            val paused = current.pausedState
            paused.sharedZones.stack
                .last()
                .shouldBeInstanceOf<StackEntry.ActivatedAbilityOnStack>()
            current.pending<DecisionRequest.ChooseAction>().seat shouldBe alice
            // Blood was sacrificed and the filler discarded — both in the graveyard.
            paused.sharedZones.battlefield.none { it.card == blood } shouldBe true
            paused.players
                .getValue(alice)
                .graveyard
                .count { it.card == blood } shouldBe 1
            paused.players
                .getValue(alice)
                .graveyard
                .count { it.card == CardRef("Ability Filler") } shouldBe 1
        }

        // ---- triage trap T17: a source may not fund a cost that also taps it -----------------------

        "CR 602.2a: a {T} ability's own source is never offered as a payer for that ability's mana cost" {
            // Four Mountains and an untapped Ornament ({T}: Add {C}; {4}, {T}: draw a card). Before the
            // fix, colorless sorted into the {4} candidates and plan 0 tapped the Ornament itself; the
            // plan enumerated, the agent picked it, mana was paid, and the {T} component then threw.
            // An enumerated action the rules do not permit is an ADR-005 defect, not a rules corner.
            val state =
                abilityState(
                    battlefield = List(4) { mountain.name } + ornament.name,
                    hand = emptyList(),
                    library = 2,
                )
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val index = window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == ornament }
            var current = engine.advance(state, Decision.SingleSelect(window.id, index))
            val payment = current.pending<DecisionRequest.ChoosePaymentPlan>()
            // Only the Mountains fund it, so there is exactly one plan and it never names the Ornament.
            payment.options.forEach { plan ->
                plan.activations.none { it.sourceClass.card == ornament } shouldBe true
            }
            // And every offered plan executes (ADR-005): the ability reaches the stack.
            payment.options.indices.forEach { choice ->
                val paid = engine.advance(current.pausedState, Decision.SingleSelect(payment.id, choice))
                paid.pausedState.sharedZones.stack
                    .last()
                    .shouldBeInstanceOf<StackEntry.ActivatedAbilityOnStack>()
            }
            current = engine.advance(current.pausedState, Decision.SingleSelect(payment.id, 0))
            current.pausedState.sharedZones.battlefield
                .single { it.card == ornament }
                .tapped shouldBe true
        }

        "CR 602.2: legality agrees with the request — three Mountains and the Ornament cannot pay {4}" {
            // The Ornament's own {C} would make four, but it is reserved by the {T} component, so the
            // ability is not offered at all rather than offered and unpayable.
            val state =
                abilityState(
                    battlefield = List(3) { mountain.name } + ornament.name,
                    hand = emptyList(),
                    library = 2,
                )
            pausedRequestOf<DecisionRequest.ChooseAction>(state)
                .options
                .none { it is PriorityOption.ActivateAbility && it.card == ornament } shouldBe true
        }

        "CR 701.17: a cost that only sacrifices its source reserves nothing — tapping it first is legal" {
            // The counterexample that keeps the reservation from being written too broadly. Sacrificing
            // a *tapped* permanent is legal Magic (CR 701.17 does not care), so a "{3}, Sacrifice this"
            // ability must keep every plan that taps its own source for mana. Reserving on
            // SacrificeSelf unconditionally would trade a crash for a silently missing legal plan.
            val state =
                abilityState(
                    battlefield = listOf(moxite.name, mountain.name, mountain.name, mountain.name),
                    hand = emptyList(),
                    library = 2,
                )
            val source = state.sharedZones.battlefield.single { it.card == moxite }
            val ability =
                state.definitions
                    .getValue(moxite)
                    .activatedAbilities
                    .single()
            manaSourcesReservedBy(state, source, ability).isEmpty() shouldBe true
        }

        "CR 113.7a: the activated ability resolves, drawing a card, though its source was sacrificed" {
            val state =
                abilityState(
                    battlefield = listOf(blood.name, mountain.name, mountain.name),
                    hand = listOf("Ability Filler"),
                    library = 2,
                )
            val resolved = activateBloodAndResolve(engine, state)
            // The effect ran: alice drew (library 2 -> hand gains, count via drawsThisTurn).
            resolved.players.getValue(alice).drawsThisTurn shouldBe 1
            resolved.sharedZones.stack.isEmpty() shouldBe true
        }

        "CR 602.2: a sacrifice-cost token ability ({3},sac: create a token) needs no discard, only payment" {
            // Moxite: {3}, Sacrifice this: Create a tapped Robot token.
            val state =
                abilityState(
                    battlefield = listOf(moxite.name) + List(3) { mountain.name },
                    hand = emptyList(),
                    library = 0,
                )
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val activateIndex =
                window.options.indexOfFirst {
                    it is PriorityOption.ActivateAbility && it.card == moxite
                }
            var current = engine.advance(state, Decision.SingleSelect(window.id, activateIndex))
            // No discard cost: straight to payment.
            current =
                engine.advance(current.pausedState, planDecision(current.pending<DecisionRequest.ChoosePaymentPlan>()))
            // Resolve.
            while (current is AdvanceResult.NeedsDecision &&
                current.state.sharedZones.stack
                    .isNotEmpty()
            ) {
                val req = current.request as? DecisionRequest.ChooseAction ?: break
                current = engine.advance(current.state, passDecision(req))
            }
            val done = current.pausedState
            done.sharedZones.battlefield.count { it.card == robot } shouldBe 1
            done.sharedZones.battlefield.none { it.card == moxite } shouldBe true
        }

        "CR 113.6c: a hand-scoped ability ({1}, discard this) is activatable from the hand" {
            // Cycler: {1}, Discard this card: Draw a card (a landcycling-shaped hand ability).
            val state = abilityState(battlefield = listOf(mountain.name), hand = listOf(cycler.name), library = 2)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val activateIndex =
                window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == cycler }
            (activateIndex >= 0) shouldBe true
            var current = engine.advance(state, Decision.SingleSelect(window.id, activateIndex))
            current =
                engine.advance(current.pausedState, planDecision(current.pending<DecisionRequest.ChoosePaymentPlan>()))
            val paused = current.pausedState
            // The Cycler left the hand for the graveyard (discard-self cost) and its ability is on the stack.
            paused.players
                .getValue(alice)
                .hand
                .none { it.card == cycler } shouldBe true
            paused.players
                .getValue(alice)
                .graveyard
                .count { it.card == cycler } shouldBe 1
            paused.sharedZones.stack
                .last()
                .shouldBeInstanceOf<StackEntry.ActivatedAbilityOnStack>()
        }

        "CR 605.1a: an Eldrazi-Spawn-style sacrifice-for-mana ability is paid during payment" {
            // An Eldrazi Spawn token: "Sacrifice this token: Add {C}." Tapping is irrelevant.
            val spawn = CardRef("Eldrazi Spawn")
            val state = abilityState(battlefield = listOf(spawn.name), hand = emptyList(), library = 0)
            // The Spawn forms its own sacrifice source class producing colorless.
            val classes = manaSourceClasses(state, alice)
            val spawnClass = classes.single { it.key.card == spawn }
            spawnClass.key.viaSacrifice shouldBe true
            spawnClass.key.profile shouldContainExactly listOf(listOf(ManaType.COLORLESS))
            // Activating it sacrifices the token and adds {C} to the pool.
            val paid = resolveTapForMana(state, alice, spawnClass.key, listOf(ManaType.COLORLESS))
            paid.players
                .getValue(alice)
                .manaPool
                .toList() shouldContainExactly listOf(ManaType.COLORLESS)
            paid.sharedZones.battlefield.none { it.card == spawn } shouldBe true
            paid.players
                .getValue(alice)
                .graveyard
                .count { it.card == spawn } shouldBe 1
        }

        "CR 602.5a: a battlefield ability is not enumerated when its {T} source is tapped" {
            val state =
                abilityState(
                    battlefield = listOf(moxite.name) + List(3) { mountain.name },
                    hand = emptyList(),
                    library = 0,
                ).let { s ->
                    // Tap the Mountains so the {3} cost cannot be paid — the ability disappears.
                    s.copy(
                        sharedZones =
                            s.sharedZones.copy(
                                battlefield =
                                    s.sharedZones.battlefield
                                        .map { if (it.card == mountain) it.copy(tapped = true) else it }
                                        .toPersistentList(),
                            ),
                    )
                }
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            window.options.filterIsInstance<PriorityOption.ActivateAbility>().none { it.card == moxite } shouldBe true
        }
    })

/** Activates Blood (discard filler, pay {1}) and resolves the ability; returns the post-resolution state. */
private fun activateBloodAndResolve(
    engine: GameEngine,
    state: GameState,
): GameState {
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
    val activateIndex =
        window.options.indexOfFirst {
            it is PriorityOption.ActivateAbility &&
                it.card == CardRef("Fixture Blood")
        }
    var current = engine.advance(state, Decision.SingleSelect(window.id, activateIndex))
    val discard = current.pending<DecisionRequest.ChooseAbilityDiscard>()
    current = engine.advance(current.pausedState, Decision.MultiSelect(discard.id, listOf(0)))
    current = engine.advance(current.pausedState, planDecision(current.pending()))
    while (current is AdvanceResult.NeedsDecision &&
        current.state.sharedZones.stack
            .isNotEmpty()
    ) {
        val req = current.request as? DecisionRequest.ChooseAction ?: break
        current = engine.advance(current.state, passDecision(req))
    }
    return current.pausedState
}

private val robotToken =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Robot",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(2, 2),
            ),
    )

private fun artifactWithAbility(
    name: String,
    ability: ActivatedAbility,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val activatedAbilities = persistentListOf(ability)
    }

private fun mountainDef(name: String): CardDefinition =
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
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.RED)))
    }

private val abilityRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef("Fixture Blood") to
            artifactWithAbility(
                "Fixture Blood",
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{1}")),
                            AbilityCost.TapSelf,
                            AbilityCost.DiscardACard,
                            AbilityCost.SacrificeSelf,
                        ),
                    effect = ResolutionEffect { s, ctx -> drawCards(s, ctx.controller, 1) },
                ),
            ),
        CardRef("Fixture Moxite") to
            artifactWithAbility(
                "Fixture Moxite",
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.Mana(ManaCost.parse("{3}")), AbilityCost.SacrificeSelf),
                    effect = ResolutionEffect { s, ctx -> createToken(s, ctx.controller, robotToken) },
                ),
            ),
        CardRef("Fixture Cycler") to
            object : CardDefinition {
                override val characteristics =
                    PrintedCharacteristics(
                        name = "Fixture Cycler",
                        manaCost = null,
                        supertypes = persistentSetOf(),
                        cardTypes = persistentSetOf(CardType.LAND),
                        subtypes = persistentSetOf(),
                        powerToughness = null,
                    )
                override val activatedAbilities =
                    persistentListOf(
                        ActivatedAbility(
                            cost = persistentListOf(AbilityCost.Mana(ManaCost.parse("{1}")), AbilityCost.DiscardSelf),
                            effect = ResolutionEffect { s, ctx -> drawCards(s, ctx.controller, 1) },
                            zoneScope = AbilityZoneScope.Hand,
                        ),
                    )
            },
        // Bonder's Ornament's shape, and the reproduction of triage trap T17: one permanent that is
        // both a mana source and the source of a {T}-costed ability with a mana component.
        CardRef("Fixture Ornament") to
            object : CardDefinition {
                override val characteristics =
                    PrintedCharacteristics(
                        name = "Fixture Ornament",
                        manaCost = null,
                        supertypes = persistentSetOf(),
                        cardTypes = persistentSetOf(CardType.ARTIFACT),
                        subtypes = persistentSetOf(),
                        powerToughness = null,
                    )
                override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.COLORLESS)))
                override val activatedAbilities =
                    persistentListOf(
                        ActivatedAbility(
                            cost = persistentListOf(AbilityCost.Mana(ManaCost.parse("{4}")), AbilityCost.TapSelf),
                            effect = ResolutionEffect { s, ctx -> drawCards(s, ctx.controller, 1) },
                        ),
                    )
            },
        CardRef("Ability Mountain") to mountainDef("Ability Mountain"),
        CardRef("Ability Filler") to mountainDef("Ability Filler"),
        CardRef("Robot") to robotToken,
        CardRef("Eldrazi Spawn") to
            TokenDefinition(
                characteristics =
                    PrintedCharacteristics(
                        name = "Eldrazi Spawn",
                        manaCost = null,
                        supertypes = persistentSetOf(),
                        cardTypes = persistentSetOf(CardType.CREATURE),
                        subtypes = persistentSetOf(),
                        powerToughness = PrintedPowerToughness(0, 1),
                    ),
                manaAbilities =
                    persistentListOf(
                        ManaAbility(persistentListOf(ManaType.COLORLESS), viaSacrifice = true),
                    ),
            ),
    )

/** Alice holding priority with the given battlefield, hand, and a [library]-card library (Mountains). */
private fun abilityState(
    battlefield: List<String>,
    hand: List<String>,
    library: Int,
): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val field = objects(battlefield, alice)
    val handObjects = objects(hand, alice)
    val libraryObjects = objects(List(library) { "Ability Mountain" }, alice)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = libraryObjects,
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
        definitions = abilityRegistry.toPersistentMap(),
    )
}
