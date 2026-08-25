package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetCount
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
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.effect.exileGraveyard
import dev.mtgplay.rules.engine.allTargetsIllegal
import dev.mtgplay.rules.engine.legalTargets
import dev.mtgplay.rules.engine.targetChoiceBounds
import dev.mtgplay.rules.engine.targetChoiceIsVacuous
import dev.mtgplay.rules.engine.targetsAvailable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

/**
 * `FW-MULTITGT`'s second wave: the **unbounded** target count ([TargetCount.AnyNumber]), the count
 * arriving on [TargetSpec.TargetPlayer], the two new [PermanentRestriction] members, and the whole-zone
 * [exileGraveyard] primitive. Fixture cards only — `mtg-rules` names no card (ADR-003).
 *
 * docs/design/multi-target.md §2 claimed "Magic prints exactly two shapes"; a third turned out to be
 * printed, and the claim this file exists to pin is that it is genuinely not the second one with a
 * number filled in. `AnyNumber`'s maximum is [Int.MAX_VALUE] and is never read raw — `targetChoiceBounds`
 * clamps it to the option count — so writing it as `UpTo(2)` would invent a printed limit that is right
 * only for a two-player game.
 *
 * Organised by *rule* rather than by shape, for the reason `MultiTargetSpec` gives: the count is consumed
 * in four independent places and each has its own failure mode.
 */
class UnboundedTargetsSpec :
    StringSpec({
        val anyPlayers = TargetSpec.TargetPlayer(TargetCount.AnyNumber)
        val onePlayer = TargetSpec.TargetPlayer()
        val enchantments = TargetSpec.TargetPermanent(PermanentRestriction.ENCHANTMENT)
        val blinkable =
            TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL)
        val twoBlinkable =
            TargetSpec.TargetPermanent(
                PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL,
                TargetCount.Exactly(2),
            )

        // ---- the unbounded count itself -----------------------------------------------------------

        "CR 115.1: an unbounded count starts at zero and names no printed maximum" {
            TargetCount.AnyNumber.minimum shouldBe 0
            TargetCount.AnyNumber.maximum shouldBe Int.MAX_VALUE
        }

        "CR 115.1a: the count does not change what 'target player' enumerates, only how many are named" {
            val state = wideBoard()
            legalTargets(state, anyPlayers, alice, self = null) shouldContainExactly
                legalTargets(state, onePlayer, alice, self = null)
        }

        "CR 115.1a: 'target player' offers the chooser as well as the opponent, unlike 'target opponent'" {
            val state = wideBoard()
            legalTargets(state, anyPlayers, alice, self = null) shouldContainExactly
                listOf(Target.Player(alice), Target.Player(bob))
            legalTargets(state, TargetSpec.TargetOpponent, alice, self = null) shouldContainExactly
                listOf(Target.Player(bob))
        }

        "CR 115.1: an unbounded maximum is clamped to the options, never surfaced as Int.MAX_VALUE" {
            // The whole safety argument for AnyNumber: the raw maximum never reaches an agent.
            val bounds = targetChoiceBounds(anyPlayers, optionCount = 2)
            bounds.first shouldBe 0
            bounds.last shouldBe 2
        }

        "CR 601.2c: an unbounded count is always satisfiable, so it never gates castability" {
            // Minimum zero: the mode is offered whatever the board holds. Contrast Exactly(2) below.
            targetsAvailable(wideBoard(), anyPlayers, alice, self = null) shouldBe true
        }

        "CR 608.2b: an unbounded line that named no target resolves; it has no illegal target" {
            allTargetsIllegal(wideBoard(), anyPlayers, emptyList(), alice, self = null) shouldBe false
        }

        "CR 601.2c: a choice with options is never vacuous, however unbounded its count" {
            targetChoiceIsVacuous(wideBoard(), anyPlayers, alice, self = null) shouldBe false
        }

        // ---- the enchantment restriction -----------------------------------------------------------

        "CR 303: 'target enchantment' offers either seat's enchantments and no other permanent" {
            legalTargets(wideBoard(), enchantments, alice, self = null) shouldContainExactly
                listOf(Target.Permanent(ALICE_AURA), Target.Permanent(BOB_ENCHANTMENT))
        }

        "CR 303.4: an Aura is an enchantment, so it is a legal target of 'destroy target enchantment'" {
            val options = legalTargets(wideBoard(), enchantments, bob, self = null)
            options.map { (it as Target.Permanent).id } shouldContainExactly listOf(ALICE_AURA, BOB_ENCHANTMENT)
        }

        // ---- the disjunctive, control-restricted restriction ----------------------------------------

        "CR 205.2b: 'artifacts, creatures, and/or lands you control' admits all three types, disjunctively" {
            legalTargets(wideBoard(), blinkable, alice, self = null) shouldContainExactly
                listOf(
                    Target.Permanent(ALICE_ARTIFACT),
                    Target.Permanent(ALICE_BEAR),
                    Target.Permanent(ALICE_HEXPROOF_BEAR),
                    Target.Permanent(ALICE_LAND),
                )
        }

        "CR 303: the disjunction excludes enchantments, so a permanent you control may still be illegal" {
            val offered = legalTargets(wideBoard(), blinkable, alice, self = null).map { (it as Target.Permanent).id }
            // ALICE_AURA is a permanent alice controls and is deliberately not offered.
            offered.contains(ALICE_AURA) shouldBe false
        }

        "CR 109.5: the restriction is decider-relative — one battlefield, two different option lists" {
            legalTargets(wideBoard(), blinkable, bob, self = null) shouldContainExactly
                listOf(Target.Permanent(BOB_BEAR))
        }

        "CR 702.11: hexproof never narrows a 'you control' line — your own hexproof creature is offered" {
            val offered = legalTargets(wideBoard(), blinkable, alice, self = null).map { (it as Target.Permanent).id }
            offered.contains(ALICE_HEXPROOF_BEAR) shouldBe true
        }

        // ---- an exact count above one, on the battlefield -------------------------------------------

        "CR 601.2c: 'two target permanents' is uncastable when the board offers only one" {
            val state = oneBlinkableBoard()
            targetsAvailable(state, twoBlinkable, alice, self = null) shouldBe false
            // The same board satisfies the one-target form, so this is the count and not the noun.
            targetsAvailable(state, blinkable, alice, self = null) shouldBe true
        }

        "CR 601.2c: an exact count of two demands two and clamps to no fewer" {
            val bounds = targetChoiceBounds(twoBlinkable, optionCount = 4)
            bounds.first shouldBe 2
            bounds.last shouldBe 2
        }

        "CR 608.2b: a two-target line whose targets are *both* gone fizzles; one survivor resolves it" {
            val state = wideBoard()
            val gone = Target.Permanent(ObjectId(999))
            allTargetsIllegal(state, twoBlinkable, listOf(gone, gone), alice, self = null) shouldBe true
            allTargetsIllegal(
                state,
                twoBlinkable,
                listOf(gone, Target.Permanent(ALICE_BEAR)),
                alice,
                self = null,
            ) shouldBe false
        }

        // ---- the whole-zone graveyard exile ---------------------------------------------------------

        "CR 701.3a: exiling a graveyard empties exactly that one and moves every card to exile" {
            val exiled = exileGraveyard(wideBoard(), bob)
            exiled.players
                .getValue(bob)
                .graveyard
                .shouldBeEmpty()
            exiled.players.getValue(alice).graveyard shouldHaveSize 2
            exiled.sharedZones.exile shouldHaveSize 2
        }

        "CR 400.7: every exiled graveyard card is reborn under a fresh id" {
            val before = wideBoard()
            val originals =
                before.players
                    .getValue(alice)
                    .graveyard
                    .map { it.id }
            val exiled = exileGraveyard(before, alice)
            exiled.sharedZones.exile.none { it.id in originals } shouldBe true
            // Owner survives the move: an exiled card is still its owner's (CR 108.3).
            exiled.sharedZones.exile.map { it.owner } shouldContainExactly listOf(alice, alice)
        }

        "CR 404: exiling an empty graveyard is a legal no-op, not a failure" {
            val emptied = exileGraveyard(wideBoard(), alice)
            val twice = exileGraveyard(emptied, alice)
            twice.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            twice.sharedZones.exile shouldHaveSize 2
        }
    })

private val ALICE_BEAR = ObjectId(100)
private val ALICE_HEXPROOF_BEAR = ObjectId(101)
private val BOB_BEAR = ObjectId(102)
private val ALICE_LAND = ObjectId(103)
private val ALICE_ARTIFACT = ObjectId(104)
private val ALICE_AURA = ObjectId(105)
private val BOB_ENCHANTMENT = ObjectId(106)

/** A fixture card definition with the given printed types (CR 205.2) and optional printed keywords. */
private fun typedFixture(
    name: String,
    vararg cardTypes: CardType,
    keywords: Set<Keyword> = emptySet(),
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = if (CardType.LAND in cardTypes) null else ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = cardTypes.toSet().toPersistentSet(),
                subtypes = persistentSetOf(),
                powerToughness =
                    if (CardType.CREATURE in cardTypes) PrintedPowerToughness(power = 2, toughness = 2) else null,
                keywords = keywords.toPersistentSet(),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

private val wideDefinitions: Map<CardRef, CardDefinition> =
    listOf(
        typedFixture("Bolt Fixture", CardType.INSTANT),
        typedFixture("Land Fixture", CardType.LAND),
        typedFixture("Bear Fixture", CardType.CREATURE),
        typedFixture("Bogle Fixture", CardType.CREATURE, keywords = setOf(Keyword.HEXPROOF)),
        typedFixture("Artifact Fixture", CardType.ARTIFACT),
        typedFixture("Aura Fixture", CardType.ENCHANTMENT),
    ).associateBy { CardRef(it.characteristics.name) }

/**
 * The shared board. Alice controls a Bear, a hexproof Bogle, a land, an artifact and an enchantment;
 * Bob controls a Bear and an enchantment. Each graveyard holds two cards, so the whole-zone exile has
 * something to move and something to leave behind. Ids run in enumeration order so the assertions are
 * exact.
 */
private fun wideBoard(): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to seatWith(listOf(graveObject(0, alice), graveObject(1, alice))),
                bob to seatWith(listOf(graveObject(2, bob), graveObject(3, bob))),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield =
                    listOf(
                        GameObject(ALICE_ARTIFACT, CardRef("Artifact Fixture"), alice),
                        GameObject(ALICE_AURA, CardRef("Aura Fixture"), alice),
                        GameObject(ALICE_BEAR, CardRef("Bear Fixture"), alice),
                        GameObject(ALICE_HEXPROOF_BEAR, CardRef("Bogle Fixture"), alice),
                        GameObject(ALICE_LAND, CardRef("Land Fixture"), alice),
                        GameObject(BOB_BEAR, CardRef("Bear Fixture"), bob),
                        GameObject(BOB_ENCHANTMENT, CardRef("Aura Fixture"), bob),
                    ).toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = BOB_ENCHANTMENT.value + 1,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = wideDefinitions.toPersistentMap(),
    )

/** Only one permanent alice controls that "artifacts, creatures, and/or lands" admits. */
private fun oneBlinkableBoard(): GameState =
    wideBoard().let { state ->
        state.copy(
            sharedZones =
                state.sharedZones.copy(
                    battlefield =
                        state.sharedZones.battlefield
                            .filter { it.id == ALICE_BEAR || it.id == ALICE_AURA }
                            .toPersistentList(),
                ),
        )
    }

/** A seat holding [graveyard] and nothing else. */
private fun seatWith(graveyard: List<GameObject>): PlayerState =
    PlayerState(
        life = 20,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = graveyard.toPersistentList(),
        priorityStatus = PriorityStatus.NONE,
    )

/** A graveyard card belonging to [owner]; the definition makes it a real, defined card. */
private fun graveObject(
    id: Long,
    owner: PlayerId,
): GameObject = GameObject(ObjectId(id), CardRef("Bolt Fixture"), owner)
