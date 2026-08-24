package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.Color
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of `FW-COUNTER` F1.2/F1.3 against the oracle cards (CR 201–205, CR 701.5): each of the
 * eight counters' cost, type line, timing, and — the part that *is* the card — its
 * [SpellRestriction].
 *
 * The restriction is the whole difference between six of these eight, so pinning it per card is pinning
 * the card. Their *behaviour* — a spell leaving the stack mid-resolution, the CR 608.2b fizzle when the
 * target has gone, the CR 118.3a payment — is exercised on fixtures in `mtg-rules`' `CounteringSpec` and
 * end to end in the acceptance module, because `mtg-rules` may not name a card (ADR-003).
 */
class CountersSpec :
    StringSpec({

        "CR 202/304: all eight counters are plain blue instants with no supertype, subtype, or P/T box" {
            val printed =
                mapOf(
                    counterspell to "{U}{U}",
                    dispel to "{U}",
                    negate to "{1}{U}",
                    annul to "{U}",
                    envelop to "{U}",
                    removeSoul to "{1}{U}",
                    forceSpike to "{U}",
                    spellPierce to "{U}",
                )
            printed.forEach { (definition, cost) ->
                with(definition.characteristics) {
                    manaCost?.render() shouldBe cost
                    supertypes shouldBe persistentSetOf<Supertype>()
                    cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                    subtypes shouldBe persistentSetOf<Subtype>()
                    powerToughness.shouldBeNull()
                }
                // CR 117.1a: every counter is an instant, castable in any window — the whole point.
                definition.timing shouldBe TimingClass.INSTANT_SPEED
            }
        }

        "CR 201: each counter is registered under its printed name" {
            listOf(
                counterspell to "Counterspell",
                dispel to "Dispel",
                negate to "Negate",
                annul to "Annul",
                envelop to "Envelop",
                removeSoul to "Remove Soul",
                forceSpike to "Force Spike",
                spellPierce to "Spell Pierce",
            ).forEach { (definition, name) -> definition.characteristics.name shouldBe name }
        }

        "CR 115.1: each counter's targeting line is its SpellRestriction, and they are all different" {
            restrictionOf(counterspell) shouldBe SpellRestriction.Any
            restrictionOf(dispel) shouldBe SpellRestriction.OfCardType(CardType.INSTANT)
            restrictionOf(negate) shouldBe SpellRestriction.NotOfCardType(CardType.CREATURE)
            restrictionOf(annul) shouldBe
                SpellRestriction.OfAnyCardType(persistentSetOf(CardType.ARTIFACT, CardType.ENCHANTMENT))
            restrictionOf(envelop) shouldBe SpellRestriction.OfCardType(CardType.SORCERY)
            restrictionOf(removeSoul) shouldBe SpellRestriction.OfCardType(CardType.CREATURE)
        }

        "CR 115.1: Negate and Remove Soul are exact complements — neither can take the other's target" {
            // "Noncreature" and "creature" partition the stack, which is why the pair is the clearest
            // pin on `NotOfCardType` being a real member and not a filter over `OfCardType`.
            restrictionOf(negate) shouldBe SpellRestriction.NotOfCardType(CardType.CREATURE)
            restrictionOf(removeSoul) shouldBe SpellRestriction.OfCardType(CardType.CREATURE)
        }

        "CR 118.3a: only Force Spike and Spell Pierce carry an unless-pay clause, at their printed amounts" {
            forceSpike.counterUnlessPaid?.cost?.render() shouldBe "{1}"
            spellPierce.counterUnlessPaid?.cost?.render() shouldBe "{2}"
            // The amount is printed on the counter, not read off the target's cost: nothing about the
            // targeted spell's cost is inspected anywhere (docs/design/countering-spells.md §1.1).
            spellPierce.counterUnlessPaid?.cost?.render() shouldBe "{2}"
            listOf(counterspell, dispel, negate, annul, envelop, removeSoul).forEach {
                it.counterUnlessPaid.shouldBeNull()
            }
        }

        "CR 115.1: Spell Pierce is restricted *and* unless-pay — the pairing that makes the verdicts split" {
            restrictionOf(spellPierce) shouldBe SpellRestriction.NotOfCardType(CardType.CREATURE)
            restrictionOf(forceSpike) shouldBe SpellRestriction.Any
        }

        "no counter in this packet is modal, kicked, conditional, or colour-restricted" {
            // Each of those is a framework this packet deliberately does not build, and a card that
            // quietly approximated one would be silently wrong rather than absent (PLAN.md §7).
            listOf(counterspell, dispel, negate, annul, envelop, removeSoul, forceSpike, spellPierce)
                .forEach { definition ->
                    definition.castingPermissions.shouldBeEmpty()
                    definition.additionalCost.shouldBeNull()
                    definition.libraryReveal.shouldBeNull()
                    definition.libraryLook.shouldBeNull()
                    definition.drawThenDiscard.shouldBeNull()
                    definition.optionalCostThenDraw.shouldBeNull()
                }
            // No card here restricts by colour: the Blasts need modes (CR 700.2) and are out of scope.
            listOf(counterspell, dispel, negate, annul, envelop, removeSoul, forceSpike, spellPierce)
                .map { restrictionOf(it) }
                .filterIsInstance<SpellRestriction.OfColor>()
                .shouldBeEmpty()
            SpellRestriction.OfColor(Color.RED).color shouldBe Color.RED
        }
    })

/** The [SpellRestriction] of a counter's targeting line; fails loudly on a definition that targets else. */
private fun restrictionOf(definition: SpellDefinition): SpellRestriction =
    (definition.targetSpec as? TargetSpec.SpellOnStack)?.restriction
        ?: error("${definition.characteristics.name} does not target a spell on the stack")
