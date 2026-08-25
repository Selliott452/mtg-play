package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Quality
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The pool's two protection cards (CR 702.16) — the first cards to *print* or *grant* protection, and
 * therefore the first to make the `FW-PROTECT` substrate reachable at all.
 *
 * `FW-PROTECT` shipped every seam protection needs — [Quality], `effectiveProtections`,
 * `hasProtectionFrom`, the DEBT checks in `Targets.kt`, `CombatActions.kt`, `StateBasedActions.kt` and
 * `effect/DealDamage.kt` — and **zero cards**, so none of it had ever run against a real board. These
 * two are why `P-ABILSOURCE` had to land first: `Targets.kt` called `error()` on any protected object
 * reached from an *ability* enumeration, because the ability-targeting sites passed no prospective
 * source (CR 702.16b, docs/design/protection.md §2.4). Either of these cards on the battlefield across
 * from a targeted ability made the engine throw. They are the regression test for that, in card form.
 *
 * **The two halves of protection's representation, one card each.** Between them they exercise
 * [Quality] in full, which is the reason they belong in one file:
 * - [maskOfLawAndGrace] is protection **granted**, from *colours*, and from **two** qualities at once
 *   (CR 702.16g) — the ordinary shape, read through the CR 613 layer-6 seam like any Aura grant.
 * - [guardianOfTheGuildpact] is protection **printed**, from a quality that is **not a colour**.
 *   CR 702.16a: the quality "is usually a color … but can be any characteristic value or information."
 *   A `Color`-shaped protection field would have carried Mask and silently failed Guardian, which is
 *   the disagreement docs/design/protection.md §0 records against the sibling project's brief.
 *
 * Neither card targets, and neither has an ability. Every rules consequence they have is a *static*
 * one the engine already implements; the cards are pure declaration.
 */

/**
 * Guardian of the Guildpact — `{3}{W}` Creature — Spirit, 2/3. "Protection from monocolored"
 *
 * The card that makes CR 702.16a's "any characteristic value" clause load-bearing. *Monocolored* is a
 * derived characteristic — exactly one colour (CR 105.4 makes colourless the *absence* of colour, not
 * a sixth one) — so [Quality.Monocolored] is a member in its own right rather than a `Color`.
 *
 * The consequence in play is famous and is reproduced faithfully rather than tidied up: Guardian is
 * untouchable by nearly every removal spell in Pauper and completely vulnerable to a *multicolored* or
 * *colourless* one. `sourceHasQuality` reads `colors.size == 1`, so a Terminate (two colours) and a
 * colourless artifact's ability both get through, and every mono-coloured Bolt, Doom Blade and Aura
 * does not — for **targeting** (CR 702.16b), **enchanting** (CR 702.16c), **blocking** (CR 702.16f)
 * and **damage** (CR 702.16e) alike.
 *
 * It prints no ability at all, which is what makes it the minimal demonstration: everything it does
 * comes from [PrintedCharacteristics.protections] passing through the layer engine untouched.
 */
val guardianOfTheGuildpact: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Guardian of the Guildpact",
                manaCost = ManaCost.parse("{3}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Spirit")),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 3),
                // CR 702.16a: the quality is a characteristic value, not a colour (§4 of the design note).
                protections = persistentSetOf(Quality.Monocolored),
            )

        // CR 302.1/601.3a: a creature spell is cast at sorcery speed and targets nothing.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // CR 608.3: a permanent spell's resolution is the engine's move onto the battlefield.
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/**
 * Mask of Law and Grace — `{W}` Enchantment — Aura. "Enchant creature / Enchanted creature has
 * protection from black and from red."
 *
 * **Two qualities from one ability** (CR 702.16g: "Multiple instances of protection from the same
 * quality on the same permanent … are redundant", and by the same token separate qualities are
 * separate protections). The grant is a set, so the two are independent: a black spell is barred by
 * one and a red spell by the other, while a black-red *multicoloured* spell is barred by both and a
 * white one by neither.
 *
 * **The Aura does not remove itself.** CR 704.5m puts an Aura into its owner's graveyard when the
 * permanent it enchants has protection from it, and this Aura is *white* while granting protection
 * from black and red — so the CR 613 layer-6 grant it applies never makes its own attachment illegal.
 * That self-consistency is a property of the printed card, not of the engine, and it is the reason
 * this card is safe to grant onto the creature it is attached to; an Aura granting protection from
 * *white* would fall off the instant it resolved, correctly and by the same state-based action.
 *
 * Sideboard card for GW Bogles, where it is a protective trick as much as a grant: giving a Bogle
 * protection from red blanks a Lightning Bolt already on the stack (CR 608.2b) *and* the blocker
 * beneath it (CR 702.16f) *and* the combat damage (CR 702.16e), all from one line of text.
 */
val maskOfLawAndGrace: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Mask of Law and Grace",
                manaCost = ManaCost.parse("{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(Subtype("Aura")),
                powerToughness = null,
            )

        // CR 303/601.3a: an Aura is an enchantment spell cast at sorcery speed…
        override val timing = TimingClass.SORCERY_SPEED

        // …that targets what it will enchant while on the stack (CR 303.4a, CR 601.2c). "Enchant
        // creature" is unrestricted by control: this Aura may legally be put on an opponent's creature,
        // which is a real (if rare) play and not something the card's own text forbids.
        override val targetSpec = TargetSpec.Enchantable(EnchantRestriction.CREATURE)
        override val resolution = ResolutionEffect { state, _ -> state }

        // CR 613 layer 6: the grant, classified by the layer engine exactly as a keyword grant is.
        override val staticContinuousEffects =
            persistentListOf(
                StaticContinuousEffect(
                    grantedProtections =
                        persistentSetOf(
                            Quality.OfColor(Color.BLACK),
                            Quality.OfColor(Color.RED),
                        ),
                ),
            )
    }
