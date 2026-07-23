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
import dev.mtgplay.core.definition.ManaAbility
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
 * The GW-Bogles utility cards of the MVP pool that are not Auras or hexproof bodies (docs/decklists.md):
 * Utopia Sprawl (the Forest-enchanting ramp Aura, encoded here for its P6.2a triggered-mana-ability and
 * as-enters colour choice — distinct machinery from the P4.2 static Auras in Auras.kt), Malevolent Rumble
 * (library manipulation plus an Eldrazi Spawn token), and Ash Barrens (a colorless-fixing land with basic
 * landcycling). Every mechanism was built and fixture-proven in P6.2a, so each is a faithful oracle
 * translation onto published DSL primitives (ADR-003) — with one STOP-flagged gap noted on [ashBarrens].
 */

/** The number of top-of-library cards Malevolent Rumble reveals (CR 701.16). */
const val MALEVOLENT_RUMBLE_REVEAL: Int = 4

/**
 * The Eldrazi Spawn token (CR 111.4) Malevolent Rumble creates: a 0/1 colorless Eldrazi Spawn creature
 * with the mana ability "Sacrifice this token: Add {C}" — a [ManaAbility] whose activation cost is
 * sacrifice rather than tap ([ManaAbility.viaSacrifice], CR 605.1a). Being a mana ability it is carried on
 * the [TokenDefinition] directly (unlike Blood's non-mana activated ability, which the token type cannot
 * yet hold — see [bloodToken]).
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
            persistentListOf(ManaAbility(persistentListOf(ManaType.COLORLESS), viaSacrifice = true)),
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
 * Ash Barrens — Land. "{T}: Add {C}. Basic landcycling {1}." A colorless-fixing land (its intrinsic mana
 * ability adds `{C}`, CR 605.1a) that is *played*, not cast (CR 305.1) — hence a plain [CardDefinition],
 * never a [SpellDefinition]. Its basic landcycling is a hand-scoped activated ability (CR 113.6c, CR 602):
 * the composite cost `{1}` + discard-this-card ([AbilityCost.Mana] + [AbilityCost.DiscardSelf]),
 * functioning from [AbilityZoneScope.Hand].
 *
 * **Architect gap (STOP-flagged, P6.2b report).** The landcycling *effect* — "Search your library for a
 * basic land card, reveal it, put it into your hand, then shuffle" — has no P6.2a vocabulary: there is no
 * library-search effect primitive and no search decision request (the whole [dev.mtgplay.rules.decision]
 * hierarchy has none), and a search is a real player choice (which basic, or fail to find) that cannot be
 * deterministically approximated without a wrong result. The ability is declared (it is part of the card
 * and is legitimately enumerated when payable) but its effect fails loudly — per the architect ruling and
 * CONVENTIONS ("fail loudly; never silently approximate"). Fixing it needs a search primitive and a
 * search decision (an architect task).
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
                    effect = ResolutionEffect { _, _ -> ashBarrensLandcyclingUnsupported() },
                    zoneScope = AbilityZoneScope.Hand,
                ),
            )
    }

/**
 * Fails loudly for Ash Barrens' unsupported basic-landcycling search effect (CR 701.18). Split out so the
 * gap is a single greppable site and the per-card test pins exactly this failure. See [ashBarrens].
 */
private fun ashBarrensLandcyclingUnsupported(): Nothing =
    error(
        "P6.2b gap (architect): Ash Barrens' basic landcycling 'search your library for a basic land card, " +
            "reveal it, put it into your hand, then shuffle' (CR 701.18) has no P6.2a support — no library-" +
            "search effect primitive and no search decision request exist. Not encodable without an engine change.",
    )
