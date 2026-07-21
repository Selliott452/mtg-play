package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

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
            )

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

        "the registry maps every definition under its printed name, ready for MatchConfig" {
            MvpCards.definitions.keys shouldBe
                setOf(CardRef("Forest"), CardRef("Lightning Bolt"), CardRef("Mountain"), CardRef("Plains"))
            MvpCards.definitions.forEach { (ref, definition) ->
                definition.characteristics.name shouldBe ref.name
            }
            MvpCards.definitions
                .getValue(CardRef("Lightning Bolt"))
                .shouldBeInstanceOf<SpellDefinition>()
            MvpCards.definitions
                .getValue(CardRef("Mountain"))
                .shouldNotBeInstanceOf<SpellDefinition>()
        }
    })

private fun playerAt20(): PlayerState =
    PlayerState(
        life = 20,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )
