package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The card-selection and draw family (CardSelection.kt) against the oracle card: printed
 * characteristics (CR 201–208), and the declarations each printed clause maps onto — "target player"
 * (CR 115.1a), islandcycling (CR 702.28), devoid (CR 702.114a), the optional cost-then-draw
 * (CR 601.3b), and flashback (CR 702.34). The behaviour of each clause is played end-to-end in
 * `CardSelectionAcceptanceSpec`; this suite pins the *data*.
 */
class CardSelectionSpec :
    StringSpec({

        "CR 202: the printed type lines and costs match the oracle cards" {
            data class Expected(
                val definition: SpellDefinition,
                val name: String,
                val cost: String,
                val type: CardType,
                val timing: TimingClass,
            )

            listOf(
                Expected(thoughtScour, "Thought Scour", "{U}", CardType.INSTANT, TimingClass.INSTANT_SPEED),
                Expected(mentalNote, "Mental Note", "{U}", CardType.INSTANT, TimingClass.INSTANT_SPEED),
                Expected(
                    lorienRevealed,
                    "Lórien Revealed",
                    "{3}{U}{U}",
                    CardType.SORCERY,
                    TimingClass.SORCERY_SPEED,
                ),
                Expected(
                    unfathomableTruths,
                    "Unfathomable Truths",
                    "{4}{U}",
                    CardType.INSTANT,
                    TimingClass.INSTANT_SPEED,
                ),
                Expected(pursueThePast, "Pursue the Past", "{R}{W}", CardType.SORCERY, TimingClass.SORCERY_SPEED),
            ).forEach { expected ->
                with(expected.definition.characteristics) {
                    name shouldBe expected.name
                    manaCost?.render() shouldBe expected.cost
                    cardTypes shouldBe persistentSetOf(expected.type)
                    supertypes shouldBe persistentSetOf<Supertype>()
                    subtypes shouldBe persistentSetOf<Subtype>()
                    powerToughness.shouldBeNull()
                }
                expected.definition.timing shouldBe expected.timing
            }
        }

        "CR 115.1a: only Thought Scour targets, and it targets a player — the rest target nothing" {
            thoughtScour.targetSpec shouldBe TargetSpec.TargetPlayer
            listOf(mentalNote, lorienRevealed, unfathomableTruths, pursueThePast).forEach {
                it.targetSpec shouldBe TargetSpec.None
            }
        }

        "CR 702.28: Lórien Revealed's islandcycling is a hand-scoped {1}-plus-discard search for an Island" {
            val cycling = lorienRevealed.activatedAbilities.single()
            cycling.cost shouldContainExactly
                persistentListOf(AbilityCost.Mana(ManaCost.parse("{1}")), AbilityCost.DiscardSelf)
            cycling.zoneScope shouldBe AbilityZoneScope.Hand
            cycling.librarySearch shouldBe LibrarySearch(LibrarySearchFilter.ISLAND_CARD)
        }

        "CR 702.114a: Unfathomable Truths is devoid — colorless despite the {U} in its cost" {
            (Keyword.DEVOID in unfathomableTruths.characteristics.keywords) shouldBe true
            unfathomableTruths.characteristics.colors.shouldBeEmpty()
            // Every other card in the family derives its colour from its cost as usual (CR 202.2).
            thoughtScour.characteristics.colors shouldBe setOf(Color.BLUE)
            pursueThePast.characteristics.colors shouldBe setOf(Color.RED, Color.WHITE)
        }

        "CR 601.3b: Pursue the Past's loot clause offers the discard mode only — never sacrifice a land" {
            val clause =
                pursueThePast.optionalCostThenDraw
                    ?: error("Pursue the Past declares an optional cost-then-draw clause")
            clause.drawCount shouldBe PURSUE_THE_PAST_DRAW
            // Highway Robbery's second mode is not printed here; offering it would invent a line.
            clause.modes shouldContainExactly persistentListOf(OptionalCostMode.DiscardCard)
        }

        "CR 702.34: Pursue the Past is the only card in the family castable from elsewhere" {
            pursueThePast.castingPermissions shouldContainExactly
                listOf(CastingPermission.Flashback(ManaCost.parse("{2}{R}{W}")))
            listOf(thoughtScour, mentalNote, lorienRevealed, unfathomableTruths).forEach {
                it.castingPermissions.shouldBeEmpty()
            }
        }

        "CR 605.1a: none of the family is a mana source, and only Lórien Revealed has an activated ability" {
            listOf(thoughtScour, mentalNote, lorienRevealed, unfathomableTruths, pursueThePast).forEach {
                it.manaAbilities.shouldBeEmpty()
                it.triggeredAbilities.shouldBeEmpty()
            }
            listOf(thoughtScour, mentalNote, unfathomableTruths, pursueThePast).forEach {
                it.activatedAbilities.shouldBeEmpty()
            }
            lorienRevealed.activatedAbilities.size shouldBe 1
        }
    })
