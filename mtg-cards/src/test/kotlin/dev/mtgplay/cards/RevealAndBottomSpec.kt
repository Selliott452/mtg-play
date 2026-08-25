package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.LibraryLookSource
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of the three filtered-look cards, checked against Scryfall oracle text (CR 201–205) and
 * against the clause each declares (CR 701.14a, CR 701.16a).
 *
 * The assertions worth reading twice are the two the *triage* got wrong. Lead the Stampede's current oracle
 * text is a **look** with an optional keep, not the reveal-and-keep-all the triage and
 * docs/design/library-look.md §9 describe, so it declares a [LibraryLook] rather than a
 * [dev.mtgplay.core.definition.LibraryReveal] — and its `maxToHand` is its full look depth, which is what
 * "any number of creature cards from among them" means over a pool that deep.
 *
 * Engine-driven behaviour — the enumeration, the partial publicity, the bottoming order — lives in the
 * acceptance module's `FilteredLookAndGraveyardAcceptanceSpec` and in `mtg-rules`' `FilteredLookSpec`.
 */
class RevealAndBottomSpec :
    StringSpec({

        "CR 701.14a: Ancient Stirrings is a {G} sorcery that looks five deep for a colorless card" {
            with(ancientStirrings.characteristics) {
                name shouldBe "Ancient Stirrings"
                manaCost shouldBe ManaCost.parse("{G}")
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            ancientStirrings.timing shouldBe TimingClass.SORCERY_SPEED
            ancientStirrings.targetSpec shouldBe TargetSpec.None
            // "You may reveal *a* colorless card": at most one, filtered on printed colour (CR 202.2).
            ancientStirrings.libraryLook shouldBe
                LibraryLook(
                    LibraryLookMode.RevealMatchingToHandRestToBottom(
                        count = ANCIENT_STIRRINGS_LOOK,
                        toHand = RevealedCardFilter.COLORLESS_CARD,
                        maxToHand = 1,
                    ),
                )
            // The clause is the whole card: no shuffle, no trailing draw, and no other clause.
            ancientStirrings.libraryReveal.shouldBeNull()
            ancientStirrings.triggeredAbilities.shouldBeEmpty()
        }

        "CR 603.6a: Augur of Bolas is a 1/3 whose enters trigger looks three deep for an instant or sorcery" {
            with(augurOfBolas.characteristics) {
                name shouldBe "Augur of Bolas"
                manaCost shouldBe ManaCost.parse("{1}{U}")
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Merfolk"), Subtype("Wizard"))
                powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 3)
            }
            // The *ability* carries the clause; the creature spell itself targets nothing (CR 302.1).
            augurOfBolas.targetSpec shouldBe TargetSpec.None
            augurOfBolas.libraryLook.shouldBeNull()
            val trigger = augurOfBolas.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            trigger.libraryLook shouldBe
                LibraryLook(
                    LibraryLookMode.RevealMatchingToHandRestToBottom(
                        count = AUGUR_OF_BOLAS_LOOK,
                        toHand = RevealedCardFilter.INSTANT_OR_SORCERY_CARD,
                        maxToHand = 1,
                    ),
                )
        }

        "CR 701.16a: Lead the Stampede looks five deep and may keep every creature it finds" {
            with(leadTheStampede.characteristics) {
                name shouldBe "Lead the Stampede"
                manaCost shouldBe ManaCost.parse("{2}{G}")
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
                powerToughness.shouldBeNull()
            }
            val mode =
                leadTheStampede.libraryLook
                    ?.mode as LibraryLookMode.RevealMatchingToHandRestToBottom
            mode.count shouldBe LEAD_THE_STAMPEDE_LOOK
            mode.toHand shouldBe RevealedCardFilter.CREATURE_CARD
            // "Any number of creature cards from among them" — the allowance is the whole pool.
            mode.maxToHand shouldBe mode.count
            // Its current oracle text is a *look*, not a reveal: no LibraryReveal clause is declared.
            leadTheStampede.libraryReveal.shouldBeNull()
        }

        "CR 701.14a: all three look at the top of their own library, and none shuffles or draws after" {
            listOf(
                ancientStirrings.libraryLook,
                augurOfBolas.triggeredAbilities
                    .single()
                    .libraryLook,
                leadTheStampede.libraryLook,
            ).forEach { look ->
                val clause = checkNotNull(look) { "each of the three declares a look clause" }
                clause.mode.source shouldBe LibraryLookSource.TOP_OF_LIBRARY
                clause.optionalShuffle shouldBe false
                clause.thenDraw shouldBe 0
            }
        }
    })
