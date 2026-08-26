package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.FaceKind
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchDestination
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed halves of `W10-B`'s two two-faced cards against the oracle text (CR 201–205, CR 715,
 * CR 720): both type lines of each card, both mana costs, and the shape of each inset frame's
 * declaration.
 *
 * Their *behaviour* — the one-sided sweep, the exile-and-play-later, the search, the shuffle back into
 * the library — is played end-to-end through the real engine in the acceptance module's
 * `TwoFacedCardAcceptanceSpec`. Nothing here asserts a game outcome.
 */
class TwoFacedCardsSpec :
    StringSpec({

        // ---- Fang Dragon // Forktail Sweep (CR 715) --------------------------------------------------

        "CR 201 / CR 302: Fang Dragon's normal half is a {5}{R}{R} Creature — Dragon 6/3 with flying" {
            with(fangDragon.characteristics) {
                name shouldBe "Fang Dragon"
                manaCost?.render() shouldBe "{5}{R}{R}"
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Dragon"))
                powerToughness shouldBe PrintedPowerToughness(power = 6, toughness = 3)
                keywords shouldBe persistentSetOf(Keyword.FLYING)
                colors shouldBe setOf(Color.RED)
                manaValue shouldBe FANG_DRAGON_MANA_VALUE
            }
            fangDragon.timing shouldBe TimingClass.SORCERY_SPEED
            // CR 715.4: in every zone but the stack the card has only its normal characteristics, and the
            // normal half prints no rules text at all beyond the keyword.
            fangDragon.triggeredAbilities.shouldBeEmpty()
            fangDragon.castingPermissions.shouldBeEmpty()
        }

        "CR 715.2: Fang Dragon's inset frame is Forktail Sweep, a {1}{R} Sorcery — Adventure" {
            val face = fangDragon.alternativeFace ?: error("Fang Dragon is an adventurer card (CR 715.1)")
            face.kind shouldBe FaceKind.ADVENTURE
            with(face.definition.characteristics) {
                // CR 715.5: the face has its own name, which is why the engine can offer two options for
                // one card ref without them reading identically.
                name shouldBe "Forktail Sweep"
                manaCost?.render() shouldBe "{1}{R}"
                // CR 205.3k: Adventure is a spell type, so the face is a sorcery card type plus that word.
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
                subtypes shouldBe persistentSetOf(Subtype("Adventure"))
                // CR 208.1: a sorcery has no power/toughness box, whatever the card's other half prints.
                powerToughness shouldBe null
                colors shouldBe setOf(Color.RED)
                manaValue shouldBe FORKTAIL_SWEEP_MANA_VALUE
            }
            face.definition.timing shouldBe TimingClass.SORCERY_SPEED
            // "deals 1 damage to **each** creature you don't control" — a sweeper targets nothing.
            face.definition.targetSpec shouldBe TargetSpec.None
        }

        "the face is the same object the registry never holds — one card, one key (CR 715.2c)" {
            // An adventurer card is one card, so only the card's own name is a registry key; the face is
            // reachable only through the card's definition, which is what stops a CR 400.7 zone move
            // having to decide which key an object carries.
            MvpCards.definitions[CardRef("Fang Dragon")] shouldBe fangDragon
            MvpCards.definitions[CardRef("Forktail Sweep")] shouldBe null
            MvpCards.definitions[CardRef("Sagu Wilds")] shouldBe null
        }

        // ---- Sagu Wildling // Sagu Wilds (CR 720) ----------------------------------------------------

        "CR 201 / CR 302: Sagu Wildling's normal half is a {4}{G} Creature — Dragon 3/3 with flying" {
            with(saguWildling.characteristics) {
                name shouldBe "Sagu Wildling"
                manaCost?.render() shouldBe "{4}{G}"
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Dragon"))
                powerToughness shouldBe PrintedPowerToughness(power = 3, toughness = 3)
                keywords shouldBe persistentSetOf(Keyword.FLYING)
                colors shouldBe setOf(Color.GREEN)
                manaValue shouldBe SAGU_WILDLING_MANA_VALUE
            }
            saguWildling.timing shouldBe TimingClass.SORCERY_SPEED
        }

        "CR 603.6a: the lifegain trigger belongs to the creature half — 'When this creature enters'" {
            val trigger = saguWildling.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            // The trigger is the creature's, so it is declared on the card and never on the face: an Omen
            // spell resolving becomes no permanent and gains nothing.
            saguWildling.alternativeFace
                ?.definition
                ?.triggeredAbilities
                ?.shouldBeEmpty()
            SAGU_WILDLING_LIFEGAIN shouldBe 3
        }

        "CR 720.2: Sagu Wildling's inset frame is Sagu Wilds, a {G} Sorcery — Omen" {
            val face = saguWildling.alternativeFace ?: error("Sagu Wildling is an omen card (CR 720.1)")
            face.kind shouldBe FaceKind.OMEN
            with(face.definition.characteristics) {
                name shouldBe "Sagu Wilds"
                manaCost?.render() shouldBe "{G}"
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
                subtypes shouldBe persistentSetOf(Subtype("Omen"))
                powerToughness shouldBe null
                colors shouldBe setOf(Color.GREEN)
                manaValue shouldBe SAGU_WILDS_MANA_VALUE
            }
            face.definition.targetSpec shouldBe TargetSpec.None
        }

        "CR 701.18: the Omen's printed line is a mandatory basic-land search that ends in the hand" {
            val face = saguWildling.alternativeFace ?: error("Sagu Wildling is an omen card (CR 720.1)")
            face.definition.librarySearch shouldBe
                LibrarySearch(
                    find = LibrarySearchFilter.BASIC_LAND_CARD,
                    // "reveal it, put it into your hand" — the destination carries the reveal (CR 701.16a).
                    destination = LibrarySearchDestination.REVEALED_TO_HAND,
                    // The card does not say "you may": the search is mandatory, and CR 701.18b's
                    // fail-to-find is a separate, always-available answer inside it.
                    optional = false,
                )
        }

        "the damage constant matches the printed line" {
            FORKTAIL_SWEEP_DAMAGE shouldBe 1
        }
    })

private const val FANG_DRAGON_MANA_VALUE: Int = 7
private const val FORKTAIL_SWEEP_MANA_VALUE: Int = 2
private const val SAGU_WILDLING_MANA_VALUE: Int = 5
private const val SAGU_WILDS_MANA_VALUE: Int = 1
