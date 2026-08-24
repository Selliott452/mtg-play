package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.engine.CreatureDeathCause
import dev.mtgplay.rules.engine.StateBasedAction
import dev.mtgplay.rules.engine.applicableStateBasedActions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * Indestructible (CR 702.12) against the destruction the engine performs: the CR 704.5g lethal-damage
 * state-based action. The keyword is a *printed characteristic* read through the effective-keyword
 * accessor, so a granted indestructible would be honoured by the same seam.
 *
 * The exemption is deliberately narrow. CR 704.5f — toughness 0 or less — is not destruction
 * (`CreatureDeathCause` records the distinction), so an indestructible creature with 0 toughness still
 * goes to the graveyard; that boundary is pinned here so a future "destroy target permanent" effect
 * cannot widen the exemption by accident.
 *
 * Uses fixtures — engine tests never name a real card. The gauntlet cards that print indestructible are
 * the Bridge artifact lands, which are never creatures and so never reach CR 704.5g at all.
 */
class IndestructibleSpec :
    StringSpec({

        "CR 702.12b: a creature with indestructible is not destroyed by lethal damage (CR 704.5g)" {
            val state = battlefieldOf(creature(0, TOUGH_INDESTRUCTIBLE, damage = TOUGHNESS))
            applicableStateBasedActions(state).shouldBeEmpty()
        }

        "CR 704.5g: the same creature without indestructible is destroyed by the same damage" {
            val state = battlefieldOf(creature(0, TOUGH_MORTAL, damage = TOUGHNESS))
            applicableStateBasedActions(state) shouldContainExactly
                listOf(StateBasedAction.CreatureDies(ObjectId(0), CreatureDeathCause.LETHAL_DAMAGE))
        }

        "CR 702.12b: the exemption is per-permanent — the mortal beside it still dies" {
            val state =
                battlefieldOf(
                    creature(0, TOUGH_INDESTRUCTIBLE, damage = TOUGHNESS),
                    creature(1, TOUGH_MORTAL, damage = TOUGHNESS),
                )
            applicableStateBasedActions(state) shouldContainExactly
                listOf(StateBasedAction.CreatureDies(ObjectId(1), CreatureDeathCause.LETHAL_DAMAGE))
        }

        "CR 704.5f: indestructible does not save a creature with toughness 0 or less — that is not destruction" {
            val state = battlefieldOf(creature(0, FRAIL_INDESTRUCTIBLE, damage = 0))
            applicableStateBasedActions(state) shouldContainExactly
                listOf(StateBasedAction.CreatureDies(ObjectId(0), CreatureDeathCause.ZERO_OR_LESS_TOUGHNESS))
        }

        "CR 702.12b: sublethal damage on an indestructible creature is still merely marked" {
            val state = battlefieldOf(creature(0, TOUGH_INDESTRUCTIBLE, damage = TOUGHNESS - 1))
            applicableStateBasedActions(state).shouldBeEmpty()
            state.sharedZones.battlefield
                .single()
                .damageMarked shouldBe TOUGHNESS - 1
        }
    })

/** The toughness of the fixture creatures that have one — small enough to mark lethal damage by hand. */
private const val TOUGHNESS: Int = 3

/** "Fixture Bulwark" — a 1/3 with indestructible (CR 702.12). */
private const val TOUGH_INDESTRUCTIBLE: String = "Fixture Bulwark"

/** "Fixture Sapling" — the same 1/3 without it, the destruction control. */
private const val TOUGH_MORTAL: String = "Fixture Sapling"

/** "Fixture Wisp" — a 1/0 with indestructible, for the CR 704.5f boundary. */
private const val FRAIL_INDESTRUCTIBLE: String = "Fixture Wisp"

/** A creature fixture of the given toughness, optionally printing indestructible. */
private fun creatureFixture(
    name: String,
    toughness: Int,
    indestructible: Boolean,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power = 1, toughness = toughness),
                keywords = if (indestructible) persistentSetOf(Keyword.INDESTRUCTIBLE) else persistentSetOf(),
            )
    }

/** The fixtures this spec registers, keyed by ref. */
private val indestructibleFixtures: Map<CardRef, CardDefinition> =
    listOf(
        creatureFixture(TOUGH_INDESTRUCTIBLE, TOUGHNESS, indestructible = true),
        creatureFixture(TOUGH_MORTAL, TOUGHNESS, indestructible = false),
        creatureFixture(FRAIL_INDESTRUCTIBLE, toughness = 0, indestructible = true),
    ).associateBy { CardRef(it.characteristics.name) }

/** A battlefield object [id] of the fixture [card], with [damage] marked on it (CR 120.3). */
private fun creature(
    id: Long,
    card: String,
    damage: Int,
): GameObject = GameObject(ObjectId(id), CardRef(card), alice, damageMarked = damage)

/**
 * A minimal paused state holding [creatures] on the battlefield, both seats at 20 life — a valid engine
 * input by construction (ADR-004), and enough for the CR 704.5 check under test.
 */
private fun battlefieldOf(vararg creatures: GameObject): GameState {
    fun seat() = PlayerState(STARTING_LIFE, persistentListOf(), persistentListOf(), persistentListOf())
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = creatures.toList().toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = creatures.size.toLong(),
        rng = Rng(0),
        events = persistentListOf(),
        definitions = indestructibleFixtures.toPersistentMap(),
    )
}
