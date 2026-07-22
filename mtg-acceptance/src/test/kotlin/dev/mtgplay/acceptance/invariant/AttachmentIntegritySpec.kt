package dev.mtgplay.acceptance.invariant

import dev.mtgplay.acceptance.STARTING_LIFE
import dev.mtgplay.acceptance.alice
import dev.mtgplay.acceptance.bob
import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.ResolutionEffect
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
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P4.1 [Invariant.ATTACHMENT_INTEGRITY] check: an Aura's `attachedTo` is well-formed at every
 * observed pause (CR 303.4, CR 704.5m). The checker tolerates no dangling attachment because it
 * only ever sees post-SBA-quiescence states (docs/design/layer-system.md §5).
 */
class AttachmentIntegritySpec :
    StringSpec({

        "CR 303.4: an object attached while off the battlefield is one ATTACHMENT_INTEGRITY violation" {
            // A hand object cannot carry an attachment — it is a battlefield-only status (CR 400.7).
            val handAura = GameObject(ObjectId(5), CardRef("Test Aura"), alice, attachedTo = ObjectId(9))
            val state = attachmentState(battlefield = emptyList(), aliceHand = listOf(handAura))
            checkAttachmentIntegrity(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.ATTACHMENT_INTEGRITY)
        }

        "CR 704.5m: a battlefield Aura attached to a gone object is one ATTACHMENT_INTEGRITY violation" {
            // A dangling attachment at a pause means the fall-off SBA failed to fire (CR 704.3).
            val aura = GameObject(ObjectId(1), CardRef("Test Aura"), alice, attachedTo = ObjectId(99))
            val state = attachmentState(battlefield = listOf(aura))
            checkAttachmentIntegrity(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.ATTACHMENT_INTEGRITY)
        }

        "CR 303.4: a non-Aura carrying an attachment is one ATTACHMENT_INTEGRITY violation" {
            val bear = GameObject(ObjectId(0), CardRef("Test Bear"), alice)
            // Test Bear is a creature, not an Aura — it must not carry an attachment even to a real object.
            val impostor = GameObject(ObjectId(1), CardRef("Test Bear"), alice, attachedTo = ObjectId(0))
            val state = attachmentState(battlefield = listOf(bear, impostor))
            checkAttachmentIntegrity(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.ATTACHMENT_INTEGRITY)
        }

        "an Aura attached to a battlefield creature is clean" {
            val bear = GameObject(ObjectId(0), CardRef("Test Bear"), alice)
            val aura = GameObject(ObjectId(1), CardRef("Test Aura"), alice, attachedTo = ObjectId(0))
            checkAttachmentIntegrity(attachmentState(battlefield = listOf(bear, aura))).shouldBeEmpty()
        }

        "an unattached battlefield permanent is clean" {
            val bear = GameObject(ObjectId(0), CardRef("Test Bear"), alice)
            checkAttachmentIntegrity(attachmentState(battlefield = listOf(bear))).shouldBeEmpty()
        }
    })

// A 2/2 creature body fixture.
private fun creatureFixture(name: String): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(2, 2),
            )
    }

// An Aura fixture: an enchantment whose enchant ability is a TargetSpec.Enchantable (the aura signal).
private fun auraFixture(name: String): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.Enchantable(EnchantRestriction.CREATURE)
        override val resolution = ResolutionEffect { state, _ -> state }
    }

private val attachmentDefinitions: Map<CardRef, CardDefinition> =
    listOf(creatureFixture("Test Bear"), auraFixture("Test Aura")).associateBy { CardRef(it.characteristics.name) }

// A handcrafted main-phase state over the attachment fixtures, with the given battlefield and hand.
private fun attachmentState(
    battlefield: List<GameObject>,
    aliceHand: List<GameObject> = emptyList(),
): GameState {
    val ids = (battlefield + aliceHand).map { it.id.value }

    fun seat(hand: List<GameObject>) =
        PlayerState(STARTING_LIFE, persistentListOf(), hand.toPersistentList(), persistentListOf())
    return GameState(
        players = persistentMapOf(alice to seat(aliceHand), bob to seat(emptyList())),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = (ids.maxOrNull() ?: -1L) + 1,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = attachmentDefinitions.toPersistentMap(),
    )
}
