package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Quality
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CountScope
import dev.mtgplay.core.definition.ManaValueBound
import dev.mtgplay.core.definition.ObjectPredicate
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The six cards `P-ABILSOURCE` encodes, against their Scryfall oracle text (fetched for this packet).
 * Printed characteristics (CR 201–208) and the declaration each printed clause maps onto; behaviour
 * lives in the rules-side specs, which is where the enumerations that define legality are.
 *
 * Two of the six exist mainly to make the protection substrate reachable at all: `FW-PROTECT` shipped
 * every seam and **zero cards**, so nothing had ever exercised [Quality] against a real board — and
 * the ability half of CR 702.16b threw rather than answering.
 */
class W7DCardsSpec :
    StringSpec({

        "CR 202: the printed type lines and costs match the oracle cards" {
            guardianOfTheGuildpact.characteristics.name shouldBe "Guardian of the Guildpact"
            guardianOfTheGuildpact.characteristics.manaCost shouldBe ManaCost.parse("{3}{W}")
            guardianOfTheGuildpact.characteristics.cardTypes shouldContainExactly persistentSetOf(CardType.CREATURE)

            maskOfLawAndGrace.characteristics.name shouldBe "Mask of Law and Grace"
            maskOfLawAndGrace.characteristics.manaCost shouldBe ManaCost.parse("{W}")
            maskOfLawAndGrace.characteristics.subtypes shouldContainExactly persistentSetOf(Subtype("Aura"))

            raze.characteristics.manaCost shouldBe ManaCost.parse("{R}")
            raze.characteristics.cardTypes shouldContainExactly persistentSetOf(CardType.SORCERY)

            ghostlyFlicker.characteristics.manaCost shouldBe ManaCost.parse("{2}{U}")
            ghostlyFlicker.timing shouldBe TimingClass.INSTANT_SPEED

            balustradeSpy.characteristics.manaCost shouldBe ManaCost.parse("{3}{B}")
            balustradeSpy.characteristics.keywords shouldContainExactly persistentSetOf(Keyword.FLYING)

            spellstutterSprite.characteristics.manaCost shouldBe ManaCost.parse("{1}{U}")
            spellstutterSprite.characteristics.subtypes shouldContainExactlyInAnyOrder
                listOf(Subtype("Faerie"), Subtype("Wizard"))
        }

        "CR 702.16a: Guardian of the Guildpact's quality is *monocolored*, not a colour" {
            // The card that makes CR 702.16a's "any characteristic value" clause load-bearing: a
            // Color-shaped protection field would carry Mask and silently fail this one.
            guardianOfTheGuildpact.characteristics.protections shouldContainExactly
                persistentSetOf(Quality.Monocolored)
        }

        "CR 702.16g: Mask of Law and Grace grants two qualities from one ability" {
            val granted = maskOfLawAndGrace.staticContinuousEffects.single().grantedProtections
            granted shouldContainExactlyInAnyOrder
                listOf(Quality.OfColor(Color.BLACK), Quality.OfColor(Color.RED))
        }

        "CR 704.5m: Mask is white, so its own grant never makes its own attachment illegal" {
            // An Aura granting protection from *white* would fall off the instant it resolved. This
            // one grants black and red and is itself white, so it stays put — a property of the
            // printed card rather than of the engine, and worth pinning as such.
            val granted = maskOfLawAndGrace.staticContinuousEffects.single().grantedProtections
            maskOfLawAndGrace.characteristics.colors shouldContainExactly persistentSetOf(Color.WHITE)
            granted.contains(Quality.OfColor(Color.WHITE)) shouldBe false
        }

        "CR 601.2b: Raze prints a sacrifice-a-land additional cost and targets a land" {
            raze.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.LAND)
            // CR 601.2h pays the cost *after* CR 601.2c chooses the target, so the land being
            // sacrificed is still on the battlefield and is itself a legal target.
            val cost = raze.additionalCost as AdditionalCost.Sacrifice
            cost.count shouldBe 1
            cost.filter.anyOfCardTypes shouldContainExactly persistentSetOf(CardType.LAND)
        }

        "CR 115.1b: Ghostly Flicker takes exactly two targets over one union noun" {
            val spec = ghostlyFlicker.targetSpec as TargetSpec.TargetPermanent
            spec.restriction shouldBe PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL
            // "Exactly two", not "up to two": uncastable with fewer than two qualifying permanents.
            spec.count.minimum shouldBe 2
            spec.count.maximum shouldBe 2
        }

        "CR 115.1a: Balustrade Spy's enters-the-battlefield trigger targets a player, either seat" {
            val trigger = balustradeSpy.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            // Pointed at yourself it assembles Spy Combo; at the opponent it is a (bad) mill spell.
            // Narrowing the enumeration to one seat would delete a legal play (ADR-005).
            trigger.targetSpec shouldBe TargetSpec.TargetPlayer
        }

        "CR 702.8a: Spellstutter Sprite's flash is a timing class, not a keyword" {
            // "You may cast this spell any time you could cast an instant" *is* instant speed; there
            // is no Keyword.FLASH and nothing would read one.
            spellstutterSprite.timing shouldBe TimingClass.INSTANT_SPEED
            spellstutterSprite.characteristics.keywords shouldContainExactly persistentSetOf(Keyword.FLYING)
        }

        "CR 202.3 + CR 109.5: the Sprite's X is a dynamic bound over Faeries you control" {
            val trigger = spellstutterSprite.triggeredAbilities.single()
            val spec = trigger.targetSpec as TargetSpec.SpellOnStack
            val restriction = spec.restriction as SpellRestriction.OfManaValueAtMost
            // A *subtype* test, not a name test: Faerie Seer and Faerie Macabre both raise X.
            restriction.bound shouldBe
                ManaValueBound.PerMatching(
                    CountScope.BATTLEFIELD_YOU_CONTROL,
                    ObjectPredicate.HasSubtype(Subtype("Faerie")),
                )
        }

        "CR 201: all six are registered in the pool under their printed names" {
            listOf(
                "Guardian of the Guildpact",
                "Mask of Law and Grace",
                "Raze",
                "Ghostly Flicker",
                "Balustrade Spy",
                "Spellstutter Sprite",
            ).forEach { name ->
                MvpCards.definitions
                    .getValue(CardRef(name))
                    .characteristics.name shouldBe name
            }
        }
    })
