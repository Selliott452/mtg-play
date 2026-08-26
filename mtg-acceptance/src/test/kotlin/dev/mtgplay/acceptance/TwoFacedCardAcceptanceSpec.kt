package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.StackEntryView
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.viewFor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `W10-B`'s two two-faced cards driven end-to-end through [ScriptedGame], which invariant-checks every
 * transition: **Fang Dragon**, an adventurer card (CR 715), and **Sagu Wildling**, an omen card
 * (CR 720).
 *
 * Each printed clause gets a test that would fail if the clause were quietly missing, which is the whole
 * point of encoding the faces rather than approximating them:
 * - *"Forktail Sweep deals 1 damage to each creature **you don't control**"* — the caster's own board is
 *   untouched, which the deck that plays the card depends on.
 * - *"(Then exile this card. You may cast the creature later from exile.)"* — CR 715.3d in full: the
 *   Adventure resolves into **exile** rather than a graveyard, and the *creature* half is then an
 *   enumerated option out of it while the Adventure is **not** offered again.
 * - *"(Also shuffle this card.)"* — CR 720.3d: the Omen resolves into its owner's **library**, so there
 *   is no exile marker and no later cast at all; the two mechanics diverge here and nowhere else.
 * - *"When this creature enters, you gain 3 life"* — the trigger belongs to the creature half, so an
 *   Omen resolving gains nothing.
 *
 * And ADR-005 in both directions: with enough mana both halves are offered, and with only the face's
 * cost available exactly one is.
 */
class TwoFacedCardAcceptanceSpec :
    StringSpec({

        // ---- Adventure (CR 715) ---------------------------------------------------------------------

        "CR 715.3, ADR-005: both halves of an adventurer card are enumerated when both are affordable" {
            val game = ScriptedGame.startFrom(dragonBoard(mountains = DRAGON_COST_MOUNTAINS))
            dragonOptions(game).map { it.permission } shouldContainExactlyInAnyOrder
                listOf(null, CastingPermission.Adventure(manaCost("{1}{R}"), FORKTAIL_SWEEP))
        }

        "ADR-005: on two Mountains only the Adventure is offered — the printed {5}{R}{R} is unaffordable" {
            // The direction of ADR-005 that crashes rather than merely hides a line: an enumerated cast
            // whose cost cannot be paid dead-ends mid-pipeline.
            val game = ScriptedGame.startFrom(dragonBoard(mountains = ADVENTURE_COST_MOUNTAINS))
            val options = dragonOptions(game)
            options shouldHaveSize 1
            options.single().permission.shouldBeInstanceOf<CastingPermission.Adventure>()
        }

        "CR 715.3b: the spell on the stack is Forktail Sweep, and every seat may see which half it is" {
            val game = ScriptedGame.startFrom(dragonBoard(mountains = ADVENTURE_COST_MOUNTAINS))
            castDragon(game, asAdventure = true)

            // The stack is public (CR 405), so the *opponent's* view carries the face name — without it
            // bob could not tell a {1}{R} sweeper from a 6/3 creature spell before deciding to respond.
            val onStack =
                viewFor(game.state, bob)
                    .stack
                    .single()
                    .shouldBeInstanceOf<StackEntryView.SpellOnStack>()
            onStack.card shouldBe CardRef(FANG_DRAGON)
            onStack.castAsFace shouldBe FORKTAIL_SWEEP
        }

        "CR 120: Forktail Sweep damages each creature the caster does not control, and none of their own" {
            val game = ScriptedGame.startFrom(dragonBoard(mountains = ADVENTURE_COST_MOUNTAINS, withCreatures = true))
            castDragon(game, asAdventure = true)
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            // bob's 1-toughness Bogle died to the sweep; alice's own creature is untouched.
            game.state.sharedZones.battlefield
                .filter { it.card == CardRef(OPPONENT_CREATURE) }
                .shouldBeEmpty()
            game.state.sharedZones.battlefield
                .single { it.card == CardRef(OWN_CREATURE) }
                .damageMarked shouldBe 0
        }

        "CR 715.3d: an Adventure that resolves is exiled and marked, never put into a graveyard" {
            val game = ScriptedGame.startFrom(dragonBoard(mountains = ADVENTURE_COST_MOUNTAINS))
            castDragon(game, asAdventure = true)
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            game.state
                .players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            val exiled =
                game.state.sharedZones.exile
                    .single()
            exiled.card shouldBe CardRef(FANG_DRAGON)
            exiled.onAnAdventure shouldBe true
        }

        "CR 715.3d: the creature half is then playable from exile — and the Adventure is not offered again" {
            // "For as long as that card remains exiled, that player may play it. It can't be cast as an
            // Adventure this way." Both halves of that sentence, and the second is the one an
            // approximation would lose: a card that could re-cast its Adventure from exile would sweep
            // the board every turn for {1}{R}.
            val game = ScriptedGame.startFrom(dragonBoard(mountains = DRAGON_COST_MOUNTAINS))
            castDragon(game, asAdventure = true)
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            val options = dragonOptions(game)
            options shouldHaveSize 1
            options.single().source shouldBe CastSource.EXILE
            options.single().permission shouldBe null
        }

        "CR 715.3d: playing the creature half off its adventure puts the printed 6/3 Dragon onto the battlefield" {
            val game = ScriptedGame.startFrom(dragonBoard(mountains = DRAGON_COST_MOUNTAINS))
            castDragon(game, asAdventure = true)
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            // The sweep spent {1}{R}; the remaining Mountains are exactly the printed cost.
            castDragon(game, asAdventure = false)
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            val dragon =
                game.state.sharedZones.battlefield
                    .single { it.card == CardRef(FANG_DRAGON) }
            // CR 400.7: the permanent is a new object, so the exile marker did not follow it.
            dragon.onAnAdventure shouldBe false
            game.state.sharedZones.exile
                .shouldBeEmpty()
        }

        // ---- Omen (CR 720) --------------------------------------------------------------------------

        "CR 720.3, ADR-005: both halves of an omen card are enumerated when both are affordable" {
            val game = ScriptedGame.startFrom(wildlingBoard(forests = WILDLING_COST_FORESTS))
            wildlingOptions(game).map { it.permission } shouldContainExactlyInAnyOrder
                listOf(null, CastingPermission.Omen(manaCost("{G}"), SAGU_WILDS))
        }

        "CR 701.18: the Omen finds a basic land, reveals it, and puts it into its caster's hand" {
            val game = ScriptedGame.startFrom(wildlingBoard(forests = OMEN_COST_FORESTS))
            castWildling(game, asOmen = true)
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
            val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()
            // A basic land card, so the nonbasic in the pinned library is not a legal find.
            find.options.map { it.card } shouldContainExactlyInAnyOrder listOf(CardRef(FOREST), CardRef(FOREST))
            game.apply(Decision.SingleSelect(find.id, 0))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            game.state
                .players
                .getValue(alice)
                .hand
                .map { it.card } shouldBe listOf(CardRef(FOREST))
        }

        "CR 720.3d: an Omen that resolves is shuffled into its owner's library, never exiled or binned" {
            // The one clause that separates CR 720 from CR 715, and the whole design of the card: the
            // Dragon goes back into the deck rather than waiting in exile to be played later.
            val game = ScriptedGame.startFrom(wildlingBoard(forests = OMEN_COST_FORESTS))
            castWildling(game, asOmen = true)
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
            val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()
            game.apply(Decision.SingleSelect(find.id, 0))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            game.state.sharedZones.exile
                .shouldBeEmpty()
            game.state
                .players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            game.state
                .players
                .getValue(alice)
                .library
                .map { it.card } shouldContainExactlyInAnyOrder
                listOf(CardRef(FOREST), CardRef(NONBASIC_LAND), CardRef(SAGU_WILDLING))
        }

        "CR 603.6a: the enters trigger belongs to the creature half — an Omen resolving gains no life" {
            val game = ScriptedGame.startFrom(wildlingBoard(forests = OMEN_COST_FORESTS))
            castWildling(game, asOmen = true)
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
            val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()
            game.apply(Decision.SingleSelect(find.id, 0))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state
                .players
                .getValue(alice)
                .life shouldBe STARTING_LIFE
        }

        "CR 119.3: the creature half enters and gains its controller 3 life" {
            val game = ScriptedGame.startFrom(wildlingBoard(forests = WILDLING_COST_FORESTS))
            castWildling(game, asOmen = false)
            game.driveUntil {
                game.state.players
                    .getValue(alice)
                    .life != STARTING_LIFE
            }
            game.state
                .players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + WILDLING_LIFEGAIN
            game.state.sharedZones.battlefield
                .count { it.card == CardRef(SAGU_WILDLING) } shouldBe 1
        }

        "ADR-006: the Omen's shuffle draws from the match PRNG, so the same seed replays the same library" {
            val orders = listOf(OMEN_SEED, OMEN_SEED).map(::shuffledLibraryAfterOmen)
            orders[0] shouldBe orders[1]
            // The two shuffles — the search's "then shuffle" and CR 720.3d's own — consumed match
            // entropy: the generator moved, and there is no other sanctioned source (ADR-006).
            val before = wildlingBoard(forests = OMEN_COST_FORESTS, seed = OMEN_SEED).rng
            val game = ScriptedGame.startFrom(wildlingBoard(forests = OMEN_COST_FORESTS, seed = OMEN_SEED))
            castWildling(game, asOmen = true)
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
            val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()
            game.apply(Decision.SingleSelect(find.id, 0))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            (game.state.rng == before) shouldBe false
        }
    })

// ---- the cards under test ------------------------------------------------------------------------------

private const val FANG_DRAGON: String = "Fang Dragon"
private const val FORKTAIL_SWEEP: String = "Forktail Sweep"
private const val SAGU_WILDLING: String = "Sagu Wildling"
private const val SAGU_WILDS: String = "Sagu Wilds"

private const val MOUNTAIN: String = "Mountain"
private const val FOREST: String = "Forest"

/** A land that is not basic, so the Omen's "a basic land card" filter must refuse it (CR 205.4). */
private const val NONBASIC_LAND: String = "Bojuka Bog"

/** bob's creature, a 1/1 the sweep kills. */
private const val OPPONENT_CREATURE: String = "Slippery Bogle"

/** alice's own creature, which "each creature you don't control" must spare. */
private const val OWN_CREATURE: String = "Gingerbrute"

private const val WILDLING_LIFEGAIN: Int = 3

/** Mountains for the printed `{5}{R}{R}` cast, plus the `{1}{R}` the Adventure spends first. */
private const val DRAGON_COST_MOUNTAINS: Int = 9

/** Mountains for the `{1}{R}` Adventure and nothing else. */
private const val ADVENTURE_COST_MOUNTAINS: Int = 2

/** Forests for the printed `{4}{G}` cast. */
private const val WILDLING_COST_FORESTS: Int = 5

/** Forests for the `{G}` Omen and nothing else. */
private const val OMEN_COST_FORESTS: Int = 1

private const val OMEN_SEED: Long = 0x0FA1E

/** The turn the pinned boards resume on — late enough that nothing is summoning sick. */
private const val BOARD_TURN: Int = 8

private const val HAND_ID: Long = 50
private const val LIBRARY_BASE: Long = 60
private const val OPPONENT_BASE: Long = 80

// ---- boards --------------------------------------------------------------------------------------------

private fun manaCost(text: String) =
    dev.mtgplay.core.mana.ManaCost
        .parse(text)

private fun settled(
    id: Long,
    name: String,
    owner: dev.mtgplay.core.identity.PlayerId = alice,
): GameObject = GameObject(ObjectId(id), CardRef(name), owner).copy(summoningSick = false)

/**
 * alice on [mountains] untapped Mountains with a Fang Dragon in hand, holding priority in her own
 * precombat main phase. Two Mountains offer only the Adventure; nine offer both and leave the printed
 * cost payable after the Adventure has spent `{1}{R}`.
 */
private fun dragonBoard(
    mountains: Int,
    withCreatures: Boolean = false,
): GameState =
    board(
        battlefield =
            List(mountains) { settled(it.toLong(), MOUNTAIN) } +
                if (withCreatures) listOf(settled(OPPONENT_BASE, OWN_CREATURE)) else emptyList(),
        opponentBattlefield =
            if (withCreatures) listOf(settled(OPPONENT_BASE + 1, OPPONENT_CREATURE, bob)) else emptyList(),
        hand = listOf(settled(HAND_ID, FANG_DRAGON)),
        library = listOf(settled(LIBRARY_BASE, MOUNTAIN)),
        seed = 0,
    )

/**
 * alice on [forests] untapped Forests with a Sagu Wildling in hand and a **pinned** library: two basic
 * Forests and one nonbasic land, so the Omen's "a basic land card" filter has something to refuse.
 */
private fun wildlingBoard(
    forests: Int,
    seed: Long = 0,
): GameState =
    board(
        battlefield = List(forests) { settled(it.toLong(), FOREST) },
        opponentBattlefield = emptyList(),
        hand = listOf(settled(HAND_ID, SAGU_WILDLING)),
        library =
            listOf(
                settled(LIBRARY_BASE, FOREST),
                settled(LIBRARY_BASE + 1, NONBASIC_LAND),
                settled(LIBRARY_BASE + 2, FOREST),
            ),
        seed = seed,
    )

/** A board with alice holding priority in her own precombat main phase (CR 117.1a). */
private fun board(
    battlefield: List<GameObject>,
    opponentBattlefield: List<GameObject>,
    hand: List<GameObject>,
    library: List<GameObject>,
    seed: Long,
): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = library.toPersistentList(),
                        hand = hand.toPersistentList(),
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(GameObject(ObjectId(999), CardRef(MOUNTAIN), bob)),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.NONE,
                    ),
            ),
        turn = Turn(alice, BOARD_TURN, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                (battlefield + opponentBattlefield).toPersistentList(),
                persistentListOf(),
                persistentListOf(),
            ),
        nextObjectId = 1000,
        rng = Rng(seed),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )

// ---- driving helpers ------------------------------------------------------------------------------------

/** Every enumerated cast of [card] in the current priority window, from any zone (ADR-005). */
private fun castOptions(
    game: ScriptedGame,
    card: String,
): List<PriorityOption.CastSpell> =
    game.pendingRequest
        .shouldBeInstanceOf<DecisionRequest.ChooseAction>()
        .options
        .filterIsInstance<PriorityOption.CastSpell>()
        .filter { it.card == CardRef(card) }

private fun dragonOptions(game: ScriptedGame): List<PriorityOption.CastSpell> = castOptions(game, FANG_DRAGON)

private fun wildlingOptions(game: ScriptedGame): List<PriorityOption.CastSpell> = castOptions(game, SAGU_WILDLING)

/** Casts Fang Dragon as its Adventure or as the printed creature, paying the first offered plan. */
private fun castDragon(
    game: ScriptedGame,
    asAdventure: Boolean,
) = castHalf(game, FANG_DRAGON) { (it is CastingPermission.Adventure) == asAdventure }

/** Casts Sagu Wildling as its Omen or as the printed creature, paying the first offered plan. */
private fun castWildling(
    game: ScriptedGame,
    asOmen: Boolean,
) = castHalf(game, SAGU_WILDLING) { (it is CastingPermission.Omen) == asOmen }

private fun castHalf(
    game: ScriptedGame,
    card: String,
    wanted: (CastingPermission?) -> Boolean,
) {
    val window = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell && it.card == CardRef(card) && wanted(it.permission)
        }
    check(index >= 0) { "no matching cast offered for $card: ${window.options}" }
    game.apply(Decision.SingleSelect(window.id, index))
    payIfAsked(game)
}

/**
 * Answers a [DecisionRequest.ChoosePaymentPlan] with its first plan when one is pending. A `{0}` cost
 * still surfaces one — the single empty plan — so every cast goes through the same door.
 */
private fun payIfAsked(game: ScriptedGame) {
    val request = game.pendingRequest
    if (request is DecisionRequest.ChoosePaymentPlan) {
        game.apply(Decision.SingleSelect(request.id, 0))
    }
}

/** alice's library after a full Omen cast on [seed], in order — the seeded shuffle's own output. */
private fun shuffledLibraryAfterOmen(seed: Long): List<CardRef> {
    val game = ScriptedGame.startFrom(wildlingBoard(forests = OMEN_COST_FORESTS, seed = seed))
    castWildling(game, asOmen = true)
    game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
    val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()
    game.apply(Decision.SingleSelect(find.id, 0))
    game.driveUntil {
        game.state.sharedZones.stack
            .isEmpty()
    }
    return game.state.players
        .getValue(alice)
        .library
        .map { it.card }
}

private const val MAX_DRIVE_STEPS: Int = 40

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
        else -> error("the drive helper only passes priority and orders triggers, but met: $request")
    }
