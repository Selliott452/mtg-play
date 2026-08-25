package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
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
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.engine.allTargetsIllegal
import dev.mtgplay.rules.engine.legalTargets
import dev.mtgplay.rules.engine.targetChoiceBounds
import dev.mtgplay.rules.engine.targetChoiceIsVacuous
import dev.mtgplay.rules.engine.targetsAvailable
import dev.mtgplay.rules.engine.validateDecision
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

/**
 * Multi-target spells and abilities (`FW-MULTITGT`, docs/design/multi-target.md): a targeting line is a
 * **noun** and a **count**, and the count drives four separate rules that used to be hard-coded to one.
 *
 * The suite is deliberately organised by *rule* rather than by card, because the count is consumed in
 * four independent places and each has its own failure mode: the CR 601.2c castability gate
 * ([targetsAvailable]), the bounds the surfaced request carries ([targetChoiceBounds]), the CR 601.2c
 * same-object rule on the answer ([validateDecision]), and the CR 608.2b fizzle verdict
 * ([allTargetsIllegal]). `mtg-rules` names no card, so every spec here is exercised directly.
 */
class MultiTargetSpec :
    StringSpec({
        val upToTwoAnyCard =
            TargetSpec.CardInGraveyard(
                GraveyardCardRestriction.ANY_CARD,
                GraveyardScope.ANY,
                TargetCount.UpTo(2),
            )
        val upToTwoYourCreatures =
            TargetSpec.CardInGraveyard(
                GraveyardCardRestriction.CREATURE,
                GraveyardScope.YOURS,
                TargetCount.UpTo(2),
            )
        val exactlyTwoCreatures =
            TargetSpec.TargetPermanent(PermanentRestriction.CREATURE, TargetCount.Exactly(2))

        // ---- the count model itself -------------------------------------------------------------

        "CR 115.1: an exact count has equal bounds and an 'up to' count starts at zero" {
            TargetCount.Exactly(2).minimum shouldBe 2
            TargetCount.Exactly(2).maximum shouldBe 2
            TargetCount.UpTo(2).minimum shouldBe 0
            TargetCount.UpTo(2).maximum shouldBe 2
            TargetSpec.None.count shouldBe TargetCount.NONE
            TargetSpec.AnyTarget.count shouldBe TargetCount.ONE
        }

        "CR 115.1: 'up to zero targets' is not representable — it is TargetSpec.None spelled long" {
            shouldThrow<IllegalArgumentException> { TargetCount.UpTo(0) }
            shouldThrow<IllegalArgumentException> { TargetCount.Exactly(-1) }
        }

        // ---- the enumeration is count-independent, and duplicate-free -----------------------------

        "CR 115.1: the enumeration is the pool of choices and does not shrink or grow with the count" {
            val state = multiTargetState()
            val one = TargetSpec.CardInGraveyard(GraveyardCardRestriction.ANY_CARD, GraveyardScope.ANY)
            legalTargets(state, one, alice, self = null) shouldContainExactly
                legalTargets(state, upToTwoAnyCard, alice, self = null)
        }

        /*
         * The load-bearing invariant: CR 601.2c's same-object rule is enforced as *index distinctness*
         * on the answer, which is only equivalent to object distinctness while no object is offered
         * twice. Pinned directly rather than left as a comment on `legalTargets`.
         */
        "CR 601.2c: no target is enumerated twice, which is what makes distinct indices distinct objects" {
            val state = multiTargetState()
            listOf(
                legalTargets(state, upToTwoAnyCard, alice, self = null),
                legalTargets(state, upToTwoYourCreatures, alice, self = null),
                legalTargets(state, exactlyTwoCreatures, alice, self = null),
                legalTargets(state, TargetSpec.AnyTarget, alice, self = null),
            ).forEach { options -> options.distinct() shouldContainExactly options }
        }

        "CR 404: 'from graveyards' spans both seats, so two chosen targets may sit in different ones" {
            val state = multiTargetState()
            val options = legalTargets(state, upToTwoAnyCard, alice, self = null)
            // alice's three defined graveyard cards, then bob's two — the undefined ref is inert.
            options shouldHaveSize 5
            options.map { (it as Target.CardInGraveyard).id } shouldContainExactly
                listOf(ObjectId(0), ObjectId(1), ObjectId(2), ObjectId(4), ObjectId(5))
        }

        // ---- the new graveyard-card restrictions --------------------------------------------------

        "CR 115.1: 'target card' admits every defined graveyard card and still excludes an inert ref" {
            val state = multiTargetState()
            val ids = legalTargets(state, upToTwoAnyCard, alice, self = null).map { (it as Target.CardInGraveyard).id }
            // ObjectId(3) is alice's undefined ref: the engine cannot know it is a card (the P2.1 ruling).
            ids.contains(ObjectId(3)) shouldBe false
        }

        "CR 302: 'target creature card' excludes the land card that CREATURE_OR_LAND would admit" {
            val state = multiTargetState()
            legalTargets(state, upToTwoYourCreatures, alice, self = null) shouldContainExactly
                listOf(Target.CardInGraveyard(ObjectId(2)))
        }

        // ---- the two decider-relative permanent restrictions ---------------------------------------

        "CR 108.4: 'target permanent you control' offers each seat a different set from one battlefield" {
            val state = multiTargetState()
            val spec = TargetSpec.TargetPermanent(PermanentRestriction.PERMANENT_YOU_CONTROL)
            legalTargets(state, spec, alice, self = null) shouldContainExactly
                listOf(Target.Permanent(ALICE_BEAR), Target.Permanent(ALICE_HEXPROOF_BEAR))
            legalTargets(state, spec, bob, self = null) shouldContainExactly listOf(Target.Permanent(BOB_BEAR))
        }

        "CR 702.11: 'target permanent you control' still offers your own hexproof permanent" {
            val state = multiTargetState()
            val spec = TargetSpec.TargetPermanent(PermanentRestriction.PERMANENT_YOU_CONTROL)
            legalTargets(state, spec, alice, self = null) shouldContainExactly
                listOf(Target.Permanent(ALICE_BEAR), Target.Permanent(ALICE_HEXPROOF_BEAR))
        }

        "CR 102.1: 'target creature an opponent controls' offers only the other seat's creatures" {
            val state = multiTargetState()
            val spec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_AN_OPPONENT_CONTROLS)
            legalTargets(state, spec, alice, self = null) shouldContainExactly listOf(Target.Permanent(BOB_BEAR))
        }

        "CR 702.11: an opponent's hexproof creature is not offered by 'creature an opponent controls'" {
            val state = multiTargetState()
            val spec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_AN_OPPONENT_CONTROLS)
            // alice's hexproof Bear is bob's opponent's creature, and the targetableBy gate removes it.
            legalTargets(state, spec, bob, self = null) shouldContainExactly listOf(Target.Permanent(ALICE_BEAR))
        }

        "CR 115.1b: 'permanent you control' is not narrowed to creatures — a land you control qualifies" {
            val state = withLand(multiTargetState())
            val spec = TargetSpec.TargetPermanent(PermanentRestriction.PERMANENT_YOU_CONTROL)
            legalTargets(state, spec, alice, self = null) shouldContainExactly
                listOf(
                    Target.Permanent(ALICE_BEAR),
                    Target.Permanent(ALICE_HEXPROOF_BEAR),
                    Target.Permanent(ALICE_LAND),
                )
        }

        // ---- the CR 601.2c castability gate --------------------------------------------------------

        "CR 601.2c: an 'up to N' object is castable with no legal target at all" {
            val state = emptyGraveyardState()
            legalTargets(state, upToTwoAnyCard, alice, self = null).shouldBeEmpty()
            targetsAvailable(state, upToTwoAnyCard, alice, self = null) shouldBe true
        }

        "CR 601.2c: an 'exactly N' object is not castable while fewer than N legal targets exist" {
            val state = multiTargetState()
            // Three creatures are on the battlefield, but alice may not target bob's hexproof-free Bear
            // *and* her own two: `exactlyTwoCreatures` needs two, which she has.
            targetsAvailable(state, exactlyTwoCreatures, alice, self = null) shouldBe true
            val lonely = singleCreatureState()
            legalTargets(lonely, exactlyTwoCreatures, alice, self = null) shouldHaveSize 1
            targetsAvailable(lonely, exactlyTwoCreatures, alice, self = null) shouldBe false
        }

        "CR 601.2c: a one-target object is still gated on a single legal choice, unchanged" {
            val state = emptyGraveyardState()
            val one = TargetSpec.CardInGraveyard(GraveyardCardRestriction.ANY_CARD, GraveyardScope.ANY)
            targetsAvailable(state, one, alice, self = null) shouldBe false
        }

        // ---- the bounds the surfaced request carries -------------------------------------------------

        "CR 115.1: an 'up to two' choice is clamped to the one option the board actually offers" {
            targetChoiceBounds(upToTwoAnyCard, optionCount = 1) shouldBe 0..1
            targetChoiceBounds(upToTwoAnyCard, optionCount = 5) shouldBe 0..2
            targetChoiceBounds(exactlyTwoCreatures, optionCount = 3) shouldBe 2..2
        }

        "CR 601.2c: bounds fail loudly when the minimum exceeds what the board offers" {
            val thrown = shouldThrow<IllegalArgumentException> { targetChoiceBounds(exactlyTwoCreatures, 1) }
            thrown.message.orEmpty() shouldContain "never enumerated as castable"
        }

        "ADR-004: a choice with nothing to choose from is settled without surfacing a request" {
            val empty = emptyGraveyardState()
            targetChoiceIsVacuous(empty, upToTwoAnyCard, alice, self = null) shouldBe true
            targetChoiceIsVacuous(empty, TargetSpec.None, alice, self = null) shouldBe true
            targetChoiceIsVacuous(multiTargetState(), upToTwoAnyCard, alice, self = null) shouldBe false
        }

        // ---- the CR 601.2c same-object rule, on the answer -------------------------------------------

        "CR 601.2c: the same target can't be chosen twice for one instance of the word 'target'" {
            val request = multiTargetRequest(minimum = 0, maximum = 2, options = 3)
            val thrown =
                shouldThrow<IllegalArgumentException> {
                    validateDecision(request, Decision.MultiSelect(request.id, listOf(1, 1)))
                }
            thrown.message.orEmpty() shouldContain "distinct"
        }

        "CR 601.2c: two distinct indices are a legal answer, and so is declining both" {
            val request = multiTargetRequest(minimum = 0, maximum = 2, options = 3)
            shouldNotThrowAny { validateDecision(request, Decision.MultiSelect(request.id, listOf(0, 2))) }
            shouldNotThrowAny { validateDecision(request, Decision.MultiSelect(request.id, emptyList())) }
            shouldNotThrowAny { validateDecision(request, Decision.MultiSelect(request.id, listOf(1))) }
        }

        "CR 601.2c: an answer above the maximum or below the minimum is refused" {
            val upTo = multiTargetRequest(minimum = 0, maximum = 2, options = 3)
            shouldThrow<IllegalArgumentException> {
                validateDecision(upTo, Decision.MultiSelect(upTo.id, listOf(0, 1, 2)))
            }
            val exact = multiTargetRequest(minimum = 2, maximum = 2, options = 3)
            shouldThrow<IllegalArgumentException> {
                validateDecision(exact, Decision.MultiSelect(exact.id, listOf(0)))
            }
        }

        "ADR-005: an out-of-range index is refused, so no enumeration can offer an illegal combination" {
            val request = multiTargetRequest(minimum = 0, maximum = 2, options = 2)
            shouldThrow<IllegalArgumentException> {
                validateDecision(request, Decision.MultiSelect(request.id, listOf(0, 2)))
            }
        }

        "ADR-004: a ranged request refuses a SingleSelect answer" {
            val request = multiTargetRequest(minimum = 0, maximum = 2, options = 2)
            shouldThrow<IllegalArgumentException> { validateDecision(request, Decision.SingleSelect(request.id, 0)) }
        }

        "CR 601.2c: a request never claims a maximum above its own option count" {
            shouldThrow<IllegalArgumentException> { multiTargetRequest(minimum = 0, maximum = 3, options = 2) }
        }

        // ---- the CR 608.2b fizzle verdict --------------------------------------------------------------

        /*
         * The divider the count exists for. Both objects below carry an empty target list; one resolves
         * and one does not, and only `TargetCount.minimum` tells them apart.
         */
        "CR 608.2b: an 'up to N' object that chose no targets still resolves and does what it can" {
            val state = multiTargetState()
            allTargetsIllegal(state, upToTwoAnyCard, emptyList(), alice, self = null) shouldBe false
        }

        "CR 603.3d/608.2b: a required-target object with no targets does not resolve" {
            val state = multiTargetState()
            val one = TargetSpec.CardInGraveyard(GraveyardCardRestriction.ANY_CARD, GraveyardScope.ANY)
            allTargetsIllegal(state, one, emptyList(), alice, self = null) shouldBe true
            allTargetsIllegal(state, exactlyTwoCreatures, emptyList(), alice, self = null) shouldBe true
        }

        "CR 608.2b: an object with some legal targets resolves; only all-illegal stops it" {
            val state = multiTargetState()
            val gone = Target.CardInGraveyard(ObjectId(9_999))
            val live = Target.CardInGraveyard(ObjectId(0))
            allTargetsIllegal(state, upToTwoAnyCard, listOf(gone, live), alice, self = null) shouldBe false
            allTargetsIllegal(state, upToTwoAnyCard, listOf(gone), alice, self = null) shouldBe true
        }

        "CR 608.2b: an object that targets nothing never fizzles" {
            allTargetsIllegal(multiTargetState(), TargetSpec.None, emptyList(), alice, self = null) shouldBe false
        }
    })

private val ALICE_BEAR = ObjectId(100)
private val ALICE_HEXPROOF_BEAR = ObjectId(101)
private val BOB_BEAR = ObjectId(102)
private val ALICE_LAND = ObjectId(103)

/** A ranged target request over [options] synthetic graveyard-card options, for validation tests. */
private fun multiTargetRequest(
    minimum: Int,
    maximum: Int,
    options: Int,
): DecisionRequest.ChooseMultipleTargets =
    DecisionRequest.ChooseMultipleTargets(
        id = DecisionRequestId(alice, 0),
        cardObjectId = ObjectId(1),
        card = CardRef("Fixture"),
        options = (0 until options).map { Target.CardInGraveyard(ObjectId(it.toLong())) },
        minimumCount = minimum,
        maximumCount = maximum,
    )

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

private val multiTargetDefinitions: Map<CardRef, CardDefinition> =
    listOf(
        typedFixture("Bolt Fixture", CardType.INSTANT),
        typedFixture("Land Fixture", CardType.LAND),
        typedFixture("Bear Fixture", CardType.CREATURE),
        typedFixture("Bogle Fixture", CardType.CREATURE, keywords = setOf(Keyword.HEXPROOF)),
    ).associateBy { CardRef(it.characteristics.name) }

/** A seat holding [graveyard] and nothing else; the key in `players` is what identifies it. */
private fun seatWith(graveyard: List<GameObject>): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = graveyard.toPersistentList(),
        priorityStatus = PriorityStatus.NONE,
    )

private fun boardOf(
    aliceGraveyard: List<GameObject>,
    bobGraveyard: List<GameObject>,
    battlefield: List<GameObject>,
): GameState =
    GameState(
        players = persistentMapOf(alice to seatWith(aliceGraveyard), bob to seatWith(bobGraveyard)),
        turn = Turn(alice, TURN_NUMBER, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = ALICE_LAND.value + 1,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = multiTargetDefinitions.toPersistentMap(),
    )

/**
 * The shared board: alice's graveyard holds an instant, a land, a creature card and an **undefined**
 * ref (ids 0..3), bob's an instant and a creature card (ids 4..5); the battlefield holds alice's Bear,
 * alice's hexproof Bogle, and bob's Bear. Ids run in enumeration order so the assertions are exact.
 */
private fun multiTargetState(): GameState =
    boardOf(
        aliceGraveyard =
            listOf(
                GameObject(ObjectId(0), CardRef("Bolt Fixture"), alice),
                GameObject(ObjectId(1), CardRef("Land Fixture"), alice),
                GameObject(ObjectId(2), CardRef("Bear Fixture"), alice),
                GameObject(ObjectId(3), CardRef("Undefined Fixture"), alice),
            ),
        bobGraveyard =
            listOf(
                GameObject(ObjectId(4), CardRef("Bolt Fixture"), bob),
                GameObject(ObjectId(5), CardRef("Bear Fixture"), bob),
            ),
        battlefield =
            listOf(
                GameObject(ALICE_BEAR, CardRef("Bear Fixture"), alice),
                GameObject(ALICE_HEXPROOF_BEAR, CardRef("Bogle Fixture"), alice),
                GameObject(BOB_BEAR, CardRef("Bear Fixture"), bob),
            ),
    )

/** The same board with a land added under alice's control, for the "permanent, not creature" test. */
private fun withLand(state: GameState): GameState =
    state.copy(
        sharedZones =
            state.sharedZones.copy(
                battlefield =
                    state.sharedZones.battlefield
                        .adding(GameObject(ALICE_LAND, CardRef("Land Fixture"), alice)),
            ),
    )

/** Both graveyards empty and the battlefield bare: nothing is a legal target of anything. */
private fun emptyGraveyardState(): GameState = boardOf(emptyList(), emptyList(), emptyList())

/** One creature on the battlefield, so an "exactly two creatures" spec cannot be satisfied. */
private fun singleCreatureState(): GameState =
    boardOf(emptyList(), emptyList(), listOf(GameObject(ALICE_BEAR, CardRef("Bear Fixture"), alice)))
