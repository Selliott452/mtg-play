package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * Sacred Cat and embalm (CR 702.90), against the oracle card — and the two model changes the card
 * needed, asserted where they can regress: a token's identity is not a card name (CR 111.1), and a
 * token's colours are defined by the effect that made it rather than derived from a mana cost
 * (CR 111.4).
 */
class EmbalmSpec :
    StringSpec({

        "CR 201-208: Sacred Cat is a {W} 1/1 Cat with lifelink" {
            with(sacredCat.characteristics) {
                name shouldBe "Sacred Cat"
                manaCost shouldBe ManaCost.parse("{W}")
                supertypes.shouldBeEmpty()
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Cat"))
                powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 1)
                keywords shouldBe persistentSetOf(Keyword.LIFELINK)
                colors shouldBe setOf(Color.WHITE)
            }
            sacredCat.timing shouldBe TimingClass.SORCERY_SPEED
            sacredCat.targetSpec shouldBe TargetSpec.None
            sacredCat.triggeredAbilities.shouldBeEmpty()
        }

        "CR 702.90a: embalm is {W} plus exiling the card from the graveyard, at sorcery speed" {
            val embalm = sacredCat.activatedAbilities.single()
            embalm.cost shouldContainExactly
                listOf(
                    AbilityCost.Mana(ManaCost.parse("{W}")),
                    // A *cost*, so it is paid on activation and is not undone if the ability is countered
                    // — and it is what makes embalm once per card, with no per-turn limiter needed.
                    AbilityCost.ExileSelfFromGraveyard,
                )
            // CR 113.6b: an ability of a card in a graveyard functions there only if it says so.
            embalm.zoneScope shouldBe AbilityZoneScope.Graveyard
            // "Embalm only as a sorcery" (CR 602.5d) restricts the *ability*, not the card.
            embalm.timing shouldBe TimingClass.SORCERY_SPEED
            embalm.targetSpec shouldBe TargetSpec.None
            embalm.oncePerTurn shouldBe false
        }

        "CR 707.2: the token copies the printed body and changes exactly the three named things" {
            with(sacredCatEmbalmToken.characteristics) {
                // Copied, because embalm's "except" clause does not name them.
                name shouldBe "Sacred Cat"
                powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 1)
                keywords shouldBe persistentSetOf(Keyword.LIFELINK)
                // Changed: white, Zombie *in addition to* its other types, and no mana cost.
                colors shouldBe setOf(Color.WHITE)
                subtypes shouldBe persistentSetOf(Subtype("Cat"), Subtype("Zombie"))
                manaCost.shouldBeNull()
                // CR 202.3b: no mana cost means mana value 0, not 1 — a real difference in a format
                // full of "mana value 2 or less".
                manaValue shouldBe 0
            }
            // The token prints no ability of its own, which is what stops embalm from recurring.
            sacredCatEmbalmToken.activatedAbilities.shouldBeEmpty()
            sacredCatEmbalmToken.triggeredAbilities.shouldBeEmpty()
        }

        "CR 111.4: the token is white *by definition*, which a derivation from its mana cost cannot be" {
            // The blocker this framework removed. Colour was `manaCost?.colors` with one CDA exception,
            // so "white with no mana cost" had no representation and the token would have been
            // colourless — wrongly immune to protection from white, and wrongly a Blast target.
            sacredCatEmbalmToken.characteristics.definedColors shouldBe persistentSetOf(Color.WHITE)
            // Every *card* still derives, so nothing else moved.
            sacredCat.characteristics.definedColors.shouldBeNull()
        }

        "CR 111.1: embalming registers the token under a token ref, beside the card it copies" {
            val resolved = embalmFrom(sacredCatState())
            val tokenRef = CardRef.token("Sacred Cat")

            // The two identities coexist: this is the whole collision the old name-keyed model had.
            resolved.definitions[tokenRef].shouldBeInstanceOf<TokenDefinition>()
            resolved.definitions[CardRef("Sacred Cat")] shouldBe sacredCat
            tokenRef.isToken shouldBe true
            CardRef("Sacred Cat").isToken shouldBe false
            // CR 201.2: the token's *name* is still Sacred Cat; only its registry key is marked.
            tokenRef.printedName shouldBe "Sacred Cat"

            val token = resolved.sharedZones.battlefield.single()
            token.card shouldBe tokenRef
            token.summoningSick shouldBe true
        }

        "CR 111.1: a token definition may not be registered under a bare card ref, and vice versa" {
            // The invariant that makes the collision unconstructible rather than merely avoided.
            val wrongWay =
                shouldThrow<IllegalArgumentException> {
                    sacredCatState().copy(
                        definitions =
                            (sacredCatState().definitions + (CardRef("Sacred Cat") to sacredCatEmbalmToken))
                                .toPersistentMap(),
                    )
                }
            wrongWay.message.shouldBeInstanceOf<String>() shouldContain "111.1"
        }
    })

private val alice = PlayerId(0)
private val bob = PlayerId(1)
private const val STARTING_LIFE: Int = 20

/** Resolves Sacred Cat's embalm ability for Alice (CR 702.90a); the cost is paid elsewhere. */
private fun embalmFrom(state: GameState): GameState =
    sacredCat.activatedAbilities
        .single()
        .effect
        .resolve(state, ResolutionContext(alice, persistentListOf(), sourceCard = CardRef("Sacred Cat")))

/** A bare two-player state over the real registry; embalm needs no board of its own. */
private fun sacredCatState(): GameState =
    GameState(
        players = persistentMapOf(alice to emptySeat(), bob to emptySeat()),
        turn = Turn(alice, 4, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf()),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )

private fun emptySeat(): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )
