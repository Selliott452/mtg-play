package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCascade
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.effect.powerOfOrLastKnown
import dev.mtgplay.rules.engine.baseCharacteristics
import dev.mtgplay.rules.engine.player
import dev.mtgplay.rules.engine.resolveTopOfStack
import dev.mtgplay.rules.engine.spellCharacteristics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The `W9-G` primitives at rules level, on fixtures (`mtg-rules` names no real card): the CR 718.3b
 * prototyped-characteristics seam on a spell and on a permanent, the CR 608.2h power read a
 * "gain life equal to its power" clause makes, and cascade's two **no-offer** branches.
 *
 * The happy paths — a prototyped 3/3 that gains 3 life, a dig that walks past lands, a free cast, a
 * seeded bottoming — are driven on the real cards in the acceptance module's
 * `AlternateCastingAcceptanceSpec`. What lives here is what an end-to-end game does not reach: the
 * fallback branch of the power read, and the two ways cascade can find nothing to offer.
 */
class AlternateCastingPrimitivesSpec :
    StringSpec({

        // ---- prototype: CR 718.3b's alternative characteristics ------------------------------------

        "CR 718.3b: a prototyped spell on the stack has the alternative cost, colour, mana value and size" {
            val printed = stackStateFor(castVia = null)
            spellCharacteristics(printed, printed.spellEntry()).let {
                it.manaCost?.render() shouldBe "{6}"
                it.colors shouldBe emptySet<Color>()
                it.manaValue shouldBe PRINTED_MANA_VALUE
                it.powerToughness shouldBe PrintedPowerToughness(PRINTED_POWER, PRINTED_TOUGHNESS)
            }

            val prototyped = stackStateFor(castVia = fixturePrototype)
            spellCharacteristics(prototyped, prototyped.spellEntry()).let {
                it.manaCost?.render() shouldBe "{1}{G}"
                // CR 718.3b: "if that mana cost includes one or more colored mana symbols, the spell …
                // is also that color" — derived from the cost, never declared.
                it.colors shouldBe setOf(Color.GREEN)
                it.manaValue shouldBe PROTOTYPE_MANA_VALUE
                it.powerToughness shouldBe PrintedPowerToughness(PROTOTYPE_SIZE, PROTOTYPE_SIZE)
            }
        }

        "CR 718.3b: a permanent's base characteristics follow the marker, and everything else is printed" {
            val plain = GameObject(ObjectId(1), CardRef(GOLEM), alice)
            val marked = plain.copy(prototyped = true)
            val state = battlefieldStateOf(marked)

            val base = baseCharacteristics(state, marked) ?: error("the fixture is registered")
            base.powerToughness shouldBe PrintedPowerToughness(PROTOTYPE_SIZE, PROTOTYPE_SIZE)
            base.colors shouldBe setOf(Color.GREEN)
            // CR 702.160a: "It keeps its abilities and types" — nothing but cost, colour and size moves.
            base.name shouldBe GOLEM
            base.cardTypes shouldBe persistentSetOf(CardType.ARTIFACT, CardType.CREATURE)
            baseCharacteristics(battlefieldStateOf(plain), plain)?.powerToughness shouldBe
                PrintedPowerToughness(PRINTED_POWER, PRINTED_TOUGHNESS)
        }

        "CR 718.3b: a prototype marker on a card that declares no prototype ability fails loudly" {
            // Nothing but a prototyped cast can set the flag, so this is an engine defect rather than a
            // rules case — it must not quietly answer with the printed values.
            val impostor = GameObject(ObjectId(2), CardRef(PLAIN_CREATURE), alice).copy(prototyped = true)
            shouldThrow<IllegalStateException> { baseCharacteristics(battlefieldStateOf(impostor), impostor) }
        }

        // ---- the CR 608.2h power read --------------------------------------------------------------

        "CR 608.2h: an 'equal to its power' read uses current information while the permanent is there" {
            val golem = GameObject(ObjectId(3), CardRef(GOLEM), alice).copy(prototyped = true)
            val state = battlefieldStateOf(golem)
            // The live read wins over the captured fallback, which is what makes the value track a pump.
            powerOfOrLastKnown(state, golem.id, lastKnown = 99) shouldBe PROTOTYPE_SIZE
        }

        "CR 608.2h: the read falls back to last-known information once the permanent has left" {
            val golem = GameObject(ObjectId(4), CardRef(GOLEM), alice)
            val gone =
                battlefieldStateOf(golem).copy(
                    sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf()),
                )
            powerOfOrLastKnown(gone, golem.id, lastKnown = PRINTED_POWER) shouldBe PRINTED_POWER
        }

        // ---- cascade: the two branches that offer nothing -------------------------------------------

        "CR 702.85a: a cascade that runs out of library exiles everything and bottoms it, offering no cast" {
            // A library of nothing but lands: the predicate is never satisfied, so the dig empties the
            // library. CR 702.85a has no fail-to-find clause — everything simply goes back.
            val state = cascadeTriggerState(library = List(LANDS_ONLY_LIBRARY) { FOREST })
            val after = resolveTopOfStack(state).pausedState

            after.pendingCascade shouldBe null
            after.sharedZones.exile.shouldBeEmpty()
            after.player(alice).library shouldHaveSize LANDS_ONLY_LIBRARY
            after.player(alice).library.map { it.card } shouldContainExactlyInAnyOrder
                List(LANDS_ONLY_LIBRARY) { CardRef(FOREST) }
            after.events.filterIsInstance<GameEvent.CardsPutOnBottomInRandomOrder>() shouldHaveSize 1
            // ADR-006: the order came from the match PRNG, so the generator advanced.
            (after.rng == state.rng) shouldBe false
        }

        "CR 702.85a / ADR-005: a cascade hit with no legal target is not offered, and is bottomed instead" {
            // The hit is a spell that must target a creature, on a board with none: casting it is not a
            // legal line, so the engine asks nothing rather than offering a yes/no whose yes dead-ends.
            val state = cascadeTriggerState(library = listOf(FOREST, TARGETED_SPELL))
            val after = resolveTopOfStack(state).pausedState

            after.pendingCascade shouldBe null
            after.sharedZones.exile.shouldBeEmpty()
            after.player(alice).library.map { it.card } shouldContainExactlyInAnyOrder
                listOf(CardRef(FOREST), CardRef(TARGETED_SPELL))
        }

        "CR 702.85a: a cascade record's free-cast candidate is one of the cards it exiled" {
            shouldThrow<IllegalArgumentException> {
                PendingCascade(alice, persistentListOf(ObjectId(1)), candidateObjectId = ObjectId(2))
            }
        }
    })

// ---- fixtures ------------------------------------------------------------------------------------------

private const val GOLEM: String = "Fixture Prototype Golem"
private const val PLAIN_CREATURE: String = "Fixture Plain Golem"
private const val CASCADER: String = "Fixture Cascader"
private const val FOREST: String = "Fixture Forest"
private const val TARGETED_SPELL: String = "Fixture Creature Zap"

private const val PRINTED_POWER: Int = 6
private const val PRINTED_TOUGHNESS: Int = 5
private const val PRINTED_MANA_VALUE: Int = 6
private const val PROTOTYPE_SIZE: Int = 3
private const val PROTOTYPE_MANA_VALUE: Int = 2
private const val LANDS_ONLY_LIBRARY: Int = 4

/** The cascading spell's mana value, and therefore the dig's "lesser than" threshold (CR 702.85a). */
private const val CASCADE_THRESHOLD: Int = 8

private val fixturePrototype =
    CastingPermission.Prototype(cost = ManaCost.parse("{1}{G}"), power = PROTOTYPE_SIZE, toughness = PROTOTYPE_SIZE)

private fun creatureCharacteristics(name: String) =
    PrintedCharacteristics(
        name = name,
        manaCost = ManaCost.parse("{6}"),
        supertypes = persistentSetOf(),
        cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
        subtypes = persistentSetOf(),
        powerToughness = PrintedPowerToughness(PRINTED_POWER, PRINTED_TOUGHNESS),
    )

/** A `{6}` 6/5 artifact creature with `Prototype {1}{G} — 3/3` (Boulderbranch Golem's shape). */
private val fixturePrototypeGolem: SpellDefinition =
    object : SpellDefinition {
        override val characteristics = creatureCharacteristics(GOLEM)
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val castingPermissions = listOf(fixturePrototype)
    }

/** The same body with no prototype ability — the card a stray marker must fail loudly against. */
private val fixturePlainGolem: SpellDefinition =
    object : SpellDefinition {
        override val characteristics = creatureCharacteristics(PLAIN_CREATURE)
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/** An `{8}` creature printing cascade (Maelstrom Colossus's shape). */
private val fixtureCascader: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = CASCADER,
                manaCost = ManaCost.parse("{8}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(7, 7),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val cascade = true
    }

/** A plain land, for the cards a cascade dig walks past. */
private val fixtureCascadeForest: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = FOREST,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
    }

/** A `{1}` sorcery that must target a creature — uncastable on an empty battlefield (CR 601.2c). */
private val fixtureCreatureZap: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = TARGETED_SPELL,
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec =
            TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        override val resolution = ResolutionEffect { state, _ -> state }
    }

private val registry: Map<CardRef, CardDefinition> =
    listOf(
        fixturePrototypeGolem,
        fixturePlainGolem,
        fixtureCascader,
        fixtureCascadeForest,
        fixtureCreatureZap,
    ).associateBy { CardRef(it.characteristics.name) }

// ---- states --------------------------------------------------------------------------------------------

private fun seat(library: List<GameObject> = emptyList()) =
    PlayerState(
        life = STARTING_LIFE,
        library = library.toPersistentList(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
        priorityStatus = PriorityStatus.NONE,
    )

private fun stateWith(
    battlefield: List<GameObject> = emptyList(),
    stack: List<StackEntry> = emptyList(),
    library: List<GameObject> = emptyList(),
    nextObjectId: Long = 500,
): GameState =
    GameState(
        players = persistentMapOf(alice to seat(library), bob to seat()),
        turn = Turn(alice, 5, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(battlefield.toPersistentList(), stack.toPersistentList(), persistentListOf()),
        nextObjectId = nextObjectId,
        rng = Rng(7),
        events = persistentListOf(),
        definitions = registry.toPersistentMap(),
    )

private fun battlefieldStateOf(obj: GameObject): GameState = stateWith(battlefield = listOf(obj))

/** A state whose stack holds one Golem spell, cast via [castVia]. */
private fun stackStateFor(castVia: CastingPermission?): GameState =
    stateWith(
        stack =
            listOf(
                StackEntry.Spell(
                    obj = GameObject(ObjectId(10), CardRef(GOLEM), alice),
                    controller = alice,
                    targets = persistentListOf(),
                    definition = fixturePrototypeGolem,
                    castVia = castVia,
                ),
            ),
    )

private fun GameState.spellEntry(): StackEntry.Spell = sharedZones.stack.filterIsInstance<StackEntry.Spell>().single()

/**
 * A state whose stack holds a resolving cascade trigger, with [library] on top of alice's library. The
 * trigger carries the cascading spell's mana value as its linked information, exactly as
 * `detectCascadeTrigger` records it at CR 601.2i.
 */
private fun cascadeTriggerState(library: List<String>): GameState {
    val libraryObjects = library.mapIndexed { index, name -> GameObject(ObjectId(100L + index), CardRef(name), alice) }
    val trigger =
        PendingTrigger(
            sourceId = ObjectId(20),
            sourceCard = CardRef(CASCADER),
            controller = alice,
            ability =
                TriggeredAbility(
                    condition = TriggerCondition.CascadeCast,
                    effect = ResolutionEffect { state, _ -> state },
                    zoneScope = TriggerZoneScope.Stack,
                ),
            amount = CASCADE_THRESHOLD,
        )
    return stateWith(stack = listOf(StackEntry.Ability(trigger, persistentListOf())), library = libraryObjects)
}
