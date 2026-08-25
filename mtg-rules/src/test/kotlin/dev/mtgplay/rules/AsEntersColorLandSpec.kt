package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.AsEntersColorChoice
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.productionProfile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/**
 * The CR 614.12 as-enters colour choice on the **play-land** path (CR 116.2a, CR 305.1), and the
 * production that reads the answer back (CR 605.1a) — the two halves of the Gate cycle, exercised
 * against fixtures because an engine test never names a real card.
 *
 * Three properties, and each was inexpressible before `W8-A`:
 * 1. **A played land can make the choice at all.** The flow existed only inside a resolving permanent
 *    *spell*, and a land is never cast — so a Gate would have entered with no colour and tapped for one
 *    mana rather than two, silently.
 * 2. **The option list is the printed one.** "Choose a color other than white" removes white from the
 *    enumeration; offering it would be an enumerated-but-illegal action (ADR-005).
 * 3. **The choice is made before the land is on the battlefield** (CR 614.12), so no reachable state
 *    holds a Gate whose colour is still unsettled.
 */
class AsEntersColorLandSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        fun boardOf(vararg hand: String) =
            fixtureState(
                aliceSetup = SeatSetup(hand = hand.toList()),
                bobSetup = SeatSetup(),
                definitions = fixtureDefinitions + asEntersColorFixtures,
            )

        fun playing(card: String): AdvanceResult {
            val start = boardOf(card)
            return engine.advance(start, playLandDecision(pausedRequestOf(start), card))
        }

        fun choose(
            paused: AdvanceResult,
            color: Color,
        ): GameState {
            val request = paused.pending<DecisionRequest.ChooseColor>()
            val index = request.options.indexOf(color)
            check(index >= 0) { "$color is not offered: ${request.options}" }
            return engine.advance(paused.pausedState, Decision.SingleSelect(request.id, index)).pausedState
        }

        /**
         * The multisets one activation of [land] may add (CR 605.1a), read with [land] seated on the
         * battlefield and untapped — a Gate enters tapped (CR 614.1c) and a tapped source has no
         * available `{T}` alternative at all, which is a separate rule from what it can produce.
         */
        fun production(
            state: GameState,
            land: GameObject,
        ): List<List<ManaType>> {
            val untapped = land.copy(tapped = false)
            val rest = state.sharedZones.battlefield.filterNot { it.id == land.id }
            val seated =
                state.copy(
                    nextObjectId = maxOf(state.nextObjectId, untapped.id.value + 1),
                    sharedZones = state.sharedZones.copy(battlefield = (rest + untapped).toPersistentList()),
                )
            return productionProfile(seated, untapped).orEmpty().map { it.produced }
        }

        "CR 614.12 and CR 305.1: playing a land that chooses a colour pauses for the choice" {
            val paused = playing(CHOOSING_LAND)

            val request = paused.pending<DecisionRequest.ChooseColor>()
            request.card shouldBe CardRef(CHOOSING_LAND)
            request.id.seat shouldBe alice
            // CR 614.12: the choice happens *as* the permanent enters, so nothing is on the battlefield
            // yet and the card is still in hand.
            paused.pausedState.sharedZones.battlefield
                .shouldBeEmpty()
            paused.pausedState.players
                .getValue(alice)
                .hand
                .single()
                .card shouldBe CardRef(CHOOSING_LAND)
            // A land is played, not cast (CR 305.1): nothing went on the stack to be responded to.
            paused.pausedState.sharedZones.stack
                .shouldBeEmpty()
        }

        "ADR-005: \"choose a color other than white\" does not enumerate white" {
            val request = playing(CHOOSING_LAND).pending<DecisionRequest.ChooseColor>()

            request.options shouldContainExactly listOf(Color.BLUE, Color.BLACK, Color.RED, Color.GREEN)
            request.options shouldNotContain Color.WHITE
        }

        "CR 614.12: an unrestricted choice still offers all five colours" {
            playing(UNRESTRICTED_LAND).pending<DecisionRequest.ChooseColor>().options shouldContainExactly
                Color.entries.toList()
        }

        "CR 614.12 and CR 614.1c: the answer is stored on the entering object, which arrives tapped" {
            val played = choose(playing(CHOOSING_LAND), Color.BLUE)

            val land = played.sharedZones.battlefield.single()
            land.card shouldBe CardRef(CHOOSING_LAND)
            land.chosenColor shouldBe Color.BLUE
            // Both as-enters modifications apply to the same entry; neither displaces the other.
            land.tapped shouldBe true
            played.players
                .getValue(alice)
                .hand
                .shouldBeEmpty()
        }

        "CR 605.1a: \"Add {W} or one mana of the chosen color\" produces both, and only both" {
            val played = choose(playing(CHOOSING_LAND), Color.BLACK)

            production(played, played.sharedZones.battlefield.single()) shouldContainExactly
                listOf(listOf(ManaType.WHITE), listOf(ManaType.BLACK))
        }

        "CR 605.1a: two copies that chose differently produce differently — the colour is per object" {
            val board = boardOf()
            val red = gateObject(id = 500, chosen = Color.RED)
            val green = gateObject(id = 501, chosen = Color.GREEN)

            production(board, red) shouldContainExactly listOf(listOf(ManaType.WHITE), listOf(ManaType.RED))
            production(board, green) shouldContainExactly listOf(listOf(ManaType.WHITE), listOf(ManaType.GREEN))
        }

        "CR 605.1a: a source that has chosen nothing taps for its printed options alone" {
            val unchosen = gateObject(id = 502, chosen = null)

            unchosen.chosenColor.shouldBeNull()
            production(boardOf(), unchosen) shouldContainExactly listOf(listOf(ManaType.WHITE))
        }
    })

/** A Gate-shaped fixture: enters tapped, chooses a colour other than white, adds `{W}` or that colour. */
private const val CHOOSING_LAND: String = "Fixture Choosing Land"

/** The Utopia-Sprawl-shaped contrast: an unrestricted "choose a color". */
private const val UNRESTRICTED_LAND: String = "Fixture Unrestricted Land"

/** A [CHOOSING_LAND] battlefield object carrying [chosen] as its CR 614.12 answer. */
private fun gateObject(
    id: Long,
    chosen: Color?,
): GameObject = GameObject(ObjectId(id), CardRef(CHOOSING_LAND), alice, chosenColor = chosen)

private fun choosingLand(
    name: String,
    excluding: Color?,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val entersTapped = EntersTapped.Always
        override val asEntersColorChoice = AsEntersColorChoice(excluding = excluding)
        override val manaAbilities =
            persistentListOf(ManaAbility(persistentListOf(ManaType.WHITE), includesChosenColor = true))
    }

/** The fixtures this spec registers, keyed by ref. */
private val asEntersColorFixtures: Map<CardRef, CardDefinition> =
    listOf(
        choosingLand(CHOOSING_LAND, excluding = Color.WHITE),
        choosingLand(UNRESTRICTED_LAND, excluding = null),
    ).associateBy { CardRef(it.characteristics.name) }
