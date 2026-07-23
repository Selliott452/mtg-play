package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaType
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
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P6.2b headline behaviours of the thirteen cards, each driven end-to-end through the real engine by
 * [ScriptedGame] (which invariant-checks every transition, deliverable 2): the flagship madness-off-a-Grab
 * combo, Guttersnipe's filtered ping, Fireblast's and Lava Dart's non-mana costs, Utopia Sprawl's ramp
 * mana in a real payment, the ETB/activated token makers, Malevolent Rumble's reveal, Sneaky Snacker's
 * third-draw return, and Highway Robbery's plot. Every state is a valid engine input by construction
 * (ADR-004). The three STOP-flagged resolutions (Highway Robbery/Faithless Looting resolution, Ash Barrens
 * search) are pinned as loud failures in the `mtg-cards` unit specs; here their working halves (plot,
 * flashback, landcycling enumeration) are driven.
 */
class MvpCardsAcceptanceSpec :
    StringSpec({

        "CR 603.2e: Guttersnipe pings each opponent when you cast an instant" {
            val game =
                gameFrom(
                    alice =
                        MvpBoard(
                            hand = listOf(obj(10, "Lightning Bolt")),
                            battlefield = listOf(notSick(obj(0, "Guttersnipe")), obj(1, "Mountain")),
                        ),
                )
            game.castTargeting("Lightning Bolt", Target.Player(bob))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            // Bob took the Bolt's 3 and Guttersnipe's 2 = 5; alice is untouched.
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 5
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE
        }

        "CR 603.2e: an opponent's instant does not fire your Guttersnipe" {
            val game =
                gameFrom(
                    alice = MvpBoard(battlefield = listOf(notSick(obj(0, "Guttersnipe")))),
                    bob = MvpBoard(hand = listOf(obj(10, "Lightning Bolt")), battlefield = listOf(obj(11, "Mountain"))),
                    holder = bob,
                )
            game.castTargeting("Lightning Bolt", Target.Player(alice))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            // Alice took the Bolt's 3; bob is untouched — alice's Guttersnipe watches "you cast", not bob.
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE - 3
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE
        }

        "CR 702.35: a Fiery Temper discarded to Grab the Prize's cost is exiled, then cast for {R}" {
            // Alice casts Grab the Prize (discarding Fiery Temper, which has madness); the reflexive trigger
            // lets her cast it from exile for {R}. She has three Mountains: {1}{R} for Grab, {R} for Temper.
            val game =
                gameFrom(
                    alice =
                        MvpBoard(
                            hand = listOf(obj(10, "Grab the Prize"), obj(11, "Fiery Temper")),
                            battlefield = listOf(obj(0, "Mountain"), obj(1, "Mountain"), obj(2, "Mountain")),
                            library = listOf(obj(20, "Mountain"), obj(21, "Mountain")),
                        ),
                )
            // Cast Grab; discard Fiery Temper to its additional cost (CR 601.2b); pay {1}{R}.
            game.castOption("Grab the Prize")
            val discard = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseCardsToDiscardForCost>()
            val temperIndex = discard.options.indexOfFirst { it.card == CardRef("Fiery Temper") }
            game.apply(Decision.MultiSelect(discard.id, listOf(temperIndex)))
            game.payFirstPlan()
            // The reflexive madness trigger resolves: accept the cast, target bob, pay {R}.
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseYesNo }
            val yesNo = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()
            game.apply(Decision.SingleSelect(yesNo.id, DecisionRequest.ChooseYesNo.ACCEPT))
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            game.apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Player(bob))))
            game.payFirstPlan()
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            // Fiery Temper (3) plus Grab's non-land bonus (2) hit bob; alice drew two off Grab.
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 3 - 2
            game.state.players
                .getValue(alice)
                .drawsThisTurn shouldBe 2
            game.state.events
                .filterIsInstance<GameEvent.CardExiledByMadness>()
                .isNotEmpty()
                .shouldBeTrue()
        }

        "CR 118.9 / CR 701.17: Fireblast is cast for its alternative cost, sacrificing two Mountains" {
            val game =
                gameFrom(
                    alice =
                        MvpBoard(
                            hand = listOf(obj(10, "Fireblast")),
                            battlefield = listOf(obj(0, "Mountain"), obj(1, "Mountain")),
                        ),
                )
            game.castAlternativeCost("Fireblast")
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            game.apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Player(bob))))
            val sacrifices = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseSacrifices>()
            sacrifices.count shouldBe 2
            game.apply(Decision.MultiSelect(sacrifices.id, listOf(0, 1)))
            game.payFirstPlan()
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            // Bob took Fireblast's 4; both Mountains are in alice's graveyard.
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 4
            game.state.players
                .getValue(alice)
                .graveyard
                .count { it.card == CardRef("Mountain") } shouldBe 2
        }

        "CR 702.34: Lava Dart is flashed back from the graveyard, sacrificing a Mountain" {
            val game =
                gameFrom(
                    alice =
                        MvpBoard(
                            battlefield = listOf(obj(0, "Mountain")),
                            graveyard = listOf(obj(5, "Lava Dart")),
                        ),
                )
            game.castFlashback("Lava Dart")
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            game.apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Player(bob))))
            val sacrifices = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseSacrifices>()
            sacrifices.count shouldBe 1
            game.apply(Decision.MultiSelect(sacrifices.id, listOf(0)))
            game.payFirstPlan()
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            // Bob took 1; the flashback spell is in exile, not the graveyard (CR 702.34e).
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 1
            game.state.sharedZones.exile
                .count { it.card == CardRef("Lava Dart") } shouldBe 1
        }

        "CR 614.12: Utopia Sprawl enters attached to a Forest with the chosen colour stored" {
            val game =
                gameFrom(
                    alice =
                        MvpBoard(
                            hand = listOf(obj(10, "Utopia Sprawl")),
                            battlefield = listOf(obj(0, "Forest"), obj(1, "Forest")),
                        ),
                )
            game.castAuraOn("Utopia Sprawl", ObjectId(0))
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseColor }
            val colour = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseColor>()
            game.apply(Decision.SingleSelect(colour.id, colour.options.indexOf(Color.RED)))
            game.driveUntil {
                game.state.sharedZones.battlefield
                    .any { it.card == CardRef("Utopia Sprawl") }
            }
            val sprawl =
                game.state.sharedZones.battlefield
                    .single { it.card == CardRef("Utopia Sprawl") }
            sprawl.attachedTo shouldBe ObjectId(0)
            sprawl.chosenColor shouldBe Color.RED
        }

        "CR 605.1b: Utopia Sprawl's bonus mana floats and pays a later spell in the same step" {
            // A Forest enchanted by a Sprawl that chose RED is alice's only mana source. Tapping it for a
            // {G} Gladecover Scout floats an extra red; that floated red then pays a {R} Lightning Bolt with
            // no further tapping — the ramp, exercised through two real casts.
            val game =
                gameFrom(
                    alice =
                        MvpBoard(
                            hand = listOf(obj(10, "Gladecover Scout"), obj(11, "Lightning Bolt")),
                            battlefield =
                                listOf(
                                    obj(0, "Forest"),
                                    obj(1, "Utopia Sprawl").copy(attachedTo = ObjectId(0), chosenColor = Color.RED),
                                ),
                        ),
                )
            // Cast the {G} Scout, tapping the enchanted Forest — this floats the bonus red into the pool.
            game.castOption("Gladecover Scout")
            game.payFirstPlan()
            game.state.players
                .getValue(alice)
                .manaPool
                .toList() shouldContain ManaType.RED
            // Cast the {R} Bolt at bob, paid entirely from the floated red (the Forest is now tapped).
            game.castTargeting("Lightning Bolt", Target.Player(bob))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 3
        }

        "CR 603.6a / CR 707.2: Voldaren Epicure's ETB burns each opponent for 1 and makes a Blood token" {
            val game =
                gameFrom(
                    alice =
                        MvpBoard(
                            hand = listOf(obj(10, "Voldaren Epicure")),
                            battlefield = listOf(obj(0, "Mountain")),
                        ),
                )
            game.castOption("Voldaren Epicure")
            game.payFirstPlan()
            game.driveUntil {
                game.state.sharedZones.battlefield
                    .any { it.card == CardRef("Blood") }
            }
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 1
            game.state.sharedZones.battlefield
                .count { it.card == CardRef("Blood") } shouldBe 1
        }

        "CR 602 / CR 707.2: Melded Moxite's {3}, sacrifice ability creates a tapped Robot token" {
            val game =
                gameFrom(
                    alice =
                        MvpBoard(
                            battlefield =
                                listOf(
                                    obj(0, "Melded Moxite"),
                                    obj(1, "Mountain"),
                                    obj(2, "Mountain"),
                                    obj(3, "Mountain"),
                                ),
                        ),
                )
            game.activateAbility("Melded Moxite")
            game.payFirstPlan()
            game.driveUntil {
                game.state.sharedZones.battlefield
                    .any { it.card == CardRef("Robot") }
            }
            val robot =
                game.state.sharedZones.battlefield
                    .single { it.card == CardRef("Robot") }
            robot.tapped.shouldBeTrue()
            game.state.sharedZones.battlefield
                .none { it.card == CardRef("Melded Moxite") } shouldBe true
        }

        "CR 701.16 / CR 707.2: Malevolent Rumble makes an Eldrazi Spawn and reveals four, keeping a permanent" {
            val game =
                gameFrom(
                    alice =
                        MvpBoard(
                            hand = listOf(obj(10, "Malevolent Rumble")),
                            battlefield = listOf(obj(0, "Forest"), obj(1, "Forest")),
                            library =
                                listOf(
                                    obj(20, "Grizzly Bears"),
                                    obj(21, "Lightning Bolt"),
                                    obj(22, "Mountain"),
                                    obj(23, "Lightning Bolt"),
                                ),
                        ),
                )
            game.castOption("Malevolent Rumble")
            game.payFirstPlan()
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromRevealed }
            val reveal = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromRevealed>()
            // Only the permanent cards (Grizzly Bears, Mountain) are keepable; keep the Bears.
            val bearsIndex = reveal.options.indexOfFirst { it.card == CardRef("Grizzly Bears") }
            game.apply(Decision.SingleSelect(reveal.id, bearsIndex))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.sharedZones.battlefield
                .count { it.card == CardRef("Eldrazi Spawn") } shouldBe 1
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContain CardRef("Grizzly Bears")
            // The two revealed instants went to the graveyard.
            game.state.players
                .getValue(alice)
                .graveyard
                .count { it.card == CardRef("Lightning Bolt") } shouldBe 2
        }

        "CR 603.2: Sneaky Snacker returns from the graveyard when its owner draws a third card in a turn" {
            // Alice has drawn two cards already this turn and Snacker sits in her graveyard. Grab the Prize
            // draws two — the first crosses the third-draw threshold and returns Snacker tapped.
            val game =
                gameFrom(
                    alice =
                        MvpBoard(
                            hand = listOf(obj(10, "Grab the Prize"), obj(11, "Mountain")),
                            battlefield = listOf(obj(0, "Mountain"), obj(1, "Mountain")),
                            library = listOf(obj(20, "Mountain"), obj(21, "Mountain")),
                            graveyard = listOf(obj(5, "Sneaky Snacker")),
                        ),
                    aliceDrawsThisTurn = 2,
                )
            game.castOption("Grab the Prize")
            val discard = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseCardsToDiscardForCost>()
            // Discard the spare Mountain (a land, so Grab deals no damage — irrelevant to the return).
            val mountainIndex = discard.options.indexOfFirst { it.card == CardRef("Mountain") }
            game.apply(Decision.MultiSelect(discard.id, listOf(mountainIndex)))
            game.payFirstPlan()
            game.driveUntil {
                game.state.sharedZones.battlefield
                    .any { it.card == CardRef("Sneaky Snacker") }
            }
            val snacker =
                game.state.sharedZones.battlefield
                    .single { it.card == CardRef("Sneaky Snacker") }
            snacker.tapped.shouldBeTrue()
            game.state.players
                .getValue(alice)
                .graveyard
                .none { it.card == CardRef("Sneaky Snacker") } shouldBe true
        }

        "CR 702.140: Highway Robbery is plotted for {1}{R}, exiled this turn, and free-cast the next" {
            val game =
                gameFrom(
                    alice =
                        MvpBoard(
                            hand = listOf(obj(10, "Highway Robbery")),
                            battlefield = listOf(obj(0, "Mountain"), obj(1, "Mountain")),
                        ),
                    turnNumber = 3,
                )
            game.plotCard("Highway Robbery")
            game.payFirstPlan()
            // It is in exile with this turn's plotted marker, and not castable-for-free this same turn.
            val exiled =
                game.state.sharedZones.exile
                    .single { it.card == CardRef("Highway Robbery") }
            exiled.plottedTurn shouldBe 3
            game
                .action()
                .options
                .filterIsInstance<PriorityOption.CastSpell>()
                .none { it.permission is CastingPermission.Plot } shouldBe true

            // On a later turn the free cast from exile is enumerated (its resolution is STOP-flagged, so we
            // assert the option exists rather than resolve it).
            val nextTurn =
                ScriptedGame.startFrom(
                    plottedExileState(plottedTurn = 3, currentTurn = 4),
                )
            nextTurn
                .action()
                .options
                .filterIsInstance<PriorityOption.CastSpell>()
                .any { it.card == CardRef("Highway Robbery") && it.permission is CastingPermission.Plot } shouldBe true
        }

        "CR 113.6c: Ash Barrens' basic landcycling is enumerated from the hand when {1} is available" {
            val game =
                gameFrom(
                    alice =
                        MvpBoard(
                            hand = listOf(obj(10, "Ash Barrens")),
                            battlefield = listOf(obj(0, "Mountain")),
                            library = listOf(obj(20, "Mountain")),
                        ),
                )
            // The hand-scoped landcycling activation is offered (its search effect is STOP-flagged, so we
            // assert enumeration rather than activate it).
            game
                .action()
                .options
                .filterIsInstance<PriorityOption.ActivateAbility>()
                .any { it.card == CardRef("Ash Barrens") } shouldBe true
        }
    })

// ---- driving helpers over ScriptedGame (invariant-checked every transition) -----------------------

private fun ScriptedGame.action(): DecisionRequest.ChooseAction =
    pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()

private fun ScriptedGame.castOption(name: String): ScriptedGame {
    val window = action()
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(name) }
    check(index >= 0) { "no CastSpell option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

private fun ScriptedGame.castAlternativeCost(name: String): ScriptedGame {
    val window = action()
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell &&
                it.card == CardRef(name) &&
                it.permission is CastingPermission.AlternativeCost
        }
    check(index >= 0) { "no alternative-cost cast for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

private fun ScriptedGame.castFlashback(name: String): ScriptedGame {
    val window = action()
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell && it.card == CardRef(name) && it.source == CastSource.GRAVEYARD
        }
    check(index >= 0) { "no flashback cast for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

private fun ScriptedGame.activateAbility(name: String): ScriptedGame {
    val window = action()
    val index = window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == CardRef(name) }
    check(index >= 0) { "no ActivateAbility option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

private fun ScriptedGame.plotCard(name: String): ScriptedGame {
    val window = action()
    val index = window.options.indexOfFirst { it is PriorityOption.PlotCard && it.card == CardRef(name) }
    check(index >= 0) { "no PlotCard option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

private fun ScriptedGame.castTargeting(
    name: String,
    target: Target,
): ScriptedGame {
    castOption(name)
    val targets = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
    val index = targets.options.indexOf(target)
    check(index >= 0) { "no legal target $target for $name in ${targets.options}" }
    apply(Decision.SingleSelect(targets.id, index))
    return payFirstPlan()
}

private fun ScriptedGame.castAuraOn(
    name: String,
    target: ObjectId,
): ScriptedGame {
    castOption(name)
    val targets = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
    val index = targets.options.indexOf(Target.Permanent(target))
    check(index >= 0) { "no legal enchant target $target for $name in ${targets.options}" }
    apply(Decision.SingleSelect(targets.id, index))
    return payFirstPlan()
}

private fun ScriptedGame.payFirstPlan(): ScriptedGame {
    val payment = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
    return apply(Decision.SingleSelect(payment.id, 0))
}

private fun passDecision(request: DecisionRequest.ChooseAction): Decision.SingleSelect {
    val index = request.options.indexOfFirst { it is PriorityOption.Pass }
    check(index >= 0) { "CR 117.3d: passing must always be enumerated" }
    return Decision.SingleSelect(request.id, index)
}

private fun ScriptedGame.passOrOrder(): ScriptedGame =
    when (val request = pendingRequest) {
        is DecisionRequest.ChooseAction -> apply(passDecision(request))
        is DecisionRequest.OrderTriggers -> apply(Decision.MultiSelect(request.id, request.options.indices.toList()))
        is DecisionRequest.DeclareAttackers -> apply(Decision.MultiSelect(request.id, emptyList()))
        is DecisionRequest.DeclareBlockers -> apply(Decision.MultiSelect(request.id, emptyList()))
        else -> error("passOrOrder cannot answer $request")
    }

private fun ScriptedGame.driveUntil(predicate: () -> Boolean): ScriptedGame {
    var steps = 0
    while (!predicate() && !isOver && steps < MAX_DRIVE_STEPS) {
        passOrOrder()
        steps++
    }
    check(predicate()) { "the drive predicate was not satisfied within $MAX_DRIVE_STEPS steps" }
    return this
}

private const val MAX_DRIVE_STEPS: Int = 200

// ---- state construction ---------------------------------------------------------------------------

/** One seat's hand, battlefield, library, and graveyard objects, for constructing a scenario board. */
private data class MvpBoard(
    val hand: List<GameObject> = emptyList(),
    val battlefield: List<GameObject> = emptyList(),
    val library: List<GameObject> = emptyList(),
    val graveyard: List<GameObject> = emptyList(),
)

/** A battlefield/hand object [id] of card [name] (owner assigned by [gameFrom]). */
private fun obj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice)

/** Marks a battlefield object as no longer summoning sick (controlled since the turn began). */
private fun notSick(obj: GameObject): GameObject = obj.copy(summoningSick = false)

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004): [holder] holds priority on
 * the given [alice] and [bob] boards over the real [MvpCards] definitions; the turn is [turnNumber] and
 * belongs to alice, who has taken [aliceDrawsThisTurn] draws already. Every transition is invariant-checked.
 */
private fun gameFrom(
    alice: MvpBoard = MvpBoard(),
    bob: MvpBoard = MvpBoard(),
    holder: PlayerId = dev.mtgplay.acceptance.alice,
    turnNumber: Int = 3,
    aliceDrawsThisTurn: Int = 0,
): ScriptedGame {
    val aliceSeat = dev.mtgplay.acceptance.alice
    val bobSeat = dev.mtgplay.acceptance.bob
    val bobHand = bob.hand.map { it.copy(owner = bobSeat) }
    val bobField = bob.battlefield.map { it.copy(owner = bobSeat) }
    val bobGrave = bob.graveyard.map { it.copy(owner = bobSeat) }
    val allObjects =
        alice.hand + alice.battlefield + alice.library + alice.graveyard + bobHand + bobField + bobGrave
    val nextId = (allObjects.maxOfOrNull { it.id.value } ?: -1L) + 1

    fun priorityOf(seat: PlayerId) = if (seat == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE
    val state =
        GameState(
            players =
                persistentMapOf(
                    aliceSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = alice.library.toPersistentList(),
                            hand = alice.hand.toPersistentList(),
                            graveyard = alice.graveyard.toPersistentList(),
                            priorityStatus = priorityOf(aliceSeat),
                            drawsThisTurn = aliceDrawsThisTurn,
                        ),
                    bobSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = persistentListOf(),
                            hand = bobHand.toPersistentList(),
                            graveyard = bobGrave.toPersistentList(),
                            priorityStatus = priorityOf(bobSeat),
                        ),
                ),
            turn = Turn(aliceSeat, turnNumber, TurnPhase.PRECOMBAT_MAIN, null),
            sharedZones =
                SharedZones(
                    battlefield = (alice.battlefield + bobField).toPersistentList(),
                    stack = persistentListOf(),
                    exile = persistentListOf(),
                ),
            nextObjectId = nextId,
            rng = Rng(0),
            events = persistentListOf(),
            definitions = MvpCards.definitions.toPersistentMap(),
        )
    return ScriptedGame.startFrom(state)
}

/** Alice holding priority on turn [currentTurn] with Highway Robbery already plotted in exile on [plottedTurn]. */
private fun plottedExileState(
    plottedTurn: Int,
    currentTurn: Int,
): GameState {
    val exiled = GameObject(ObjectId(0), CardRef("Highway Robbery"), alice, plottedTurn = plottedTurn)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
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
        turn = Turn(alice, currentTurn, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf(exiled)),
        nextObjectId = 1,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
