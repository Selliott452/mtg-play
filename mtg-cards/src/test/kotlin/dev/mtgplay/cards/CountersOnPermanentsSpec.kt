package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAmount
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The two cards `FW-COUNTERS` brings, checked against their Oracle text (CR 201–208). What the
 * counters and the keyword *do* is rules behaviour and is tested in `mtg-rules`
 * (`PermanentCountersSpec`, `HasteDefenderReachSpec`, `HasteManaSourceSpec`); this suite pins the
 * printed boxes and the declarations.
 */
class CountersOnPermanentsSpec :
    StringSpec({

        "CR 201-208: Unexpected Fangs is a {1}{B} Instant with no printed P/T" {
            val printed = unexpectedFangs.characteristics
            printed.name shouldBe "Unexpected Fangs"
            printed.manaCost shouldBe ManaCost.parse("{1}{B}")
            printed.supertypes.shouldBeEmpty()
            printed.cardTypes shouldBe persistentSetOf(CardType.INSTANT)
            printed.subtypes.shouldBeEmpty()
            printed.powerToughness shouldBe null
            printed.keywords.shouldBeEmpty()
        }

        "CR 115.1b: Unexpected Fangs targets one creature at instant speed" {
            unexpectedFangs.timing shouldBe TimingClass.INSTANT_SPEED
            unexpectedFangs.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        }

        "CR 201-208: Overgrown Battlement is a {1}{G} 0/4 Wall with defender" {
            val printed = overgrownBattlement.characteristics
            printed.name shouldBe "Overgrown Battlement"
            printed.manaCost shouldBe ManaCost.parse("{1}{G}")
            printed.supertypes.shouldBeEmpty()
            printed.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Wall"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 0, toughness = 4)
            printed.keywords shouldBe persistentSetOf(Keyword.DEFENDER)
        }

        "CR 605.2: Overgrown Battlement adds one {G} per creature you control with defender" {
            // Three conjuncts, matching the printed line word for word: you control, creature, with
            // defender. Dropping any one of them would be a different card.
            overgrownBattlement.manaAbilities shouldBe
                persistentListOf(
                    ManaAbility(
                        options = persistentListOf(ManaType.GREEN),
                        amount =
                            ManaAmount.PerPermanent(
                                PermanentFilter(
                                    controlledByYou = true,
                                    cardType = CardType.CREATURE,
                                    keyword = Keyword.DEFENDER,
                                ),
                            ),
                    ),
                )
        }

        "CR 601.2c: Overgrown Battlement is a sorcery-speed creature spell that targets nothing" {
            overgrownBattlement.timing shouldBe TimingClass.SORCERY_SPEED
            overgrownBattlement.targetSpec shouldBe TargetSpec.None
        }

        "ADR-009: both cards are registered in the MVP pool" {
            MvpCards.definitions[CardRef("Unexpected Fangs")] shouldBe unexpectedFangs
            MvpCards.definitions[CardRef("Overgrown Battlement")] shouldBe overgrownBattlement
        }
    })
