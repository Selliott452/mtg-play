package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.Counter
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
import dev.mtgplay.rules.effect.putCounters
import dev.mtgplay.rules.effect.sacrificePermanent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `W10-D`: the two trigger conditions Writhing Chrysalis needs — [TriggerCondition.CastSelf], the
 * self-referential cast trigger that functions from the stack (CR 603.2), and
 * [TriggerCondition.YouSacrificedAnother], the engine's first watcher of a CR 701.17a sacrifice and its
 * first condition with a CR 205.3 subtype axis.
 *
 * `mtg-rules` names no card (ADR-003), so the fixture is a Brood creature that makes two tokens when cast
 * and grows when another Beast it controls is sacrificed — the real card's shape without its name.
 *
 * The claims, each a way the pair could be silently wrong:
 * 1. the cast trigger goes on the stack **above** its own spell, so its tokens exist while the creature
 *    spell is still an unresolved object — the observable difference between this and an ETB trigger;
 * 2. a spell with no such ability fires nothing, so the detector costs nothing to every other card;
 * 3. the sacrifice watcher fires for a permanent of the declared subtype;
 * 4. **"another"** — never for the source's own sacrifice;
 * 5. **"you"** — never for an opponent's sacrifice;
 * 6. the subtype really is checked, so a sacrificed permanent of another type fires nothing;
 * 7. the subtype is answered through CR 702.73a changeling rather than the printed line;
 * 8. every sacrifice path reaches the same watcher, so a mana ability's sacrifice-self cost fires it too.
 */
class SelfCastAndSacrificeTriggerSpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val bob = PlayerId(1)

        "CR 603.2: a cast trigger resolves while its own spell is still on the stack" {
            val cast = castBrood(engine, triggerState(broodsInHand = 1))
            // The cast completed and the trigger was put on the stack above the creature spell
            // (CR 601.2i then CR 603.3b): two objects, the spell below and its own ability above.
            cast.pausedState.sharedZones.stack shouldHaveSize 2
            val resolved = passUntilStackSize(engine, cast, size = 1)
            // The tokens are on the battlefield and the creature spell has still not resolved. Nothing
            // that happens to that spell now — a counterspell, a fizzle — can take the tokens back.
            resolved.sharedZones.battlefield.count { it.card == BROOD_TOKEN_REF } shouldBe BROOD_TOKENS
            resolved.sharedZones.stack
                .filterIsInstance<StackEntry.Spell>()
                .single()
                .obj.card shouldBe BROOD
        }

        "CR 603.2: a spell with no cast-self ability fires no trigger" {
            val state = triggerState(broodsInHand = 0, plainsInHand = 1)
            val cast = castNamed(engine, state, PLAIN)
            // One object on the stack — the spell — and no pending trigger behind it.
            cast.pausedState.sharedZones.stack shouldHaveSize 1
            cast.pausedState.pendingTriggers.shouldBeEmpty()
        }

        "CR 701.17a: sacrificing another Beast you control fires the watcher" {
            val state = sacrificeState()
            val beast = state.sharedZones.battlefield.first { it.card == BEAST && it.owner == alice }
            val fired = sacrificePermanent(state, beast.id)
            fired.pendingTriggers shouldHaveSize 1
            fired.pendingTriggers.single().sourceCard shouldBe BROOD
        }

        "CR 603.2: 'another' excludes the source's own sacrifice" {
            val state = sacrificeState()
            val brood = state.sharedZones.battlefield.first { it.card == BROOD }
            sacrificePermanent(state, brood.id).pendingTriggers.shouldBeEmpty()
        }

        "CR 603.2: 'you' excludes an opponent's sacrifice of the same subtype" {
            val state = sacrificeState()
            val theirs = state.sharedZones.battlefield.first { it.card == BEAST && it.owner == bob }
            sacrificePermanent(state, theirs.id).pendingTriggers.shouldBeEmpty()
        }

        "CR 205.3: a sacrificed permanent of another subtype fires nothing" {
            val state = sacrificeState()
            val forest = state.sharedZones.battlefield.first { it.card == FOREST && it.owner == alice }
            sacrificePermanent(state, forest.id).pendingTriggers.shouldBeEmpty()
        }

        "CR 702.73a: a changeling permanent has every creature type, so its sacrifice fires the watcher" {
            val state = sacrificeState()
            val shifter = state.sharedZones.battlefield.first { it.card == SHIFTER }
            sacrificePermanent(state, shifter.id).pendingTriggers shouldHaveSize 1
        }

        "CR 701.17a: a mana ability's sacrifice-self cost reaches the same watcher" {
            // The token the cast trigger makes is itself a Beast that sacrifices for mana, so the card's
            // own engine — spend a token for {C}, grow the creature — runs through one detection site.
            val state = sacrificeState()
            val token = state.sharedZones.battlefield.first { it.card == BROOD_TOKEN_REF }
            sacrificePermanent(state, token.id).pendingTriggers shouldHaveSize 1
        }

        "CR 608.2b: the counter lands on the source when the trigger's effect runs" {
            val state = sacrificeState()
            val brood = state.sharedZones.battlefield.first { it.card == BROOD }
            val beast = state.sharedZones.battlefield.first { it.card == BEAST && it.owner == alice }
            val fired = sacrificePermanent(state, beast.id)
            val trigger = fired.pendingTriggers.single()
            val context =
                ResolutionContext(
                    controller = trigger.controller,
                    targets = persistentListOf(),
                    source = trigger.sourceId,
                    sourceCard = trigger.sourceCard,
                )
            val applied = trigger.ability.effect.resolve(fired, context)
            applied.sharedZones.battlefield
                .first { it.id == brood.id }
                .counters[Counter.PLUS_ONE_PLUS_ONE] shouldBe 1
        }
    })

/** How many tokens the fixture's cast trigger creates. */
private const val BROOD_TOKENS: Int = 2

private val BROOD: CardRef = CardRef("Fixture Brood")
private val PLAIN: CardRef = CardRef("Fixture Plain Creature")
private val BEAST: CardRef = CardRef("Fixture Beast")
private val SHIFTER: CardRef = CardRef("Fixture Shifter")
private val FOREST: CardRef = CardRef("Fixture Trigger Forest")
private val BROOD_TOKEN_REF: CardRef = CardRef.token("Fixture Spawn")

private val BEAST_TYPE: Subtype = Subtype("Beast")

private fun creature(
    name: String,
    cost: String,
    subtypes: Set<Subtype>,
    keywords: Set<Keyword> = emptySet(),
): PrintedCharacteristics =
    PrintedCharacteristics(
        name = name,
        manaCost = ManaCost.parse(cost),
        supertypes = persistentSetOf(),
        cardTypes = persistentSetOf(CardType.CREATURE),
        subtypes = subtypes.toPersistentSetOrEmpty(),
        powerToughness = PrintedPowerToughness(power = 2, toughness = 3),
        keywords = keywords.toPersistentKeywords(),
    )

private fun Set<Subtype>.toPersistentSetOrEmpty() = persistentSetOf<Subtype>().addingAll(this)

private fun Set<Keyword>.toPersistentKeywords() = persistentSetOf<Keyword>().addingAll(this)

private val broodToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Fixture Spawn",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(BEAST_TYPE),
                powerToughness = PrintedPowerToughness(power = 0, toughness = 1),
            ),
    )

private val fixtureBrood: SpellDefinition =
    object : SpellDefinition {
        override val characteristics = creature("Fixture Brood", "{1}{G}", setOf(BEAST_TYPE))
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { s, _ -> s }
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.CastSelf,
                    zoneScope = TriggerZoneScope.Stack,
                    effect =
                        ResolutionEffect { s, ctx ->
                            (1..BROOD_TOKENS).fold(s) { current, _ -> createToken(current, ctx.controller, broodToken) }
                        },
                ),
                TriggeredAbility(
                    condition = TriggerCondition.YouSacrificedAnother(BEAST_TYPE),
                    effect =
                        ResolutionEffect { s, ctx ->
                            val source = ctx.source
                            if (source == null || s.sharedZones.battlefield.none { it.id == source }) {
                                s
                            } else {
                                putCounters(s, source, Counter.PLUS_ONE_PLUS_ONE)
                            }
                        },
                ),
            )
    }

private val fixturePlainCreature: SpellDefinition =
    object : SpellDefinition {
        override val characteristics = creature("Fixture Plain Creature", "{1}{G}", setOf(BEAST_TYPE))
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { s, _ -> s }
    }

private val fixtureBeast: SpellDefinition =
    object : SpellDefinition {
        override val characteristics = creature("Fixture Beast", "{G}", setOf(BEAST_TYPE))
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { s, _ -> s }
    }

private val fixtureShifter: SpellDefinition =
    object : SpellDefinition {
        // CR 702.73a: changeling makes it every creature type in every zone, "Beast" included.
        override val characteristics =
            creature("Fixture Shifter", "{G}", setOf(Subtype("Shapeshifter")), setOf(Keyword.CHANGELING))
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { s, _ -> s }
    }

private val fixtureTriggerForest: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Trigger Forest",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.GREEN)))
    }

private val triggerRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        BROOD to fixtureBrood,
        PLAIN to fixturePlainCreature,
        BEAST to fixtureBeast,
        SHIFTER to fixtureShifter,
        FOREST to fixtureTriggerForest,
        BROOD_TOKEN_REF to broodToken,
    )

/** A priority window for alice with [broodsInHand] Broods, [plainsInHand] plain creatures, and four lands. */
private fun triggerState(
    broodsInHand: Int,
    plainsInHand: Int = 0,
): GameState {
    var nextId = 0L

    fun obj(card: CardRef) = GameObject(ObjectId(nextId), card, alice).also { nextId += 1 }

    val field = List(4) { obj(FOREST) }.toPersistentList()
    val hand =
        (List(broodsInHand) { obj(BROOD) } + List(plainsInHand) { obj(PLAIN) }).toPersistentList()
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = hand,
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                    ),
                PlayerId(1) to
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
        definitions = triggerRegistry.toPersistentMap(),
    )
}

/**
 * A board with alice's Brood, a Beast, a changeling, a Forest and one of the Brood's tokens, plus a Beast
 * bob controls — everything the sacrifice watcher's five axes need, in one state.
 */
private fun sacrificeState(): GameState {
    var nextId = 0L

    fun obj(
        card: CardRef,
        owner: PlayerId,
    ) = GameObject(ObjectId(nextId), card, owner).also { nextId += 1 }

    val bob = PlayerId(1)
    val field =
        persistentListOf(
            obj(BROOD, alice),
            obj(BEAST, alice),
            obj(SHIFTER, alice),
            obj(FOREST, alice),
            obj(BROOD_TOKEN_REF, alice),
            obj(BEAST, bob),
        )

    fun seat() =
        PlayerState(
            life = STARTING_LIFE,
            library = persistentListOf(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
        )
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(field, persistentListOf(), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = triggerRegistry.toPersistentMap(),
    )
}

/** Casts the fixture Brood from alice's hand, stopping at the first window after the cast completes. */
private fun castBrood(
    engine: GameEngine,
    state: GameState,
): AdvanceResult = castNamed(engine, state, BROOD)

private fun castNamed(
    engine: GameEngine,
    state: GameState,
    card: CardRef,
): AdvanceResult {
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == card }
    check(index >= 0) { "$card is not castable in this window: ${window.options}" }
    val chosen = engine.advance(state, Decision.SingleSelect(window.id, index))
    return engine.advance(chosen.pausedState, planDecision(chosen.pending()))
}

/** Passes priority until the stack has shrunk to [size]. */
private fun passUntilStackSize(
    engine: GameEngine,
    from: AdvanceResult,
    size: Int,
): GameState {
    var current = from
    while (current.pausedState.sharedZones.stack.size > size) {
        current = engine.advance(current.pausedState, passDecision(current.pending()))
    }
    return current.pausedState
}
