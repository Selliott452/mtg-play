package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.createToken
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Embalm (CR 702.90), and the token-identity model it forced (`FW-COPYTOKEN`).
 *
 * Sacred Cat is the gauntlet's only embalm card and it is a one-mana 1/1 — the kind of card an engine
 * is tempted to wave through. It could not be encoded for three waves, and the reason was never the
 * keyword: `AbilityZoneScope.Graveyard`, `AbilityCost.ExileSelfFromGraveyard` and sorcery-timed
 * activated abilities had all landed by wave 8, so three of embalm's four requirements were already
 * printed vocabulary. The fourth was **a token that copies a card**, and it collided with two things
 * the engine had built on the assumption that tokens and cards never share a name:
 *
 * 1. **Token identity was keyed on the name.** A [TokenDefinition] was registered under
 *    `CardRef(its name)`, and "this object is a token" was `definitions[card] is TokenDefinition`. An
 *    embalm token named "Sacred Cat" therefore landed on the registry entry the *real card* occupies,
 *    and `createToken`'s register-if-absent silently gave the token the card's definition — castable,
 *    embalmable again, and invisible to the CR 704.5d token-ceases state-based action. Fixed by giving
 *    a token its own key space (`CardRef.token`) with a registry invariant that refuses the overlap.
 * 2. **Colour was derived from the mana cost.** The token is "a white Zombie Cat with **no mana
 *    cost**", and `PrintedCharacteristics.colors` was `manaCost?.colors` with one CDA exception for
 *    devoid (CR 702.114a). White-and-costless had no representation, and calling the token colourless
 *    is not cosmetic: it decides whether protection from white stops it, whether a colour-based
 *    prevention shield covers it, and whether a Red Elemental Blast may point at it. Fixed by
 *    `PrintedCharacteristics.definedColors`, which only a token sets — CR 111.4 says a token's
 *    characteristics are *defined by the effect that creates it*, not printed.
 *
 * Neither of those is about embalm. Both are load-bearing for every copy effect the gauntlet still
 * has ahead of it, which is why this card was worth the framework rather than the other way round.
 */

/** Sacred Cat's printed characteristics, named so the embalm token can be *derived* from them. */
private val SACRED_CAT_PRINTED: PrintedCharacteristics =
    PrintedCharacteristics(
        name = "Sacred Cat",
        manaCost = ManaCost.parse("{W}"),
        supertypes = persistentSetOf(),
        cardTypes = persistentSetOf(CardType.CREATURE),
        subtypes = persistentSetOf(Subtype("Cat")),
        powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
        keywords = persistentSetOf(Keyword.LIFELINK),
    )

/**
 * Sacred Cat — `{W}` Creature — Cat, a 1/1 with lifelink and *"Embalm `{W}`"*.
 *
 * A one-mana lifelinker that comes back once, which is a card about **card advantage in a body**
 * rather than about the body: the 1/1 blocks, gains a life, dies, and is still a card in your graveyard
 * that turns into a second 1/1 for one more mana. Against a deck trying to trade one-for-one it is two
 * cards in one slot; against a sweeper it is the half that survives.
 *
 * **Embalm is spelled out, not abbreviated** (CR 702.90a): the reminder text on the card *is* the
 * ability, and the engine composes it from the four primitives it names —
 * [AbilityZoneScope.Graveyard] (CR 113.6b, the ability functions from a graveyard),
 * [AbilityCost.ExileSelfFromGraveyard] (the card exiles itself as a **cost**, so it happens on
 * activation and is not undone if the ability is countered), [TimingClass.SORCERY_SPEED] ("Embalm only
 * as a sorcery", CR 602.5d), and [createToken].
 *
 * **Exiling as a cost is what makes embalm once-per-card**, with no `oncePerTurn` restriction needed to
 * say so — the same shape Bramble Wurm's graveyard ability already uses. And because the *token* is not
 * a card and never reaches a graveyard as one (CR 704.5d removes it from wherever it lands), the token
 * cannot be embalmed again: it carries [sacredCatEmbalmToken]'s definition, which prints no ability at
 * all.
 *
 * **The two objects are not interchangeable, and an agent can see all three differences.** The token is
 * white by definition rather than by cost, it is a **Zombie** Cat rather than a Cat, and it has no mana
 * cost — so its mana value is 0 (CR 202.3b), which matters to every "mana value 2 or less" line in the
 * format. What it keeps is the printed 1/1 body and lifelink, because CR 707.2 copies the *printed*
 * values and embalm's "except" clause changes only what it names.
 */
val sacredCat: SpellDefinition =
    object : SpellDefinition {
        override val characteristics = SACRED_CAT_PRINTED

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // CR 608.3: a permanent spell with no instructions of its own resolves by entering.
        override val resolution = ResolutionEffect { state, _ -> state }

        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    // CR 702.90a: "{W}, Exile this card from your graveyard: Create a token that's a
                    // copy of it, except …". The exile is a cost, so it is paid on activation.
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{W}")),
                            AbilityCost.ExileSelfFromGraveyard,
                        ),
                    // CR 113.6b: an ability of a card in a graveyard functions there only if it says so,
                    // and embalm says so by naming the zone in its own cost.
                    zoneScope = AbilityZoneScope.Graveyard,
                    // "Embalm only as a sorcery" (CR 602.5d) — the ability, not the card, is restricted.
                    timing = TimingClass.SORCERY_SPEED,
                    effect =
                        ResolutionEffect { state, context ->
                            createToken(state, context.controller, sacredCatEmbalmToken)
                        },
                ),
            )
    }

/**
 * The embalm token (CR 702.90a, CR 707.2): *"a copy of it, except it's a white Zombie Cat with no mana
 * cost"*.
 *
 * **Derived from [SACRED_CAT_PRINTED] rather than retyped**, and that is the difference between
 * modelling CR 707.2 and approximating it. A copy takes the copiable values of the original and then
 * applies exactly the modifications the effect names; writing the result out by hand would give the
 * same characteristics today and would silently stop agreeing the moment Sacred Cat's printed line was
 * corrected — the token would keep the old body with nothing to say it had drifted. So the `copy` below
 * changes precisely the three things the printed text changes and nothing else, and the 1/1 body, the
 * name and lifelink come across because they are not named.
 *
 * The three modifications, each one a rules fact rather than a flavour note:
 * - **white** — via `definedColors`, because the token has no mana cost to derive a colour from
 *   (CR 111.4: a token's characteristics are defined by the effect that creates it);
 * - **Zombie Cat** — "a Zombie **in addition to** its other types" (CR 702.90a), so Cat is kept and
 *   Zombie added, exactly as CR 205.1b's union works for a layer-4 type change;
 * - **no mana cost** — which makes its mana value 0 (CR 202.3b), not 1.
 *
 * It prints **no abilities of its own**, which is what stops embalm from recurring: the token is not a
 * card, has no embalm ability, and ceases to exist the moment it leaves the battlefield (CR 704.5d).
 */
val sacredCatEmbalmToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            SACRED_CAT_PRINTED.copy(
                manaCost = null,
                definedColors = persistentSetOf(Color.WHITE),
                subtypes = SACRED_CAT_PRINTED.subtypes.adding(Subtype("Zombie")),
            ),
    )
