package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.AttackerAssignment
import dev.mtgplay.core.state.BlockAssignment
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.engine.Chooser
import dev.mtgplay.rules.engine.creaturesBlockedBy
import dev.mtgplay.rules.engine.legalTargets
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

/**
 * The "target creature **it's blocking**" enumeration and its CR 113.7c capture (`W9-F`) — Tinder Wall.
 *
 * Pinned at the **enumeration**, because that is where legality is defined (ADR-005): the option list an
 * agent sees, the CR 601.2c choice and the CR 608.2b re-check are one function, so a test of the
 * enumeration is a test of all three. That is also what makes the last case here the important one — the
 * re-check runs against a board where the Wall has been sacrificed and *nothing* is blocking any more,
 * and it must still name the attacker.
 *
 * `mtg-rules` names no card, so these are plain battlefield objects and a plain combat state.
 */
class BlockedBySourceTargetingSpec :
    StringSpec({

        val wall = ObjectId(0)
        val attacker = ObjectId(1)
        val bystander = ObjectId(2)
        val otherAttacker = ObjectId(3)

        "CR 509.1: the capture names exactly the attackers the source is blocking" {
            val state = combatBoard(blocks = listOf(BlockAssignment(wall, attacker)))

            creaturesBlockedBy(state, wall) shouldBe persistentSetOf(attacker)
            // A creature that declared no block is blocking nothing, and neither is an attacker.
            creaturesBlockedBy(state, bystander).shouldBeEmpty()
            creaturesBlockedBy(state, attacker).shouldBeEmpty()
        }

        "CR 509.1: outside combat, and before blockers are declared, the capture is empty" {
            creaturesBlockedBy(combatBoard(blocks = null), wall).shouldBeEmpty()
            creaturesBlockedBy(combatBoard(combat = null), wall).shouldBeEmpty()
        }

        "CR 115.1b: the spec offers exactly the captured attacker, not every attacker in combat" {
            val state =
                combatBoard(
                    attackers = listOf(attacker, otherAttacker),
                    blocks = listOf(BlockAssignment(wall, attacker)),
                )
            val chooser = Chooser.Ability(WALL_CARD, creaturesBlockedBy(state, wall).toPersistentSet())

            // The *other* attacker is a creature in the same combat and is still not a legal target:
            // the printed line is "it's blocking", not "attacking".
            legalTargets(state, TargetSpec.CreatureBlockedBySource, bob, chooser) shouldContainExactly
                listOf(Target.Permanent(attacker))
        }

        "CR 113.7c: the re-check reads the capture, so a sacrificed source still names its attacker" {
            val inCombat = combatBoard(blocks = listOf(BlockAssignment(wall, attacker)))
            val chooser = Chooser.Ability(WALL_CARD, creaturesBlockedBy(inCombat, wall).toPersistentSet())

            // CR 601.2h: the sacrifice cost is paid *after* the target is chosen. The Wall is gone from
            // the battlefield and from combat — this is the board the CR 608.2b re-check actually sees.
            val afterSacrifice =
                inCombat.copy(
                    sharedZones =
                        inCombat.sharedZones.copy(
                            battlefield =
                                inCombat.sharedZones.battlefield
                                    .filterNot { it.id == wall }
                                    .toPersistentList(),
                        ),
                    turn = inCombat.turn.copy(combat = inCombat.turn.combat?.copy(blocks = persistentListOf())),
                )

            legalTargets(afterSacrifice, TargetSpec.CreatureBlockedBySource, bob, chooser) shouldContainExactly
                listOf(Target.Permanent(attacker))
        }

        "CR 608.2b: the target is still re-checked — an attacker that has left the battlefield fizzles" {
            val inCombat = combatBoard(blocks = listOf(BlockAssignment(wall, attacker)))
            val chooser = Chooser.Ability(WALL_CARD, creaturesBlockedBy(inCombat, wall).toPersistentSet())
            val attackerGone =
                inCombat.copy(
                    sharedZones =
                        inCombat.sharedZones.copy(
                            battlefield =
                                inCombat.sharedZones.battlefield
                                    .filterNot { it.id == attacker }
                                    .toPersistentList(),
                        ),
                )

            // The capture is not a licence to damage a dead creature: the candidate must still be a
            // creature on the battlefield, exactly as for any other permanent spec.
            legalTargets(attackerGone, TargetSpec.CreatureBlockedBySource, bob, chooser).shouldBeEmpty()
        }

        "CR 113.7a: a chooser that captured nothing enumerates nothing — a spell is never blocking" {
            val state = combatBoard(blocks = listOf(BlockAssignment(wall, attacker)))

            legalTargets(state, TargetSpec.CreatureBlockedBySource, bob, Chooser.Ability(WALL_CARD)).shouldBeEmpty()
            legalTargets(state, TargetSpec.CreatureBlockedBySource, bob, Chooser.Spell(wall)).shouldBeEmpty()
            legalTargets(state, TargetSpec.CreatureBlockedBySource, bob, Chooser.Nobody).shouldBeEmpty()
        }
    })

/** The printed identity standing in for the blocking source; `mtg-rules` names no real card. */
private val WALL_CARD = CardRef("Fixture Wall")

/** The printed identity of the fixture creatures on both sides of combat. */
private val CREATURE_CARD = CardRef("Fixture Creature")

/** A vanilla 2/2 creature definition, enough for the enumeration's CR 302 creature-hood test. */
private fun vanillaCreature(name: CardRef): CardDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name.name,
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/**
 * A declare-blockers board: Alice attacks with two creatures, Bob holds a Wall and a bystander.
 *
 * [blocks] `null` means the declare-blockers turn-based action has not run yet (CR 509.1), which is a
 * different state from an empty list and is what the second case tells apart.
 */
private fun combatBoard(
    attackers: List<ObjectId> = listOf(ObjectId(1)),
    blocks: List<BlockAssignment>? = emptyList(),
    combat: Unit? = Unit,
): GameState {
    val battlefield =
        persistentListOf(
            GameObject(ObjectId(0), WALL_CARD, bob),
            GameObject(ObjectId(1), CREATURE_CARD, alice),
            GameObject(ObjectId(2), CREATURE_CARD, bob),
            GameObject(ObjectId(3), CREATURE_CARD, alice),
        )

    fun seat() = PlayerState(STARTING_LIFE, persistentListOf(), persistentListOf(), persistentListOf())
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn =
            Turn(
                alice,
                3,
                TurnPhase.COMBAT,
                TurnStep.DECLARE_BLOCKERS,
                combat =
                    combat?.let {
                        CombatState(
                            attackers = attackers.map { AttackerAssignment(it, bob) }.toPersistentList(),
                            blocks = blocks?.toPersistentList(),
                            blockedAttackers = blocks.orEmpty().map(BlockAssignment::attacker).toPersistentSet(),
                        )
                    },
            ),
        sharedZones = SharedZones(battlefield = battlefield, stack = persistentListOf(), exile = persistentListOf()),
        nextObjectId = 10,
        rng = Rng(0),
        events = persistentListOf(),
        definitions =
            mapOf(WALL_CARD to vanillaCreature(WALL_CARD), CREATURE_CARD to vanillaCreature(CREATURE_CARD))
                .toPersistentMap(),
    )
}
