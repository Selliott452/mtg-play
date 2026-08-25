package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.SacrificeRequirement
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of Dread Return (CR 201–205), the card docs/design/graveyard-targeting.md §6 recorded
 * as blocked on one thing: a flashback cost naming a **card type** rather than a printed subtype.
 */
class DreadReturnSpec :
    StringSpec({

        "CR 307.1: Dread Return is a {2}{B}{B} sorcery" {
            with(dreadReturn.characteristics) {
                name shouldBe "Dread Return"
                manaCost shouldBe ManaCost.parse("{2}{B}{B}")
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
                powerToughness.shouldBeNull()
            }
            dreadReturn.timing shouldBe TimingClass.SORCERY_SPEED
        }

        "CR 115.1: Dread Return targets a creature card in *your* graveyard" {
            dreadReturn.targetSpec shouldBe
                TargetSpec.CardInGraveyard(
                    restriction = GraveyardCardRestriction.CREATURE,
                    scope = GraveyardScope.YOURS,
                )
        }

        "CR 702.34c: Dread Return's flashback cost is {0} plus sacrificing three creatures" {
            val flashback = dreadReturn.castingPermissions.single()
            check(flashback is CastingPermission.Flashback)
            // The whole cost is non-mana: a seat with three creatures and no lands may still flash it back.
            flashback.cost shouldBe ManaCost.parse("{0}")
            flashback.sacrifice shouldBe
                SacrificeRequirement(
                    count = DREAD_RETURN_FLASHBACK_SACRIFICE,
                    filter = SacrificeFilter(persistentSetOf(CardType.CREATURE)),
                )
            DREAD_RETURN_FLASHBACK_SACRIFICE shouldBe 3
            // A card type, not a subtype — the axis the requirement could not express before `W8-D`.
            val filter = flashback.sacrifice?.filter
            filter?.subtype.shouldBeNull()
            filter?.anyOfCardTypes shouldBe persistentSetOf(CardType.CREATURE)
        }

        "CR 702.34e: a flashed-back Dread Return is exiled as it leaves the stack, and casts from the graveyard" {
            val flashback = dreadReturn.castingPermissions.single()
            flashback.source shouldBe CastSource.GRAVEYARD
            flashback.exilesOnLeaveStack shouldBe true
            flashback.offeredAtPriority shouldBe true
            // Nothing gates the permission beyond the card being in the graveyard.
            flashback.condition.shouldBeNull()
            flashback.additionalExileCount shouldBe 0
        }
    })
