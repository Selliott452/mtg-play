package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ClauseCondition
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * [ClauseCondition] (`W9-D`, CR 608.2c): a post-resolution clause gated on the resolving spell's own cast
 * record. Fixture card only; the `mtg-rules`-names-no-card rule holds.
 *
 * The whole claim is an ADR-005 one, and it is not observable from the definition: a spell whose condition
 * is false must open **no pause at all**, rather than opening the clause's pause and answering it
 * vacuously. So both branches are resolved through [resolveTopOfStack] and the pause itself is what is
 * asserted, not the library that comes out the other side.
 */
class ClauseConditionSpec :
    StringSpec({

        "CR 608.2c: a spell that paid its optional additional cost runs its gated clause" {
            val result = resolveTopOfStack(conditionalScryState(paid = true))

            // The scry pause opened: a CR 701.17a arrangement request is due, and the spell is still on
            // the stack until it is answered.
            result
                .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
                .request
                .shouldBeInstanceOf<DecisionRequest.ChooseLibraryArrangement>()
            result.pausedState.sharedZones.stack.size shouldBe 1
        }

        "ADR-005: a spell that declined the cost opens no pause at all — not a pause with one answer" {
            val result = resolveTopOfStack(conditionalScryState(paid = false))

            // No arrangement request, and the spell has finished resolving into the graveyard (CR 608.2m).
            val request = result.shouldBeInstanceOf<AdvanceResult.NeedsDecision>().request
            (request as? DecisionRequest.ChooseLibraryArrangement).shouldBeNull()
            result.pausedState.sharedZones.stack
                .shouldBeEmpty()
            result.pausedState.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldBe listOf(CardRef(CONDITIONAL_SCRY))
        }

        "CR 608.2c: the library is untouched on the declined branch" {
            val declined = resolveTopOfStack(conditionalScryState(paid = false)).pausedState
            declined.players
                .getValue(alice)
                .library
                .map { it.card } shouldBe
                listOf(CardRef("Gate Bear"), CardRef("Gate Bolt"))
        }
    })

private const val CONDITIONAL_SCRY: String = "Gate Scryer"

/**
 * The fixture: an instant with a scry-1 clause gated on
 * [ClauseCondition.SpellPaidOptionalAdditionalCost]. It declares no
 * [SpellDefinition.optionalAdditionalCost] of its own — the condition reads the *cast record*, which the
 * scenario writes directly, so the gate is exercised without also exercising the CR 601.2b announcement
 * that a full cast would bring.
 */
private val conditionalScryFixture: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = CONDITIONAL_SCRY,
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val libraryLook = LibraryLook(LibraryLookMode.Scry(1))
        override val clauseCondition = ClauseCondition.SpellPaidOptionalAdditionalCost
    }

private fun plainCard(name: String): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

private val conditionalScryRegistry: Map<CardRef, CardDefinition> =
    listOf(conditionalScryFixture, plainCard("Gate Bear"), plainCard("Gate Bolt"))
        .associateBy { CardRef(it.characteristics.name) }

/** The fixture on top of a two-card library, with [paid] written onto its cast record (CR 601.2b). */
private fun conditionalScryState(paid: Boolean): GameState {
    var nextId = 0L

    fun obj(
        name: String,
        owner: PlayerId,
    ) = GameObject(ObjectId(nextId++), CardRef(name), owner)

    val spellObject = obj(CONDITIONAL_SCRY, alice)
    val library = listOf(obj("Gate Bear", alice), obj("Gate Bolt", alice)).toPersistentList()
    val entry =
        StackEntry.Spell(
            obj = spellObject,
            controller = alice,
            targets = persistentListOf(),
            definition = conditionalScryFixture,
            optionalCostPaid = paid,
        )

    fun seat(owner: PlayerId) =
        PlayerState(
            life = STARTING_LIFE,
            library = if (owner == alice) library else persistentListOf(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
            priorityStatus = PriorityStatus.NONE,
        )
    return GameState(
        players = persistentMapOf(alice to seat(alice), bob to seat(bob)),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(entry), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(5),
        events = persistentListOf(),
        definitions = conditionalScryRegistry.toPersistentMap(),
    )
}
