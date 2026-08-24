package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAmount
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The three Urza lands' printed boxes against their oracle cards (CR 201–205) and the conditional
 * mana ability each carries (CR 605.1a, CR 605.2). What the *engine* does with the condition — that
 * an assembled Tower forms a different payment source class from an unassembled one, and that the
 * count is read when the ability resolves — is a rules behaviour, tested in `mtg-rules`'
 * `PaymentEnumerationSpec` and in `mtg-acceptance`' `MonsterTronBudgetAcceptanceSpec`.
 *
 * Two of these assertions exist because the upstream card brief got them wrong, and both failure
 * modes are silent rather than loud (docs/gauntlet-card-triage.md): the Tower adds **three**, not
 * two, and the conditions name land **subtypes** — with `Urza's Power-Plant` hyphenated, unlike the
 * card's name.
 */
class UrzaLandsSpec :
    StringSpec({
        val lands =
            mapOf(
                urzasMine to ("Urza's Mine" to Subtype("Urza's Mine")),
                urzasPowerPlant to ("Urza's Power Plant" to Subtype("Urza's Power-Plant")),
                urzasTower to ("Urza's Tower" to Subtype("Urza's Tower")),
            )

        "CR 201-205: each Urza land is a costless land whose only subtype is its own land type" {
            lands.forEach { (definition: CardDefinition, naming) ->
                val (name, subtype) = naming
                val printed = definition.characteristics
                printed.name shouldBe name
                printed.manaCost shouldBe null
                printed.supertypes.shouldBeEmpty()
                printed.cardTypes shouldBe persistentSetOf(CardType.LAND)
                printed.subtypes shouldBe persistentSetOf(subtype)
                printed.powerToughness shouldBe null
                printed.keywords.shouldBeEmpty()
                definition.entersTapped shouldBe false
            }
        }

        "CR 205.3i: Urza's Power Plant's land subtype is hyphenated and its card name is not" {
            // The oracle text of the other two conditions on "an Urza's Power-Plant", the subtype.
            // Matching the card name instead would never throw — the count would simply always miss.
            urzasPowerPlant.characteristics.name shouldBe "Urza's Power Plant"
            urzasPowerPlant.characteristics.subtypes shouldBe persistentSetOf(Subtype("Urza's Power-Plant"))
            urzasPowerPlant.characteristics.name shouldNotBe
                urzasPowerPlant.characteristics.subtypes
                    .single()
                    .value
        }

        "CR 605.2: each Urza land has one colorless mana ability conditioned on the other two subtypes" {
            lands.forEach { (definition, naming) ->
                val ability = definition.manaAbilities.single()
                ability.options shouldBe listOf(ManaType.COLORLESS)
                ability.viaSacrifice shouldBe false
                val amount = ability.amount as ManaAmount.Conditional
                // Alone, every Urza land adds exactly one — the "instead" clause is the exception.
                amount.otherwise shouldBe 1
                // The condition names the other two land types, both controller-scoped ("you control").
                amount.requires.map { it.subtype }.toSet() shouldBe
                    lands.values.map { it.second }.toSet() - naming.second
                amount.requires.forEach { it shouldBe PermanentFilter(it.subtype, controlledByYou = true) }
            }
        }

        "CR 605.2: the Tower adds three where the Mine and the Power Plant add two" {
            // The upstream brief said {C}{C} for all three. The oracle text says {C}{C}{C} for the
            // Tower, which is what makes assembled Tron seven mana rather than six.
            assembledAmount(urzasTower) shouldBe 3
            assembledAmount(urzasMine) shouldBe 2
            assembledAmount(urzasPowerPlant) shouldBe 2
        }

        "all three Urza lands are registered under their printed names" {
            lands.values.forEach { (name, _) ->
                MvpCards.definitions[CardRef(name)] shouldNotBe null
            }
        }
    })

/** What [definition]'s mana ability adds once its condition is met (CR 605.2). */
private fun assembledAmount(definition: CardDefinition): Int =
    (definition.manaAbilities.single().amount as ManaAmount.Conditional).ifMet
