package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.engine.legalTargets
import dev.mtgplay.rules.engine.priorityTo
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The `FW-ABILTGT` framework at rules level with fixture cards (the `mtg-rules`-names-no-card rule
 * holds): a triggered ability chooses its targets as it is put on the stack (CR 603.3d), an activated
 * ability chooses them while being activated (CR 602.2b), and both re-check on resolution (CR 608.2b).
 *
 * Design note: docs/design/targeted-abilities.md.
 */
class TargetedAbilitySpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 115.1a/102.1: TargetSpec.TargetOpponent enumerates every player but the decider, and no permanent" {
            val state = targetingState(battlefield = listOf(alice to "Bear"))
            legalTargets(state, TargetSpec.TargetOpponent, alice, self = null) shouldContainExactly
                listOf(Target.Player(bob))
            // Decider-relative: the same board gives the other seat the opposite enumeration.
            legalTargets(state, TargetSpec.TargetOpponent, bob, self = null) shouldContainExactly
                listOf(Target.Player(alice))
            // Narrower than AnyTarget, which offers both players and the creature.
            legalTargets(state, TargetSpec.AnyTarget, alice, self = null) shouldHaveSize 3
        }

        "CR 603.3d: a triggered ability chooses its targets as it is put on the stack, not when it resolves" {
            val state =
                targetingState(
                    pendingTriggers = listOf(firedTrigger(alice, damageOpponentAbility(BOLT), CardRef("Zapper"))),
                )
            val request = priorityTo(state, alice).pending<DecisionRequest.ChooseTargets>()
            // The ability's controller decides (CR 603.3d) — not blindly the active player or the
            // priority recipient, though here they coincide.
            request.seat shouldBe alice
            request.card shouldBe CardRef("Zapper")
            request.options shouldContainExactly listOf(Target.Player(bob))
            // The pause happens *before* the ability reaches the stack: it is still pending.
            val paused = priorityTo(state, alice).pausedState
            paused.sharedZones.stack.shouldBeEmpty()
            paused.pendingTriggers shouldHaveSize 1
            paused.pendingTriggerTargets?.controller shouldBe alice

            val placed = engine.advance(paused, Decision.SingleSelect(request.id, 0)).pausedState
            placed.pendingTriggerTargets.shouldBeNull()
            placed.pendingTriggers.shouldBeEmpty()
            val entry = placed.sharedZones.stack.single() as StackEntry.Ability
            entry.targets shouldContainExactly listOf(Target.Player(bob))
        }

        "ADR-004: the CR 603.3d target pause re-derives its own request from the paused state alone" {
            val state =
                targetingState(
                    pendingTriggers = listOf(firedTrigger(alice, damageOpponentAbility(BOLT), CardRef("Zapper"))),
                )
            val result = priorityTo(state, alice)
            val rederived = pausedRequestOf<DecisionRequest.ChooseTargets>(result.pausedState)
            rederived shouldBe result.pending<DecisionRequest.ChooseTargets>()
        }

        "CR 603.3d: a triggered ability with no legal target is still put on the stack, carrying none" {
            // A fixture spec whose enumeration is empty: no creature is on the battlefield, and the
            // ability targets a creature-shaped Enchantable restriction.
            val state =
                targetingState(
                    pendingTriggers = listOf(firedTrigger(alice, damageCreatureAbility(BOLT), CardRef("Zapper"))),
                )
            // No pause at all — the engine places it and opens the window (CR 603.3d has no
            // "otherwise it doesn't trigger" clause).
            val window = priorityTo(state, alice).pending<DecisionRequest.ChooseAction>()
            window.seat shouldBe alice
            val placed = priorityTo(state, alice).pausedState
            val entry = placed.sharedZones.stack.single() as StackEntry.Ability
            entry.targets.shouldBeEmpty()
        }

        "CR 608.2b: a triggered ability whose only target is illegal on resolution does nothing" {
            // The trigger targeted a creature that has since left the battlefield.
            val goneId = ObjectId(900)
            val state =
                targetingState(
                    stack =
                        listOf(
                            StackEntry.Ability(
                                firedTrigger(alice, damageCreatureAbility(BOLT), CardRef("Zapper")),
                                persistentListOf(Target.Permanent(goneId)),
                            ),
                        ),
                )
            val resolved = resolveTopOfStack(state).pausedState
            // Removed from the stack, no damage dealt, and — CR 113.7a — no card moved anywhere.
            resolved.sharedZones.stack.shouldBeEmpty()
            resolved.players.getValue(bob).life shouldBe STARTING_LIFE
            resolved.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            resolved.events.filterIsInstance<GameEvent.TriggeredAbilityResolved>().shouldBeEmpty()
            val fizzled = resolved.events.filterIsInstance<GameEvent.AbilityFizzled>().single()
            fizzled.sourceCard shouldBe CardRef("Zapper")
            fizzled.triggered shouldBe true
        }

        "CR 608.2b: a targeting trigger placed with no targets at all fizzles vacuously" {
            val state =
                targetingState(
                    stack =
                        listOf(
                            StackEntry.Ability(firedTrigger(alice, damageCreatureAbility(BOLT), CardRef("Zapper"))),
                        ),
                )
            val resolved = resolveTopOfStack(state).pausedState
            resolved.sharedZones.stack.shouldBeEmpty()
            resolved.events.filterIsInstance<GameEvent.AbilityFizzled>() shouldHaveSize 1
        }

        "CR 603.3b/603.3d: simultaneous triggers are ordered first, then targeted in placement order" {
            val state =
                targetingState(
                    pendingTriggers =
                        listOf(
                            firedTrigger(alice, damageOpponentAbility(1), CardRef("First")),
                            firedTrigger(alice, damageOpponentAbility(2), CardRef("Second")),
                        ),
                )
            // The ordering choice comes first (CR 603.3b), before any target choice.
            val order = priorityTo(state, alice).pending<DecisionRequest.OrderTriggers>()
            order.options.map { it.sourceCard } shouldContainExactly listOf(CardRef("First"), CardRef("Second"))

            // Put Second on the stack first, then First.
            val afterOrder =
                engine.advance(priorityTo(state, alice).pausedState, Decision.MultiSelect(order.id, listOf(1, 0)))
            // Now the *first-placed* ability (Second) asks for its target, and it alone is being placed.
            val firstTargets = afterOrder.pending<DecisionRequest.ChooseTargets>()
            firstTargets.card shouldBe CardRef("Second")
            afterOrder.pausedState.sharedZones.stack
                .shouldBeEmpty()

            val afterFirst = engine.advance(afterOrder.pausedState, Decision.SingleSelect(firstTargets.id, 0))
            // The ordering is *not* re-asked: the second ability's target request follows directly.
            val secondTargets = afterFirst.pending<DecisionRequest.ChooseTargets>()
            secondTargets.card shouldBe CardRef("First")
            afterFirst.pausedState.sharedZones.stack shouldHaveSize 1

            val done = engine.advance(afterFirst.pausedState, Decision.SingleSelect(secondTargets.id, 0))
            val stack =
                done.pausedState.sharedZones.stack
                    .map { (it as StackEntry.Ability).trigger.sourceCard }
            stack shouldContainExactly listOf(CardRef("Second"), CardRef("First"))
            done.pending<DecisionRequest.ChooseAction>().seat shouldBe alice
        }

        "CR 602.2b: an activated ability chooses its targets before its cost is paid (CR 601.2b-i order)" {
            val source = CardRef("Pinger")
            val state =
                targetingState(
                    battlefield = listOf(alice to source.name),
                    definitions = mapOf(source to pingerCard(source)),
                    holder = alice,
                )
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val activate = window.options.indexOfFirst { it is PriorityOption.ActivateAbility }
            activate shouldBe 1

            val afterActivate = engine.advance(state, Decision.SingleSelect(window.id, activate))
            // Targets first — no payment plan has been asked for yet (this ability's cost is `{T}` only).
            val targets = afterActivate.pending<DecisionRequest.ChooseTargets>()
            targets.options shouldContainExactly listOf(Target.Player(bob))
            afterActivate.pausedState.pendingActivation
                ?.chosenTargets
                .shouldBeNull()

            val onStack = engine.advance(afterActivate.pausedState, Decision.SingleSelect(targets.id, 0))
            val entry =
                onStack.pausedState.sharedZones.stack
                    .single() as StackEntry.ActivatedAbilityOnStack
            entry.targets shouldContainExactly listOf(Target.Player(bob))
            // The `{T}` cost was paid as part of the same transition (CR 602.2b).
            onStack.pausedState.sharedZones.battlefield
                .single()
                .tapped shouldBe true
        }

        "CR 601.2c: an activated ability with no legal target is not enumerated at all" {
            // The mirror of the trigger case above: an activation cannot happen without a legal target.
            val source = CardRef("Creature Pinger")
            val state =
                targetingState(
                    battlefield = listOf(alice to source.name),
                    definitions = mapOf(source to creaturePingerCard(source)),
                    holder = alice,
                )
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            window.options.filterIsInstance<PriorityOption.ActivateAbility>().shouldBeEmpty()
        }

        "CR 608.2b: an activated ability whose only target is illegal on resolution does nothing" {
            val source = CardRef("Creature Pinger")
            val goneId = ObjectId(900)
            val state =
                targetingState(
                    definitions = mapOf(source to creaturePingerCard(source)),
                    stack =
                        listOf(
                            StackEntry.ActivatedAbilityOnStack(
                                sourceId = ObjectId(800),
                                sourceCard = source,
                                controller = alice,
                                ability = creaturePingerCard(source).activatedAbilities.single(),
                                targets = persistentListOf(Target.Permanent(goneId)),
                            ),
                        ),
                )
            val resolved = resolveTopOfStack(state).pausedState
            resolved.sharedZones.stack.shouldBeEmpty()
            resolved.events.filterIsInstance<GameEvent.AbilityResolved>().shouldBeEmpty()
            val fizzled = resolved.events.filterIsInstance<GameEvent.AbilityFizzled>().single()
            fizzled.triggered shouldBe false
        }

        "CR 603.3d: the target choice belongs to the ability's controller, not the priority recipient" {
            // alice is the active player and the priority recipient, but *bob* controls the fired
            // trigger — so bob decides, and his enumeration excludes himself (CR 102.1).
            val state =
                targetingState(
                    pendingTriggers = listOf(firedTrigger(bob, damageOpponentAbility(BOLT), CardRef("Bob Zapper"))),
                )
            val request = priorityTo(state, alice).pending<DecisionRequest.ChooseTargets>()
            request.seat shouldBe bob
            request.options shouldContainExactly listOf(Target.Player(alice))

            val placed = engine.advance(priorityTo(state, alice).pausedState, Decision.SingleSelect(request.id, 0))
            val entry =
                placed.pausedState.sharedZones.stack
                    .single() as StackEntry.Ability
            entry.targets shouldContainExactly listOf(Target.Player(alice))
            // Priority still lands on the recorded recipient once the queue drains (CR 601.2i).
            placed.pending<DecisionRequest.ChooseAction>().seat shouldBe alice

            // And the ability actually damages the chosen player when it resolves (CR 608.2c).
            val resolved = resolveTopOfStack(placed.pausedState).pausedState
            resolved.players.getValue(alice).life shouldBe STARTING_LIFE - BOLT
            resolved.players.getValue(bob).life shouldBe STARTING_LIFE
        }
    })

private const val BOLT: Int = 3

/** A fixture triggered ability: on resolution it deals [amount] damage to the opponent it targeted. */
private fun damageOpponentAbility(amount: Int): TriggeredAbility =
    TriggeredAbility(
        condition = TriggerCondition.EnteredBattlefieldSelf,
        targetSpec = TargetSpec.TargetOpponent,
        effect = ResolutionEffect { state, context -> dealDamage(state, context.targets.single(), amount) },
    )

/**
 * A fixture triggered ability targeting a **creature** ([TargetSpec.AnyTarget] restricted by the
 * fixture states' empty battlefield): used for the no-legal-target and fizzle cases, since a targeted
 * player can never become illegal in a two-player game (CR 104.2a).
 */
private fun damageCreatureAbility(amount: Int): TriggeredAbility =
    TriggeredAbility(
        condition = TriggerCondition.EnteredBattlefieldSelf,
        targetSpec = TargetSpec.Enchantable(EnchantRestriction.CREATURE),
        effect = ResolutionEffect { state, context -> dealDamage(state, context.targets.single(), amount) },
    )

/** A fixture permanent with a `{T}`-cost activated ability that pings target opponent for [BOLT]. */
private fun pingerCard(name: CardRef): CardDefinition = abilityCard(name, TargetSpec.TargetOpponent)

/** As [pingerCard], but targeting a creature — so with no creature anywhere it cannot be activated. */
private fun creaturePingerCard(name: CardRef): CardDefinition =
    abilityCard(name, TargetSpec.Enchantable(EnchantRestriction.CREATURE))

private fun abilityCard(
    name: CardRef,
    spec: TargetSpec,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name.name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.TapSelf),
                    targetSpec = spec,
                    effect = ResolutionEffect { state, context -> dealDamage(state, context.targets.single(), BOLT) },
                ),
            )
    }

/** A fired [PendingTrigger] for [controller] carrying [ability], sourced from [sourceCard]. */
private fun firedTrigger(
    controller: PlayerId,
    ability: TriggeredAbility,
    sourceCard: CardRef,
): PendingTrigger = PendingTrigger(ObjectId(800), sourceCard, controller, ability)

/** A minimal two-player state for the targeting specs: alice active, in her precombat main. */
private fun targetingState(
    pendingTriggers: List<PendingTrigger> = emptyList(),
    stack: List<StackEntry> = emptyList(),
    battlefield: List<Pair<PlayerId, String>> = emptyList(),
    definitions: Map<CardRef, CardDefinition> = emptyMap(),
    holder: PlayerId? = null,
): GameState {
    val permanents =
        battlefield
            .mapIndexed { index, (owner, name) ->
                GameObject(ObjectId(index.toLong()), CardRef(name), owner, summoningSick = false)
            }.toPersistentList()
    // The explicit [definitions] win: a fixture battlefield object only falls back to a plain body.
    val registry = battlefield.associate { CardRef(it.second) to bearCard(it.second) } + definitions
    return GameState(
        players =
            persistentMapOf(
                alice to seat(alice, holder),
                bob to seat(bob, holder),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(permanents, stack.toPersistentList(), persistentListOf()),
        nextObjectId = 1000,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = registry.toPersistentMap(),
        pendingTriggers = pendingTriggers.toPersistentList(),
    )
}

private fun seat(
    seat: PlayerId,
    holder: PlayerId?,
): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
        priorityStatus = if (seat == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
    )

/** A plain 2/2 creature definition, so a battlefield fixture object has printed characteristics. */
private fun bearCard(name: String): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
            )
    }
