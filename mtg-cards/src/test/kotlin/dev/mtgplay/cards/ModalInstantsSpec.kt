package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * Cast into the Fire and Thraben Charm (ModalInstants.kt) against the oracle cards: the printed line,
 * each mode's targeting line, and the details of each mode's resolution that are silently wrong if got
 * wrong.
 *
 * These are the two cards `FW-MODAL` wrote and dropped for a variable-count multi-target line, so what
 * this file exists to pin is the **per-mode count** — the axis `FW-MULTITGT` supplied and `FW-MODAL`
 * needed. Mode availability, the CR 601.2b-before-CR 601.2c ordering, and the CR 601.2c same-object rule
 * are exercised on real boards in `mtg-rules` and end to end in the acceptance module, because
 * `mtg-rules` may not name a card (ADR-003).
 */
class ModalInstantsSpec :
    StringSpec({

        "CR 202/304: both cards are plain single-colour instants with no supertype, subtype, or P/T box" {
            mapOf(castIntoTheFire to "{1}{R}", thrabenCharm to "{1}{W}").forEach { (definition, cost) ->
                with(definition.characteristics) {
                    manaCost?.render() shouldBe cost
                    supertypes shouldBe persistentSetOf<Supertype>()
                    cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                    subtypes shouldBe persistentSetOf<Subtype>()
                    powerToughness.shouldBeNull()
                }
                definition.timing shouldBe TimingClass.INSTANT_SPEED
            }
        }

        "CR 601.2c: Cast into the Fire's two modes carry different counts on the same restriction axis" {
            val modes = castIntoTheFire.modes
            modes shouldHaveSize 2
            // Mode 0: "each of *up to two* target creatures" — minimum zero, so always castable.
            modes[0].targetSpec shouldBe
                TargetSpec.TargetPermanent(PermanentRestriction.CREATURE, TargetCount.UpTo(2))
            modes[0].targetSpec.count.minimum shouldBe 0
            modes[0].targetSpec.count.maximum shouldBe 2
            // Mode 1: an ordinary single target — not castable with no artifact anywhere.
            modes[1].targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT)
            modes[1].targetSpec.count.minimum shouldBe 1
        }

        "CR 700.2: each printed bullet is carried verbatim, because an agent picks a mode by index" {
            castIntoTheFire.modes.map { it.text } shouldBe
                listOf(
                    "Cast into the Fire deals 1 damage to each of up to two target creatures.",
                    "Exile target artifact.",
                )
            thrabenCharm.modes.map { it.text } shouldBe
                listOf(
                    "Thraben Charm deals damage equal to twice the number of creatures you " +
                        "control to target creature.",
                    "Destroy target enchantment.",
                    "Exile any number of target players' graveyards.",
                )
        }

        "CR 120: Cast into the Fire deals 1 to *each* named creature, not 1 split between them" {
            // Two Grizzly Bears (2/2). One damage marked on each, not one damage total.
            val state =
                boardState(listOf(bfObject(0, "Grizzly Bears", alice), bfObject(1, "Grizzly Bears", alice)))
            val burned =
                resolveMode(
                    castIntoTheFire.modes[0],
                    state,
                    listOf(Target.Permanent(ObjectId(0)), Target.Permanent(ObjectId(1))),
                )
            burned.sharedZones.battlefield.map { it.damageMarked } shouldBe listOf(1, 1)
        }

        "CR 608.2b: Cast into the Fire's first mode resolves having named no creature at all" {
            // "Up to two" with zero chosen is a real answer: the spell resolves and marks nothing.
            val state = boardState(listOf(bfObject(0, "Grizzly Bears", alice)))
            val resolved = resolveMode(castIntoTheFire.modes[0], state, emptyList())
            resolved.sharedZones.battlefield
                .single()
                .damageMarked shouldBe 0
        }

        "CR 608.2: Thraben Charm's damage is twice the creatures you control, counted on resolution" {
            // Alice controls two creatures (the Bears she is pointing at counts itself); Bob's does not.
            val state =
                boardState(
                    listOf(
                        bfObject(0, "Grizzly Bears", alice),
                        bfObject(1, "Hill Giant", alice),
                        bfObject(2, "Grizzly Bears", bob),
                    ),
                )
            val burned = resolveMode(thrabenCharm.modes[0], state, listOf(Target.Permanent(ObjectId(2))))
            // 2 creatures Alice controls × 2 = 4 damage, not 6 (Bob's creature is not counted).
            burned.sharedZones.battlefield[2].damageMarked shouldBe 4
        }

        "CR 120.8: Thraben Charm's damage mode with no creatures deals zero, which is not dealt at all" {
            val state = boardState(listOf(bfObject(0, "Grizzly Bears", bob)))
            val resolved = resolveMode(thrabenCharm.modes[0], state, listOf(Target.Permanent(ObjectId(0))))
            resolved.sharedZones.battlefield
                .single()
                .damageMarked shouldBe 0
        }

        "CR 303.4: Thraben Charm's second mode targets any enchantment, an Aura included" {
            thrabenCharm.modes[1].targetSpec shouldBe
                TargetSpec.TargetPermanent(PermanentRestriction.ENCHANTMENT)
        }

        "CR 115.1a: Thraben Charm's third mode names any number of players, with no printed limit" {
            val spec = thrabenCharm.modes[2].targetSpec
            spec shouldBe TargetSpec.TargetPlayer(TargetCount.AnyNumber)
            // Not UpTo(2): the card prints no bound, and only the board supplies one.
            spec.count.minimum shouldBe 0
            spec.count.maximum shouldBe Int.MAX_VALUE
        }

        "CR 701.3a: the third mode exiles every card in each named player's graveyard, and only those" {
            val state =
                boardState(
                    battlefield = emptyList(),
                    graveyards = mapOf(alice to listOf(0L, 1L), bob to listOf(2L, 3L)),
                )
            val exiled = resolveMode(thrabenCharm.modes[2], state, listOf(Target.Player(bob)))
            exiled.players
                .getValue(bob)
                .graveyard
                .shouldBeEmpty()
            // Alice was not named, so her graveyard is untouched.
            exiled.players.getValue(alice).graveyard shouldHaveSize 2
            exiled.sharedZones.exile shouldHaveSize 2
        }

        "CR 608.2b: the third mode naming no player resolves and exiles nothing" {
            val state =
                boardState(battlefield = emptyList(), graveyards = mapOf(alice to listOf(0L), bob to listOf(1L)))
            val resolved = resolveMode(thrabenCharm.modes[2], state, emptyList())
            resolved.players.getValue(alice).graveyard shouldHaveSize 1
            resolved.players.getValue(bob).graveyard shouldHaveSize 1
            resolved.sharedZones.exile.shouldBeEmpty()
        }

        "CR 115.1a: the third mode may name both players at once, exiling both graveyards" {
            val state =
                boardState(
                    battlefield = emptyList(),
                    graveyards = mapOf(alice to listOf(0L, 1L), bob to listOf(2L)),
                )
            val exiled =
                resolveMode(thrabenCharm.modes[2], state, listOf(Target.Player(alice), Target.Player(bob)))
            exiled.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            exiled.players
                .getValue(bob)
                .graveyard
                .shouldBeEmpty()
            exiled.sharedZones.exile shouldHaveSize 3
        }
    })

private val alice = PlayerId(0)
private val bob = PlayerId(1)

/** Resolves [mode] for Alice against [state] with [targets] already chosen and re-checked. */
private fun resolveMode(
    mode: dev.mtgplay.core.definition.SpellMode,
    state: GameState,
    targets: List<Target>,
): GameState =
    mode.resolution.resolve(
        state,
        ResolutionContext(
            controller = alice,
            targets = targets.toPersistentList(),
            source = ObjectId(SOURCE_ID),
            sourceCard = CardRef("Lightning Bolt"),
        ),
    )

/** A stack-residence id no fixture puts on the battlefield, standing in for the resolving spell. */
private const val SOURCE_ID: Long = 900

/** A battlefield [GameObject] over [MvpCards]: [name] resolves via the registry. */
private fun bfObject(
    id: Long,
    name: String,
    owner: PlayerId,
): GameObject = GameObject(id = ObjectId(id), card = CardRef(name), owner = owner)

/** A handcrafted main-phase two-player [GameState] over [MvpCards]. */
private fun boardState(
    battlefield: List<GameObject>,
    graveyards: Map<PlayerId, List<Long>> = emptyMap(),
): GameState {
    fun seat(owner: PlayerId) =
        PlayerState(
            20,
            persistentListOf(),
            persistentListOf(),
            graveyards[owner]
                .orEmpty()
                .map { bfObject(it, "Lightning Bolt", owner) }
                .toPersistentList(),
        )

    val ids = battlefield.map { it.id.value } + graveyards.values.flatten()
    return GameState(
        players = persistentMapOf(alice to seat(alice), bob to seat(bob)),
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
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
