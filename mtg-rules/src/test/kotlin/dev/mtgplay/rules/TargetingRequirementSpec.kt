package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TargetingRequirement
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.engine.Chooser
import dev.mtgplay.rules.engine.announceableTargets
import dev.mtgplay.rules.engine.legalTargets
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

/**
 * CR 601.2c targeting **requirements** (`W8-G`, docs/design/protection.md §8) — Standard Bearer's shape,
 * exercised against fixtures because `mtg-rules` names no card (ADR-003).
 *
 * The whole framework is the difference between two questions the engine must never confuse: which
 * targets are *legal* ([legalTargets], re-asked at CR 608.2b) and which may be *announced*
 * ([announceableTargets], asked once at CR 601.2c). Every spec below is a property of that split.
 */
class TargetingRequirementSpec :
    StringSpec({

        "CR 601.2c: with a Flagbearer on the battlefield, an opponent's announceable targets are only Flagbearers" {
            val state = board(listOf(obj(0, "Bannerman", bob), obj(1, "Grunt", bob), obj(2, "Grunt", alice)))
            val announceable = announceableTargets(state, TARGET_CREATURE, alice, Chooser.Nobody)

            announceable shouldContainExactly listOf(Target.Permanent(ObjectId(0)))
            // The *legal* set is untouched: a requirement narrows a choice, it does not make an object
            // an illegal target, which is what keeps the CR 608.2b re-check honest.
            legalTargets(state, TARGET_CREATURE, alice, Chooser.Nobody).size shouldBe 3
        }

        "CR 601.2c: the requirement never constrains the Flagbearer's own controller" {
            val state = board(listOf(obj(0, "Bannerman", bob), obj(1, "Grunt", bob), obj(2, "Grunt", alice)))
            // bob controls the Bannerman, so bob chooses freely — "while an *opponent* is choosing".
            announceableTargets(state, TARGET_CREATURE, bob, Chooser.Nobody).size shouldBe 3
        }

        "CR 601.2c: any Flagbearer satisfies it, including one the choosing player controls themself" {
            val state = board(listOf(obj(0, "Bannerman", bob), obj(1, "Bannerman", alice), obj(2, "Grunt", bob)))
            val announceable = announceableTargets(state, TARGET_CREATURE, alice, Chooser.Nobody)
            // The card says "at least one Flagbearer on the battlefield", not "this creature".
            announceable shouldContainExactly
                listOf(Target.Permanent(ObjectId(0)), Target.Permanent(ObjectId(1)))
        }

        "CR 702.73a: a changeling is a Flagbearer and is offered as one" {
            val state = board(listOf(obj(0, "Bannerman", bob), obj(1, "Shifter", bob), obj(2, "Grunt", bob)))
            val announceable = announceableTargets(state, TARGET_CREATURE, alice, Chooser.Nobody)
            announceable shouldContain Target.Permanent(ObjectId(1))
            announceable shouldNotContain Target.Permanent(ObjectId(2))
        }

        "CR 601.2c: restrictions beat requirements — a hexproof Flagbearer makes the requirement inert" {
            // bob's only Flagbearer has hexproof, so it is not a legal choice for alice at all and the
            // "if able" clause is unsatisfiable: alice chooses freely from what remains.
            val state = board(listOf(obj(0, "Wardbearer", bob), obj(1, "Grunt", bob), obj(2, "Grunt", alice)))
            val announceable = announceableTargets(state, TARGET_CREATURE, alice, Chooser.Nobody)
            announceable shouldContainExactly
                listOf(Target.Permanent(ObjectId(1)), Target.Permanent(ObjectId(2)))
        }

        "CR 601.2c: a spec no Flagbearer can satisfy is unconstrained — targeting a player is untouched" {
            val state = board(listOf(obj(0, "Bannerman", bob), obj(1, "Grunt", bob)))
            announceableTargets(state, TargetSpec.TargetOpponent, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.Player(bob))
        }

        "CR 601.2c: with no Flagbearer anywhere the announceable set is exactly the legal set" {
            val state = board(listOf(obj(0, "Grunt", bob), obj(1, "Grunt", alice)))
            announceableTargets(state, TARGET_CREATURE, alice, Chooser.Nobody) shouldBe
                legalTargets(state, TARGET_CREATURE, alice, Chooser.Nobody)
        }

        "CR 601.2c: an obeyable requirement over a multi-target spec fails loudly rather than filtering" {
            val state = board(listOf(obj(0, "Bannerman", bob), obj(1, "Grunt", bob)))
            val twoCreatures =
                TargetSpec.TargetPermanent(PermanentRestriction.CREATURE, TargetCount.UpTo(2))
            // "At least one Flagbearer" over two targets is a maximisation over the *combination*;
            // filtering the pool would force both targets to be Flagbearers, a rule no card prints.
            shouldThrow<IllegalArgumentException> {
                announceableTargets(state, twoCreatures, alice, Chooser.Nobody)
            }
        }

        "CR 601.2c: two distinct required subtypes fail loudly rather than picking one" {
            val state = board(listOf(obj(0, "Bannerman", bob), obj(1, "Herald", bob), obj(2, "Grunt", bob)))
            shouldThrow<IllegalStateException> {
                announceableTargets(state, TARGET_CREATURE, alice, Chooser.Nobody)
            }
        }
    })

private val TARGET_CREATURE = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
private val FLAGBEARER = Subtype("Flagbearer")

/** A battlefield object over [requirementDefinitions]. */
private fun obj(
    id: Long,
    name: String,
    owner: PlayerId,
): GameObject = GameObject(ObjectId(id), CardRef(name), owner)

/** A paused two-player state over [requirementDefinitions] with [battlefield] in place. */
private fun board(battlefield: List<GameObject>): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to seat(),
                bob to seat(),
            ),
        turn = Turn(alice, TURN_NUMBER, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = requirementDefinitions.toPersistentMap(),
    )

private fun seat(): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )

/*
 * The fixture pool. "Bannerman" is Standard Bearer's shape: a Flagbearer that declares the requirement.
 * "Herald" declares a *different* subtype, which is the two-requirement loud gate. "Wardbearer" is a
 * Flagbearer with hexproof — the restrictions-beat-requirements case. "Shifter" is a changeling, so it
 * is every creature type including Flagbearer, and "Grunt" is a plain body.
 */
private val requirementDefinitions: Map<CardRef, CardDefinition> =
    listOf(
        fixture("Bannerman", subtypes = setOf(FLAGBEARER), requires = FLAGBEARER),
        fixture("Herald", subtypes = setOf(Subtype("Soldier")), requires = Subtype("Soldier")),
        fixture(
            "Wardbearer",
            subtypes = setOf(FLAGBEARER),
            keywords = setOf(Keyword.HEXPROOF),
            requires = FLAGBEARER,
        ),
        fixture("Shifter", keywords = setOf(Keyword.CHANGELING)),
        fixture("Grunt"),
    ).associateBy { CardRef(it.characteristics.name) }

private fun fixture(
    name: String,
    subtypes: Set<Subtype> = emptySet(),
    keywords: Set<Keyword> = emptySet(),
    requires: Subtype? = null,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = subtypes.toPersistentSet(),
                powerToughness = PrintedPowerToughness(1, 1),
                keywords = keywords.toPersistentSet(),
            )
        override val targetingRequirements =
            requires?.let { persistentListOf(TargetingRequirement(it)) } ?: persistentListOf()
    }
