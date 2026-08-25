package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
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
import dev.mtgplay.rules.engine.Chooser
import dev.mtgplay.rules.engine.isTargetLegal
import dev.mtgplay.rules.engine.legalTargets
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

/**
 * "Target &lt;kind of&gt; card from your/a graveyard" (CR 115.1, CR 404): the `FW-ZONETGT` enumeration
 * offers exactly the graveyard cards its restriction and scope admit, never a permanent, a spell, or a
 * player — and a card that has left the graveyard stops being a legal choice, which is what makes the
 * CR 608.2b re-check bite. `mtg-rules` names no card, so the spec is exercised directly.
 *
 * The graveyards here hold cards of every relevant type at once, on **both** seats, because the two
 * axes the spec carries — the [GraveyardCardRestriction] noun and the [GraveyardScope] possessive —
 * are independent and each has to be shown not to leak into the other.
 */
class GraveyardTargetingSpec :
    StringSpec({
        val yourInstantOrSorcery =
            TargetSpec.CardInGraveyard(GraveyardCardRestriction.INSTANT_OR_SORCERY, GraveyardScope.YOURS)
        val anyCreatureOrLand =
            TargetSpec.CardInGraveyard(GraveyardCardRestriction.CREATURE_OR_LAND, GraveyardScope.ANY)

        "CR 115.1/404: a 'your graveyard' spec enumerates only the deciding player's own graveyard" {
            val state = graveyardState()
            legalTargets(state, yourInstantOrSorcery, alice, Chooser.Nobody) shouldContainExactly
                listOf(
                    Target.CardInGraveyard(state.graveyardCard("Bolt Fixture", alice).id),
                    Target.CardInGraveyard(state.graveyardCard("Ritual Fixture", alice).id),
                )
            // The very same board, decided by the other seat, names that seat's cards instead: the spec
            // is decider-relative, and the enumeration is the only thing that says so.
            legalTargets(state, yourInstantOrSorcery, bob, Chooser.Nobody) shouldContainExactly
                listOf(Target.CardInGraveyard(state.graveyardCard("Bolt Fixture", bob).id))
        }

        "CR 115.1/404: an 'a graveyard' spec enumerates both graveyards, in turn order" {
            val state = graveyardState()
            legalTargets(state, anyCreatureOrLand, alice, Chooser.Nobody) shouldContainExactly
                listOf(
                    Target.CardInGraveyard(state.graveyardCard("Bear Fixture", alice).id),
                    Target.CardInGraveyard(state.graveyardCard("Land Fixture", alice).id),
                    Target.CardInGraveyard(state.graveyardCard("Bear Fixture", bob).id),
                )
        }

        "CR 205.2: the restriction reads the card's whole type set, so an artifact creature qualifies" {
            val state = graveyardState()
            // The "Bear Fixture" is printed as an *artifact creature*: two types, not one.
            val artifactCreature = state.graveyardCard("Bear Fixture", alice)
            state.definitions
                .getValue(artifactCreature.card)
                .characteristics.cardTypes shouldBe persistentSetOf(CardType.ARTIFACT, CardType.CREATURE)
            // CREATURE_OR_LAND offers it on the strength of the CREATURE membership alone…
            legalTargets(state, anyCreatureOrLand, alice, Chooser.Nobody) shouldContain
                Target.CardInGraveyard(artifactCreature.id)
            // …and is not offered by INSTANT_OR_SORCERY, so the two nouns really are different predicates.
            legalTargets(state, yourInstantOrSorcery, alice, Chooser.Nobody) shouldNotContain
                Target.CardInGraveyard(artifactCreature.id)
        }

        "CR 115.1: no player, permanent, or spell on the stack is ever a legal graveyard-card choice" {
            val state = graveyardState()
            val targets = legalTargets(state, anyCreatureOrLand, alice, Chooser.Nobody)
            targets shouldNotContain Target.Player(alice)
            targets shouldNotContain Target.Player(bob)
            // The board really does hold a creature the removal spec would offer — so the absence above
            // is the graveyard spec declining it, not an empty battlefield.
            legalTargets(
                state,
                TargetSpec.TargetPermanent(PermanentRestriction.CREATURE),
                alice,
                Chooser.Nobody,
            ) shouldContainExactly listOf(Target.Permanent(BATTLEFIELD_CREATURE_ID))
            targets shouldNotContain Target.Permanent(BATTLEFIELD_CREATURE_ID)
        }

        "CR 115.1: an empty graveyard enumerates nothing, so no target request is ever surfaced" {
            val state = withEmptyGraveyard(graveyardState(), alice)
            legalTargets(state, yourInstantOrSorcery, alice, Chooser.Nobody).shouldBeEmpty()
        }

        "CR 400.7/608.2b: a card that has left the graveyard is no longer a legal target" {
            val state = graveyardState()
            val chosen = Target.CardInGraveyard(state.graveyardCard("Bolt Fixture", alice).id)
            isTargetLegal(state, yourInstantOrSorcery, chosen, alice, Chooser.Nobody) shouldBe true

            // Remove it from the graveyard, as any return-to-hand or exile effect would (CR 400.7 mints a
            // fresh id in the new zone), and the stale target names nothing anywhere.
            val moved =
                state.copy(
                    players =
                        state.players.putting(
                            alice,
                            state.players.getValue(alice).let { seat ->
                                seat.copy(graveyard = seat.graveyard.filter { it.id != chosen.id }.toPersistentList())
                            },
                        ),
                )
            isTargetLegal(moved, yourInstantOrSorcery, chosen, alice, Chooser.Nobody) shouldBe false
        }

        "CR 109.3: an undefined card ref in a graveyard is inert and is never offered" {
            val state = graveyardState()
            val inert = state.graveyardCard("Undefined Fixture", alice)
            state.definitions.containsKey(inert.card) shouldBe false
            legalTargets(state, anyCreatureOrLand, alice, Chooser.Nobody) shouldNotContain
                Target.CardInGraveyard(inert.id)
            legalTargets(state, yourInstantOrSorcery, alice, Chooser.Nobody) shouldNotContain
                Target.CardInGraveyard(inert.id)
        }
    })

private val BATTLEFIELD_CREATURE_ID = ObjectId(100)

/**
 * A fixture card definition with the given printed card types (CR 205.2) and nothing else of interest.
 * A creature card gets a printed P/T and a land card no mana cost, because [PrintedCharacteristics]
 * enforces both (CR 208.1, CR 305.1) — the fixtures have to be real cards, not shapes.
 */
private fun typedFixture(
    name: String,
    vararg cardTypes: CardType,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = if (CardType.LAND in cardTypes) null else ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = cardTypes.toSet().toPersistentSet(),
                subtypes = persistentSetOf(),
                powerToughness =
                    if (CardType.CREATURE in cardTypes) PrintedPowerToughness(power = 2, toughness = 2) else null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

private val graveyardDefinitions: Map<CardRef, CardDefinition> =
    listOf(
        typedFixture("Bolt Fixture", CardType.INSTANT),
        typedFixture("Ritual Fixture", CardType.SORCERY),
        typedFixture("Bear Fixture", CardType.ARTIFACT, CardType.CREATURE),
        typedFixture("Land Fixture", CardType.LAND),
    ).associateBy { CardRef(it.characteristics.name) }

/**
 * A board with a creature on the battlefield and a deliberately mixed pair of graveyards: alice holds an
 * instant, a sorcery, an artifact creature, a land, and an **undefined** ref; bob holds an instant and an
 * artifact creature. Ids are assigned in graveyard order so the enumeration order assertions are exact.
 */
private fun graveyardState(): GameState {
    var nextId = 0L

    fun obj(
        name: String,
        owner: PlayerId,
    ) = GameObject(ObjectId(nextId++), CardRef(name), owner)

    val aliceGraveyard =
        listOf(
            obj("Bolt Fixture", alice),
            obj("Ritual Fixture", alice),
            obj("Bear Fixture", alice),
            obj("Land Fixture", alice),
            obj("Undefined Fixture", alice),
        )
    val bobGraveyard = listOf(obj("Bolt Fixture", bob), obj("Bear Fixture", bob))

    // A seat is identified by its key in `players`, so the graveyard is all this needs.
    fun seat(graveyard: List<GameObject>) =
        PlayerState(
            life = STARTING_LIFE,
            library = persistentListOf(),
            hand = persistentListOf(),
            graveyard = graveyard.toPersistentList(),
            priorityStatus = PriorityStatus.NONE,
        )

    return GameState(
        players = persistentMapOf(alice to seat(aliceGraveyard), bob to seat(bobGraveyard)),
        turn = Turn(alice, TURN_NUMBER, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(GameObject(BATTLEFIELD_CREATURE_ID, CardRef("Bear Fixture"), alice)),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = BATTLEFIELD_CREATURE_ID.value + 1,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = graveyardDefinitions.toPersistentMap(),
    )
}

/** The graveyard object with card [name] owned by [owner] — the fixtures place same-card objects per seat. */
private fun GameState.graveyardCard(
    name: String,
    owner: PlayerId,
): GameObject =
    players
        .getValue(owner)
        .graveyard
        .first { it.card == CardRef(name) }

/** [state] with [owner]'s graveyard emptied (CR 404), for the no-legal-target case. */
private fun withEmptyGraveyard(
    state: GameState,
    owner: PlayerId,
): GameState =
    state.copy(
        players = state.players.putting(owner, state.players.getValue(owner).copy(graveyard = persistentListOf())),
    )
