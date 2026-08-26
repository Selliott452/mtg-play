package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CounterAmount
import dev.mtgplay.core.definition.DungeonRoom
import dev.mtgplay.core.definition.DungeonRoomAbility
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.LibrarySearchDestination
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.RevealDisposition
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * The Undercity (CR 309) as printed, and the two cards that enter it — `W10-A`, finished by `W11`.
 *
 * Every assertion here reads a printed line off the dungeon card. `W10-A` pinned the two
 * [DungeonRoomAbility.Unimplemented] rooms **by name** and pinned both cards' absence, so that the
 * packet implementing goad and Throne of the Dead Three had to delete assertions on its way to
 * registering them rather than discovering the gap in a game. `W11` did exactly that, and the pins now
 * point the other way: no room is unimplemented, both cards are registered, and neither fact can
 * change back silently.
 */
class InitiativeSpec :
    StringSpec({
        "CR 309.3: the Undercity's nine rooms are encoded in printed order" {
            undercity.name shouldBe "Undercity"
            undercity.rooms.map(DungeonRoom::name) shouldContainExactly
                listOf(
                    "Secret Entrance",
                    "Forge",
                    "Lost Well",
                    "Trap!",
                    "Arena",
                    "Stash",
                    "Archives",
                    "Catacombs",
                    "Throne of the Dead Three",
                )
        }

        "CR 309.4: every room leads where the card says it leads" {
            leadsTo("Secret Entrance") shouldContainExactly listOf("Forge", "Lost Well")
            leadsTo("Forge") shouldContainExactly listOf("Trap!", "Arena")
            leadsTo("Lost Well") shouldContainExactly listOf("Arena", "Stash")
            leadsTo("Trap!") shouldContainExactly listOf("Archives")
            leadsTo("Arena") shouldContainExactly listOf("Archives", "Catacombs")
            leadsTo("Stash") shouldContainExactly listOf("Catacombs")
            leadsTo("Archives") shouldContainExactly listOf("Throne of the Dead Three")
            leadsTo("Catacombs") shouldContainExactly listOf("Throne of the Dead Three")
            // CR 309.6: the last room leads nowhere, which is what completes the dungeon.
            leadsTo("Throne of the Dead Three") shouldContainExactly emptyList()
        }

        "CR 701.18: Secret Entrance searches for a basic land card and puts it into your hand" {
            val search = ability("Secret Entrance").librarySearch.shouldNotBeNull()
            search.find shouldBe LibrarySearchFilter.BASIC_LAND_CARD
            search.destination shouldBe LibrarySearchDestination.REVEALED_TO_HAND
            // "Search your library …", not "you may search": the room is mandatory.
            search.optional shouldBe false
        }

        "CR 122.1: Forge puts *two* +1/+1 counters on target creature" {
            ability("Forge").targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
            val forged =
                ability("Forge").effect.resolve(
                    boardWithBear(),
                    ResolutionContext(alice, persistentListOf(Target.Permanent(BEAR_ID))),
                )
            forged.sharedZones.battlefield
                .single()
                .counterCount(Counter.PLUS_ONE_PLUS_ONE) shouldBe 2
        }

        "CR 119.3: Trap! takes *five* life off the player it targets" {
            val sprung =
                ability("Trap!").effect.resolve(
                    boardWithBear(),
                    ResolutionContext(alice, persistentListOf(Target.Player(bob))),
                )
            sprung.players.getValue(bob).life shouldBe STARTING_LIFE - 5
            sprung.players.getValue(alice).life shouldBe STARTING_LIFE
        }

        "CR 701.17a: Lost Well is scry 2" {
            val look = ability("Lost Well").libraryLook.shouldNotBeNull()
            look.mode.shouldBeInstanceOf<LibraryLookMode.Scry>().count shouldBe 2
            // "Scry 2." and nothing else — no trailing draw, no shuffle.
            look.thenDraw shouldBe 0
            look.optionalShuffle shouldBe false
        }

        "CR 115.1b: Trap! targets a *player*, not an opponent" {
            // The venturing player may point it at themselves; TargetOpponent would delete that line.
            ability("Trap!").targetSpec shouldBe TargetSpec.TargetPlayer()
        }

        "CR 111.4: Stash's Treasure taps and sacrifices for one mana of any color" {
            treasureToken.characteristics.cardTypes shouldContainExactly setOf(CardType.ARTIFACT)
            treasureToken.characteristics.subtypes shouldContainExactly setOf(Subtype("Treasure"))
            val mana = treasureToken.manaAbilities.single()
            mana.options shouldContainExactly
                listOf(ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN)
            mana.cost shouldContainExactly listOf(ManaAbilityCost.TapSelf, ManaAbilityCost.SacrificeSelf)
        }

        "CR 111.4/702.110a: Catacombs' Skeleton is a 4/1 black creature with menace" {
            val printed = undercitySkeletonToken.characteristics
            printed.cardTypes shouldContainExactly setOf(CardType.CREATURE)
            printed.subtypes shouldContainExactly setOf(Subtype("Skeleton"))
            printed.powerToughness.shouldNotBeNull().power shouldBe 4
            printed.powerToughness.shouldNotBeNull().toughness shouldBe 1
            printed.keywords shouldContainExactly setOf(Keyword.MENACE)
            // CR 111.4: a token has no mana cost, so its colour is defined by the effect, not derived.
            printed.colors shouldContainExactly setOf(Color.BLACK)
        }

        "CR 309.5: every Undercity room now runs — the dungeon has no unimplemented rooms" {
            // `W10-A` pinned Arena and Throne of the Dead Three here by name, and pinned both cards'
            // absence below. `W11` implemented the two rooms, so this is the same assertion in the
            // opposite direction: the gap cannot reopen silently either.
            undercity.unimplementedRooms.shouldBeEmpty()
        }

        "CR 701.38a: Arena goads target creature, recording who goaded and when" {
            ability("Arena").targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
            val goaded =
                ability("Arena").effect.resolve(
                    boardWithBear(),
                    ResolutionContext(alice, persistentListOf(Target.Permanent(BEAR_ID))),
                )
            val bear = goaded.sharedZones.battlefield.single()
            // "Goad target creature", not "target creature an opponent controls": the venturing player
            // may point it at their own, and the goader is recorded because CR 701.38a's second half
            // names them — vacuous at two seats, but not dropped.
            bear.goadedBy shouldBe alice
            bear.goadedOnTurn shouldBe goaded.turn.number
        }

        "CR 701.16: Throne reveals ten and puts one creature card onto the battlefield" {
            val reveal = ability("Throne of the Dead Three").libraryReveal.shouldNotBeNull()
            reveal.count shouldBe 10
            reveal.toHand shouldBe RevealedCardFilter.CREATURE_CARD
            // "Put *a* creature card": exactly one, and no legal way to decline (ADR-005).
            reveal.toHandCount shouldBe 1
            reveal.mandatory shouldBe true
            // "Then shuffle": the nine it did not take never left the library, and are shuffled back
            // into obscurity rather than binned.
            reveal.disposition shouldBe RevealDisposition.CHOSEN_TO_BATTLEFIELD_REST_SHUFFLED
        }

        "CR 614.1c: Throne's creature enters *with* three +1/+1 counters, and gains hexproof" {
            val reveal = ability("Throne of the Dead Three").libraryReveal.shouldNotBeNull()
            val counters = reveal.entersWithCounters.shouldNotBeNull()
            counters.counter shouldBe Counter.PLUS_ONE_PLUS_ONE
            // A replacement of the entering event, not a placement afterwards: the creature is never on
            // the battlefield as a counterless body.
            counters.amount shouldBe CounterAmount.Fixed(3)
            reveal.grantedUntilYourNextTurn shouldContainExactly setOf(Keyword.HEXPROOF)
        }

        "CR 701.51a: Avenging Hunter is a 5/4 Elf Ranger with trample that takes the initiative" {
            val printed = avengingHunter.characteristics
            printed.manaCost shouldBe ManaCost.parse("{4}{G}")
            printed.powerToughness.shouldNotBeNull().power shouldBe 5
            printed.powerToughness.shouldNotBeNull().toughness shouldBe 4
            printed.keywords shouldContainExactly setOf(Keyword.TRAMPLE)
            // CR 205.3m: an **Elf**, which the repo's Scryfall snapshot had as Dragon. Elves counts
            // Elves with Priest of Titania, Timberwatch Elf and Wellwisher, so the type is load-bearing.
            printed.subtypes shouldContainExactly setOf(Subtype("Elf"), Subtype("Ranger"))
            avengingHunter.triggeredAbilities.single().condition shouldBe TriggerCondition.EnteredBattlefieldSelf
        }

        "CR 701.51a: Goliath Paladin is a 3/6 Giant Knight with vigilance that takes the initiative" {
            val printed = goliathPaladin.characteristics
            printed.manaCost shouldBe ManaCost.parse("{4}{W}")
            printed.powerToughness.shouldNotBeNull().power shouldBe 3
            printed.powerToughness.shouldNotBeNull().toughness shouldBe 6
            // CR 702.21b with CR 701.51c: the initiative passes to an opponent who deals combat damage
            // to its holder, so attacking without tapping is what the card is for.
            printed.keywords shouldContainExactly setOf(Keyword.VIGILANCE)
            printed.subtypes shouldContainExactly setOf(Subtype("Giant"), Subtype("Knight"))
            goliathPaladin.triggeredAbilities.single().condition shouldBe TriggerCondition.EnteredBattlefieldSelf
        }

        "CR 701.51a: both initiative cards are registered, now that the dungeon can be walked" {
            MvpCards.definitions[CardRef("Avenging Hunter")] shouldBe avengingHunter
            MvpCards.definitions[CardRef("Goliath Paladin")] shouldBe goliathPaladin
        }
    })

private val alice = PlayerId(0)
private val bob = PlayerId(1)
private const val STARTING_LIFE: Int = 20

/** The one creature Forge's counters land on. */
private val BEAR_ID = ObjectId(0)

/** A two-player board with one creature of alice's, which is all Forge and Trap! need. */
private fun boardWithBear(): GameState =
    GameState(
        players = persistentMapOf(alice to emptySeat(), bob to emptySeat()),
        turn = Turn(alice, 5, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(GameObject(BEAR_ID, CardRef("Grizzly Bears"), alice)),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
    )

private fun emptySeat(): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )

/** The names of the rooms [room] leads to, in printed order (CR 309.4). */
private fun leadsTo(room: String): List<String> = roomNamed(room).successors.map { undercity.rooms[it].name }

/** The triggered ability [room] runs on entry (CR 309.5); fails if the room is unimplemented. */
private fun ability(room: String) = roomNamed(room).ability.shouldBeInstanceOf<DungeonRoomAbility.Runs>().ability

/** The Undercity room printed as [room]; fails loudly if the graph no longer has it. */
private fun roomNamed(room: String): DungeonRoom =
    undercity.rooms.firstOrNull { it.name == room } ?: error("the Undercity has no room named \"$room\"")
