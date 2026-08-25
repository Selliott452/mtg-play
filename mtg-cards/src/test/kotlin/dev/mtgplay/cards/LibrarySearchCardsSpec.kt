package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchDestination
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/**
 * The printed half of the library-search packet against the oracle cards (CR 201–205): each card's type
 * line and mana cost, the shape of every ability it prints (CR 602.1 composite costs, CR 603.6a–b
 * trigger conditions, the CR 701.18 search filter and destination), and the CR 702.29 cycling shapes.
 *
 * Their *behaviour* — the land actually found, the card actually drawn, the artifact actually shuffled
 * back in — is played end-to-end through the real engine in the acceptance module's
 * `LibrarySearchAcceptanceSpec`. Nothing here asserts a game outcome.
 */
class LibrarySearchCardsSpec :
    StringSpec({

        // ------------------------------------------------------------------ the Landscape cycle

        "CR 305: each Landscape is a costless, typeless land whose only mana ability adds {C}" {
            listOf(
                contaminatedLandscape to "Contaminated Landscape",
                twistedLandscape to "Twisted Landscape",
                perilousLandscape to "Perilous Landscape",
            ).forEach { (card, name) ->
                with(card.characteristics) {
                    this.name shouldBe name
                    manaCost.shouldBeNull()
                    supertypes shouldBe persistentSetOf<Supertype>()
                    cardTypes shouldBe persistentSetOf(CardType.LAND)
                    // A Landscape prints no land type at all, which is why its {C} ability is authored.
                    subtypes shouldBe persistentSetOf<Subtype>()
                    powerToughness.shouldBeNull()
                    keywords shouldBe persistentSetOf<Keyword>()
                }
                card.manaAbilities
                    .single()
                    .options shouldContainExactly listOf(ManaType.COLORLESS)
                card.triggeredAbilities.shouldBeEmpty()
            }
        }

        "CR 701.18: each Landscape's fetch costs {T} + sacrifice and finds a basic of its own three types" {
            listOf(
                contaminatedLandscape to setOf(PLAINS, ISLAND, SWAMP),
                twistedLandscape to setOf(SWAMP, MOUNTAIN, FOREST),
                perilousLandscape to setOf(ISLAND, MOUNTAIN, PLAINS),
            ).forEach { (card, types) ->
                val fetch = card.activatedAbilities[0]
                fetch.cost shouldContainExactly listOf(AbilityCost.TapSelf, AbilityCost.SacrificeSelf)
                // Battlefield-scoped: a sacrifice cost is payable only from there (CR 602.5a).
                fetch.zoneScope shouldBe AbilityZoneScope.Battlefield
                fetch.librarySearch shouldBe
                    LibrarySearch(
                        find = LibrarySearchFilter(basic = true, landTypes = types.toPersistentSet()),
                        destination = LibrarySearchDestination.BATTLEFIELD_TAPPED,
                    )
            }
        }

        "CR 702.29a: each Landscape's cycling is a hand-scoped mana + discard-self ability that draws" {
            listOf(
                contaminatedLandscape to "{W}{U}{B}",
                twistedLandscape to "{B}{R}{G}",
                perilousLandscape to "{U}{R}{W}",
            ).forEach { (card, cost) ->
                val cycling = card.activatedAbilities[1]
                cycling.cost shouldContainExactly
                    listOf(AbilityCost.Mana(ManaCost.parse(cost)), AbilityCost.DiscardSelf)
                cycling.zoneScope shouldBe AbilityZoneScope.Hand
                // CR 702.29a is "draw a card": plain cycling carries no search, unlike typecycling.
                cycling.librarySearch.shouldBeNull()
                cycling.libraryLook.shouldBeNull()
            }
        }

        "the three Landscapes differ only in their fetch types and cycling cost" {
            // A cycle encoded by copy-paste is where a wrong colour triple hides; this pins that each
            // member really is distinct on both axes rather than three aliases of one card.
            val fetches = LANDSCAPES.map { it.activatedAbilities[0].librarySearch }
            fetches.toSet().size shouldBe LANDSCAPES.size
            val cyclings = LANDSCAPES.map { it.activatedAbilities[1].cost }
            cyclings.toSet().size shouldBe LANDSCAPES.size
        }

        // ------------------------------------------------------------------ Generous Ent

        "CR 201 / CR 302: Generous Ent is a {5}{G} 5/7 Treefolk with reach" {
            with(generousEnt.characteristics) {
                name shouldBe "Generous Ent"
                manaCost?.render() shouldBe "{5}{G}"
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Treefolk"))
                powerToughness?.power shouldBe 5
                powerToughness?.toughness shouldBe 7
                keywords shouldBe persistentSetOf(Keyword.REACH)
            }
            generousEnt.timing shouldBe TimingClass.SORCERY_SPEED
            generousEnt.targetSpec shouldBe TargetSpec.None
        }

        "CR 603.6a: Generous Ent's enters trigger creates the shared Food token" {
            val trigger = generousEnt.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
        }

        "CR 702.28b: Generous Ent's forestcycling {1} finds a **Forest card**, not a basic land card" {
            val cycling = generousEnt.activatedAbilities.single()
            cycling.cost shouldContainExactly
                listOf(AbilityCost.Mana(ManaCost.parse("{1}")), AbilityCost.DiscardSelf)
            cycling.zoneScope shouldBe AbilityZoneScope.Hand
            cycling.librarySearch shouldBe LibrarySearch(LibrarySearchFilter.FOREST_CARD)
            // Typecycling names a land subtype: it must not demand the Basic supertype.
            LibrarySearchFilter.FOREST_CARD.basic shouldBe false
            LibrarySearchFilter.FOREST_CARD shouldNotBe LibrarySearchFilter.BASIC_LAND_CARD
            // The default destination is the printed "reveal it, put it into your hand".
            cycling.librarySearch?.destination shouldBe LibrarySearchDestination.REVEALED_TO_HAND
        }

        // ------------------------------------------------------------------ Crop Rotation

        "CR 601.2b: Crop Rotation is a {G} instant whose additional cost sacrifices one land" {
            with(cropRotation.characteristics) {
                name shouldBe "Crop Rotation"
                manaCost?.render() shouldBe "{G}"
                cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                powerToughness.shouldBeNull()
            }
            cropRotation.timing shouldBe TimingClass.INSTANT_SPEED
            cropRotation.targetSpec shouldBe TargetSpec.None
            cropRotation.additionalCost shouldBe
                AdditionalCost.Sacrifice(count = 1, filter = SacrificeFilter(persistentSetOf(CardType.LAND)))
        }

        "CR 701.18: Crop Rotation searches for any land card and puts it onto the battlefield untapped" {
            cropRotation.librarySearch shouldBe
                LibrarySearch(
                    find = LibrarySearchFilter.LAND_CARD,
                    destination = LibrarySearchDestination.BATTLEFIELD,
                )
            // The card does not print "tapped", so the destination must not be the tapped one.
            cropRotation.librarySearch?.destination shouldNotBe LibrarySearchDestination.BATTLEFIELD_TAPPED
        }

        "the search clause rides on the **spell**, which is what a field on ActivatedAbility could not do" {
            // The regression this packet exists to prevent: a spell that searches.
            cropRotation.activatedAbilities.shouldBeEmpty()
            cropRotation.librarySearch shouldNotBe null
        }

        // ------------------------------------------------------------------ Lembas

        "CR 201 / CR 301: Lembas is a {2} artifact with the Food subtype" {
            with(lembas.characteristics) {
                name shouldBe "Lembas"
                manaCost?.render() shouldBe "{2}"
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
                subtypes shouldBe persistentSetOf(Subtype("Food"))
                powerToughness.shouldBeNull()
            }
            lembas.manaAbilities.shouldBeEmpty()
        }

        "CR 701.17a: Lembas' enters trigger carries a scry-1-then-draw look clause" {
            val enters = lembas.triggeredAbilities.first { it.condition == TriggerCondition.EnteredBattlefieldSelf }
            enters.libraryLook shouldBe LibraryLook(LibraryLookMode.Scry(1), thenDraw = 1)
        }

        "CR 603.6b: Lembas' third line is a dies **trigger**, not a leave-battlefield replacement" {
            // The card really reaches the graveyard and can be answered there; a replacement encoding
            // would look identical in a solitaire game and be wrong in every game with an opponent.
            val dies =
                lembas.triggeredAbilities.single {
                    it.condition == TriggerCondition.PutIntoGraveyardFromBattlefieldSelf
                }
            dies.libraryLook.shouldBeNull()
            dies.librarySearch.shouldBeNull()
            lembas.replacementEffects.shouldBeEmpty()
        }

        "CR 602.1: Lembas prints the standard Food ability, {2} + {T} + sacrifice, in printed order" {
            lembas.activatedAbilities.single().cost shouldContainExactly
                listOf(
                    AbilityCost.Mana(ManaCost.parse("{2}")),
                    AbilityCost.TapSelf,
                    AbilityCost.SacrificeSelf,
                )
        }

        // ------------------------------------------------------------------ the registry

        "every card the packet ships is registered under its printed name (CR 201)" {
            listOf(
                "Contaminated Landscape",
                "Twisted Landscape",
                "Perilous Landscape",
                "Generous Ent",
                "Crop Rotation",
                "Lembas",
            ).forEach { name ->
                MvpCards.definitions[CardRef(name)]?.characteristics?.name shouldBe name
            }
        }

        "CR 118.9: Land Grant, dropped by `P-SEARCH`, is encoded now that `FW-ALTCOST` exists" {
            // The card this spec used to assert *absent*. `P-SEARCH` had its search half — one line of
            // LibrarySearchCards.kt — and dropped the card because its alternative cost was conditional
            // on a hidden zone and paid by revealing. Both halves landed with `FW-ALTCOST`, so the
            // assertion inverts rather than being deleted: the reason it was absent is gone.
            MvpCards.definitions[CardRef("Land Grant")].shouldNotBeNull()
        }

        "CR 509.1b: Troll of Khazad-dûm, dropped by `P-SEARCH`, is encoded now the block set exists" {
            // The card this spec used to assert *absent*. `P-SEARCH` had its swampcycling half and
            // dropped the card because "can't be blocked except by three or more creatures" is a
            // constraint on the whole block declaration, which nothing could express. `W8-E` added it
            // (Evasion.BLOCKABLE_ONLY_BY_THREE_OR_MORE, published on the declare-blockers request), so
            // the assertion inverts rather than being deleted: the reason it was absent is gone.
            MvpCards.definitions[CardRef("Troll of Khazad-dûm")].shouldNotBeNull()
            trollOfKhazadDum.characteristics.evasions shouldBe
                persistentSetOf(Evasion.BLOCKABLE_ONLY_BY_THREE_OR_MORE)
        }
    })

/** The three Landscapes, for the assertions that compare the cycle against itself. */
private val LANDSCAPES: List<CardDefinition> =
    listOf(contaminatedLandscape, twistedLandscape, perilousLandscape)

private val PLAINS = LibrarySearchFilter.PLAINS
private val ISLAND = LibrarySearchFilter.ISLAND
private val SWAMP = LibrarySearchFilter.SWAMP
private val MOUNTAIN = LibrarySearchFilter.MOUNTAIN
private val FOREST = LibrarySearchFilter.FOREST
