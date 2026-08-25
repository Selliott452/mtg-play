package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CostReduction
import dev.mtgplay.core.definition.CountScope
import dev.mtgplay.core.definition.ObjectPredicate
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The five `FW-COST` cards against their oracle text: printed characteristics (CR 201–208) and the
 * [CostReduction] declaration each printed clause maps onto (CR 601.2f,
 * docs/design/cost-modification.md). The *arithmetic* is `mtg-rules`' `CostModificationSpec`; this
 * suite pins the data, including the five siblings that are deliberately not here.
 */
class CostReductionCardsSpec :
    StringSpec({

        "CR 202: the printed costs and type lines match the oracle cards" {
            data class Expected(
                val definition: SpellDefinition,
                val name: String,
                val cost: String,
                val types: Set<CardType>,
                val subtypes: Set<String>,
                val powerToughness: PrintedPowerToughness?,
            )
            listOf(
                Expected(
                    myrEnforcer,
                    "Myr Enforcer",
                    "{7}",
                    setOf(CardType.ARTIFACT, CardType.CREATURE),
                    setOf("Myr"),
                    PrintedPowerToughness(4, 4),
                ),
                Expected(
                    utromMonitor,
                    "Utrom Monitor",
                    "{4}{U}",
                    setOf(CardType.ARTIFACT, CardType.CREATURE),
                    setOf("Utrom", "Scientist"),
                    PrintedPowerToughness(3, 3),
                ),
                Expected(thoughtcast, "Thoughtcast", "{4}{U}", setOf(CardType.SORCERY), emptySet(), null),
                Expected(
                    crypticSerpent,
                    "Cryptic Serpent",
                    "{5}{U}{U}",
                    setOf(CardType.CREATURE),
                    setOf("Serpent"),
                    PrintedPowerToughness(6, 5),
                ),
                Expected(ofOneMind, "Of One Mind", "{2}{U}", setOf(CardType.SORCERY), emptySet(), null),
            ).forEach { expected ->
                val printed = expected.definition.characteristics
                printed.name shouldBe expected.name
                printed.manaCost.shouldNotNull().render() shouldBe expected.cost
                printed.cardTypes.toSet() shouldBe expected.types
                printed.subtypes.map { it.value }.toSet() shouldBe expected.subtypes
                printed.powerToughness shouldBe expected.powerToughness
            }
        }

        "CR 702.41a: the three affinity cards declare one reduction per artifact you control" {
            listOf(myrEnforcer, utromMonitor, thoughtcast).forEach { card ->
                val reduction =
                    card.costReduction.shouldNotBeNull().shouldBeInstanceOf<CostReduction.PerMatching>()
                reduction.amountPerMatch shouldBe 1
                reduction.scope shouldBe CountScope.BATTLEFIELD_YOU_CONTROL
                reduction.predicate shouldBe ObjectPredicate.HasCardType(CardType.ARTIFACT)
            }
        }

        "CR 202.2: Thoughtcast has affinity without being an artifact, so the counter need not share a type" {
            // The one affinity card in the pool that is not itself an artifact. Nothing about the
            // reduction depends on the spell sharing a card type with what it counts.
            (CardType.ARTIFACT in thoughtcast.characteristics.cardTypes) shouldBe false
            thoughtcast.costReduction shouldBe myrEnforcer.costReduction
        }

        "CR 205.2a: Cryptic Serpent counts instant and sorcery cards as a disjunction, not a conjunction" {
            // "each instant and sorcery card" enumerates two accepted types. No card is both, so an
            // And would make this reduction permanently zero — the one way to encode it silently wrong.
            val reduction =
                crypticSerpent.costReduction.shouldNotBeNull().shouldBeInstanceOf<CostReduction.PerMatching>()
            reduction.scope shouldBe CountScope.YOUR_GRAVEYARD
            reduction.predicate.shouldBeInstanceOf<ObjectPredicate.AnyOf>().predicates shouldContainExactly
                listOf(
                    ObjectPredicate.HasCardType(CardType.INSTANT),
                    ObjectPredicate.HasCardType(CardType.SORCERY),
                )
        }

        "CR 118.7a: Cryptic Serpent's two blue pips are a floor no reduction can pass" {
            // {U}{U} survives any graveyard, because a generic reduction affects only the generic
            // component. The pins here are the printed halves the rules-side floor test relies on.
            crypticSerpent.characteristics.colors shouldBe setOf(Color.BLUE)
            crypticSerpent.characteristics.manaCost
                .shouldNotNull()
                .manaValue shouldBe CRYPTIC_SERPENT_MANA_VALUE
        }

        "CR 601.2f: Of One Mind is a flat conditional reduction over a Human and a non-Human creature" {
            val reduction = ofOneMind.costReduction.shouldNotBeNull().shouldBeInstanceOf<CostReduction.IfAll>()
            reduction.amount shouldBe OF_ONE_MIND_REDUCTION
            reduction.conditions.size shouldBe 2
            reduction.conditions.forEach { it.scope shouldBe CountScope.BATTLEFIELD_YOU_CONTROL }
            reduction.conditions.forEach { it.atLeast shouldBe 1 }
            // The second condition is the pool's first negated predicate: a creature that does *not*
            // have the Human subtype. Encoding it as "not a Human creature" would also match a
            // non-creature and is a different, wrong test.
            val second = reduction.conditions[1].predicate.shouldBeInstanceOf<ObjectPredicate.And>()
            second.predicates shouldContainExactly
                listOf(
                    ObjectPredicate.HasCardType(CardType.CREATURE),
                    ObjectPredicate.Not(ObjectPredicate.HasSubtype(Subtype("Human"))),
                )
        }

        "CR 702.9: Utrom Monitor prints flying and Myr Enforcer prints nothing" {
            utromMonitor.characteristics.keywords.toSet() shouldBe setOf(Keyword.FLYING)
            myrEnforcer.characteristics.keywords.toSet() shouldBe emptySet()
        }

        "the five cost cards are registered in the catalog and no other card declares a reduction" {
            val registered = listOf(myrEnforcer, utromMonitor, thoughtcast, crypticSerpent, ofOneMind)
            registered.forEach { card ->
                MvpCards.definitions[CardRef(card.characteristics.name)] shouldBe card
            }
            // The whole set of cost-reducing cards in the pool, so a sixth cannot arrive unnoticed.
            MvpCards.definitions.values
                .filterIsInstance<SpellDefinition>()
                .filter { it.costReduction != null }
                .map { it.characteristics.name }
                .toSet() shouldBe registered.map { it.characteristics.name }.toSet()
        }

        "no card declares an other-object cost reduction yet: Sunscape Familiar needs FW-DEFENDERKW" {
            // The C6 declaration slot ships and is exercised by rules fixtures; the only gauntlet card
            // that would use it prints Defender, which `mtg-core` has no keyword for. Encoding it
            // without Defender would put a 0/3 Wall in the pool that can attack.
            MvpCards.definitions.values.forEach { it.spellCostReductions.shouldBeEmptyList() }
            MvpCards.definitions[CardRef("Sunscape Familiar")].shouldBeNull()
        }

        "the four other FW-COST cards stay unencoded, each on a framework this packet does not own" {
            // Tolarian Terror: ward {2} (CR 702.21a) — a triggered ability, `FW-WARD`.
            // Refurbished Familiar / Deem Inferior: a decision put to a non-controller, `FW-NONCTRLDEC`.
            // Ride's End: a cost priced off the chosen target, `FW-TGTCOND`.
            listOf("Tolarian Terror", "Refurbished Familiar", "Deem Inferior", "Ride's End").forEach {
                MvpCards.definitions[CardRef(it)].shouldBeNull()
            }
        }
    })

/** Cryptic Serpent's printed mana value (CR 203.3): five generic plus two blue. */
private const val CRYPTIC_SERPENT_MANA_VALUE: Int = 7

/** Asserts a collection is empty, reading as the absence it documents. */
private fun <T> Collection<T>.shouldBeEmptyList() {
    size shouldBe 0
}

/** Kotest's non-null assertion, named to read inside a chained expression. */
private fun <T : Any> T?.shouldNotNull(): T = this.shouldNotBeNull()
