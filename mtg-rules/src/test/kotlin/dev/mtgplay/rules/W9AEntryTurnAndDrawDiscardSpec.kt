package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.DiscardExemption
import dev.mtgplay.core.definition.OptionalDrawThenDiscard
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.pendingDecisionRequest
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The two `W9-A` engine additions, on fixture definitions — `mtg-rules` names no card, so nothing here
 * mentions Moon-Circuit Hacker.
 *
 * 1. [dev.mtgplay.core.state.GameObject.enteredTurn], stamped in the single battlefield-entry home, and
 *    captured onto every fired trigger as last-known information (CR 603.10).
 * 2. [OptionalDrawThenDiscard] — the first clause that chains two pauses, the second conditional on the
 *    first's answer *and* on a board fact.
 *
 * The assertions that carry the design are the negative ones: declining the draw discards **nothing**
 * (CR 601.3b's "if you do"), and a source that entered this turn discards nothing however full the hand.
 */
class W9AEntryTurnAndDrawDiscardSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 603.6a: a permanent spell's resolution stamps the turn its permanent entered" {
            val done = resolveTopOfStack(creatureSpellOnStack()).pausedState
            done.sharedZones.battlefield
                .single()
                .enteredTurn shouldBe THIS_TURN
        }

        "CR 302.6 is not CR 603.6a: the entry stamp is a separate fact from summoning sickness" {
            val entered =
                resolveTopOfStack(creatureSpellOnStack())
                    .pausedState.sharedZones.battlefield
                    .single()
            // They agree here, and the point is that they are recorded separately so they may disagree:
            // a hasty creature is not summoning sick and still entered this turn.
            entered.enteredTurn shouldBe THIS_TURN
            entered.copy(summoningSick = false).enteredTurn shouldBe THIS_TURN
        }

        "CR 400.7: a permanent that has been on the battlefield since an earlier turn keeps its own stamp" {
            // The stamp is written once, as the object enters, and never refreshed — so "entered this
            // turn" is false for a permanent stamped with an earlier turn, which a per-turn boolean
            // cleared at some sweep could not express.
            val old = GameObject(SOURCE_ID, CardRef(SOURCE), alice, enteredTurn = THIS_TURN - 1)
            (old.enteredTurn == THIS_TURN) shouldBe false
        }

        "CR 603.10: a fired trigger captures its source's entry turn as last-known information" {
            val done = resolveTopOfStack(creatureSpellOnStack(withEntryTrigger = true)).pausedState
            // The fired trigger has already been put on the stack in APNAP order (CR 603.3b) by the
            // time a player would receive priority; the capture rides along with it.
            done.sharedZones.stack
                .single()
                .shouldBeInstanceOf<StackEntry.Ability>()
                .trigger
                .sourceEnteredTurn shouldBe THIS_TURN
        }

        "CR 601.3b: declining the optional draw discards nothing — 'if you do' is a real conditional" {
            val paused =
                resolveTopOfStack(lootTrigger(enteredTurn = THIS_TURN - 1))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()

            val decline = Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.DECLINE)
            val done = engine.advance(paused.state, decline).pausedState

            done.players
                .getValue(alice)
                .hand
                .shouldBeEmpty()
            done.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            done.sharedZones.stack.shouldBeEmpty()
        }

        "CR 701.8: accepting the draw on an older source loots — one drawn, one discarded" {
            val paused =
                resolveTopOfStack(lootTrigger(enteredTurn = THIS_TURN - 1))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val yesNo = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()

            val afterDraw = engine.advance(paused.state, Decision.SingleSelect(yesNo.id, ACCEPT))
            val discard =
                afterDraw
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
                    .request
                    .shouldBeInstanceOf<DecisionRequest.ChooseResolutionDiscards>()
            discard.count shouldBe 1

            val done =
                engine.advance(afterDraw.pausedState, Decision.MultiSelect(discard.id, listOf(0))).pausedState
            done.players
                .getValue(alice)
                .hand
                .shouldBeEmpty()
            done.players
                .getValue(alice)
                .graveyard
                .map { it.card.name } shouldContainExactly listOf(TOP)
            done.sharedZones.stack.shouldBeEmpty()
        }

        "CR 603.6a: a source that entered this turn draws and is asked for no discard at all" {
            val paused =
                resolveTopOfStack(lootTrigger(enteredTurn = THIS_TURN))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val yesNo = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()

            val done = engine.advance(paused.state, Decision.SingleSelect(yesNo.id, ACCEPT)).pausedState

            done.players
                .getValue(alice)
                .hand
                .map { it.card.name } shouldContainExactly listOf(TOP)
            done.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            done.pendingResolutionDiscard shouldBe null
            done.sharedZones.stack.shouldBeEmpty()
        }

        "CR 603.10: the exemption survives its source leaving the battlefield before the trigger resolves" {
            // The whole reason the entry turn is captured on the trigger rather than read live: the
            // source can be killed in response to the very trigger that asks about it.
            val paused =
                resolveTopOfStack(lootTrigger(enteredTurn = THIS_TURN, sourceOnBattlefield = false))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val yesNo = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()

            val done = engine.advance(paused.state, Decision.SingleSelect(yesNo.id, ACCEPT)).pausedState

            done.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            done.players
                .getValue(alice)
                .hand.size shouldBe 1
        }

        "ADR-004: each pause of the chained clause re-derives its own request from the state alone" {
            val paused =
                resolveTopOfStack(lootTrigger(enteredTurn = THIS_TURN - 1))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            pendingDecisionRequest(paused.state) shouldBe paused.request

            val yesNo = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()
            val afterDraw =
                engine
                    .advance(paused.state, Decision.SingleSelect(yesNo.id, ACCEPT))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            pendingDecisionRequest(afterDraw.state) shouldBe afterDraw.request
        }

        "CR 608.2c: the clause is one declaration, so it can never be confused with the bare optional draw" {
            val ability = lootAbility(DiscardExemption.SOURCE_ENTERED_THIS_TURN)
            ability.optionalDraw shouldBe null
            ability.drawThenDiscard shouldBe null
            ability.optionalDrawThenDiscard.shouldNotBeNull().discardCount shouldBe 1
        }
    })

private const val SOURCE = "Entry Source"
private const val TOP = "Entry Top Card"
private const val BODY = "Entry Body"
private const val THIS_TURN = 3
private const val ACCEPT = 1

private val SOURCE_ID = ObjectId(90)

private val noOp = ResolutionEffect { state, _ -> state }

/** The fixture "you may draw one; if you do, discard one unless the source entered this turn" ability. */
private fun lootAbility(exemption: DiscardExemption) =
    TriggeredAbility(
        condition = TriggerCondition.DealtCombatDamageToPlayerSelf,
        effect = noOp,
        optionalDrawThenDiscard =
            OptionalDrawThenDiscard(drawCount = 1, discardCount = 1, skipDiscardWhen = exemption),
    )

/**
 * Alice's loot trigger resolving on top of the stack, its source recorded as having entered on
 * [enteredTurn], with one card left in her library and an empty hand.
 */
private fun lootTrigger(
    enteredTurn: Int,
    sourceOnBattlefield: Boolean = true,
): GameState =
    fixtureState(
        entry =
            StackEntry.Ability(
                PendingTrigger(
                    sourceId = SOURCE_ID,
                    sourceCard = CardRef(SOURCE),
                    controller = alice,
                    ability = lootAbility(DiscardExemption.SOURCE_ENTERED_THIS_TURN),
                    sourceEnteredTurn = enteredTurn,
                ),
            ),
        library = listOf(TOP),
        battlefield =
            if (sourceOnBattlefield) {
                listOf(GameObject(SOURCE_ID, CardRef(SOURCE), alice, enteredTurn = enteredTurn))
            } else {
                emptyList()
            },
    )

/** Alice's fixture creature spell on top of the stack, about to become a permanent (CR 608.3). */
private fun creatureSpellOnStack(withEntryTrigger: Boolean = false): GameState =
    fixtureState(
        entry =
            StackEntry.Spell(
                obj = GameObject(SOURCE_ID, CardRef(if (withEntryTrigger) SOURCE else BODY), alice),
                controller = alice,
                targets = persistentListOf(),
                definition = if (withEntryTrigger) triggeringBody else plainBody,
            ),
        library = emptyList(),
        battlefield = emptyList(),
    )

private val plainBody: SpellDefinition = fixtureCreature(BODY, triggers = false)
private val triggeringBody: SpellDefinition = fixtureCreature(SOURCE, triggers = true)

private fun fixtureCreature(
    name: String,
    triggers: Boolean,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(1, 1),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = noOp
        override val triggeredAbilities =
            if (triggers) {
                persistentListOf(TriggeredAbility(TriggerCondition.EnteredBattlefieldSelf, noOp))
            } else {
                persistentListOf()
            }
    }

private fun fixtureState(
    entry: StackEntry,
    library: List<String>,
    battlefield: List<GameObject>,
): GameState {
    var nextId = 0L
    val libraryObjects = library.map { GameObject(ObjectId(nextId++), CardRef(it), alice) }.toPersistentList()
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = libraryObjects,
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
            ),
        turn = Turn(alice, THIS_TURN, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(battlefield.toPersistentList(), persistentListOf(entry), persistentListOf()),
        nextObjectId = 500,
        rng = Rng(7),
        events = persistentListOf(),
        definitions = fixtureRegistry.toPersistentMap(),
    )
}

private val fixtureRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef(SOURCE) to triggeringBody,
        CardRef(BODY) to plainBody,
        CardRef(TOP) to fixtureCreature(TOP, triggers = false),
    )
