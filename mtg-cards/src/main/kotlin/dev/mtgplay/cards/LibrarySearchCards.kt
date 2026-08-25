package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchDestination
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.effect.gainLife
import dev.mtgplay.rules.effect.shuffleIntoOwnersLibrary
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's remaining library-search cards, plus the one card that shuffles itself back in
 * (`P-SEARCH` / `FW-SHUFFLEIN`, docs/design/library-search.md).
 *
 * [generousEnt] is the second typecycler after Lórien Revealed and the first to name a land type other
 * than Island; [cropRotation] is the first **spell** whose resolution is a search at all, which is what
 * made the CR 701.18 clause move onto the [dev.mtgplay.core.definition.ResolutionClauses] carrier; and
 * [lembas] is the first card to move a card from a graveyard back into a library.
 *
 * Two siblings from the same brief are deliberately absent rather than approximated — see the packet
 * report, and §6 of the design note, for exactly what each needs:
 * - **Land Grant** ("If you have no land cards in hand, you may reveal your hand rather than pay this
 *   spell's mana cost") is an alternative cost that is *gated on a hidden-zone condition* and whose
 *   payment is *revealing your hand*. [dev.mtgplay.core.definition.CastingPermission.AlternativeCost]
 *   carries mana plus an optional sacrifice and nothing else, and nothing anywhere conditions a casting
 *   permission on the state. Its search half is one line of this file; shipping only that would be a
 *   two-mana Lay of the Land wearing Land Grant's name, which is the plausible-looking wrong card
 *   PLAN.md §7 forbids.
 * - **Troll of Khazad-dûm** ("This creature can't be blocked except by three or more creatures") is a
 *   constraint over the whole block declaration, and `DeclareBlockers` enumerates blocks pairwise;
 *   [dev.mtgplay.core.card.Evasion] has one member and it is a per-blocker predicate. Its swampcycling
 *   is exactly [LibrarySearchFilter.SWAMP_CARD], which this packet publishes, so the card is one
 *   framework away and no primitives away.
 */

/**
 * A permanent spell's resolution (CR 608.3): the engine puts the permanent onto the battlefield, so the
 * definition's own resolution has nothing left to do. The file-private convention every permanent-card
 * file in `mtg-cards` uses.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/** How deep Lembas scries as it enters (CR 701.17a). */
const val LEMBAS_SCRY: Int = 1

/** What Lembas draws after its scry (CR 120.1). */
const val LEMBAS_DRAW: Int = 1

/** The life Lembas' sacrifice ability gains (CR 120.1) — the Food ability's three. */
const val LEMBAS_LIFE: Int = 3

/** What a Lembas shuffle-in trigger without its CR 603.10 graveyard object fails loudly with. */
private const val LEMBAS_NO_SUBJECT: String =
    "CR 603.10: Lembas' shuffle-in trigger requires the graveyard object it carries"

/** Generous Ent's printed power and toughness (CR 208). */
private const val GENEROUS_ENT_POWER: Int = 5

/** Generous Ent's printed toughness (CR 208). */
private const val GENEROUS_ENT_TOUGHNESS: Int = 7

/**
 * Generous Ent — `{5}{G}` Creature — Treefolk, a 5/7. "Reach. When this creature enters, create a Food
 * token. Forestcycling {1}."
 *
 * A six-drop that is really a one-mana Forest, which is why three gauntlet lists run it as a land-slot
 * filler. Three printed abilities, each already-published vocabulary meeting one new filter:
 * - **Reach** (CR 702.17) is [Keyword.REACH] on the printed characteristics, blocking-side only.
 * - The **enters** trigger (CR 603.6a) creates the same [foodToken] Gingerbread Cabin makes — a
 *   `TokenDefinition` with its own "{2}, {T}, Sacrifice this token: You gain 3 life" activated ability,
 *   shared rather than re-declared.
 * - **Forestcycling {1}** is typecycling (CR 702.29f, CR 702.28b): a hand-scoped activated ability
 *   ([AbilityZoneScope.Hand], CR 113.6c) costing [AbilityCost.Mana]`({1})` + [AbilityCost.DiscardSelf],
 *   whose [LibrarySearch] finds a **Forest card** and reveals it into the hand. Typecycling names the
 *   land *subtype*, never the basic land, so a nonbasic land with the Forest type — Gingerbread Cabin
 *   is one, and shares a deck with this card — is an equally legal find. That is
 *   [LibrarySearchFilter.FOREST_CARD], not [LibrarySearchFilter.BASIC_LAND_CARD].
 *
 * The ordinary [ActivatedAbility.effect] of the cycling ability is a no-op: the search is the whole
 * resolution, exactly as it is for Ash Barrens and Lórien Revealed.
 */
val generousEnt: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Generous Ent",
                manaCost = ManaCost.parse("{5}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Treefolk")),
                powerToughness =
                    PrintedPowerToughness(power = GENEROUS_ENT_POWER, toughness = GENEROUS_ENT_TOUGHNESS),
                keywords = persistentSetOf(Keyword.REACH),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = ResolutionEffect { state, context -> createToken(state, context.controller, foodToken) },
                ),
            )
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.Mana(ManaCost.parse("{1}")), AbilityCost.DiscardSelf),
                    effect = ResolutionEffect { state, _ -> state },
                    zoneScope = AbilityZoneScope.Hand,
                    librarySearch = LibrarySearch(LibrarySearchFilter.FOREST_CARD),
                ),
            )
    }

/**
 * Crop Rotation — `{G}` Instant. "As an additional cost to cast this spell, sacrifice a land. Search
 * your library for a land card, put that card onto the battlefield, then shuffle."
 *
 * **The first spell in the pool whose resolution is a library search**, and the card that forced the
 * CR 701.18 clause off [ActivatedAbility] onto the shared carrier: while `librarySearch` was a field of
 * an activated ability, a *sorcery or instant* that searched could not be declared at all
 * (docs/design/library-search.md §2).
 *
 * Both halves are published vocabulary meeting one new destination:
 * - the additional cost is [AdditionalCost.Sacrifice]`(1, land)` (CR 601.2b), paid at CR 601.2h from an
 *   enumerated selection, so the land is gone before the spell resolves — and, being a cost, cannot be
 *   responded to;
 * - the search is [LibrarySearchFilter.LAND_CARD] — the widest filter, an artifact land and an Urza
 *   land are both legal finds — to [LibrarySearchDestination.BATTLEFIELD].
 *
 * **Untapped, and that is the destination's default rather than a special case.** The card does not say
 * "tapped", so the found land enters by the CR 110.5a default — but its *own* CR 614.1c clause still
 * applies, so a Crop Rotation that finds a Bridge land or Gingerbread Cabin gets exactly what that card
 * says. An effect that moves a permanent does not overrule a replacement on the permanent moved.
 *
 * No reveal: the battlefield is public (CR 400.2) and the card prints none.
 */
val cropRotation: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Crop Rotation",
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val additionalCost =
            AdditionalCost.Sacrifice(count = 1, filter = SacrificeFilter(persistentSetOf(CardType.LAND)))
        override val librarySearch =
            LibrarySearch(
                find = LibrarySearchFilter.LAND_CARD,
                destination = LibrarySearchDestination.BATTLEFIELD,
            )
    }

/**
 * Lembas — `{2}` Artifact — Food. "When this artifact enters, scry 1, then draw a card. {2}, {T},
 * Sacrifice this artifact: You gain 3 life. When this artifact is put into a graveyard from the
 * battlefield, its owner shuffles it into their library."
 *
 * A Food that replaces itself and then never runs out — the third line is why a prior packet encoded
 * the first two and dropped the card.
 *
 * - The **enters** trigger (CR 603.6a) carries a [LibraryLook] clause: `Scry(1)` with `thenDraw = 1`
 *   (CR 701.17a then CR 120.1). Carried by the *trigger* rather than by the spell's resolution, which
 *   is what `FW-CLAUSEHOOK` made possible — the artifact's entry is a trigger, not the spell's
 *   resolution, and Faerie Seer is the precedent.
 * - The **sacrifice** ability is the standard Food ability, cost [AbilityCost.Mana]`({2})` +
 *   [AbilityCost.TapSelf] + [AbilityCost.SacrificeSelf] in printed order (CR 602.1). Being an artifact
 *   rather than a creature it is not summoning sick for its `{T}` (CR 302.6), so it can be eaten the
 *   turn it arrives.
 * - The **dies** trigger (CR 603.6b, CR 603.10) is [TriggerCondition.PutIntoGraveyardFromBattlefieldSelf],
 *   the condition Rancor already uses, and its effect is the packet's new
 *   [shuffleIntoOwnersLibrary] primitive (CR 701.20, ADR-006 — the shuffle draws from the match PRNG).
 *   It fires however the artifact reaches the graveyard: its own sacrifice ability, an opponent's
 *   removal, or a sweeper. The trigger carries the *fresh graveyard object* as its subject (CR 400.7),
 *   which is what the primitive shuffles in; if that object has already moved on, the effect does
 *   nothing rather than conjuring a second Lembas.
 *
 * Note what the third line is **not**: it is not a replacement effect. The card really does go to the
 * graveyard, really is in the graveyard while the trigger is on the stack, and can be answered there —
 * exiling it in response leaves nothing to shuffle in. Encoding it as "goes to the library instead"
 * would look identical in a solitaire game and be wrong in every game with an opponent.
 */
val lembas: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Lembas",
                manaCost = ManaCost.parse("{2}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(Subtype("Food")),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = ResolutionEffect { state, _ -> state },
                    libraryLook = LibraryLook(LibraryLookMode.Scry(LEMBAS_SCRY), thenDraw = LEMBAS_DRAW),
                ),
                TriggeredAbility(
                    condition = TriggerCondition.PutIntoGraveyardFromBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            shuffleIntoOwnersLibrary(
                                state,
                                context.subject ?: error(LEMBAS_NO_SUBJECT),
                            )
                        },
                ),
            )
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{2}")),
                            AbilityCost.TapSelf,
                            AbilityCost.SacrificeSelf,
                        ),
                    effect = ResolutionEffect { state, context -> gainLife(state, context.controller, LEMBAS_LIFE) },
                ),
            )
    }
