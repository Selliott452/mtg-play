package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.LibraryReveal
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggeredManaAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * The three remaining GW-Bogles utility definitions (docs/decklists.md): Utopia Sprawl's as-enters colour
 * choice and triggered mana ability (CR 614.12, CR 605.1b), Malevolent Rumble's token-plus-reveal
 * resolution (CR 707, CR 701.16), and Ash Barrens' colorless fixing plus hand-scoped basic landcycling
 * (CR 305, CR 113.6c) — with the search effect STOP-flagged. Engine-driven behaviour (Sprawl's bonus mana
 * in a real payment, Rumble's reveal-and-keep) lives in the acceptance module.
 */
class BoglesUtilitySpec :
    StringSpec({
        val alice = PlayerId(0)
        val bob = PlayerId(1)

        "CR 303 / CR 605.1b: Utopia Sprawl enchants a Forest, chooses a colour, and adds one of it on tap" {
            with(utopiaSprawl.characteristics) {
                name shouldBe "Utopia Sprawl"
                manaCost?.render() shouldBe "{G}"
                cardTypes shouldBe persistentSetOf(CardType.ENCHANTMENT)
                subtypes shouldBe persistentSetOf(Subtype("Aura"))
            }
            utopiaSprawl.timing shouldBe TimingClass.SORCERY_SPEED
            utopiaSprawl.targetSpec shouldBe TargetSpec.Enchantable(EnchantRestriction.FOREST)
            utopiaSprawl.choosesColorAsItEnters shouldBe true
            utopiaSprawl.triggeredManaAbilities shouldContainExactly listOf(TriggeredManaAbility.AddChosenColor(1))
        }

        "CR 707 / CR 701.16: Malevolent Rumble creates an Eldrazi Spawn and reveals the top four" {
            with(malevolentRumble.characteristics) {
                name shouldBe "Malevolent Rumble"
                manaCost?.render() shouldBe "{1}{G}"
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
            }
            malevolentRumble.targetSpec shouldBe TargetSpec.None
            malevolentRumble.libraryReveal shouldBe
                LibraryReveal(MALEVOLENT_RUMBLE_REVEAL, RevealedCardFilter.PERMANENT_CARD)
            // The independent clause (create a token) is the ordinary resolution effect.
            val resolved = malevolentRumble.resolution.resolve(boardState(alice, bob), noTargets(alice))
            resolved.sharedZones.battlefield.count { it.card == CardRef("Eldrazi Spawn") } shouldBe 1
        }

        "CR 111.4 / CR 605.1a: the Eldrazi Spawn is a 0/1 that sacrifices for {C}" {
            with(eldraziSpawnToken.characteristics) {
                name shouldBe "Eldrazi Spawn"
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Eldrazi"), Subtype("Spawn"))
                powerToughness shouldBe PrintedPowerToughness(0, 1)
            }
            eldraziSpawnToken.manaAbilities shouldContainExactly
                listOf(ManaAbility(persistentListOf(ManaType.COLORLESS), viaSacrifice = true))
        }

        "CR 305 / CR 605.1a: Ash Barrens is a played land that taps for {C} — not a spell" {
            with(ashBarrens.characteristics) {
                name shouldBe "Ash Barrens"
                manaCost.shouldBeNull()
                cardTypes shouldBe persistentSetOf(CardType.LAND)
                subtypes shouldBe persistentSetOf()
            }
            ashBarrens.shouldNotBeInstanceOf<SpellDefinition>()
            ashBarrens.manaAbilities shouldContainExactly listOf(ManaAbility(persistentListOf(ManaType.COLORLESS)))
        }

        "CR 113.6c: Ash Barrens' basic landcycling is a hand-scoped {1}+discard-self ability; search STOP-flagged" {
            val cycling = ashBarrens.activatedAbilities.single()
            cycling.zoneScope shouldBe AbilityZoneScope.Hand
            cycling.cost shouldContainExactly
                listOf(AbilityCost.Mana(ManaCost.parse("{1}")), AbilityCost.DiscardSelf)
            shouldThrow<IllegalStateException> {
                cycling.effect.resolve(boardState(alice, bob), noTargets(alice))
            }
        }

        "the utility cards register as the expected definition types" {
            utopiaSprawl.shouldBeInstanceOf<SpellDefinition>()
            malevolentRumble.shouldBeInstanceOf<SpellDefinition>()
        }
    })

private const val STARTING_LIFE: Int = 20

/** A resolution context for [seat] with no targets. */
private fun noTargets(seat: PlayerId): ResolutionContext = ResolutionContext(seat, persistentListOf())

/** A two-player main-phase state over [MvpCards], both seats at 20 with empty zones. */
private fun boardState(
    alice: PlayerId,
    bob: PlayerId,
): GameState {
    fun seat() =
        PlayerState(
            life = STARTING_LIFE,
            library = persistentListOf(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
        )
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf()),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
