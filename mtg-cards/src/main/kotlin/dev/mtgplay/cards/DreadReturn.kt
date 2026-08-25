package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.SacrificeRequirement
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.returnFromGraveyardToBattlefield
import kotlinx.collections.immutable.persistentSetOf

/*
 * Dread Return — the gauntlet's reanimation spell, and the card
 * docs/design/graveyard-targeting.md §6 recorded as blocked on one narrow thing: its flashback cost is
 * "Sacrifice three creatures", and [SacrificeRequirement] predicated on a printed *subtype*, so it could
 * say "three Mountains" and not "three creatures".
 *
 * `W8-D` closed that by giving [SacrificeRequirement] the [SacrificeFilter] the cast-side and
 * activation-side sacrifice costs were already written against, rather than bolting a second axis onto
 * it — so the three places a sacrifice cost can attach now share one answer to "which permanents may pay
 * this?". The other half the card needed was [returnFromGraveyardToBattlefield], the **untapped**
 * sibling of the tapped return Sneaky Snacker uses.
 */

/** How many creatures Dread Return's flashback cost sacrifices (CR 702.34c). */
const val DREAD_RETURN_FLASHBACK_SACRIFICE: Int = 3

/**
 * Dread Return — `{2}{B}{B}` Sorcery. "Return target creature card from your graveyard to the
 * battlefield. Flashback—Sacrifice three creatures."
 *
 * **The flashback cost is entirely non-mana**, which is the shape Lava Dart established and this card
 * takes to its limit: [CastingPermission.Flashback] carries `{0}` mana plus a
 * [SacrificeRequirement], so a seat with three creatures and no mana at all may cast it from the
 * graveyard. CR 702.34c is explicit that a flashback cost may include more than mana, and CR 118.9 is
 * what makes it *replace* the printed `{2}{B}{B}` rather than add to it.
 *
 * **Targets are chosen before the cost is paid, and that is observable here.** CR 601.2c puts target
 * choice ahead of CR 601.2h payment, so the three creatures sacrificed to flash this back are **not**
 * candidates for its own target: they reach the graveyard after the target is locked in. A seat wanting
 * to reanimate one of them must cast Dread Return a second time. Encoding the sacrifice as anything
 * resolution-side would have quietly granted a line the rules forbid (ADR-005).
 *
 * **"Then exile it" needs nothing here.** CR 702.34e is a property of the flashback *cast*, not of the
 * card, and the engine carries it on [CastingPermission.exilesOnLeaveStack] — which
 * [CastingPermission.Flashback] sets — so a flashed-back Dread Return is exiled as it leaves the stack
 * and cannot be flashed back again. Cast the ordinary way it goes to the graveyard and stays flashable.
 *
 * **The target is a card in the controller's own graveyard** ([GraveyardScope.YOURS]) restricted to
 * creature cards ([GraveyardCardRestriction.CREATURE], the restriction Blood Fountain added). With no
 * creature card there the spell is castable and simply does nothing on resolution: a sorcery with no
 * legal target *cannot be cast at all* (CR 601.2c), so the engine does not enumerate it — which is the
 * correct answer and not a silent omission.
 *
 * The returned permanent arrives **untapped** and summoning sick, under its owner's control (CR 110.5a,
 * CR 302.6), and its own enters-the-battlefield triggers fire (CR 603.6a).
 */
val dreadReturn: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Dread Return",
                manaCost = ManaCost.parse("{2}{B}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec =
            TargetSpec.CardInGraveyard(
                restriction = GraveyardCardRestriction.CREATURE,
                scope = GraveyardScope.YOURS,
            )
        override val resolution =
            ResolutionEffect { state, context ->
                val target =
                    context.targets.singleOrNull() as? Target.CardInGraveyard
                        ?: error(
                            "CR 115.1: Dread Return targets exactly one creature card in a graveyard, " +
                                "got ${context.targets}",
                        )
                returnFromGraveyardToBattlefield(state, target.id)
            }
        override val castingPermissions =
            listOf(
                CastingPermission.Flashback(
                    cost = ManaCost.parse("{0}"),
                    sacrifice =
                        SacrificeRequirement(
                            count = DREAD_RETURN_FLASHBACK_SACRIFICE,
                            filter = SacrificeFilter(persistentSetOf(CardType.CREATURE)),
                        ),
                ),
            )
    }
