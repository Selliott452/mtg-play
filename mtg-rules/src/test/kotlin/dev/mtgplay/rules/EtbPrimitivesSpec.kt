package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchAxisCombination
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.engine.activationOptions
import dev.mtgplay.rules.engine.interveningIfHolds
import dev.mtgplay.rules.engine.matchingLibraryCards
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

/**
 * The three `W8-E` primitives whose whole behaviour is a derivation over the state, driven on fixtures
 * (`mtg-rules` names no card):
 *
 * - **[InterveningIf.YouControlAnotherCreatureNamed]** (CR 603.4, CR 201.2) — the first intervening-if
 *   condition whose two checks can genuinely disagree, so the *only* way to get it right is to ask the
 *   live battlefield at both ends rather than to freeze a fact.
 * - **[AbilityZoneScope.Graveyard]** with [AbilityCost.ExileSelfFromGraveyard] (CR 113.6b) — an ability
 *   that is enumerated off a graveyard card, and only when it says it functions there.
 * - **[LibrarySearchAxisCombination]** (CR 701.18) — a search filter whose two axes are alternatives
 *   rather than a narrowing, which no conjunction of the same axes can express.
 *
 * The fourth, the blocker-count restriction, has its own spec (BlockerCountRestrictionSpec); the fifth,
 * the colour cast filter, is exercised through the card layer, which is where the colours live.
 */
class EtbPrimitivesSpec :
    StringSpec({
        val alice = PlayerId(0)
        val bob = PlayerId(1)
        val miscreant = CardRef("Fixture Miscreant")
        val bear = CardRef("Fixture Bear")

        val namedCondition = InterveningIf.YouControlAnotherCreatureNamed(miscreant.name)
        val namedTrigger =
            TriggeredAbility(
                condition = TriggerCondition.EnteredBattlefieldSelf,
                interveningIf = namedCondition,
                effect = ResolutionEffect { state, _ -> state },
            )

        "CR 603.4: a lone source does not satisfy 'another creature named X' — it never counts itself" {
            val only = GameObject(ObjectId(0), miscreant, alice)
            val state = primitiveBoardOf(alice, bob, listOf(only)).withCreatures(miscreant.name, bear.name)
            interveningIfHolds(state, namedTrigger, only.id, alice) shouldBe false
        }

        "CR 201.2/109.1: a second copy of the same card satisfies it, and 'another' excludes the source" {
            val first = GameObject(ObjectId(0), miscreant, alice)
            val second = GameObject(ObjectId(1), miscreant, alice)
            val state = primitiveBoardOf(alice, bob, listOf(first, second)).withCreatures(miscreant.name, bear.name)
            // Symmetric: whichever one is the source, the other one is the "another".
            interveningIfHolds(state, namedTrigger, first.id, alice) shouldBe true
            interveningIfHolds(state, namedTrigger, second.id, alice) shouldBe true
        }

        "CR 109.5: an opponent's copy is not one 'you control'" {
            val yours = GameObject(ObjectId(0), miscreant, alice)
            val theirs = GameObject(ObjectId(1), miscreant, bob)
            val state = primitiveBoardOf(alice, bob, listOf(yours, theirs)).withCreatures(miscreant.name, bear.name)
            interveningIfHolds(state, namedTrigger, yours.id, alice) shouldBe false
        }

        "CR 201.2: a differently named creature does not satisfy it, however many you control" {
            val source = GameObject(ObjectId(0), miscreant, alice)
            val other = GameObject(ObjectId(1), bear, alice)
            val state = primitiveBoardOf(alice, bob, listOf(source, other)).withCreatures(miscreant.name, bear.name)
            interveningIfHolds(state, namedTrigger, source.id, alice) shouldBe false
        }

        "CR 603.4: the source leaving the battlefield leaves the condition about the board, not itself" {
            // The partner is still there and the source has gone: nothing is left to exclude, so the
            // remaining Miscreant *is* "another one" and the resolution check still holds. Contrast
            // InterveningIf.SourceWasKicked, which is a fact about the departed permanent and fails.
            val survivor = GameObject(ObjectId(1), miscreant, alice)
            val state = primitiveBoardOf(alice, bob, listOf(survivor)).withCreatures(miscreant.name, bear.name)
            interveningIfHolds(state, namedTrigger, ObjectId(99), alice) shouldBe true
        }

        "CR 113.6b: a graveyard-scoped ability is enumerated off the graveyard card that carries it" {
            val wurm = CardRef("Fixture Wurm")
            val card = GameObject(ObjectId(0), wurm, alice)
            val state =
                primitiveBoardOf(alice, bob, emptyList(), PrimitiveZones(graveyard = listOf(card)))
                    .withDefinition(wurm, graveyardAbilityCard(wurm))
            activationOptions(state, alice).map { it.card to it.scope } shouldContainExactly
                listOf(wurm to AbilityZoneScope.Graveyard)
        }

        "CR 113.6b: the same card's ability is NOT enumerated while it sits on the battlefield" {
            val wurm = CardRef("Fixture Wurm")
            val onBattlefield = GameObject(ObjectId(0), wurm, alice)
            val state =
                primitiveBoardOf(alice, bob, listOf(onBattlefield))
                    .withDefinition(wurm, graveyardAbilityCard(wurm))
            // "Only abilities that say they function from a graveyard do" cuts the other way too:
            // one that says *only* graveyard does nothing from the battlefield.
            activationOptions(state, alice).shouldBeEmpty()
        }

        "CR 113.6b: an opponent's graveyard is not yours to activate from" {
            val wurm = CardRef("Fixture Wurm")
            val theirs = GameObject(ObjectId(0), wurm, bob)
            val state =
                primitiveBoardOf(alice, bob, emptyList(), PrimitiveZones(opponentGraveyard = listOf(theirs)))
                    .withDefinition(wurm, graveyardAbilityCard(wurm))
            activationOptions(state, alice).shouldBeEmpty()
        }

        "CR 701.18: an ANY filter finds a basic land and a typed nonbasic, which no ALL filter can" {
            val gateType = Subtype("Gate")
            val basicForest = CardRef("Fixture Forest")
            val nonbasicGate = CardRef("Fixture Gate")
            val plainNonbasic = CardRef("Fixture Tower")
            val library =
                listOf(
                    GameObject(ObjectId(0), basicForest, alice),
                    GameObject(ObjectId(1), nonbasicGate, alice),
                    GameObject(ObjectId(2), plainNonbasic, alice),
                )
            val state =
                primitiveBoardOf(alice, bob, emptyList(), PrimitiveZones(library = library))
                    .withDefinition(basicForest, landCard(basicForest, basic = true))
                    .withDefinition(nonbasicGate, landCard(nonbasicGate, basic = false, types = setOf(gateType)))
                    .withDefinition(plainNonbasic, landCard(plainNonbasic, basic = false))

            val disjunctive = LibrarySearchFilter.basicOrOneOf(setOf(gateType))
            matchingLibraryCards(state, alice, disjunctive).map { it.card } shouldContainExactly
                listOf(basicForest, nonbasicGate)

            // The two conjunctive readings each delete one of them, which is why neither is the card.
            matchingLibraryCards(state, alice, LibrarySearchFilter.BASIC_LAND_CARD).map { it.card } shouldContainExactly
                listOf(basicForest)
            val gateOnly = LibrarySearchFilter(landTypes = persistentSetOf(gateType))
            matchingLibraryCards(state, alice, gateOnly).map { it.card } shouldContainExactly listOf(nonbasicGate)

            // And the naive "both axes" reading finds nothing at all: no basic land has the Gate type.
            val basicGate = LibrarySearchFilter(basic = true, landTypes = persistentSetOf(gateType))
            matchingLibraryCards(state, alice, basicGate).shouldBeEmpty()
        }

        "CR 601.3b: a search declares its optionality, and it defaults to mandatory" {
            LibrarySearch(find = LibrarySearchFilter.BASIC_LAND_CARD).optional shouldBe false
            LibrarySearch(find = LibrarySearchFilter.BASIC_LAND_CARD, optional = true).optional shouldBe true
        }
    })

/**
 * Registers a plain 1/1 creature definition for each of [refs] (CR 302) — the intervening-if condition
 * asks whether a permanent is a *creature*, which it can only answer through the definition registry.
 */
private fun GameState.withCreatures(vararg refs: String): GameState =
    refs.fold(this) { state, name ->
        val ref = CardRef(name)
        state.withDefinition(
            ref,
            object : CardDefinition {
                override val characteristics =
                    PrintedCharacteristics(
                        name = ref.name,
                        manaCost = ManaCost.parse("{U}"),
                        supertypes = persistentSetOf(),
                        cardTypes = persistentSetOf(CardType.CREATURE),
                        subtypes = persistentSetOf(),
                        powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
                    )
            },
        )
    }

/** A fixture card whose only ability is a `{2}{G}`, exile-self-from-graveyard lifegain (CR 113.6b). */
private fun graveyardAbilityCard(ref: CardRef): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = ref.name,
                manaCost = ManaCost.parse("{6}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Wurm")),
                powerToughness = PrintedPowerToughness(power = 7, toughness = 6),
            )
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    // A {0} mana component keeps the fixture payable with no lands, so the assertion is
                    // about the *zone* and nothing else.
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{0}")),
                            AbilityCost.ExileSelfFromGraveyard,
                        ),
                    zoneScope = AbilityZoneScope.Graveyard,
                    effect = ResolutionEffect { state, _ -> state },
                ),
            )
    }

/** A fixture land card, optionally basic and optionally carrying land [types] (CR 205.3b, CR 205.4). */
private fun landCard(
    ref: CardRef,
    basic: Boolean,
    types: Set<Subtype> = emptySet(),
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = ref.name,
                manaCost = null,
                supertypes = if (basic) persistentSetOf(Supertype.BASIC) else persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = types.toPersistentSet(),
                powerToughness = null,
            )
    }

/** Registers [definition] under [ref] in this state's definition registry (ADR-009). */
private fun GameState.withDefinition(
    ref: CardRef,
    definition: CardDefinition,
): GameState = copy(definitions = (definitions + (ref to definition)).toPersistentMap())

/** The non-battlefield zones a [primitiveBoardOf] fixture may populate; every one defaults to empty. */
private data class PrimitiveZones(
    val graveyard: List<GameObject> = emptyList(),
    val opponentGraveyard: List<GameObject> = emptyList(),
    val library: List<GameObject> = emptyList(),
)

/** A two-seat state with the given battlefield and [zones]; everything not named is empty. */
private fun primitiveBoardOf(
    alice: PlayerId,
    bob: PlayerId,
    battlefield: List<GameObject>,
    zones: PrimitiveZones = PrimitiveZones(),
): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to primitiveSeatOf(zones.graveyard, zones.library),
                bob to primitiveSeatOf(zones.opponentGraveyard, emptyList()),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 1000,
        rng = Rng(0),
        events = persistentListOf(),
    )

private fun primitiveSeatOf(
    graveyard: List<GameObject>,
    library: List<GameObject>,
): PlayerState =
    PlayerState(
        life = 20,
        library = library.toPersistentList(),
        hand = persistentListOf(),
        graveyard = graveyard.toPersistentList(),
    )
