package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.LibraryReveal
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.definition.TriggeredManaAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.rules.effect.createToken
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The GW-Bogles utility cards of the MVP pool that are not static-effect Auras or hexproof bodies
 * (docs/decklists.md): the two ramp Auras — Utopia Sprawl (Forest-enchanting, with an as-enters colour
 * choice) and Wild Growth (any land, a printed additional {G}) — encoded here rather than in Auras.kt
 * because their whole grant is a *triggered mana ability* (CR 605.1b), distinct machinery from the P4.2
 * static Auras; the two library-manipulation spells Malevolent Rumble (reveal four, keep one permanent
 * card, plus an Eldrazi Spawn token) and Kruphix's Insight (reveal six, keep up to three enchantment
 * cards); and Ash Barrens (a colorless-fixing land with basic landcycling, whose [LibrarySearch] effect
 * P6.2c completed). Every mechanism is a published DSL primitive (ADR-003), so each is a faithful oracle
 * translation; no card action is gap-avoided.
 */

/** The number of top-of-library cards Malevolent Rumble reveals (CR 701.16). */
const val MALEVOLENT_RUMBLE_REVEAL: Int = 4

/**
 * The Eldrazi Spawn token (CR 111.4) Malevolent Rumble creates: a 0/1 colorless Eldrazi Spawn creature
 * with the mana ability "Sacrifice this token: Add {C}" — a [ManaAbility] whose activation cost is
 * sacrifice rather than tap ([ManaAbilityCost.SacrificeSelf], CR 605.1a). Being a mana ability it is carried on
 * the [TokenDefinition] directly and uses no stack (CR 605.3).
 *
 * This KDoc used to add "unlike Blood's non-mana activated ability, which the token type cannot yet
 * hold". That has been untrue since P6.2c completed [TokenDefinition.activatedAbilities]: Blood itself
 * carries "{1}, {T}, Discard a card, Sacrifice this token: Draw a card", and Gingerbread Cabin's Food
 * token carries "{2}, {T}, Sacrifice this token: You gain 3 life". The distinction that survives is
 * the CR 605.3 one only — a mana ability uses no stack, a non-mana activated ability does.
 */
val eldraziSpawnToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Eldrazi Spawn",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Eldrazi"), Subtype("Spawn")),
                powerToughness = PrintedPowerToughness(power = 0, toughness = 1),
            ),
        manaAbilities =
            persistentListOf(
                ManaAbility(
                    persistentListOf(ManaType.COLORLESS),
                    cost = persistentListOf(ManaAbilityCost.SacrificeSelf),
                ),
            ),
    )

/**
 * Utopia Sprawl — `{G}` Enchantment — Aura. "Enchant Forest. As this Aura enters, choose a color. Whenever
 * enchanted Forest is tapped for mana, its controller adds an additional one mana of the chosen color."
 * The pool's ramp Aura, encoded on the P6.2a triggered-mana-ability machinery (CR 605.1b) rather than the
 * P4.2 static-effect Auras (Auras.kt). It enchants a Forest ([TargetSpec.Enchantable]`(FOREST)`,
 * CR 303.4a); resolution is entering attached (CR 303.4f, CR 608.3); it chooses a colour as it enters
 * ([choosesColorAsItEnters], CR 614.12), stored on the entering object; and its
 * [TriggeredManaAbility.AddChosenColor]`(1)` fires inside the enchanted Forest's tap-for-mana resolution,
 * adding one mana of that chosen colour to the pool with no stack (CR 605.3).
 */
val utopiaSprawl: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Utopia Sprawl",
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(Subtype("Aura")),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.Enchantable(EnchantRestriction.FOREST)
        override val resolution = ResolutionEffect { state, _ -> state }
        override val choosesColorAsItEnters = true
        override val triggeredManaAbilities =
            persistentListOf<TriggeredManaAbility>(TriggeredManaAbility.AddChosenColor(1))
    }

/**
 * Malevolent Rumble — `{1}{G}` Sorcery. "Reveal the top four cards of your library. You may put a
 * permanent card from among them into your hand. Put the rest into your graveyard. Create a 0/1 colorless
 * Eldrazi Spawn creature token with 'Sacrifice this token: Add {C}.'" Two independent clauses:
 * - the token creation is the ordinary [ResolutionEffect] (it needs no mid-resolution choice), creating
 *   the [eldraziSpawnToken] under the caster (CR 707.2);
 * - the reveal-four is a [LibraryReveal]`(4, PERMANENT_CARD)` (CR 701.16): the engine runs it after the
 *   ordinary effect, revealing the top four, pausing for the up-to-one permanent-card keep selection, and
 *   putting the rest into the graveyard.
 */
val malevolentRumble: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Malevolent Rumble",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context -> createToken(state, context.controller, eldraziSpawnToken) }
        override val libraryReveal = LibraryReveal(MALEVOLENT_RUMBLE_REVEAL, RevealedCardFilter.PERMANENT_CARD)
    }

/**
 * Wild Growth — `{G}` Enchantment — Aura. "Enchant land. Whenever enchanted land is tapped for mana,
 * its controller adds an additional `{G}`." The list's second ramp Aura (P6.3), sharing Utopia Sprawl's
 * triggered-mana-ability machinery (CR 605.1b) and differing from it in exactly two printed ways:
 * - it enchants **any** land ([EnchantRestriction.LAND], CR 303.4a), not only a Forest, so it can sit
 *   on a Plains;
 * - the additional mana is the printed `{G}` ([TriggeredManaAbility.AddFixedMana]), so — unlike Utopia
 *   Sprawl — it makes **no** as-it-enters colour choice (CR 614.12) and carries no `chosenColor`.
 *
 * Resolution is entering attached (CR 303.4f, CR 608.3). The bonus resolves inside the enchanted land's
 * tap-for-mana resolution, adding `{G}` to the pool with no stack (CR 605.3).
 */
val wildGrowth: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Wild Growth",
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(Subtype("Aura")),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.Enchantable(EnchantRestriction.LAND)
        override val resolution = ResolutionEffect { state, _ -> state }
        override val triggeredManaAbilities =
            persistentListOf<TriggeredManaAbility>(TriggeredManaAbility.AddFixedMana(ManaType.GREEN, 1))
    }

/** The number of top-of-library cards Kruphix's Insight reveals (CR 701.16). */
const val KRUPHIXS_INSIGHT_REVEAL: Int = 6

/** How many revealed enchantment cards Kruphix's Insight may put into the hand — "up to three". */
const val KRUPHIXS_INSIGHT_KEEP: Int = 3

/**
 * Kruphix's Insight — `{2}{G}` Sorcery. "Reveal the top six cards of your library. Put up to three
 * enchantment cards from among them into your hand and the rest of the revealed cards into your
 * graveyard." The list's card-advantage engine (P6.3), and the second client of the [LibraryReveal]
 * primitive after [malevolentRumble] — the extension it required is the *keep allowance*: this is
 * `LibraryReveal(6, ENCHANTMENT_CARD, toHandCount = 3)` against the Rumble's implicit one.
 *
 * Two consequences worth stating, both faithful to the oracle text rather than to the analogue:
 * - the filter is **enchantment card**, not permanent card, so a revealed Forest or Gladecover Scout
 *   is *not* keepable and goes to the graveyard with the rest (CR 303.1);
 * - "the rest of the revealed cards" is every revealed card not put into the hand — including
 *   keepable enchantments the controller chose to leave (keeping fewer than three is legal).
 *
 * The whole card is the reveal clause, so its ordinary [ResolutionEffect] is a no-op; `mtg-rules`
 * reveals, gathers the keeps, and distributes.
 */
val kruphixsInsight: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Kruphix's Insight",
                manaCost = ManaCost.parse("{2}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val libraryReveal =
            LibraryReveal(
                count = KRUPHIXS_INSIGHT_REVEAL,
                toHand = RevealedCardFilter.ENCHANTMENT_CARD,
                toHandCount = KRUPHIXS_INSIGHT_KEEP,
            )
    }

/**
 * Ash Barrens — Land. "{T}: Add {C}. Basic landcycling {1}." A colorless-fixing land (its intrinsic mana
 * ability adds `{C}`, CR 605.1a) that is *played*, not cast (CR 305.1) — hence a plain [CardDefinition],
 * never a [SpellDefinition]. Its basic landcycling is a hand-scoped activated ability (CR 113.6c, CR 602):
 * the composite cost `{1}` + discard-this-card ([AbilityCost.Mana] + [AbilityCost.DiscardSelf]),
 * functioning from [AbilityZoneScope.Hand], whose effect is a [LibrarySearch] for a basic land card
 * (CR 701.18). `mtg-rules` surfaces the find-one choice (a basic land, or fail to find), reveals the found
 * card, puts it into the hand, and shuffles the library through the match PRNG (ADR-006). The ordinary
 * [ActivatedAbility.effect] is a no-op — the search is the whole of the resolution.
 */
val ashBarrens: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Ash Barrens",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.COLORLESS)))
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.Mana(ManaCost.parse("{1}")), AbilityCost.DiscardSelf),
                    effect = ResolutionEffect { state, _ -> state },
                    zoneScope = AbilityZoneScope.Hand,
                    librarySearch = LibrarySearch(LibrarySearchFilter.BASIC_LAND_CARD),
                ),
            )
    }
