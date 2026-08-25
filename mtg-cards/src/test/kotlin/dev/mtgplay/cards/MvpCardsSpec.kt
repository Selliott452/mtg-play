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
import dev.mtgplay.core.state.DamageSource
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
                    ResolutionContext(
                        alice,
                        persistentListOf(Target.Player(bob)),
                        // CR 120.1: a resolving spell that deals damage is that damage's source, and
                        // the context is where it comes from (`FW-PREVENT`).
                        sourceCard = CardRef("Lightning Bolt"),
                    ),
                )
            resolved.players.getValue(bob).life shouldBe 20 - LIGHTNING_BOLT_DAMAGE
            resolved.events shouldBe
                listOf(
                    GameEvent.DamageDealt(
                        DamageSource(objectId = null, card = CardRef("Lightning Bolt")),
                        Target.Player(bob),
                        LIGHTNING_BOLT_DAMAGE,
                    ),
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
                    CardRef("Goblin Bushwhacker"),
                    CardRef("Land Grant"),
                    CardRef("Prohibit"),
                    CardRef("Ancestral Mask"),
                    CardRef("Ancient Grudge"),
                    CardRef("Ancient Stirrings"),
                    CardRef("Annul"),
                    CardRef("Archaeomancer"),
                    CardRef("Armadillo Cloak"),
                    CardRef("Augur of Bolas"),
                    CardRef("Ash Barrens"),
                    CardRef("Basilisk Gate"),
                    CardRef("Balustrade Spy"),
                    CardRef("Azorius Chancery"),
                    CardRef("Blood Fountain"),
                    CardRef("Bojuka Bog"),
                    CardRef("Blue Elemental Blast"),
                    CardRef("Barrels of Blasting Jelly"),
                    CardRef("Brainstorm"),
                    CardRef("Breath Weapon"),
                    CardRef("Brinebarrow Intruder"),
                    CardRef("Cartouche of Solidarity"),
                    CardRef("Cast into the Fire"),
                    CardRef("Cast Down"),
                    CardRef("Contaminated Landscape"),
                    CardRef("Counterspell"),
                    CardRef("Crop Rotation"),
                    CardRef("Cryptic Serpent"),
                    CardRef("Dispel"),
                    CardRef("Drossforge Bridge"),
                    CardRef("Duress"),
                    CardRef("Elvish Mystic"),
                    CardRef("End the Festivities"),
                    CardRef("Envelop"),
                    CardRef("Ephemerate"),
                    CardRef("Ethereal Armor"),
                    CardRef("Eviscerator's Insight"),
                    CardRef("Expedition Map"),
                    CardRef("Faerie Macabre"),
                    CardRef("Faerie Seer"),
                    CardRef("Faithless Looting"),
                    CardRef("Fiery Temper"),
                    CardRef("Fireblast"),
                    CardRef("Force Spike"),
                    CardRef("Forest"),
                    CardRef("Fyndhorn Elves"),
                    CardRef("Galvanic Blast"),
                    CardRef("Generous Ent"),
                    CardRef("Giant's Boulder"),
                    CardRef("Gingerbread Cabin"),
                    CardRef("Gingerbrute"),
                    CardRef("Glacial Floodplain"),
                    CardRef("Ghostly Flicker"),
                    CardRef("Gladecover Scout"),
                    CardRef("Harrier Strix"),
                    CardRef("Gnaw to the Bone"),
                    CardRef("Goblin Tomb Raider"),
                    CardRef("Grab the Prize"),
                    CardRef("Great Furnace"),
                    CardRef("Grizzly Bears"),
                    CardRef("Ghostly Flicker"),
                    CardRef("Gut Shot"),
                    CardRef("Guttersnipe"),
                    CardRef("Guardian of the Guildpact"),
                    CardRef("Haunted Fengraf"),
                    CardRef("Healer of the Glade"),
                    CardRef("Highway Robbery"),
                    CardRef("Hill Giant"),
                    CardRef("Ichor Wellspring"),
                    CardRef("Idyllic Beachfront"),
                    CardRef("Hydroblast"),
                    CardRef("Impulse"),
                    CardRef("Island"),
                    CardRef("Journey to Nowhere"),
                    CardRef("Kessig Flamebreather"),
                    CardRef("Krark-Clan Shaman"),
                    CardRef("Kruphix's Insight"),
                    CardRef("Last Breath"),
                    CardRef("Lava Dart"),
                    CardRef("Lead the Stampede"),
                    CardRef("Lembas"),
                    CardRef("Lifelink"),
                    CardRef("Lightning Bolt"),
                    CardRef("Lórien Revealed"),
                    CardRef("Lotleth Giant"),
                    CardRef("Makeshift Munitions"),
                    CardRef("Mask of Law and Grace"),
                    CardRef("Malevolent Rumble"),
                    CardRef("Melded Moxite"),
                    CardRef("Mental Note"),
                    CardRef("Mesmeric Fiend"),
                    CardRef("Mistvault Bridge"),
                    CardRef("Mountain"),
                    CardRef("Murmuring Mystic"),
                    CardRef("Myr Enforcer"),
                    CardRef("Negate"),
                    CardRef("Ninja of the Deep Hours"),
                    CardRef("Of One Mind"),
                    CardRef("Outlaw Medic"),
                    CardRef("Overgrown Battlement"),
                    CardRef("Perilous Landscape"),
                    CardRef("Plains"),
                    CardRef("Ponder"),
                    CardRef("Pyroblast"),
                    CardRef("Preordain"),
                    CardRef("Priest of Titania"),
                    CardRef("Pulse of Murasa"),
                    CardRef("Pursue the Past"),
                    CardRef("Quirion Ranger"),
                    CardRef("Rancor"),
                    CardRef("Raze"),
                    CardRef("Reckoner's Bargain"),
                    CardRef("Red Elemental Blast"),
                    CardRef("Refurbished Familiar"),
                    CardRef("Remove Soul"),
                    CardRef("Saruli Caretaker"),
                    CardRef("Scour from Existence"),
                    CardRef("Sea Gate Oracle"),
                    CardRef("Seat of the Synod"),
                    CardRef("Sentinel's Eyes"),
                    CardRef("Silhana Ledgewalker"),
                    CardRef("Silverbluff Bridge"),
                    CardRef("Rooftop Percher"),
                    CardRef("Skred"),
                    CardRef("Slagwoods Bridge"),
                    CardRef("Sleep of the Dead"),
                    CardRef("Slippery Bogle"),
                    CardRef("Smash to Smithereens"),
                    CardRef("Snap"),
                    CardRef("Sneaky Snacker"),
                    CardRef("Snow-Covered Island"),
                    CardRef("Snow-Covered Mountain"),
                    CardRef("Snow-Covered Plains"),
                    CardRef("Spell Pierce"),
                    CardRef("Spellstutter Sprite"),
                    CardRef("Spinewoods Paladin"),
                    CardRef("Steel Sabotage"),
                    CardRef("Spirit Link"),
                    CardRef("Standing Troops"),
                    CardRef("Swamp"),
                    CardRef("Tamiyo's Safekeeping"),
                    CardRef("Terminate"),
                    CardRef("Thoughtcast"),
                    CardRef("Thought Scour"),
                    CardRef("Thraben Charm"),
                    CardRef("Timberwatch Elf"),
                    CardRef("Toxin Analysis"),
                    CardRef("Twisted Landscape"),
                    CardRef("Unexpected Fangs"),
                    CardRef("Unfathomable Truths"),
                    CardRef("Union of the Third Path"),
                    CardRef("Urza's Mine"),
                    CardRef("Urza's Power Plant"),
                    CardRef("Urza's Tower"),
                    CardRef("Utopia Sprawl"),
                    CardRef("Utrom Monitor"),
                    CardRef("Vault of Whispers"),
                    CardRef("Volatile Fjord"),
                    CardRef("Voldaren Epicure"),
                    CardRef("Wall of Roots"),
                    CardRef("Wellwisher"),
                    CardRef("Wild Growth"),
                    CardRef("Wind Drake"),
                    CardRef("Youthful Knight"),
                    // W8-D: card advantage and graveyard artifacts.
                    CardRef("Mulldrifter"),
                    CardRef("Winding Way"),
                    CardRef("Dread Return"),
                    CardRef("Nihil Spellbomb"),
                    CardRef("Relic of Progenitus"),
                    CardRef("Reckless Impulse"),
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
