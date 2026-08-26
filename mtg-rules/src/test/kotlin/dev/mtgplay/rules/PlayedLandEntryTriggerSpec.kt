package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.effect.gainLife
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/**
 * CR 603.6a at the two battlefield-entry paths that used to skip it: the play-land special action
 * (CR 116.2a, CR 305.1) and token creation (CR 111.4). The gauntlet triage records the land half as
 * **T18**.
 *
 * **Why these tests are written against fixtures rather than a real card.** T18 survived precisely
 * because it was unreachable from the card pool: no encoded land had an enters-the-battlefield
 * trigger, so no game could expose it, and the gap was invisible from the state — a trigger that
 * never fires enqueues nothing, emits nothing, and throws nothing. A test that waits for a pool card
 * to reach the path inherits that blindness. These fixtures declare the trigger directly, so the
 * assertions hold whether or not any real card has reached the path yet, and would have failed on
 * the pre-fix engine the moment they were written.
 *
 * A land is *played*, not cast (CR 305.1), and a token is *created*, not resolved — but CR 603.6a
 * cares only that an object entered the battlefield, so all four entry paths owe the same triggers.
 */
class PlayedLandEntryTriggerSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        fun windowOf(state: GameState) = pausedRequestOf<DecisionRequest.ChooseAction>(state)

        fun handOf(vararg cards: String) =
            fixtureState(
                aliceSetup = SeatSetup(hand = cards.toList()),
                bobSetup = SeatSetup(),
                definitions = fixtureDefinitions + entryTriggerFixtures,
            )

        fun play(card: String): GameState {
            val start = handOf(card)
            return engine.advance(start, playLandDecision(windowOf(start), card)).pausedState
        }

        "CR 603.6a: a played land's enters-the-battlefield ability triggers, exactly as a resolving permanent's does" {
            val state = play(TRIGGER_LAND)

            state.sharedZones.battlefield
                .single()
                .card shouldBe CardRef(TRIGGER_LAND)
            // The fired trigger is on the stack, not lost: CR 603.3b places it at the priority grant
            // the special action performs (CR 116.4), which is the checkpoint executePlayLand ends on.
            state.sharedZones.stack.filterIsInstance<StackEntry.Ability>() shouldHaveSize 1
            state.events.filterIsInstance<GameEvent.TriggeredAbilityPutOnStack>() shouldHaveSize 1
        }

        "CR 603.3b: the trigger a played land fires reaches the stack and resolves, taking effect" {
            val start = handOf(TRIGGER_LAND)
            var current = engine.advance(start, playLandDecision(windowOf(start), TRIGGER_LAND))
            // Both seats pass, resolving the top of the stack (CR 608.1).
            repeat(2) {
                current = engine.advance(current.pausedState, respondTo(current.pending<DecisionRequest>()))
            }

            current.pausedState.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + GAIN
            current.pausedState.events
                .filterIsInstance<GameEvent.TriggeredAbilityResolved>() shouldHaveSize 1
        }

        "CR 603.6a: a played land with no such ability fires nothing — the detector is not indiscriminate" {
            val state = play(PLAIN_LAND)

            state.pendingTriggers.shouldBeEmpty()
            state.sharedZones.stack.shouldBeEmpty()
            state.events.filterIsInstance<GameEvent.TriggeredAbilityPutOnStack>().shouldBeEmpty()
        }

        "CR 603.6a and CR 111.4: a created token's enters-the-battlefield ability triggers — T18's twin path" {
            val created = createToken(handOf(), alice, triggeringToken)

            created.pendingTriggers shouldHaveSize 1
            created.pendingTriggers
                .single()
                .sourceCard shouldBe CardRef.token(TOKEN_NAME)
            created.pendingTriggers
                .single()
                .ability.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
        }

        "CR 603.6a: a created token with no such ability fires nothing" {
            createToken(handOf(), alice, plainToken).pendingTriggers.shouldBeEmpty()
        }
    })

/** A land fixture printing "When this land enters, you gain [GAIN] life." — the T18 shape. */
private const val TRIGGER_LAND: String = "Fixture Trigger Land"

/** The contrast fixture: a land with no triggered ability at all. */
private const val PLAIN_LAND: String = "Fixture Plain Land"

/** The token fixture carrying an enters-the-battlefield trigger. */
private const val TOKEN_NAME: String = "Fixture Trigger Token"

/** The token fixture with no triggered ability. */
private const val PLAIN_TOKEN_NAME: String = "Fixture Plain Token"

/** The life the fixture trigger gains, distinct from any other fixture's amount. */
private const val GAIN: Int = 3

/** The fixture enters-the-battlefield ability: "you gain [GAIN] life". */
private val gainLifeOnEntry: TriggeredAbility =
    TriggeredAbility(
        condition = TriggerCondition.EnteredBattlefieldSelf,
        effect = ResolutionEffect { state, context -> gainLife(state, context.controller, GAIN) },
    )

/** A land fixture adding [produces], carrying [triggers]. */
private fun landFixture(
    name: String,
    produces: ManaType,
    triggers: List<TriggeredAbility>,
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
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(produces)))
        override val triggeredAbilities = triggers.toPersistentListOfAbilities()
    }

/** A token fixture carrying [triggers]. */
private fun tokenFixture(
    name: String,
    triggers: List<TriggeredAbility>,
): TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            ),
        triggeredAbilities = triggers.toPersistentListOfAbilities(),
    )

private fun List<TriggeredAbility>.toPersistentListOfAbilities() = toPersistentList()

private val triggeringToken: TokenDefinition = tokenFixture(TOKEN_NAME, listOf(gainLifeOnEntry))

private val plainToken: TokenDefinition = tokenFixture(PLAIN_TOKEN_NAME, emptyList())

/** The fixtures this spec registers, keyed by ref. */
private val entryTriggerFixtures: Map<CardRef, CardDefinition> =
    listOf(
        landFixture(TRIGGER_LAND, ManaType.GREEN, listOf(gainLifeOnEntry)),
        landFixture(PLAIN_LAND, ManaType.BLUE, emptyList()),
    ).associateBy { CardRef(it.characteristics.name) }
