package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P6.3 cards that refresh the two encoded decklists, driven end-to-end through the real engine by
 * [ScriptedGame] (which invariant-checks every transition): Kessig Flamebreather's noncreature cast
 * trigger — including the enchantment cast that fires it where Guttersnipe's instant-or-sorcery
 * whitelist would not — Wild Growth's additional `{G}` in a real payment, both spent inside the cast that
 * produced it (the P8.3 direct line, one tap paying `{1}{G}`) and left floating when the cast does not
 * need it, and Kruphix's Insight's up-to-three enchantment-card keep. The fourth card, the one
 * *named* Lifelink, is a combat behaviour and lives in [BoglesTriggerAcceptanceSpec] beside Armadillo
 * Cloak, the trigger it contrasts with. Every state is a valid engine input by construction (ADR-004).
 */
class DecklistRefreshAcceptanceSpec :
    StringSpec({

        "CR 603.2e: Kessig Flamebreather pings each opponent when you cast a noncreature spell" {
            val game =
                refreshGame(
                    alice =
                        RefreshBoard(
                            hand = listOf(obj(10, "Lightning Bolt")),
                            battlefield = listOf(notSick(obj(0, "Kessig Flamebreather")), obj(1, "Mountain")),
                        ),
                )
            game.castTargeting("Lightning Bolt", Target.Player(bob))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            // Bob took the Bolt's 3 and the Flamebreather's 1 = 4; alice is untouched.
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 4
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE
        }

        "CR 603.2e: Kessig Flamebreather does NOT fire on a creature spell — the exclusion is real" {
            val game =
                refreshGame(
                    alice =
                        RefreshBoard(
                            hand = listOf(obj(10, "Grizzly Bears")),
                            battlefield =
                                listOf(
                                    notSick(obj(0, "Kessig Flamebreather")),
                                    obj(1, "Forest"),
                                    obj(2, "Forest"),
                                ),
                        ),
                )
            game.castOption("Grizzly Bears")
            game.payFirstPlan()
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE
        }

        "CR 603.2e: Kessig Flamebreather fires on an enchantment spell, where Guttersnipe would not" {
            // Rancor is neither instant nor sorcery: Guttersnipe's whitelist misses it, the
            // noncreature exclusion catches it. Both watchers are on the battlefield to prove it.
            val game =
                refreshGame(
                    alice =
                        RefreshBoard(
                            hand = listOf(obj(10, "Rancor")),
                            battlefield =
                                listOf(
                                    notSick(obj(0, "Kessig Flamebreather")),
                                    notSick(obj(3, "Guttersnipe")),
                                    obj(1, "Forest"),
                                ),
                        ),
                )
            game.castAuraOn("Rancor", ObjectId(0))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            // Exactly 1 damage: the Flamebreather's. Guttersnipe's 2 never happened.
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - KESSIG_PING
        }

        "CR 605.1b: Wild Growth's enchanted Forest pays a two-mana spell off one tap, as a single plan" {
            // The direct line, and the reason P8.3 exists. A Forest enchanted by Wild Growth is alice's
            // ONLY land, and its single activation pays the whole of Malevolent Rumble's {1}{G}: the
            // Forest's own {G} and the Aura's printed additional {G}. Before CR 601.2g production was
            // split from CR 601.2h payment, one tap could pay only one symbol and this cast enumerated
            // no plan at all — a legal line missing from the action space (ADR-005).
            val game =
                refreshGame(
                    alice =
                        RefreshBoard(
                            hand = listOf(obj(10, "Malevolent Rumble")),
                            battlefield =
                                listOf(obj(0, "Forest"), obj(1, "Wild Growth").copy(attachedTo = ObjectId(0))),
                            library =
                                listOf(
                                    obj(20, "Gladecover Scout"),
                                    obj(21, "Lightning Bolt"),
                                    obj(22, "Forest"),
                                    obj(23, "Mountain"),
                                ),
                        ),
                )
            game.castOption("Malevolent Rumble")
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            payment.options shouldHaveSize 1
            payment.options.single().activations shouldHaveSize 1
            payment.options.single().payments shouldHaveSize 2
            game.apply(Decision.SingleSelect(payment.id, 0))
            // One tap, both symbols paid, nothing left floating (CR 601.2g-h).
            game.state.sharedZones.battlefield
                .single { it.id == ObjectId(0) }
                .tapped
                .shouldBeTrue()
            game.state.players
                .getValue(alice)
                .manaPool
                .shouldBeEmpty()
            game.state.events
                .filterIsInstance<GameEvent.ManaAdded>()
                .map { it.mana } shouldContainExactly listOf(ManaType.GREEN, ManaType.GREEN)
        }

        "CR 500.4: Wild Growth's bonus still floats when the cast does not spend it, and pays a second spell" {
            // Floating stays legal: tapping the enchanted Forest for a {G} Rancor spends only the primary
            // green, and the Aura's additional green survives to pay a {G} Gladecover Scout with no
            // untapped land left — one land, two one-drops. The surplus half of the same model.
            val game =
                refreshGame(
                    alice =
                        RefreshBoard(
                            hand = listOf(obj(10, "Rancor"), obj(11, "Gladecover Scout")),
                            battlefield =
                                listOf(
                                    obj(0, "Forest"),
                                    obj(1, "Wild Growth").copy(attachedTo = ObjectId(0)),
                                    notSick(obj(2, "Grizzly Bears")),
                                ),
                        ),
                )
            game.castAuraOn("Rancor", ObjectId(2))
            // The primary {G} paid Rancor; the Wild Growth bonus {G} is floating (CR 605.1b, CR 500.4).
            game.state.players
                .getValue(alice)
                .manaPool
                .toList() shouldContainExactly listOf(ManaType.GREEN)
            game.driveUntil {
                game.state.sharedZones.battlefield
                    .any { it.card == CardRef("Rancor") }
            }
            // The Forest is tapped, so the Scout can only be paid from the floated bonus green.
            game.state.sharedZones.battlefield
                .single { it.id == ObjectId(0) }
                .tapped
                .shouldBeTrue()
            game.castOption("Gladecover Scout")
            game.payFirstPlan()
            game.driveUntil {
                game.state.sharedZones.battlefield
                    .any { it.card == CardRef("Gladecover Scout") }
            }
            game.state.players
                .getValue(alice)
                .manaPool
                .shouldBeEmpty()
        }

        "CR 605.1b: Wild Growth enchants any land — a Plains taps for {W} plus the printed {G}" {
            // Unlike Utopia Sprawl (Enchant Forest, chosen colour), Wild Growth's land may be any type
            // and its bonus is the printed green — so a Plains produces one of each.
            val game =
                refreshGame(
                    alice =
                        RefreshBoard(
                            hand = listOf(obj(10, "Sentinel's Eyes")),
                            battlefield =
                                listOf(
                                    obj(0, "Plains"),
                                    obj(1, "Wild Growth").copy(attachedTo = ObjectId(0)),
                                    notSick(obj(2, "Grizzly Bears")),
                                ),
                        ),
                )
            game.castAuraOn("Sentinel's Eyes", ObjectId(2))
            // {W} paid the Aura; the additional {G} floats even though no green source is on the board.
            game.state.players
                .getValue(alice)
                .manaPool
                .toList() shouldContainExactly listOf(ManaType.GREEN)
            game.state.events
                .filterIsInstance<GameEvent.ManaAdded>()
                .map { it.mana } shouldContainExactly listOf(ManaType.WHITE, ManaType.GREEN)
        }

        "CR 701.16: Kruphix's Insight reveals six and keeps up to three enchantment cards, rest to graveyard" {
            val game =
                refreshGame(
                    alice =
                        RefreshBoard(
                            hand = listOf(obj(10, "Kruphix's Insight")),
                            battlefield = listOf(obj(0, "Forest"), obj(1, "Forest"), obj(2, "Forest")),
                            library =
                                listOf(
                                    obj(20, "Rancor"),
                                    obj(21, "Lightning Bolt"),
                                    obj(22, "Ethereal Armor"),
                                    obj(23, "Forest"),
                                    obj(24, "Utopia Sprawl"),
                                    obj(25, "Gladecover Scout"),
                                    obj(26, "Mountain"),
                                ),
                        ),
                )
            game.castOption("Kruphix's Insight")
            game.payFirstPlan()
            // Three keep rounds, each taking the first remaining enchantment card.
            repeat(KRUPHIX_KEEPS) {
                game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromRevealed }
                val reveal = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromRevealed>()
                // Only enchantment cards are keepable: the Forest and Gladecover Scout never appear.
                reveal.options.map { it.card } shouldNotContain CardRef("Forest")
                reveal.options.map { it.card } shouldNotContain CardRef("Gladecover Scout")
                game.apply(Decision.SingleSelect(reveal.id, 0))
            }
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactlyInAnyOrder
                listOf(CardRef("Rancor"), CardRef("Ethereal Armor"), CardRef("Utopia Sprawl"))
            // "The rest of the revealed cards" — the other three of the six — went to the graveyard.
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactlyInAnyOrder
                listOf(
                    CardRef("Lightning Bolt"),
                    CardRef("Forest"),
                    CardRef("Gladecover Scout"),
                    CardRef("Kruphix's Insight"),
                )
            // The seventh library card was never revealed and stays on top.
            game.state.players
                .getValue(alice)
                .library
                .map { it.card } shouldContainExactly listOf(CardRef("Mountain"))
        }

        "CR 701.16: Kruphix's Insight's allowance is a maximum — declining leaves enchantments in the graveyard" {
            val game =
                refreshGame(
                    alice =
                        RefreshBoard(
                            hand = listOf(obj(10, "Kruphix's Insight")),
                            battlefield = listOf(obj(0, "Forest"), obj(1, "Forest"), obj(2, "Forest")),
                            library =
                                listOf(
                                    obj(20, "Rancor"),
                                    obj(21, "Ethereal Armor"),
                                    obj(22, "Utopia Sprawl"),
                                    obj(23, "Abundant Growth"),
                                ),
                        ),
                )
            game.castOption("Kruphix's Insight")
            game.payFirstPlan()
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromRevealed }
            val first = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromRevealed>()
            game.apply(Decision.SingleSelect(first.id, 0))
            // A second round is offered (allowance 3, three candidates left); stop after one keep.
            val second = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromRevealed>()
            game.apply(Decision.SingleSelect(second.id, second.keepNoneIndex))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly listOf(CardRef("Rancor"))
            // The three declined enchantment cards are still "the rest of the revealed cards".
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactlyInAnyOrder
                listOf(
                    CardRef("Ethereal Armor"),
                    CardRef("Utopia Sprawl"),
                    CardRef("Abundant Growth"),
                    CardRef("Kruphix's Insight"),
                )
        }
    })

/** The damage Kessig Flamebreather's trigger deals to each opponent (CR 120.3a). */
private const val KESSIG_PING: Int = 1

/** Kruphix's Insight's keep allowance — "put up to three enchantment cards … into your hand". */
private const val KRUPHIX_KEEPS: Int = 3

/** One seat's hand, battlefield, and library objects, for constructing a refresh scenario board. */
private data class RefreshBoard(
    val hand: List<GameObject> = emptyList(),
    val battlefield: List<GameObject> = emptyList(),
    val library: List<GameObject> = emptyList(),
)

/** A battlefield/hand object [id] of card [name] (owner reassigned per seat by [refreshGame]). */
private fun obj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice)

/** Marks a battlefield creature as no longer summoning sick (controlled since the turn began). */
private fun notSick(obj: GameObject): GameObject = obj.copy(summoningSick = false)

/** The current priority window, which must be a [DecisionRequest.ChooseAction] (CR 117). */
private fun ScriptedGame.action(): DecisionRequest.ChooseAction =
    pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()

/** Selects the cast option for [name] from the current priority window (CR 601.2). */
private fun ScriptedGame.castOption(name: String): ScriptedGame {
    val window = action()
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(name) }
    check(index >= 0) { "no CastSpell option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Answers the pending payment request with its first enumerated plan (CR 601.2g). */
private fun ScriptedGame.payFirstPlan(): ScriptedGame {
    val payment = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
    return apply(Decision.SingleSelect(payment.id, 0))
}

/** Casts the targeted spell [name] at [target], paying its first plan (CR 601.2c, CR 601.2g). */
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

/** Casts the Aura [name] onto the battlefield object [target], paying its first plan (CR 303.4a). */
private fun ScriptedGame.castAuraOn(
    name: String,
    target: ObjectId,
): ScriptedGame = castTargeting(name, Target.Permanent(target))

/** Passes priority, ordering any simultaneous triggers in the deterministic identity permutation. */
private fun ScriptedGame.passOrOrder(): ScriptedGame =
    when (val request = pendingRequest) {
        is DecisionRequest.ChooseAction -> {
            val index = request.options.indexOfFirst { it is PriorityOption.Pass }
            check(index >= 0) { "CR 117.3d: passing must always be enumerated" }
            apply(Decision.SingleSelect(request.id, index))
        }

        is DecisionRequest.OrderTriggers -> apply(Decision.MultiSelect(request.id, request.options.indices.toList()))
        is DecisionRequest.DeclareAttackers -> apply(Decision.MultiSelect(request.id, emptyList()))
        is DecisionRequest.DeclareBlockers -> apply(Decision.MultiSelect(request.id, emptyList()))
        else -> error("passOrOrder cannot answer $request")
    }

/** Advances (passing / declining combat / ordering triggers) until [predicate] holds. */
private fun ScriptedGame.driveUntil(predicate: () -> Boolean): ScriptedGame {
    var steps = 0
    while (!predicate() && !isOver && steps < MAX_REFRESH_DRIVE_STEPS) {
        passOrOrder()
        steps++
    }
    check(predicate()) { "the drive predicate was not satisfied within $MAX_REFRESH_DRIVE_STEPS steps" }
    return this
}

private const val MAX_REFRESH_DRIVE_STEPS: Int = 200

/** The turn these scenarios resume on — late enough that nothing is summoning sick by construction. */
private const val REFRESH_TURN: Int = 3

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004): [holder] holds priority on
 * the given [alice] and [bob] boards over the real [MvpCards] definitions; the turn is [REFRESH_TURN]
 * and belongs to alice. Every transition is invariant-checked by the driver.
 */
private fun refreshGame(
    alice: RefreshBoard = RefreshBoard(),
    bob: RefreshBoard = RefreshBoard(),
    holder: PlayerId = dev.mtgplay.acceptance.alice,
): ScriptedGame {
    val aliceSeat = dev.mtgplay.acceptance.alice
    val bobSeat = dev.mtgplay.acceptance.bob
    val bobHand = bob.hand.map { it.copy(owner = bobSeat) }
    val bobField = bob.battlefield.map { it.copy(owner = bobSeat) }
    val bobLibrary = bob.library.map { it.copy(owner = bobSeat) }
    val allObjects = alice.hand + alice.battlefield + alice.library + bobHand + bobField + bobLibrary
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
                            graveyard = persistentListOf(),
                            priorityStatus = priorityOf(aliceSeat),
                        ),
                    bobSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = bobLibrary.toPersistentList(),
                            hand = bobHand.toPersistentList(),
                            graveyard = persistentListOf(),
                            priorityStatus = priorityOf(bobSeat),
                        ),
                ),
            turn = Turn(aliceSeat, REFRESH_TURN, TurnPhase.PRECOMBAT_MAIN, null),
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
