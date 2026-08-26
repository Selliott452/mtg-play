package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.DungeonRoom
import dev.mtgplay.core.definition.DungeonRoomAbility
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.LibrarySearchDestination
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * The Undercity (CR 309) as printed, and the drop of the two cards that enter it — `W10-A`.
 *
 * Every assertion here reads a printed line off the dungeon card. The two [DungeonRoomAbility.Unimplemented]
 * rooms are pinned **by name**, so the packet that implements goad and Throne of the Dead Three has to
 * delete assertions here on its way to registering Avenging Hunter and Goliath Paladin — the gap cannot
 * be closed silently, and it cannot be widened silently either.
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

        "CR 309.5: exactly Arena and Throne of the Dead Three are unimplemented" {
            undercity.unimplementedRooms.map(DungeonRoom::name) shouldContainExactly
                listOf("Arena", "Throne of the Dead Three")
        }

        "the drop is recorded: Arena needs an attack requirement and a duration the engine lacks" {
            val arena = unimplemented("Arena")
            arena.printed shouldBe "Goad target creature."
            arena.diagnosis shouldBe
                "CR 701.38a: goad is an attack *requirement* (CR 508.1d) lasting until the goading " +
                "player's next turn. The engine has no attack-requirement framework — " +
                "eligibleAttackers publishes a free subset and DecisionValidation accepts any " +
                "distinct subset of it — and no 'until your next turn' EffectDuration, which the " +
                "CR 514.2 cleanup could not end anyway since it outlives the turn it began in"
        }

        "the drop is recorded: Throne of the Dead Three needs four absent frameworks" {
            unimplemented("Throne of the Dead Three").printed shouldBe
                "Reveal the top ten cards of your library. Put a creature card from among them onto " +
                "the battlefield with three +1/+1 counters on it. It gains hexproof until your " +
                "next turn. Then shuffle."
        }

        "Avenging Hunter and Goliath Paladin are not registered while the Undercity is incomplete" {
            // Both print "When this creature enters, you take the initiative", which works — but the
            // dungeon that line walks you into cannot be finished, and the last room is unavoidable.
            MvpCards.definitions[CardRef("Avenging Hunter")].shouldBeNull()
            MvpCards.definitions[CardRef("Goliath Paladin")].shouldBeNull()
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

/** The recorded gap of an unimplemented [room] (CR 309.5). */
private fun unimplemented(room: String) = roomNamed(room).ability.shouldBeInstanceOf<DungeonRoomAbility.Unimplemented>()

/** The Undercity room printed as [room]; fails loudly if the graph no longer has it. */
private fun roomNamed(room: String): DungeonRoom =
    undercity.rooms.firstOrNull { it.name == room } ?: error("the Undercity has no room named \"$room\"")
