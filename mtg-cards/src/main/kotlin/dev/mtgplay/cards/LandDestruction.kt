package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.destroy
import kotlinx.collections.immutable.persistentSetOf

/*
 * Land destruction (CR 305), the gauntlet's Spy Combo and Jund Wildfire sideboard plan — built on
 * [PermanentRestriction.LAND], the target noun this packet adds.
 *
 * Only [raze] is here. Cleansing Wildfire is the other half of the pair and is **deliberately
 * absent**: its oracle text is "Destroy target land. **Its controller** may search their library for a
 * basic land card, put it onto the battlefield tapped, then shuffle. / Draw a card." The destroy and
 * the draw are both trivial; the search is not, and it is not this packet's to build. `LibrarySearch`
 * derives its single searcher from `entry.resolutionController` (`engine/LibrarySearch.kt`), so the
 * clause can only ever search the *resolving spell's* controller's library — here the searcher is the
 * destroyed land's controller, who is normally the opponent. `FW-NONCTRLDEC` landed that shape for
 * discards (`EachOpponentDiscards`) and explicitly not for a search. Encoding it with the caster
 * searching would be a different card, and a strictly better one for the caster (PLAN.md §7).
 */

/**
 * Raze — `{R}` Sorcery. "As an additional cost to cast this spell, sacrifice a land. / Destroy target
 * land."
 *
 * Two rules stages, and the order between them is the whole card. CR 601.2b announces the additional
 * cost and CR 601.2c chooses the target, but neither is *paid* until CR 601.2h — so the land being
 * sacrificed is **still on the battlefield** while the target is chosen, and is therefore itself a
 * legal target. "Raze targeting the land it sacrifices" is a legal (if pointless) play, and the
 * engine offers it because CR 601.2c says it must; the two are different instances and the CR 601.2c
 * same-object rule does not span a cost and a target.
 *
 * The additional cost is [AdditionalCost.Sacrifice] over `{LAND}` — the shape Crop Rotation already
 * uses — which means the enumeration reserves the sacrificed land from funding the `{R}` only where it
 * must (docs/design/mana-payment.md §2.2). Tapping a Mountain for the `{R}` and *then* sacrificing
 * that same Mountain is legal and enumerated, because the mana is paid before the sacrifice.
 *
 * A one-mana Stone Rain that costs a land is a losing trade on rate and a winning one on tempo, which
 * is why it appears in the gauntlet only where a land is already a resource to be spent.
 */
val raze: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Raze",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED

        // CR 601.2b: the additional cost, announced with the spell and paid at CR 601.2h — after the
        // target is chosen, which is what keeps the sacrificed land targetable by the spell paying for it.
        override val additionalCost =
            AdditionalCost.Sacrifice(count = 1, filter = SacrificeFilter(persistentSetOf(CardType.LAND)))

        // CR 115.1b with CR 305: any land, whoever controls it — including the caster's own.
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.LAND)
        override val resolution =
            ResolutionEffect { state, context -> destroy(state, targetedPermanent(context.targets, "Raze")) }
    }
