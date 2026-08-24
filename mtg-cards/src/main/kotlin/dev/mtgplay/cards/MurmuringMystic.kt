package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.createToken
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The 1/1 blue Bird Illusion creature token with flying (CR 111.4) that Murmuring Mystic creates. Like
 * [warriorToken], "blue" is flavour the MVP models nowhere — colour is derived from a mana cost and a
 * token has none (the CR 204 colour indicator is unmodeled until a card cares about a token's colour)
 * — so the token is left colourless-by-model without loss. Flying (CR 702.9) is real and printed: the
 * block-legality seam reads it, and a wall of Birds is the whole reason the card is played.
 */
val birdIllusionToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Bird Illusion",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Bird"), Subtype("Illusion")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
                keywords = persistentSetOf(Keyword.FLYING),
            ),
    )

/**
 * Murmuring Mystic — `{3}{U}` Creature — Human Wizard, a 1/5. "Whenever you cast an instant or sorcery
 * spell, create a 1/1 blue Bird Illusion creature token with flying." The body enters the battlefield
 * with no resolution instructions (CR 608.3); the printed text is one triggered ability (CR 603.2) on
 * the cast-trigger seam.
 *
 * Its filter is exactly [guttersnipe]'s — `SpellCast(spellTypes = {INSTANT, SORCERY}, controlledByYou
 * = true)` (CR 603.2e) — and the contrast between the two is instructive: the same declared condition
 * pays off in damage for one card and in a token (CR 707.2) for the other, with no rules code in
 * common beyond the detector. The trigger fires as the spell finishes casting (CR 601.2i), so the Bird
 * is on the battlefield **before** the spell that made it resolves.
 *
 * The token is [birdIllusionToken]; [createToken] registers its definition in the state on first use,
 * so the Bird's flying reads through the same `definitions[card]` path a real card's does.
 */
val murmuringMystic: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Murmuring Mystic",
                manaCost = ManaCost.parse("{3}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Human"), Subtype("Wizard")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 5),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition =
                        TriggerCondition.SpellCast(
                            spellTypes = persistentSetOf(CardType.INSTANT, CardType.SORCERY),
                            controlledByYou = true,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            createToken(state, context.controller, birdIllusionToken)
                        },
                ),
            )
    }
