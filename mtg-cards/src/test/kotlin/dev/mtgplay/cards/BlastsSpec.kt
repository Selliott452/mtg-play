package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.ModalSpell
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.Color
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of `FW-MODAL` against the oracle cards (CR 201–205, CR 700.2): each modal card's
 * cost, type line, timing, and — the part that *is* the card — the targeting line and effect of each of
 * its modes.
 *
 * **The one thing this file exists to pin is that the four Blasts are two templates, not one.** The
 * upstream brief grouped them; docs/design/countering-spells.md §1.2 split them; the oracle text
 * confirms the split. Encoded, the difference is exactly *which side of the mode carries the colour*:
 * the Elemental Blasts put it in the [TargetSpec], Hydroblast and Pyroblast put it in the
 * [dev.mtgplay.core.definition.ResolutionEffect]. That is asserted below on the declarations; the
 * *behavioural* consequence — that a target-restricted Blast vanishes from enumeration while an
 * effect-conditional one stays castable against anything — is exercised on real boards in `mtg-rules`'
 * `ModalCastingSpec` and end to end in the acceptance module, because `mtg-rules` may not name a card
 * (ADR-003).
 */
class BlastsSpec :
    StringSpec({

        "CR 202/304: all five modal cards are plain single-colour instants with no supertype or P/T box" {
            val printed =
                mapOf(
                    blueElementalBlast to "{U}",
                    redElementalBlast to "{R}",
                    hydroblast to "{U}",
                    pyroblast to "{R}",
                    steelSabotage to "{U}",
                )
            printed.forEach { (definition, cost) ->
                with(definition.characteristics) {
                    manaCost?.render() shouldBe cost
                    supertypes shouldBe persistentSetOf<Supertype>()
                    cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                    subtypes shouldBe persistentSetOf<Subtype>()
                    powerToughness.shouldBeNull()
                }
                definition.timing shouldBe TimingClass.INSTANT_SPEED
            }
        }

        "CR 201: each modal card is registered under its printed name" {
            listOf(
                blueElementalBlast to "Blue Elemental Blast",
                redElementalBlast to "Red Elemental Blast",
                hydroblast to "Hydroblast",
                pyroblast to "Pyroblast",
                steelSabotage to "Steel Sabotage",
            ).forEach { (definition, name) -> definition.characteristics.name shouldBe name }
        }

        "CR 700.2: every modal card declares exactly the two modes its oracle text prints, in order" {
            listOf(blueElementalBlast, redElementalBlast, hydroblast, pyroblast, steelSabotage)
                .forEach { it.modes shouldHaveSize 2 }

            blueElementalBlast.modes.map { it.text } shouldBe
                listOf("Counter target red spell.", "Destroy target red permanent.")
            redElementalBlast.modes.map { it.text } shouldBe
                listOf("Counter target blue spell.", "Destroy target blue permanent.")
            hydroblast.modes.map { it.text } shouldBe
                listOf("Counter target spell if it's red.", "Destroy target permanent if it's red.")
            pyroblast.modes.map { it.text } shouldBe
                listOf("Counter target spell if it's blue.", "Destroy target permanent if it's blue.")
            steelSabotage.modes.map { it.text } shouldBe
                listOf("Counter target artifact spell.", "Return target artifact to its owner's hand.")
        }

        // The packet's correctness core, stated as one assertion pair. Both cards counter, both cards
        // destroy, both cards care about red — and their *targeting lines* are completely different.
        "CR 115.1 vs CR 608.2c: the Elemental Blasts restrict the target, Hydro/Pyroblast restrict the effect" {
            // Target-restricted: the colour is IN the spec, so the engine filters the option list.
            blueElementalBlast.modes[0].targetSpec shouldBe
                TargetSpec.SpellOnStack(SpellRestriction.OfColor(Color.RED))
            blueElementalBlast.modes[1].targetSpec shouldBe
                TargetSpec.TargetPermanent(PermanentRestriction.RED_PERMANENT)

            // Effect-conditional: the specs are UNRESTRICTED, so every spell and every permanent is a
            // legal choice and the colour is tested at resolution instead. This is the assertion that
            // fails if somebody "tidies" Hydroblast into its sibling's shape — the silent ADR-005
            // enumeration gap docs/design/countering-spells.md §1.2 warns about.
            hydroblast.modes[0].targetSpec shouldBe TargetSpec.SpellOnStack(SpellRestriction.Any)
            hydroblast.modes[1].targetSpec shouldBe
                TargetSpec.TargetPermanent(PermanentRestriction.ANY_PERMANENT)

            // And the mirrors, with the colours exchanged.
            redElementalBlast.modes[0].targetSpec shouldBe
                TargetSpec.SpellOnStack(SpellRestriction.OfColor(Color.BLUE))
            redElementalBlast.modes[1].targetSpec shouldBe
                TargetSpec.TargetPermanent(PermanentRestriction.BLUE_PERMANENT)
            pyroblast.modes[0].targetSpec shouldBe TargetSpec.SpellOnStack(SpellRestriction.Any)
            pyroblast.modes[1].targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.ANY_PERMANENT)
        }

        "CR 700.2: no mode of an effect-conditional Blast names a colour in its targeting line" {
            // Restated as a property so a future colour-carrying restriction cannot creep in unnoticed:
            // whatever `SpellRestriction` grows, Hydroblast's and Pyroblast's must stay `Any`.
            listOf(hydroblast, pyroblast).forEach { card ->
                val spellSpec = card.modes[0].targetSpec as TargetSpec.SpellOnStack
                spellSpec.restriction shouldBe SpellRestriction.Any
                val permanentSpec = card.modes[1].targetSpec as TargetSpec.TargetPermanent
                permanentSpec.restriction shouldBe PermanentRestriction.ANY_PERMANENT
            }
        }

        "CR 601.2b: Steel Sabotage's two modes target different kinds of object, which is why modes precede targets" {
            // A spell on the stack for mode 0, a battlefield permanent for mode 1. There is no single
            // question "what does Steel Sabotage target?" — which is the mechanical reason CR 601.2b
            // must run before CR 601.2c rather than merely the order the rules happen to print.
            steelSabotage.modes[0].targetSpec shouldBe
                TargetSpec.SpellOnStack(SpellRestriction.OfCardType(CardType.ARTIFACT))
            steelSabotage.modes[1].targetSpec shouldBe
                TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT)

            // The same property holds for every modal card in the pool, which is what makes it the
            // framework's invariant rather than one card's quirk.
            listOf(blueElementalBlast, redElementalBlast, hydroblast, pyroblast, steelSabotage).forEach { card ->
                (card.modes[0].targetSpec is TargetSpec.SpellOnStack) shouldBe true
                (card.modes[1].targetSpec is TargetSpec.TargetPermanent) shouldBe true
            }
        }

        "CR 601.2b: a modal card cannot answer what it targets or what it does without a chosen mode" {
            // The ADR-005 safety property of `ModalSpell`: the two unanswerable questions throw rather
            // than returning a plausible default. A `TargetSpec.None` default here would make Blue
            // Elemental Blast enumerable with no red object on the table — castable, targetless, and
            // silently doing nothing.
            listOf(blueElementalBlast, redElementalBlast, hydroblast, pyroblast, steelSabotage).forEach { card ->
                (card as ModalSpell)
                shouldThrow<IllegalStateException> { card.targetSpec }
                    .message
                    .orEmpty() shouldContain "is modal"
                shouldThrow<IllegalStateException> { card.resolution }
                    .message
                    .orEmpty() shouldContain "is modal"
            }
        }

        "CR 700.2: a non-modal card declares no modes, so the engine's modality test stays a single question" {
            listOf<SpellDefinition>(counterspell, dispel, lightningBolt, terminate).forEach { card ->
                card.modes shouldHaveSize 0
            }
        }
    })
