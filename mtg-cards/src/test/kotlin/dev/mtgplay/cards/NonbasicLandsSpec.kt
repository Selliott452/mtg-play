package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of the P8.4 nonbasic lands, checked against the oracle card (CR 201–205): type
 * lines, the authored intrinsic mana abilities (CR 605.1a, CR 305.6), the Bridges' indestructible
 * (CR 702.12), and the CR 614.1c enters-tapped clause on exactly the cards that print it.
 *
 * Engine-driven behaviour — playing each land and watching it arrive tapped or untapped, and spending
 * both halves of each dual producer — lives in the acceptance module's `NonbasicLandAcceptanceSpec`.
 */
class NonbasicLandsSpec :
    StringSpec({

        "CR 301 / CR 305: the three artifact lands are Artifact Lands with one single-colour mana ability" {
            val artifactLands =
                mapOf(
                    greatFurnace to ("Great Furnace" to ManaType.RED),
                    seatOfTheSynod to ("Seat of the Synod" to ManaType.BLUE),
                    vaultOfWhispers to ("Vault of Whispers" to ManaType.BLACK),
                )
            artifactLands.forEach { (definition, expected) ->
                val (name, produces) = expected
                with(definition.characteristics) {
                    this.name shouldBe name
                    manaCost.shouldBeNull()
                    supertypes shouldBe persistentSetOf<Supertype>()
                    cardTypes shouldBe persistentSetOf(CardType.ARTIFACT, CardType.LAND)
                    subtypes shouldBe persistentSetOf<Subtype>()
                    powerToughness.shouldBeNull()
                    keywords shouldBe persistentSetOf<Keyword>()
                }
                definition.manaAbilities shouldBe persistentListOf(ManaAbility(persistentListOf(produces)))
                // CR 110.5a: no enters-tapped clause is printed on these three.
                definition.entersTapped shouldBe EntersTapped.Never
            }
        }

        "CR 614.1c / CR 702.12: each Bridge enters tapped, is indestructible, and adds one of two colours" {
            val bridges =
                mapOf(
                    drossforgeBridge to ("Drossforge Bridge" to persistentListOf(ManaType.BLACK, ManaType.RED)),
                    mistvaultBridge to ("Mistvault Bridge" to persistentListOf(ManaType.BLUE, ManaType.BLACK)),
                    silverbluffBridge to ("Silverbluff Bridge" to persistentListOf(ManaType.BLUE, ManaType.RED)),
                    slagwoodsBridge to ("Slagwoods Bridge" to persistentListOf(ManaType.RED, ManaType.GREEN)),
                )
            bridges.forEach { (definition, expected) ->
                val (name, options) = expected
                with(definition.characteristics) {
                    this.name shouldBe name
                    manaCost.shouldBeNull()
                    cardTypes shouldBe persistentSetOf(CardType.ARTIFACT, CardType.LAND)
                    subtypes shouldBe persistentSetOf<Subtype>()
                    keywords shouldBe persistentSetOf(Keyword.INDESTRUCTIBLE)
                }
                // One printed ability offering a choice, not two — "{T}: Add {B} or {R}".
                definition.manaAbilities shouldBe persistentListOf(ManaAbility(options))
                definition.entersTapped shouldBe EntersTapped.Always
            }
        }

        "CR 305.6: Idyllic Beachfront is a nonbasic Plains Island with two type-derived abilities" {
            with(idyllicBeachfront.characteristics) {
                name shouldBe "Idyllic Beachfront"
                manaCost.shouldBeNull()
                // Nonbasic (CR 205.4b): the basic land *types* do not bring the Basic supertype.
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.LAND)
                subtypes shouldBe persistentSetOf(Subtype("Plains"), Subtype("Island"))
                keywords shouldBe persistentSetOf<Keyword>()
            }
            // CR 305.6: two separate intrinsic abilities, one per land type — not one ability with a choice.
            idyllicBeachfront.manaAbilities shouldBe
                persistentListOf(
                    ManaAbility(persistentListOf(ManaType.WHITE)),
                    ManaAbility(persistentListOf(ManaType.BLUE)),
                )
            idyllicBeachfront.entersTapped shouldBe EntersTapped.Always
        }

        "CR 305.1: none of the eight is castable, and none has any ability beyond producing mana" {
            packetLands.forEach { definition ->
                definition.shouldNotBeInstanceOf<SpellDefinition>()
                definition.activatedAbilities.shouldBeEmpty()
                definition.triggeredAbilities.shouldBeEmpty()
                definition.triggeredManaAbilities.shouldBeEmpty()
                definition.staticContinuousEffects.shouldBeEmpty()
                definition.choosesColorAsItEnters.shouldBeFalse()
            }
        }

        "CR 205.3i: Basilisk Gate is an untyped-supertype Land — Gate that enters untapped" {
            with(basiliskGate.characteristics) {
                name shouldBe "Basilisk Gate"
                // CR 305.2: a land has no mana cost.
                manaCost.shouldBeNull()
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.LAND)
                subtypes shouldBe persistentSetOf(Subtype("Gate"))
                powerToughness.shouldBeNull()
                keywords.shouldBeEmpty()
            }
            // Unlike the Bridges, it prints no enters-tapped clause (CR 614.1c).
            basiliskGate.entersTapped shouldBe EntersTapped.Never
            // A land is played, never cast (CR 305.1) — so it is a plain CardDefinition.
            basiliskGate.shouldNotBeInstanceOf<SpellDefinition>()
        }

        "CR 605.1a: Basilisk Gate's mana ability is the bare {T}: Add {C}" {
            basiliskGate.manaAbilities shouldBe
                persistentListOf(ManaAbility(persistentListOf(ManaType.COLORLESS)))
        }

        "CR 602.5d: the pump ability costs {2} and {T} and is activatable only as a sorcery" {
            val ability = basiliskGate.activatedAbilities.single()
            ability.cost shouldBe
                persistentListOf(AbilityCost.Mana(ManaCost.parse("{2}")), AbilityCost.TapSelf)
            // "Activate only as a sorcery" — the field `FW-MANACOST` added and this card is named for.
            // Without it the Gate would be an instant-speed combat trick it is deliberately not.
            ability.timing shouldBe TimingClass.SORCERY_SPEED
            // "Target creature" carries no control clause, so it may pump an opponent's creature.
            ability.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        }

        "trap T17: Basilisk Gate is a mana source whose own ability also demands it untapped" {
            // The other half of the shape that used to crash payment enumeration; the reservation in
            // `manaSourcesReservedBy` is what makes this encodable (mana-payment.md §2.2).
            basiliskGate.manaAbilities.shouldNotBeEmpty()
            basiliskGate.activatedAbilities
                .single()
                .cost
                .contains(AbilityCost.TapSelf) shouldBe true
        }

        "Basilisk Gate is registered under its printed name (CR 201)" {
            MvpCards.definitions[CardRef("Basilisk Gate")] shouldBe basiliskGate
        }
    })

/** Every land the P8.4 packet encodes; Basilisk Gate is checked separately, having real rules text. */
private val packetLands: List<CardDefinition> =
    listOf(
        greatFurnace,
        seatOfTheSynod,
        vaultOfWhispers,
        drossforgeBridge,
        mistvaultBridge,
        silverbluffBridge,
        slagwoodsBridge,
        idyllicBeachfront,
    )
