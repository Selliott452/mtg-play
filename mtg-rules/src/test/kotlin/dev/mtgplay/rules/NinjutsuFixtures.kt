package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.Ninjutsu
import dev.mtgplay.core.definition.OptionalDraw
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.AttackerAssignment
import dev.mtgplay.core.state.BlockAssignment
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.engine.player
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

/*
 * Fixtures for the `FW-NINJUTSU` / `FW-TRIGCOMBAT` / `FW-OPTDRAW` scenarios (CR 702.49, CR 510.2,
 * CR 601.3b). Self-contained rather than folded into CombatTestSupport.kt, because a ninjutsu scenario
 * needs three things that file's creature-only battlefields cannot express: a card in **hand**, untapped
 * **mana sources** to pay the ninjutsu cost with, and a **library** deep enough for the optional draw to
 * be a real choice.
 */

/** The ninjutsu cost every fixture ninja here carries (Ninja of the Deep Hours' printed one). */
internal const val FIXTURE_NINJUTSU_COST: String = "{1}{U}"

/** A vanilla creature fixture with no mana cost — these objects are placed, never cast. */
private fun vanilla(
    name: String,
    power: Int,
    toughness: Int,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power, toughness),
            )
    }

/**
 * A ninja fixture: a castable creature with a [Ninjutsu] cost and, optionally, the
 * combat-damage-to-a-player trigger with a bare optional draw that Ninja of the Deep Hours prints.
 *
 * [etbEffect] lets a scenario hang an ordinary enters-the-battlefield trigger on the ninja, which is how
 * the "a ninja put onto the battlefield by ninjutsu still fires CR 603.6a" case is observed.
 */
private fun ninja(
    name: String,
    power: Int,
    toughness: Int,
    drawTrigger: Boolean = false,
    etbEffect: ResolutionEffect? = null,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{3}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power, toughness),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val ninjutsu = Ninjutsu(ManaCost.parse(FIXTURE_NINJUTSU_COST))
        override val triggeredAbilities =
            listOfNotNull(
                if (drawTrigger) {
                    TriggeredAbility(
                        condition = TriggerCondition.DealtCombatDamageToPlayerSelf,
                        effect = ResolutionEffect { state, _ -> state },
                        optionalDraw = OptionalDraw(1),
                    )
                } else {
                    null
                },
                etbEffect?.let {
                    TriggeredAbility(condition = TriggerCondition.EnteredBattlefieldSelf, effect = it)
                },
            ).toPersistentList()
    }

/** A non-creature card that nevertheless declares ninjutsu — the definition defect the engine rejects. */
private val ninjutsuArtifact: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Ninja Tool",
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val ninjutsu = Ninjutsu(ManaCost.parse(FIXTURE_NINJUTSU_COST))
    }

/** A land that taps for one blue — the ninjutsu cost's coloured half. */
private val ninjutsuIsland: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Island",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.BLUE)))
    }

/** An inert card with no definition of its own beyond a name — library filler that is never played. */
internal const val NINJUTSU_FILLER: String = "Fixture Filler"

/** The definition registry every ninjutsu scenario builds its state over. */
internal val ninjutsuDefinitions: Map<CardRef, CardDefinition> =
    listOf(
        vanilla("Rat", 1, 1),
        vanilla("Blocker", 2, 2),
        ninja("Deep Ninja", 2, 2, drawTrigger = true),
        ninja("Plain Ninja", 3, 1),
        ninja("Trigger Ninja", 1, 1, etbEffect = ResolutionEffect { state, _ -> state }),
        ninjutsuArtifact,
        ninjutsuIsland,
    ).associateBy { CardRef(it.characteristics.name) }

/** One battlefield creature in a ninjutsu scenario. */
internal data class NinjaBoard(
    val name: String,
    val tapped: Boolean = false,
)

/**
 * A paused two-player state for a ninjutsu scenario: [aliceField] and [bobField] on the battlefield (ids
 * allocated alice-first), [aliceHand] in Alice's hand, [aliceLands] untapped blue sources for her, and a
 * [libraryDepth]-card library each. Positioned at [step] of Alice's combat phase, with [holder] mid-priority.
 *
 * Ids are allocated field-then-hand-then-lands-then-library so a scenario can name objects positionally
 * without the ordering shifting when an unrelated list grows.
 */
@Suppress("LongParameterList")
internal fun ninjutsuState(
    aliceField: List<NinjaBoard> = emptyList(),
    bobField: List<NinjaBoard> = emptyList(),
    aliceHand: List<String> = emptyList(),
    aliceLands: Int = 2,
    step: TurnStep = TurnStep.DECLARE_BLOCKERS,
    holder: PlayerId? = alice,
    libraryDepth: Int = 3,
    attackers: List<String> = emptyList(),
    blocks: List<Pair<String, String>>? = emptyList(),
): GameState {
    var nextId = 0L

    fun creatures(
        field: List<NinjaBoard>,
        owner: PlayerId,
    ): List<GameObject> =
        field.map { spec ->
            GameObject(ObjectId(nextId++), CardRef(spec.name), owner, tapped = spec.tapped, summoningSick = false)
        }

    val aliceCreatures = creatures(aliceField, alice)
    val bobCreatures = creatures(bobField, bob)
    val hand = aliceHand.map { GameObject(ObjectId(nextId++), CardRef(it), alice) }
    val lands =
        List(aliceLands) {
            GameObject(ObjectId(nextId++), CardRef("Fixture Island"), alice, summoningSick = false)
        }

    fun seat(
        owner: PlayerId,
        ownHand: List<GameObject>,
    ) = PlayerState(
        life = STARTING_LIFE,
        library =
            List(libraryDepth) { GameObject(ObjectId(nextId++), CardRef(NINJUTSU_FILLER), owner) }
                .toPersistentList(),
        hand = ownHand.toPersistentList(),
        graveyard = persistentListOf(),
        priorityStatus = if (owner == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
    )

    val alicePlayer = seat(alice, hand)
    val bobPlayer = seat(bob, emptyList())

    // CR 508.1 / CR 509.1h: attackers by name off Alice's field, blocks by (blocker, attacker) name off
    // Bob's. A `null` [blocks] is the pre-declare-blockers window, where no attacker is yet unblocked.
    fun aliceId(name: String) = aliceCreatures.first { it.card == CardRef(name) }.id

    fun bobId(name: String) = bobCreatures.first { it.card == CardRef(name) }.id

    val assignments = attackers.map { AttackerAssignment(aliceId(it), bob) }
    val blockList = blocks?.map { (blocker, attacker) -> BlockAssignment(bobId(blocker), aliceId(attacker)) }
    val combat =
        if (assignments.isEmpty()) {
            null
        } else {
            CombatState(
                attackers = assignments.toPersistentList(),
                blocks = blockList?.toPersistentList(),
                blockedAttackers = blockList.orEmpty().map { it.attacker }.toPersistentSet(),
            )
        }
    return GameState(
        players = persistentMapOf(alice to alicePlayer, bob to bobPlayer),
        turn = Turn(alice, TURN_NUMBER, TurnPhase.COMBAT, step, combat = combat),
        sharedZones =
            SharedZones(
                battlefield = (aliceCreatures + bobCreatures + lands).toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = ninjutsuDefinitions.toPersistentMap(),
    )
}

/** The battlefield object with card [name] owned by [owner]. */
internal fun GameState.ninjaBattlefield(
    name: String,
    owner: PlayerId = alice,
): GameObject = sharedZones.battlefield.first { it.card == CardRef(name) && it.owner == owner }

/** The hand object with card [name] held by [owner]. */
internal fun GameState.ninjaHand(
    name: String,
    owner: PlayerId = alice,
): GameObject = player(owner).hand.first { it.card == CardRef(name) }
