package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SacrificeRequirement
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.event.GameEvent
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
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.effect.loseLife
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P6.2a non-mana sacrifice cost machinery (CR 601.2h): Fireblast's alternative cost "sacrifice two
 * Mountains rather than pay this spell's mana cost" and Lava Dart's flashback cost "Sacrifice a
 * Mountain". Fixtures mirror the real cards' shapes (the `mtg-rules`-names-no-card rule holds).
 */
class SacrificeCostSpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val fireblast = CardRef("Fixture Fireblast")
        val lavaDart = CardRef("Fixture Lava Dart")
        val mountain = CardRef("Real Mountain")

        "CR 118.9: Fireblast's alternative sacrifice cost is enumerated when two Mountains are available" {
            val state =
                sacState(aliceHand = listOf(fireblast.name), aliceBattlefield = listOf(mountain.name, mountain.name))
            val request = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val altOption =
                request.options
                    .filterIsInstance<PriorityOption.CastSpell>()
                    .singleOrNull { it.permission is CastingPermission.AlternativeCost }
            // The alternative-cost cast is offered; the normal {4}{R}{R} cast is not (unaffordable).
            (altOption != null) shouldBe true
        }

        "CR 601.2h: Fireblast's alternative cost is not enumerated with only one Mountain" {
            val state = sacState(aliceHand = listOf(fireblast.name), aliceBattlefield = listOf(mountain.name))
            val request = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            request.options
                .filterIsInstance<PriorityOption.CastSpell>()
                .none { it.permission is CastingPermission.AlternativeCost } shouldBe true
        }

        "CR 701.17: casting Fireblast for its alternative cost sacrifices two Mountains to the graveyard" {
            val state =
                sacState(aliceHand = listOf(fireblast.name), aliceBattlefield = listOf(mountain.name, mountain.name))
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val altIndex =
                window.options.indexOfFirst {
                    it is PriorityOption.CastSpell && it.permission is CastingPermission.AlternativeCost
                }
            var current = engine.advance(state, Decision.SingleSelect(window.id, altIndex))
            // Targets: bob.
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            // Sacrifice both Mountains (exactly count = 2 options).
            val sacRequest = current.pending<DecisionRequest.ChooseSacrifices>()
            sacRequest.count shouldBe 2
            current = engine.advance(current.pausedState, Decision.MultiSelect(sacRequest.id, listOf(0, 1)))
            // Payment: the {0} alternative cost has a single (empty) plan.
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            // Resolve the Fireblast on the stack (both players pass).
            val resolved = passUntilResolved(engine, current.pausedState)
            val aliced = resolved.players.getValue(alice)
            // Both Mountains are in alice's graveyard; none remain on the battlefield.
            resolved.sharedZones.battlefield.none { it.card == mountain } shouldBe true
            aliced.graveyard.count { it.card == mountain } shouldBe 2
            resolved.events.filterIsInstance<GameEvent.PermanentSacrificed>() shouldHaveSize 2
            // The spell resolved: bob lost life.
            (STARTING_LIFE - resolved.players.getValue(bob).life) shouldBeGreaterThan 0
        }

        "CR 702.34c: Lava Dart's flashback with a sacrifice-a-Mountain cost is enumerated from the graveyard" {
            val state =
                sacState(
                    aliceHand = emptyList(),
                    aliceBattlefield = listOf(mountain.name),
                    aliceGraveyard = listOf(lavaDart.name),
                )
            val request = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            request.options
                .filterIsInstance<PriorityOption.CastSpell>()
                .singleOrNull { it.permission is CastingPermission.Flashback }
                ?.card shouldBe lavaDart
        }

        "CR 702.34e: flashing back Lava Dart sacrifices a Mountain and exiles the spell as it resolves" {
            val state =
                sacState(
                    aliceHand = emptyList(),
                    aliceBattlefield = listOf(mountain.name),
                    aliceGraveyard = listOf(lavaDart.name),
                )
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val fbIndex =
                window.options.indexOfFirst {
                    it is PriorityOption.CastSpell && it.permission is CastingPermission.Flashback
                }
            var current = engine.advance(state, Decision.SingleSelect(window.id, fbIndex))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            val sacRequest = current.pending<DecisionRequest.ChooseSacrifices>()
            sacRequest.count shouldBe 1
            current = engine.advance(current.pausedState, Decision.MultiSelect(sacRequest.id, listOf(0)))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            val resolved = passUntilResolved(engine, current.pausedState)
            // The Mountain was sacrificed; the flashback spell is in exile, not the graveyard (CR 702.34e).
            resolved.players
                .getValue(alice)
                .graveyard
                .count { it.card == mountain } shouldBe 1
            resolved.sharedZones.exile.count { it.card == lavaDart } shouldBe 1
            resolved.players
                .getValue(alice)
                .graveyard
                .none { it.card == lavaDart } shouldBe true
        }
    })

/** Drives both players to pass until the stack empties (the spell on top resolves). */
private fun passUntilResolved(
    engine: GameEngine,
    start: GameState,
): GameState {
    var current: AdvanceResult =
        AdvanceResult.NeedsDecision(
            start,
            dev.mtgplay.rules.engine
                .pendingDecisionRequest(start)!!,
        )
    while (current is AdvanceResult.NeedsDecision &&
        current.state.sharedZones.stack
            .isNotEmpty()
    ) {
        val request = current.request as? DecisionRequest.ChooseAction ?: break
        current = engine.advance(current.state, passDecision(request))
    }
    return current.pausedState
}

/** A Mountain land fixture with subtype Mountain and {T}: add {R}. */
private fun mountainCard(name: CardRef): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name.name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(Subtype("Mountain")),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.RED)))
    }

private fun burnFixture(
    name: CardRef,
    cost: String,
    permissions: List<CastingPermission>,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name.name,
                manaCost = ManaCost.parse(cost),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.AnyTarget
        override val resolution =
            ResolutionEffect { state, context ->
                when (val target = context.targets.single()) {
                    is Target.Player -> loseLife(state, target.id, 4)
                    is Target.Permanent -> error("fixture unexpectedly targeted a permanent: $target")
                    is Target.SpellOnStack -> error("fixture unexpectedly targeted a spell: $target")
                    is Target.CardInGraveyard -> error("fixture unexpectedly targeted a graveyard card: $target")
                }
            }
        override val castingPermissions = permissions
    }

/** The sacrifice-cost fixture registry: a Mountain, Fireblast (alternative cost), and Lava Dart (flashback). */
private val sacRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef("Real Mountain") to mountainCard(CardRef("Real Mountain")),
        CardRef("Fixture Fireblast") to
            burnFixture(
                CardRef("Fixture Fireblast"),
                cost = "{4}{R}{R}",
                permissions =
                    listOf(
                        CastingPermission.AlternativeCost(
                            cost = ManaCost.parse("{0}"),
                            sacrifice = SacrificeRequirement(2, Subtype("Mountain")),
                        ),
                    ),
            ),
        CardRef("Fixture Lava Dart") to
            burnFixture(
                CardRef("Fixture Lava Dart"),
                cost = "{R}",
                permissions =
                    listOf(
                        CastingPermission.Flashback(
                            cost = ManaCost.parse("{0}"),
                            sacrifice = SacrificeRequirement(1, Subtype("Mountain")),
                        ),
                    ),
            ),
    )

/** A handcrafted priority window (alice active and holding) with the sacrifice-cost fixture registry. */
private fun sacState(
    aliceHand: List<String>,
    aliceBattlefield: List<String>,
    aliceGraveyard: List<String> = emptyList(),
): GameState {
    val registry = sacRegistry
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    val aliceField = objects(aliceBattlefield, alice)
    val aliceHandObjects = objects(aliceHand, alice)
    val aliceGraveyardObjects = objects(aliceGraveyard, alice)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = aliceHandObjects,
                        graveyard = aliceGraveyardObjects,
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
        sharedZones = SharedZones(aliceField, persistentListOf(), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = registry.toPersistentMap(),
    )
}
