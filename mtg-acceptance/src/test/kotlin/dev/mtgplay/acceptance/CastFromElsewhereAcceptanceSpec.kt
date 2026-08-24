package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.replay.fingerprint
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ReplacementEffect
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.event.GameEvent
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
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.effect.loseLife
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P5.2 cast-from-elsewhere flows exercised end-to-end through [ScriptedGame], which invariant-checks
 * **every** transition (deliverable 6) — so the madness marker, exile-zone growth, and cast-from-exile
 * all pass the checker throughout. Madness (a fixture, no real madness card lands this packet) runs the
 * discard→exile→reflexive→cast pathway, including the CR 514.3a cleanup repeat; Sentinel's Eyes' escape
 * (a real card) runs the graveyard cast with the exile-two additional cost. Every state is a valid engine
 * input by construction (ADR-004).
 */
class CastFromElsewhereAcceptanceSpec :
    StringSpec({

        "CR 702.35 / CR 514.3a: a madness card discarded at cleanup is exiled, cast, and forces a cleanup repeat" {
            val game = madnessCleanupGame()
            // The turn ends and the first cleanup step begins; discard the madness bolt (CR 514.1) — it
            // is exiled instead (CR 702.35a).
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseDiscards }
            val discard = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseDiscards>()
            val boltIndex = discard.options.indexOfFirst { it.card == CardRef(MADNESS_BOLT) }
            game.apply(Decision.MultiSelect(discard.id, listOf(boltIndex)))
            // The reflexive trigger (CR 702.35b) resolves during cleanup; accept the cast.
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseYesNo }
            val yesNo = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()
            game.apply(Decision.SingleSelect(yesNo.id, DecisionRequest.ChooseYesNo.ACCEPT))
            // The full cast pipeline runs from exile at the madness cost: target bob, pay {R}.
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            game.apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Player(bob))))
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            game.apply(Decision.SingleSelect(payment.id, 0))
            // Drive through the cast's resolution and the CR 514.3a cleanup repeat, into the next turn.
            game.driveUntil { game.state.turn.number > MADNESS_TURN }

            // Bob took the madness bolt's 3; the bolt is in its owner's graveyard; exile is empty again.
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - MADNESS_BOLT_DAMAGE
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldBe listOf(CardRef(MADNESS_BOLT))
            game.state.sharedZones.exile
                .shouldBeEmpty()
            // CR 514.3a: the first cleanup step ran, a player received priority during it (the madness
            // cast), so a second cleanup step followed — two StepBegan(CLEANUP) for this turn.
            game.state.events
                .takeWhile { !(it is GameEvent.TurnBegan && it.turnNumber > MADNESS_TURN) }
                .count { it is GameEvent.StepBegan && it.step == TurnStep.CLEANUP } shouldBe 2
            game.state.events.filterIsInstance<GameEvent.CardExiledByMadness>() shouldHaveSize 1
        }

        "CR 702.35b: a madness card whose cast is declined is put into its owner's graveyard" {
            val game = madnessCleanupGame()
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseDiscards }
            val discard = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseDiscards>()
            val boltIndex = discard.options.indexOfFirst { it.card == CardRef(MADNESS_BOLT) }
            game.apply(Decision.MultiSelect(discard.id, listOf(boltIndex)))
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseYesNo }
            val yesNo = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()
            game.apply(Decision.SingleSelect(yesNo.id, DecisionRequest.ChooseYesNo.DECLINE))

            // Declined: the card left exile for the graveyard (CR 702.35b); bob is untouched.
            game.driveUntil {
                game.state.events.any { it is GameEvent.MadnessCardPutIntoGraveyard }
            }
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE
            game.state.sharedZones.exile
                .shouldBeEmpty()
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldBe listOf(CardRef(MADNESS_BOLT))
        }

        "ADR-006: a madness cast game replays exactly from the same state and decisions" {
            val original = madnessCleanupGame()
            playMadnessCast(original)
            // Re-run the identical (state, decisions) and compare final-state fingerprints (ADR-006).
            val replay = ScriptedGame.startFrom(madnessCleanupState())
            original.decisions.forEach { replay.apply(it) }
            fingerprint(replay.state) shouldBe fingerprint(original.state)
        }

        "CR 702.139: Sentinel's Eyes escapes from the graveyard, exiling two other cards, and enters attached" {
            // Sentinel's Eyes + two other cards are in alice's graveyard; she has a Plains for {W} and a
            // Grizzly Bears to enchant.
            val bearsId = ObjectId(1)
            val game =
                ScriptedGame.startFrom(
                    escapeState(
                        aliceBattlefield =
                            listOf(
                                GameObject(ObjectId(0), CardRef("Plains"), alice, summoningSick = false),
                                GameObject(bearsId, CardRef("Grizzly Bears"), alice, summoningSick = false),
                            ),
                        aliceGraveyard =
                            listOf(
                                GameObject(ObjectId(2), CardRef("Sentinel's Eyes"), alice),
                                GameObject(ObjectId(3), CardRef("Forest"), alice),
                                GameObject(ObjectId(4), CardRef("Forest"), alice),
                            ),
                    ),
                )
            // Enumeration offers the escape cast from the graveyard (ADR-005).
            val window = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            val escape =
                window.options.indexOfFirst {
                    it is PriorityOption.CastSpell &&
                        it.card == CardRef("Sentinel's Eyes") &&
                        it.source == CastSource.GRAVEYARD
                }
            check(escape >= 0) { "no escape cast enumerated in ${window.options}" }
            game.apply(Decision.SingleSelect(window.id, escape))
            // Target the Bears to enchant (CR 601.2c).
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            game.apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Permanent(bearsId))))
            // The additional cost: exile the two other graveyard cards (CR 702.139a).
            val exile = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseCardsToExile>()
            exile.count shouldBe 2
            game.apply(Decision.MultiSelect(exile.id, listOf(0, 1)))
            // Pay {W}.
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            game.apply(Decision.SingleSelect(payment.id, 0))
            game.driveUntil { game.state.events.any { it is GameEvent.AuraAttached } }

            // Sentinel's Eyes resolved onto the battlefield attached to the Bears (CR 303.4f).
            val aura =
                game.state.sharedZones.battlefield
                    .single { it.card == CardRef("Sentinel's Eyes") }
            aura.attachedTo shouldBe bearsId
            // The two other cards were exiled to pay the escape cost (CR 702.139a).
            game.state.sharedZones.exile
                .map { it.card } shouldBe listOf(CardRef("Forest"), CardRef("Forest"))
            game.state.events.filterIsInstance<GameEvent.CardsExiledForCost>() shouldHaveSize 1
        }
    })

// ---- driving helpers ----------------------------------------------------------------------------------

private fun ScriptedGame.driveUntil(predicate: () -> Boolean): ScriptedGame {
    var steps = 0
    while (!predicate() && !isOver && steps < MAX_DRIVE_STEPS) {
        passOrOrder()
        steps++
    }
    check(predicate()) { "the drive predicate was not satisfied within $MAX_DRIVE_STEPS steps" }
    return this
}

private fun ScriptedGame.passOrOrder(): ScriptedGame =
    when (val request = pendingRequest) {
        is DecisionRequest.ChooseAction -> {
            val pass = request.options.indexOfFirst { it is PriorityOption.Pass }
            apply(Decision.SingleSelect(request.id, pass))
        }
        is DecisionRequest.OrderTriggers -> apply(Decision.MultiSelect(request.id, request.options.indices.toList()))
        is DecisionRequest.DeclareAttackers -> apply(Decision.MultiSelect(request.id, emptyList()))
        is DecisionRequest.DeclareBlockers -> apply(Decision.MultiSelect(request.id, emptyList()))
        else -> error("passOrOrder cannot answer $request")
    }

/** Plays the full accept-and-cast madness sequence on [game], leaving it after the bolt has resolved. */
private fun playMadnessCast(game: ScriptedGame) {
    game.driveUntil { game.pendingRequest is DecisionRequest.ChooseDiscards }
    val discard = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseDiscards>()
    val boltIndex = discard.options.indexOfFirst { it.card == CardRef(MADNESS_BOLT) }
    game.apply(Decision.MultiSelect(discard.id, listOf(boltIndex)))
    game.driveUntil { game.pendingRequest is DecisionRequest.ChooseYesNo }
    val yesNo = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()
    game.apply(Decision.SingleSelect(yesNo.id, DecisionRequest.ChooseYesNo.ACCEPT))
    val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
    game.apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Player(bob))))
    val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
    game.apply(Decision.SingleSelect(payment.id, 0))
    game.driveUntil {
        game.state.players
            .getValue(bob)
            .life < STARTING_LIFE
    }
}

private const val MAX_DRIVE_STEPS: Int = 200

// ---- fixture and state construction -------------------------------------------------------------------

private const val MADNESS_BOLT: String = "Fixture Madness Bolt"
private const val MADNESS_BOLT_DAMAGE: Int = 3
private const val MADNESS_TURN: Int = 3

/** A Fiery-Temper-style madness fixture: {1}{R} instant, any target loses 3, madness {R}. */
private val madnessBolt: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = MADNESS_BOLT,
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.AnyTarget
        override val resolution =
            ResolutionEffect { state, context ->
                when (val target = context.targets.single()) {
                    is Target.Player -> loseLife(state, target.id, MADNESS_BOLT_DAMAGE)
                    is Target.Permanent -> error("the madness fixture targets a player in this scenario: $target")
                    is Target.SpellOnStack ->
                        error("the madness fixture targets a player in this scenario: $target")
                }
            }
        override val castingPermissions = listOf(CastingPermission.Madness(ManaCost.parse("{R}")))
        override val replacementEffects =
            persistentListOf<ReplacementEffect>(ReplacementEffect.DiscardToExileInstead)
    }

private val madnessRegistry: Map<CardRef, CardDefinition> =
    MvpCards.definitions + (CardRef(MADNESS_BOLT) to madnessBolt)

private fun madnessCleanupGame(): ScriptedGame = ScriptedGame.startFrom(madnessCleanupState())

/**
 * An end-step state (alice holding priority) where alice will discard down to seven at cleanup and holds
 * the madness bolt plus a red source: an eight-card hand (the bolt and seven fillers), a Mountain for the
 * madness cost. Starting at the end step lets the engine enter cleanup naturally, so the first
 * StepBegan(CLEANUP) is emitted and the CR 514.3a repeat is observable.
 */
private fun madnessCleanupState(): GameState {
    val hand =
        listOf(GameObject(ObjectId(0), CardRef(MADNESS_BOLT), alice)) +
            (1..7).map { GameObject(ObjectId(it.toLong()), CardRef("Forest"), alice) }
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = hand.toPersistentList(),
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
            ),
        turn = Turn(alice, MADNESS_TURN, TurnPhase.ENDING, TurnStep.END),
        sharedZones =
            SharedZones(
                battlefield =
                    persistentListOf(GameObject(ObjectId(10), CardRef("Mountain"), alice, summoningSick = false)),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 11,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = madnessRegistry.toPersistentMap(),
    )
}

/** A precombat-main state where alice holds priority with the given battlefield and graveyard. */
private fun escapeState(
    aliceBattlefield: List<GameObject>,
    aliceGraveyard: List<GameObject>,
): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = aliceGraveyard.toPersistentList(),
                        priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(aliceBattlefield.toPersistentList(), persistentListOf(), persistentListOf()),
        nextObjectId = nextIdOf(aliceBattlefield + aliceGraveyard),
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )

private fun nextIdOf(objects: List<GameObject>): Long = (objects.maxOfOrNull { it.id.value } ?: -1L) + 1
