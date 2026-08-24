package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/**
 * The first real definitions: printed characteristics against the oracle card (CR 201–208),
 * the authored intrinsic mana abilities (CR 605.1a; the file-level CR 305.6 authoring
 * decision), Lightning Bolt's resolution through the damage primitive (CR 120.3a), and the
 * registry's `MatchConfig` shape.
 */
class MvpCardsSpec :
    StringSpec({
        val basics =
            mapOf(
                mountain to Triple("Mountain", ManaType.RED, Subtype("Mountain")),
                forest to Triple("Forest", ManaType.GREEN, Subtype("Forest")),
                plains to Triple("Plains", ManaType.WHITE, Subtype("Plains")),
                island to Triple("Island", ManaType.BLUE, Subtype("Island")),
                swamp to Triple("Swamp", ManaType.BLACK, Subtype("Swamp")),
            )

        "CR 305.6: all five basic land types are defined, each with its own subtype" {
            basics.keys.size shouldBe BASIC_LAND_TYPE_COUNT
            basics.values.map { (name, _, _) -> name } shouldBe
                listOf("Mountain", "Forest", "Plains", "Island", "Swamp")
        }

        "CR 305: each basic land is a Basic Land of its own subtype with no mana cost and no P/T box" {
            basics.forEach { (definition, expected) ->
                val (name, _, subtype) = expected
                with(definition.characteristics) {
                    this.name shouldBe name
                    manaCost.shouldBeNull()
                    supertypes shouldBe persistentSetOf(Supertype.BASIC)
                    cardTypes shouldBe persistentSetOf(CardType.LAND)
                    subtypes shouldBe persistentSetOf(subtype)
                    powerToughness.shouldBeNull()
                }
            }
        }

        "CR 605.1a: each basic land's authored intrinsic ability taps for exactly its one color" {
            basics.forEach { (definition, expected) ->
                val (_, mana, _) = expected
                definition.manaAbilities shouldBe persistentListOf(ManaAbility(persistentListOf(mana)))
            }
        }

        "CR 305.4: a basic land is not castable — its definition is no SpellDefinition" {
            basics.keys.forEach { definition ->
                definition.shouldNotBeInstanceOf<SpellDefinition>()
            }
        }

        "CR 202: Lightning Bolt is a {R} instant — mana value 1, red, any target, instant speed" {
            with(lightningBolt.characteristics) {
                name shouldBe "Lightning Bolt"
                manaCost?.render() shouldBe "{R}"
                manaValue shouldBe 1
                colors shouldBe setOf(Color.RED)
                cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                supertypes shouldBe persistentSetOf<Supertype>()
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            lightningBolt.timing shouldBe TimingClass.INSTANT_SPEED
            lightningBolt.targetSpec shouldBe TargetSpec.AnyTarget
        }

        "CR 120.3a: Lightning Bolt's resolution deals 3 damage to the targeted player — damage, not bare life loss" {
            val alice = PlayerId(0)
            val bob = PlayerId(1)
            val state =
                GameState(
                    players =
                        persistentMapOf(
                            alice to playerAt20(),
                            bob to playerAt20(),
                        ),
                    turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
                    sharedZones =
                        SharedZones(
                            battlefield = persistentListOf(),
                            stack = persistentListOf(),
                            exile = persistentListOf(),
                        ),
                    nextObjectId = 0,
                    rng = Rng(0),
                    events = persistentListOf(),
                )
            val resolved =
                lightningBolt.resolution.resolve(
                    state,
                    ResolutionContext(alice, persistentListOf(Target.Player(bob))),
                )
            resolved.players.getValue(bob).life shouldBe 20 - LIGHTNING_BOLT_DAMAGE
            resolved.events shouldBe
                listOf(
                    GameEvent.DamageDealt(Target.Player(bob), LIGHTNING_BOLT_DAMAGE),
                    GameEvent.LifeChanged(bob, -LIGHTNING_BOLT_DAMAGE, 20 - LIGHTNING_BOLT_DAMAGE),
                )
        }

        "CR 302: each P3.2 creature is a sorcery-speed, untargeted permanent spell with its printed box" {
            data class Expected(
                val name: String,
                val cost: String,
                val power: Int,
                val toughness: Int,
                val subtypes: Set<Subtype>,
                val keywords: Set<Keyword>,
            )

            val creatures =
                listOf(
                    grizzlyBears to Expected("Grizzly Bears", "{1}{G}", 2, 2, setOf(Subtype("Bear")), emptySet()),
                    hillGiant to Expected("Hill Giant", "{3}{R}", 3, 3, setOf(Subtype("Giant")), emptySet()),
                    windDrake to
                        Expected("Wind Drake", "{2}{U}", 2, 2, setOf(Subtype("Drake")), setOf(Keyword.FLYING)),
                    youthfulKnight to
                        Expected(
                            "Youthful Knight",
                            "{1}{W}",
                            2,
                            2,
                            setOf(Subtype("Human"), Subtype("Knight")),
                            setOf(Keyword.FIRST_STRIKE),
                        ),
                    standingTroops to
                        Expected(
                            "Standing Troops",
                            "{2}{W}",
                            1,
                            4,
                            setOf(Subtype("Soldier")),
                            setOf(Keyword.VIGILANCE),
                        ),
                )
            creatures.forEach { (definition, expected) ->
                with(definition.characteristics) {
                    name shouldBe expected.name
                    manaCost?.render() shouldBe expected.cost
                    cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                    supertypes shouldBe persistentSetOf<Supertype>()
                    subtypes shouldBe expected.subtypes.toPersistentSet()
                    powerToughness shouldBe PrintedPowerToughness(expected.power, expected.toughness)
                    keywords shouldBe expected.keywords.toPersistentSet()
                }
                // CR 302.1: a creature spell is cast at sorcery speed and targets nothing.
                definition.timing shouldBe TimingClass.SORCERY_SPEED
                definition.targetSpec shouldBe TargetSpec.None
                // No intrinsic mana abilities (CR 605.1a): a vanilla creature is not a mana source.
                definition.manaAbilities.shouldBeEmpty()
            }
        }

        "CR 608.3: a creature's resolution effect performs no instructions — the engine moves it to the battlefield" {
            val alice = PlayerId(0)
            val bob = PlayerId(1)
            val state =
                GameState(
                    players = persistentMapOf(alice to playerAt20(), bob to playerAt20()),
                    turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
                    sharedZones =
                        SharedZones(
                            battlefield = persistentListOf(),
                            stack = persistentListOf(),
                            exile = persistentListOf(),
                        ),
                    nextObjectId = 0,
                    rng = Rng(0),
                    events = persistentListOf(),
                )
            // The permanent-spell resolution move is the engine's (CR 608.3); the effect is a no-op,
            // so resolving it returns the state unchanged — no zone changes, no events.
            grizzlyBears.resolution.resolve(state, ResolutionContext(alice, persistentListOf())) shouldBe state
        }

        "the registry maps every definition under its printed name, ready for MatchConfig" {
            MvpCards.definitions.keys shouldBe
                setOf(
                    CardRef("Abundant Growth"),
                    CardRef("Ancestral Mask"),
                    CardRef("Armadillo Cloak"),
                    CardRef("Ash Barrens"),
                    CardRef("Cartouche of Solidarity"),
                    CardRef("Ethereal Armor"),
                    CardRef("Faithless Looting"),
                    CardRef("Fiery Temper"),
                    CardRef("Fireblast"),
                    CardRef("Forest"),
                    CardRef("Gladecover Scout"),
                    CardRef("Grab the Prize"),
                    CardRef("Grizzly Bears"),
                    CardRef("Guttersnipe"),
                    CardRef("Highway Robbery"),
                    CardRef("Hill Giant"),
                    CardRef("Island"),
                    CardRef("Kessig Flamebreather"),
                    CardRef("Kruphix's Insight"),
                    CardRef("Lava Dart"),
                    CardRef("Lifelink"),
                    CardRef("Lightning Bolt"),
                    CardRef("Lotleth Giant"),
                    CardRef("Malevolent Rumble"),
                    CardRef("Melded Moxite"),
                    CardRef("Mountain"),
                    CardRef("Plains"),
                    CardRef("Rancor"),
                    CardRef("Sentinel's Eyes"),
                    CardRef("Silhana Ledgewalker"),
                    CardRef("Slippery Bogle"),
                    CardRef("Sneaky Snacker"),
                    CardRef("Standing Troops"),
                    CardRef("Swamp"),
                    CardRef("Utopia Sprawl"),
                    CardRef("Voldaren Epicure"),
                    CardRef("Wild Growth"),
                    CardRef("Wind Drake"),
                    CardRef("Youthful Knight"),
                )
            MvpCards.definitions.forEach { (ref, definition) ->
                definition.characteristics.name shouldBe ref.name
            }
            MvpCards.definitions
                .getValue(CardRef("Lightning Bolt"))
                .shouldBeInstanceOf<SpellDefinition>()
            MvpCards.definitions
                .getValue(CardRef("Grizzly Bears"))
                .shouldBeInstanceOf<SpellDefinition>()
            MvpCards.definitions
                .getValue(CardRef("Mountain"))
                .shouldNotBeInstanceOf<SpellDefinition>()
        }
    })

/** The five basic land types (CR 305.6): Plains, Island, Swamp, Mountain, Forest. */
private const val BASIC_LAND_TYPE_COUNT: Int = 5

private fun playerAt20(): PlayerState =
    PlayerState(
        life = 20,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )
