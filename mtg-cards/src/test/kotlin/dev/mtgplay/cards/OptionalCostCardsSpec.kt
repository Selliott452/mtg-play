package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CastCondition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.LibrarySearchDestination
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The three optional-cost cards, asserted against the Scryfall oracle text fetched for this packet.
 *
 * These are **declaration** assertions: that each card prints what the oracle says it prints. The
 * behaviour they declare is exercised at the rules level (`OptionalCostSpec`, `XCostSpec`) against
 * fixtures, which is the split `FW-MANA` §8.3 argues for — a profile computed correctly from a wrong
 * declaration is the failure mode a rules-level spec structurally cannot see, and this is what sees it.
 */
class OptionalCostCardsSpec :
    StringSpec({

        // ---- Goblin Bushwhacker (Zendikar) ---------------------------------------------------------

        "CR 702.33a: Goblin Bushwhacker is a {R} 1/1 Goblin Warrior with Kicker {R}" {
            val card = goblinBushwhacker
            card.characteristics.name shouldBe "Goblin Bushwhacker"
            card.characteristics.manaCost shouldBe ManaCost.parse("{R}")
            card.characteristics.cardTypes shouldBe setOf(CardType.CREATURE)
            card.characteristics.subtypes shouldBe setOf(Subtype("Goblin"), Subtype("Warrior"))
            card.characteristics.powerToughness
                .shouldNotBeNull()
                .power shouldBe 1
            card.characteristics.powerToughness
                .shouldNotBeNull()
                .toughness shouldBe 1
            card.timing shouldBe TimingClass.SORCERY_SPEED
            // "Kicker {R}" — the additional cost is the *card's own colour*, not generic, which is the
            // half a mana-value-summing implementation would have got wrong.
            card.kicker shouldBe ManaCost.parse("{R}")
        }

        "CR 603.4: its enters trigger carries the intervening-if, not a condition inside the effect" {
            val ability = goblinBushwhacker.triggeredAbilities.single()
            ability.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            ability.interveningIf shouldBe InterveningIf.SourceWasKicked
        }

        // ---- Prohibit (Modern Horizons) ------------------------------------------------------------

        "CR 702.33a: Prohibit is a {1}{U} Instant with Kicker {2}" {
            prohibit.characteristics.name shouldBe "Prohibit"
            prohibit.characteristics.manaCost shouldBe ManaCost.parse("{1}{U}")
            prohibit.characteristics.cardTypes shouldBe setOf(CardType.INSTANT)
            prohibit.timing shouldBe TimingClass.INSTANT_SPEED
            prohibit.kicker shouldBe ManaCost.parse("{2}")
        }

        "CR 115.1: Prohibit targets *any* spell — the mana value is a condition, not a restriction" {
            // The distinction docs/design/countering-spells.md §1.2 most warns about. Encoding "mana
            // value 2 or less" as a targeting restriction would hide the legal (if pointless) cast at a
            // big spell, and could not express a threshold that moves with a CR 601.2b announcement the
            // engine has not made when targets are enumerated.
            prohibit.targetSpec.shouldBeInstanceOf<TargetSpec.SpellOnStack>().restriction shouldBe
                SpellRestriction.Any
        }

        "CR 702.33f: the two thresholds are the printed ones — 2 unkicked, 4 kicked" {
            PROHIBIT_UNKICKED_MAX_MANA_VALUE shouldBe 2
            PROHIBIT_KICKED_MAX_MANA_VALUE shouldBe 4
        }

        // ---- Land Grant (Mercadian Masques) --------------------------------------------------------

        "CR 118.9: Land Grant's alternative cost is free, conditional, and paid by revealing" {
            landGrant.characteristics.manaCost shouldBe ManaCost.parse("{1}{G}")
            landGrant.characteristics.cardTypes shouldBe setOf(CardType.SORCERY)
            val permission =
                landGrant.castingPermissions
                    .single()
                    .shouldBeInstanceOf<CastingPermission.AlternativeCost>()
            permission.cost shouldBe ManaCost.parse("{0}")
            permission.condition shouldBe CastCondition.NoLandCardsInHand
            permission.revealsHand shouldBe true
            // The two fields are independent, and Land Grant needs both — a sacrifice is not involved.
            permission.sacrifice.shouldBeNull()
        }

        "CR 701.18: Land Grant's search is for a Forest card, revealed, to hand" {
            val search = landGrant.librarySearch.shouldNotBeNull()
            search.find shouldBe LibrarySearchFilter.FOREST_CARD
            // CR 701.16a: the reveal is folded into the destination, because a search ending in a hidden
            // zone is unverifiable unless the card is shown.
            search.destination shouldBe LibrarySearchDestination.REVEALED_TO_HAND
        }

        // ---- Registry -------------------------------------------------------------------------------

        "all three are registered under their printed names (CR 201)" {
            listOf("Goblin Bushwhacker", "Prohibit", "Land Grant").forEach {
                MvpCards.definitions[CardRef(it)].shouldNotBeNull()
            }
        }

        "the one card this packet was offered and could not encode stays absent" {
            // Kaervek's Torch needs a **cost increase** applied to another spell and keyed on that
            // spell's chosen targets. Shipping it without that half would be a plausible-looking wrong
            // card (PLAN.md §7) — see the packet report.
            MvpCards.definitions[CardRef("Kaervek's Torch")].shouldBeNull()
            // Nyxborn Hydra was the second of the pair and is **no longer absent**: `W10-C` built
            // bestow (CR 702.103) and the CR 614.1c enters-with-counters replacement it also needed.
            // The pin is inverted rather than deleted, so the day a packet claims to encode a card the
            // registry still lacks, this line says so.
            MvpCards.definitions[CardRef("Nyxborn Hydra")].shouldNotBeNull()
        }
    })
