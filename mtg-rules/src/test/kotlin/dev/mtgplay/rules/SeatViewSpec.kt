package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.LibraryReveal
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingRevealSelection
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The per-seat filtered view (ADR-007): [viewFor] reveals a seat's own hand in full, an opponent's
 * as a count only, both libraries as counts, and every public zone unfiltered — and it routes the
 * pending decision to only the deciding seat.
 */
class SeatViewSpec :
    StringSpec({
        "ADR-007: a seat sees its own hand in full but only a count of an opponent's hand" {
            val state = twoSeatFixture()

            val aliceView = viewFor(state, alice)
            val aliceSelf = aliceView.players.single { it.seat == alice }
            val aliceOnOpponent = aliceView.players.single { it.seat == bob }

            val aliceHand = aliceSelf.hand
            aliceHand.shouldBeInstanceOf<HandView.Revealed>()
            aliceHand.cards.map { it.card.name } shouldContainExactly listOf("Lightning Bolt", "Mountain")
            aliceOnOpponent.hand shouldBe HandView.Concealed(count = 2)
        }

        "ADR-007: a library is a count only on both sides, own side included" {
            val state = twoSeatFixture()

            val bobView = viewFor(state, bob)
            // Both the viewer's own library and the opponent's are counts, never contents (CR 401.1).
            bobView.players.single { it.seat == bob }.libraryCount shouldBe 1
            bobView.players.single { it.seat == alice }.libraryCount shouldBe 2
        }

        "ADR-007: battlefield, graveyards, and exile are fully public to every seat" {
            val state = twoSeatFixture()

            val aliceView = viewFor(state, alice)
            val bobView = viewFor(state, bob)

            // Battlefield and exile: identical for both seats, every object visible (CR 403/406).
            aliceView.battlefield.map { it.card.name } shouldContainExactly listOf("Grizzly Bears", "Plains")
            aliceView.battlefield shouldBe bobView.battlefield
            aliceView.exile.map { it.card.name } shouldContainExactly listOf("Highway Robbery")
            aliceView.exile shouldBe bobView.exile
            // Each graveyard's contents are public to both seats (CR 404).
            val aliceGraveyard = aliceView.players.single { it.seat == alice }.graveyard
            aliceGraveyard.map { it.card.name } shouldContainExactly listOf("Faithless Looting")
            bobView.players.single { it.seat == alice }.graveyard shouldBe aliceGraveyard
        }

        "CR 701.16: revealed cards are public to both seats even though the library is otherwise secret" {
            val state = revealPauseState()

            // The opponent sees the actual revealed card, resolved from the deciding seat's library.
            val revealToBob = viewFor(state, bob).pendingReveal
            revealToBob shouldBe viewFor(state, alice).pendingReveal
            revealToBob?.decider shouldBe alice
            revealToBob?.revealed?.map { it.card.name } shouldContainExactly listOf("View Bear")
        }

        "ADR-005/ADR-007: only the deciding seat sees its request; others see who decides and the kind" {
            // A real engine pause yields a priority window (CR 117) for the active player.
            val paused = DefaultGameEngine().start(mountainConfig()) as AdvanceResult.NeedsDecision
            val decider = paused.request.seat
            val other = (paused.state.players.keys - decider).single()

            viewFor(paused.state, decider).pendingDecision shouldBe DecisionView.ToDecide(paused.request)
            viewFor(paused.state, other).pendingDecision shouldBe
                DecisionView.Elsewhere(decider, DecisionRequestKind.CHOOSE_ACTION)
        }

        "ADR-007: viewFor rejects a seat that is not seated" {
            shouldThrow<IllegalArgumentException> { viewFor(twoSeatFixture(), PlayerId(9)) }
        }

        "CR 111: a token on the battlefield is described to every seat, and marked as a token" {
            val state = cardTableFixture()

            // A token is a public object with public characteristics (CR 111), so both seats get them.
            for (view in listOf(viewFor(state, alice), viewFor(state, bob))) {
                val token = view.cards.getValue(CardRef("Table Warrior"))
                token.isToken shouldBe true
                token.characteristics.powerToughness shouldBe PrintedPowerToughness(1, 1)
                token.characteristics.keywords shouldContain Keyword.VIGILANCE
                // A real card on the same battlefield is described too, and is not a token.
                view.cards.getValue(CardRef("Table Bear")).isToken shouldBe false
            }
        }

        "ADR-007: the card table describes exactly the cards a seat's own view names" {
            val state = cardTableFixture()

            // Alice: the public battlefield and graveyard plus her own hand — never Bob's hand, and
            // never either library, though the match registry defines all of them.
            viewFor(state, alice).cards.keys.map { it.name } shouldContainExactly
                listOf("Table Bear", "Table Bolt", "Table Cantrip", "Table Warrior")
            // Bob: the same public cards, with his own hand card instead of Alice's.
            viewFor(state, bob).cards.keys.map { it.name } shouldContainExactly
                listOf("Table Bear", "Table Bogle", "Table Cantrip", "Table Warrior")
        }

        "P2.1: a card with no definition is inert and simply has no card-table entry" {
            val aliceView = viewFor(cardTableFixture(), alice)

            // The undefined permanent is a visible object; the engine invents no characteristics for it.
            aliceView.battlefield.map { it.card.name } shouldContain "Table Relic"
            aliceView.cards.keys shouldNotContain CardRef("Table Relic")
        }

        "ADR-007: viewFor is a pure derivation — two calls on the same state and seat are equal" {
            val state = cardTableFixture()
            viewFor(state, alice) shouldBe viewFor(state, alice)
        }
    })

private fun id(value: Long): ObjectId = ObjectId(value)

private fun obj(
    value: Long,
    name: String,
    owner: PlayerId,
): GameObject = GameObject(id(value), CardRef(name), owner)

/**
 * A handcrafted two-seat state with distinct, identifiable cards in every zone, so filtering is
 * observable by card name. Not a pause point (no request pending) — the decision-routing tests use
 * a real engine pause instead.
 */
private fun twoSeatFixture(): GameState {
    val aliceState =
        PlayerState(
            life = STARTING_LIFE,
            library = persistentListOf(obj(2, "Sneaky Snacker", alice), obj(3, "Mountain", alice)),
            hand = persistentListOf(obj(0, "Lightning Bolt", alice), obj(1, "Mountain", alice)),
            graveyard = persistentListOf(obj(4, "Faithless Looting", alice)),
        )
    val bobState =
        PlayerState(
            life = STARTING_LIFE,
            library = persistentListOf(obj(7, "Rancor", bob)),
            hand = persistentListOf(obj(5, "Slippery Bogle", bob), obj(6, "Forest", bob)),
            graveyard = persistentListOf(),
        )
    return GameState(
        players = persistentMapOf(alice to aliceState, bob to bobState),
        turn = Turn(activePlayer = alice, number = 1, phase = TurnPhase.PRECOMBAT_MAIN, step = null),
        sharedZones =
            SharedZones(
                battlefield = listOf(obj(8, "Grizzly Bears", alice), obj(9, "Plains", bob)).toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(obj(10, "Highway Robbery", alice)),
            ),
        nextObjectId = 11,
        rng = Rng(0),
        events = persistentListOf(),
    )
}

/** A creature card (a permanent card, keepable by a CR 701.16 reveal). */
private fun creatureDefinition(name: String): CardDefinition =
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

/** A sorcery that reveals the top card and keeps a permanent (Malevolent Rumble's CR 701.16 clause, minimal). */
private val revealSpellDefinition: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "View Reveal",
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { s, _ -> s }
        override val libraryReveal = LibraryReveal(1, RevealedCardFilter.PERMANENT_CARD)
    }

/**
 * A genuine mid-resolution reveal pause (CR 701.16): the reveal spell is on top of the stack, the
 * revealed permanent card is still the top of alice's library, and the keep-one selection is pending.
 * Constructed as the engine leaves it so [viewFor] derives the same [SeatView.pendingReveal] here as
 * it would in a real game.
 */
private fun revealPauseState(): GameState {
    val bear = obj(2, "View Bear", alice)
    val revealSpell = obj(20, "View Reveal", alice)
    val aliceState =
        PlayerState(
            life = STARTING_LIFE,
            library = persistentListOf(bear),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
        )
    val bobState =
        PlayerState(
            life = STARTING_LIFE,
            library = persistentListOf(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
        )
    return GameState(
        players = persistentMapOf(alice to aliceState, bob to bobState),
        turn = Turn(activePlayer = alice, number = 3, phase = TurnPhase.PRECOMBAT_MAIN, step = null),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(),
                stack =
                    persistentListOf(
                        StackEntry.Spell(
                            obj = revealSpell,
                            controller = alice,
                            targets = persistentListOf(),
                            definition = revealSpellDefinition,
                        ),
                    ),
                exile = persistentListOf(),
            ),
        nextObjectId = 21,
        rng = Rng(0),
        events = persistentListOf(),
        pendingRevealSelection = PendingRevealSelection(alice, persistentListOf(id(2))),
        definitions =
            mapOf(
                CardRef("View Reveal") to revealSpellDefinition,
                CardRef("View Bear") to creatureDefinition("View Bear"),
            ).toPersistentMap(),
    )
}

/** A non-creature spell card, for a hand or graveyard card the card table must describe. */
private fun spellCardDefinition(name: String): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
    }

/**
 * A 1/1 Warrior creature token with vigilance (CR 111.4) — registered the way the create-token
 * primitive registers one: under its name-[CardRef], as a [TokenDefinition], only once it exists.
 */
private val tableWarriorToken =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Table Warrior",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Warrior")),
                powerToughness = PrintedPowerToughness(1, 1),
                keywords = persistentSetOf(Keyword.VIGILANCE),
            ),
    )

/**
 * A state whose match registry defines **more** than either seat can see: both hands, both libraries,
 * the shared battlefield and a graveyard, plus a token created on the battlefield. "Table Relic" is
 * deliberately left undefined — an inert card (P2.1). The card-table scope is observable by name.
 */
private fun cardTableFixture(): GameState {
    val aliceState =
        PlayerState(
            life = STARTING_LIFE,
            library = persistentListOf(obj(30, "Table Secret", alice)),
            hand = persistentListOf(obj(31, "Table Bolt", alice)),
            graveyard = persistentListOf(obj(32, "Table Cantrip", alice)),
        )
    val bobState =
        PlayerState(
            life = STARTING_LIFE,
            library = persistentListOf(obj(33, "Table Other Secret", bob)),
            hand = persistentListOf(obj(34, "Table Bogle", bob)),
            graveyard = persistentListOf(),
        )
    return GameState(
        players = persistentMapOf(alice to aliceState, bob to bobState),
        turn = Turn(activePlayer = alice, number = 4, phase = TurnPhase.PRECOMBAT_MAIN, step = null),
        sharedZones =
            SharedZones(
                battlefield =
                    listOf(
                        obj(35, "Table Bear", alice),
                        obj(36, "Table Warrior", bob),
                        obj(37, "Table Relic", alice),
                    ).toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 38,
        rng = Rng(0),
        events = persistentListOf(),
        definitions =
            mapOf(
                CardRef("Table Bear") to creatureDefinition("Table Bear"),
                CardRef("Table Bogle") to creatureDefinition("Table Bogle"),
                CardRef("Table Bolt") to spellCardDefinition("Table Bolt"),
                CardRef("Table Cantrip") to spellCardDefinition("Table Cantrip"),
                CardRef("Table Other Secret") to creatureDefinition("Table Other Secret"),
                CardRef("Table Secret") to creatureDefinition("Table Secret"),
                CardRef("Table Warrior") to tableWarriorToken,
            ).toPersistentMap(),
    )
}
