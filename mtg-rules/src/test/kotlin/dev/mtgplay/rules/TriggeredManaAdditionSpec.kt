package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.player
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * `W8-B` — a triggered ability that **adds mana without being a mana ability** (CR 106.1), declared as
 * [TriggeredAbility.addsMana]: Burning-Tree Emissary's "When this creature enters, add {R}{G}".
 *
 * **The distinction under test is CR 605.1b's, and it is entirely about the stack.** A triggered
 * ability is a *mana* ability only when it triggers off the activation or resolution of a mana ability;
 * this one triggers off a permanent entering the battlefield (CR 603.2), so it is an ordinary triggered
 * ability. It goes on the stack, both players get priority, and only then does the mana arrive. Encoding
 * it as a [dev.mtgplay.core.definition.TriggeredManaAbility] would make it stackless and unrespondable —
 * a different card, and one an opponent could never interact with.
 *
 * The second property is the one the card is *played* for: the mana has to survive into the priority
 * window the resolution hands back. It does, because CR 500.4 empties pools at the end of a **step or
 * phase**, not when priority changes hands. A test is the only place that claim is checked, since the
 * failure mode — mana emptied a moment too early — would make the Emissary silently free-of-value
 * rather than crash.
 *
 * Written against a fixture rather than the real card for [PlayedLandEntryTriggerSpec]'s reason: the
 * assertion should hold whether or not any pool card has reached this path.
 */
class TriggeredManaAdditionSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        fun windowOf(state: GameState) = pausedRequestOf<DecisionRequest.ChooseAction>(state)

        fun handOf(vararg cards: String) =
            fixtureState(
                aliceSetup = SeatSetup(hand = cards.toList()),
                bobSetup = SeatSetup(),
                definitions = fixtureDefinitions + manaTriggerFixtures,
            )

        fun play(card: String): AdvanceResult {
            val start = handOf(card)
            return engine.advance(start, playLandDecision(windowOf(start), card))
        }

        "CR 605.1b: an enters-the-battlefield mana trigger uses the stack — the mana is not there yet" {
            val played = play(MANA_TRIGGER_LAND).pausedState

            // The trigger is on the stack and respondable; nothing has been added to any pool.
            played.sharedZones.stack.filterIsInstance<StackEntry.Ability>() shouldHaveSize 1
            played
                .player(alice)
                .manaPool
                .shouldBeEmpty()
            played.events.filterIsInstance<GameEvent.ManaAdded>().shouldBeEmpty()
        }

        "CR 106.1: the trigger's declared mana reaches its controller's pool when it resolves" {
            var current = play(MANA_TRIGGER_LAND)
            // Both seats pass, resolving the top of the stack (CR 608.1).
            repeat(2) {
                current = engine.advance(current.pausedState, respondTo(current.pending<DecisionRequest>()))
            }
            val resolved = current.pausedState

            resolved
                .player(alice)
                .manaPool shouldContainExactly listOf(ManaType.RED, ManaType.GREEN)
            resolved.events.filterIsInstance<GameEvent.ManaAdded>().map { it.mana } shouldContainExactly
                listOf(ManaType.RED, ManaType.GREEN)
            resolved.events.filterIsInstance<GameEvent.TriggeredAbilityResolved>() shouldHaveSize 1
        }

        "CR 500.4: the mana survives into the priority window the resolution hands back" {
            // The whole point of the card. Mana empties at the end of a step or phase, not when
            // priority changes hands — so it is still spendable in the very window below, and the
            // engine is at a decision point with a non-empty pool, which is legitimate.
            var current = play(MANA_TRIGGER_LAND)
            repeat(2) {
                current = engine.advance(current.pausedState, respondTo(current.pending<DecisionRequest>()))
            }
            val window = current.pausedState
            windowOf(window).seat shouldBe alice
            window
                .player(alice)
                .manaPool shouldHaveSize 2
        }

        "an ability declaring no mana adds none — the addition is not applied indiscriminately" {
            var current = play(PLAIN_TRIGGER_LAND)
            repeat(2) {
                current = engine.advance(current.pausedState, respondTo(current.pending<DecisionRequest>()))
            }
            current.pausedState
                .player(alice)
                .manaPool
                .shouldBeEmpty()
        }
    })

/** A land fixture printing "When this land enters, add {R}{G}" — Burning-Tree Emissary's clause. */
private const val MANA_TRIGGER_LAND: String = "Fixture Ritual Land"

/** The contrast fixture: the same entry trigger, declaring no mana. */
private const val PLAIN_TRIGGER_LAND: String = "Fixture Quiet Land"

/** Resolution: no instructions of its own — the whole ability is the declared mana. */
private val noInstructions: ResolutionEffect = ResolutionEffect { state, _ -> state }

/** A land fixture whose enters-the-battlefield trigger declares [adds] (CR 106.1). */
private fun manaTriggerLand(
    name: String,
    adds: List<ManaType>,
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
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.COLORLESS)))
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = noInstructions,
                    addsMana = adds.toPersistentManaList(),
                ),
            )
    }

private fun List<ManaType>.toPersistentManaList() = persistentListOf<ManaType>().addingAll(this)

/** The fixtures this spec registers, keyed by ref. */
private val manaTriggerFixtures: Map<CardRef, CardDefinition> =
    listOf(
        manaTriggerLand(MANA_TRIGGER_LAND, listOf(ManaType.RED, ManaType.GREEN)),
        manaTriggerLand(PLAIN_TRIGGER_LAND, emptyList()),
    ).associateBy { CardRef(it.characteristics.name) }
