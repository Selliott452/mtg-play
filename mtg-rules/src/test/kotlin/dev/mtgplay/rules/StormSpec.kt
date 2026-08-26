package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
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
import dev.mtgplay.core.state.cardObject
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.effect.gainLife
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `W9-C`: storm (CR 702.40) and the spell-copying primitive it is the first client of (CR 707.10).
 *
 * Four claims, and each of them is a way the keyword could be silently wrong:
 * 1. the count is **every** player's spells cast **before** it this turn, so a storm spell never counts
 *    itself and an opponent's cantrip grows it;
 * 2. the copies really are separate objects on the stack, not one scaled effect;
 * 3. a copy is **not a card** (CR 707.10a), so it contributes no zone residence and reaches no graveyard;
 * 4. a copy is not *cast*, so it never feeds the tally — the one mistake that would make storm compound.
 *
 * `mtg-rules` names no card (ADR-003): the fixture is a "gain 2 life" instant with the keyword, which is
 * Weather the Storm's shape without its name.
 */
class StormSpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val surge = CardRef("Fixture Storm Surge")
        val cantrip = CardRef("Fixture Cantrip")

        "CR 702.40a: the first spell of a turn has a storm count of zero and makes no copies" {
            val state = stormState(handSurges = 1, handCantrips = 0, lands = 4)
            val resolved = castAndResolveAll(engine, state, surge)
            // One resolution, one lifegain: the storm trigger resolved having copied nothing, which is
            // the ordinary case rather than a degenerate one.
            resolved.players.getValue(alice).life shouldBe STARTING_LIFE + STORM_LIFE
            resolved.sharedZones.stack.isEmpty() shouldBe true
        }

        "CR 702.40a: two prior spells this turn make two copies, and all three resolutions happen" {
            val state = stormState(handSurges = 1, handCantrips = 2, lands = 6)
            var current: GameState = state
            repeat(2) { current = castAndResolveAll(engine, current, cantrip) }
            current.turn.spellsCastThisTurn shouldBe 2
            val resolved = castAndResolveAll(engine, current, surge)
            // Three lifegains: the original plus one copy per spell cast before it.
            resolved.players.getValue(alice).life shouldBe STARTING_LIFE + STORM_LIFE * 3
        }

        "CR 707.10a: a copy is not a card — it never reaches a graveyard, and only the original does" {
            val state = stormState(handSurges = 1, handCantrips = 1, lands = 6)
            var current = castAndResolveAll(engine, state, cantrip)
            current = castAndResolveAll(engine, current, surge)
            // Exactly one Storm Surge card exists anywhere, and it is in the graveyard (CR 608.2m). The
            // copy simply ceased to exist (CR 707.10a / CR 704.5e), which is what `cardObject` returning
            // null for a copy buys: no census entry, no zone residence, no second card.
            current.players
                .getValue(alice)
                .graveyard
                .count { it.card == surge } shouldBe 1
            current.sharedZones.battlefield.none { it.card == surge } shouldBe true
            current.sharedZones.exile.none { it.card == surge } shouldBe true
        }

        "CR 707.10a: a copy is created rather than cast, so it does not grow the turn's tally" {
            // The compounding mistake. With one prior spell the tally must end at 2 — the cantrip and the
            // Surge — never at 3, which is what counting the copy would produce.
            val state = stormState(handSurges = 1, handCantrips = 1, lands = 6)
            var current = castAndResolveAll(engine, state, cantrip)
            current = castAndResolveAll(engine, current, surge)
            current.turn.spellsCastThisTurn shouldBe 2
        }

        "CR 601.2i: the tally is game-wide — an opponent's spell grows a later storm count" {
            // Storm counts "each spell cast before it this turn" without qualification, so the counter
            // lives on the turn rather than on a seat. Simulated by seeding the turn's tally, which is
            // exactly what an opponent's cast would have left behind.
            val seeded = stormState(handSurges = 1, handCantrips = 0, lands = 4)
            val withOpponentSpells = seeded.copy(turn = seeded.turn.copy(spellsCastThisTurn = 3))
            val resolved = castAndResolveAll(engine, withOpponentSpells, surge)
            resolved.players.getValue(alice).life shouldBe STARTING_LIFE + STORM_LIFE * 4
        }

        "CR 707.10a: a copy on the stack carries a fresh object id and is marked as a copy" {
            val state = stormState(handSurges = 1, handCantrips = 1, lands = 6)
            var current: AdvanceResult = castUntilStackHolds(engine, castAndResolveAll(engine, state, cantrip), surge)
            // Storm is a *cast* trigger, so it sits above the spell that made it and needs one full
            // priority round to resolve before any copy exists — the ordering that makes the copies
            // resolve before the original.
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            // The storm trigger has resolved; a copy and the original are both on the stack.
            val spells =
                current.pausedState.sharedZones.stack
                    .filterIsInstance<StackEntry.Spell>()
            spells.size shouldBe 2
            spells.count { it.isCopy } shouldBe 1
            spells.map { it.obj.id }.distinct().size shouldBe 2
            // The seam that keeps a copy out of the card census and the zone-residence invariant.
            spells.first { it.isCopy }.cardObject.shouldBeNull()
        }
    })

/** The life the fixture storm spell gains, per resolution. */
private const val STORM_LIFE: Int = 2

/**
 * Casts [card] from alice's hand and passes until the stack is empty, returning the resulting state.
 * Every request the fixtures can raise is a priority window or a payment plan, so the loop needs no
 * policy beyond "take the first option".
 */
private fun castAndResolveAll(
    engine: GameEngine,
    state: GameState,
    card: CardRef,
): GameState {
    var current: AdvanceResult = castUntilStackHolds(engine, state, card)
    while (current.pausedState.sharedZones.stack
            .isNotEmpty()
    ) {
        val request = current.pending<DecisionRequest.ChooseAction>()
        current = engine.advance(current.pausedState, passDecision(request))
    }
    return current.pausedState
}

/** Casts [card] and stops at the first priority window after the cast completes. */
private fun castUntilStackHolds(
    engine: GameEngine,
    state: GameState,
    card: CardRef,
): AdvanceResult {
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == card }
    check(index >= 0) { "$card is not castable in this window: ${window.options}" }
    var current = engine.advance(state, Decision.SingleSelect(window.id, index))
    current = engine.advance(current.pausedState, planDecision(current.pending()))
    return current
}

private val stormSurge: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Storm Surge",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val storm = true
        override val resolution = ResolutionEffect { s, ctx -> gainLife(s, ctx.controller, STORM_LIFE) }
    }

private val stormCantrip: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Cantrip",
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { s, _ -> s }
    }

private val stormForest: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Storm Forest",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.GREEN)))
    }

private val stormRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef("Fixture Storm Surge") to stormSurge,
        CardRef("Fixture Cantrip") to stormCantrip,
        CardRef("Storm Forest") to stormForest,
    )

private fun stormState(
    handSurges: Int,
    handCantrips: Int,
    lands: Int,
): GameState {
    var nextId = 0L

    fun obj(card: CardRef) = GameObject(ObjectId(nextId), card, alice).also { nextId += 1 }

    val field = List(lands) { obj(CardRef("Storm Forest")) }.toPersistentList()
    val hand =
        (
            List(handCantrips) { obj(CardRef("Fixture Cantrip")) } +
                List(handSurges) { obj(CardRef("Fixture Storm Surge")) }
        ).toPersistentList()
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
        definitions = stormRegistry.toPersistentMap(),
    )
}
