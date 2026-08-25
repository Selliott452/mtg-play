package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.core.definition.OptionalCostThenDraw
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.gainLife
import dev.mtgplay.rules.effect.mill
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The card-selection and draw family of the gauntlet pool (docs/decklists.md): the cheap "fill the
 * graveyard and replace itself" cantrips Thought Scour and Mental Note (Mono-Blue Terror), the
 * draw-three-with-a-land-mode Lórien Revealed (Mono-Blue Terror, UWX Familiar, Jeskai Ephemerate), the
 * devoid draw-three-plus-a-token Unfathomable Truths (Monster Tron), and the lifegain-loot Pursue the
 * Past (Gates).
 *
 * Every mechanism here is a published DSL primitive (ADR-003), so each definition is a faithful oracle
 * translation and no card action is gap-avoided. This packet added three primitives to make that true:
 * the mill effect ([dev.mtgplay.rules.effect.mill], CR 701.13), "target player"
 * ([TargetSpec.TargetPlayer], CR 115.1a), and the Island-card search filter
 * ([LibrarySearchFilter.ISLAND_CARD], CR 702.28).
 *
 * The rest of the family was deliberately **not** here, because each needed a library-ordering, scry, or
 * choose-a-card-type decision the engine could not enumerate (ADR-005) — a framework change, not a card.
 * Four of the six have since landed in LibraryLookCards.kt on the `FW-LIBLOOK` framework
 * (docs/design/library-look.md): Brainstorm, Ponder, Preordain, and Impulse. **Lead the Stampede** has now
 * landed too (RevealAndBottom.kt) — on a filtered *look*, not the public reveal this paragraph once
 * predicted, because its current oracle text reads "Look at the top five cards … You may reveal any number
 * of creature cards from among them" rather than the printed "Reveal the top five cards".
 *
 * **Winding Way** is the last of the six still absent, and its blocker is narrower than "`FW-MODAL`" —
 * modality has landed and does not carry it. "Choose creature or land" is chosen **as the spell resolves**,
 * not at CR 601.2b when a modal spell's modes are chosen, so a [dev.mtgplay.core.definition.SpellMode]
 * would lock the choice in a whole priority round too early. What it needs is a *resolution-time* card-type
 * choice: a pending record, its own enumerated request, and the mandatory keep-all-matching that goes with
 * it. That is a framework, and it is documented rather than approximated.
 */

/** The cards Thought Scour's target player mills (CR 701.13a). */
const val THOUGHT_SCOUR_MILL: Int = 2

/** The cards Mental Note's controller mills (CR 701.13a). */
const val MENTAL_NOTE_MILL: Int = 2

/** The cards a resolving cantrip replaces itself with (CR 120.1) — Thought Scour's and Mental Note's one. */
const val CANTRIP_DRAW: Int = 1

/** The cards Lórien Revealed draws on resolution (CR 120.1). */
const val LORIEN_REVEALED_DRAW: Int = 3

/** The cards Unfathomable Truths draws on resolution (CR 120.1). */
const val UNFATHOMABLE_TRUTHS_DRAW: Int = 3

/** The life Pursue the Past's controller gains on resolution (CR 119.3). */
const val PURSUE_THE_PAST_LIFEGAIN: Int = 2

/** The cards Pursue the Past's "if you do, draw" clause draws when the discard is paid (CR 601.3b). */
const val PURSUE_THE_PAST_DRAW: Int = 2

/**
 * Thought Scour — `{U}` Instant. "Target player mills two cards. Draw a card." Two clauses over two
 * different players: the mill lands on the *target* (CR 115.1a, [TargetSpec.TargetPlayer] — a creature
 * is never a legal choice, unlike [TargetSpec.AnyTarget]), the draw on the *controller* (CR 120.1). The
 * mill is [dev.mtgplay.rules.effect.mill] (CR 701.13a), this packet's new primitive: the top two cards
 * of the targeted player's library go to their graveyard as new objects, milling as many as possible
 * from a short library (CR 701.13b) and never causing a loss. Milling is not discarding, so a milled
 * madness card is not exiled (CR 702.35a replaces a discard only).
 */
val thoughtScour: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Thought Scour",
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPlayer()
        override val resolution =
            ResolutionEffect { state, context ->
                val target =
                    context.targets.single() as? Target.Player
                        ?: error("CR 115.1a: Thought Scour's target is a player, got ${context.targets}")
                val milled = mill(state, target.id, THOUGHT_SCOUR_MILL)
                drawCards(milled, context.controller, CANTRIP_DRAW)
            }
    }

/**
 * Mental Note — `{U}` Instant. "Mill two cards. Draw a card." Thought Scour's untargeted twin: an
 * unqualified "mill" is the spell's controller milling their own library (CR 701.13a, CR 109.5), so
 * both clauses land on [dev.mtgplay.core.definition.ResolutionContext.controller] and the spell targets
 * nothing. The self-mill half is the point in Mono-Blue Terror, where the graveyard is a resource.
 */
val mentalNote: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Mental Note",
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context ->
                val milled = mill(state, context.controller, MENTAL_NOTE_MILL)
                drawCards(milled, context.controller, CANTRIP_DRAW)
            }
    }

/**
 * Lórien Revealed — `{3}{U}{U}` Sorcery. "Draw three cards. Islandcycling `{1}`." Two independent
 * halves, and the reason the card is played as a one-of-many: it is a five-mana draw-three that is also
 * a one-mana Island.
 * - the resolution draws [LORIEN_REVEALED_DRAW] (CR 120.1);
 * - islandcycling is typecycling (CR 702.28b), a hand-scoped activated ability (CR 113.6c, CR 602)
 *   exactly like Ash Barrens' basic landcycling: the composite cost `{1}` + discard-this-card
 *   ([AbilityCost.Mana] + [AbilityCost.DiscardSelf]) functioning from [AbilityZoneScope.Hand], whose
 *   effect is a [LibrarySearch] for an *Island card* ([LibrarySearchFilter.ISLAND_CARD], CR 701.18).
 *   Typecycling names a land subtype, not the basic land, so a nonbasic land with the Island type is an
 *   equally legal find. `mtg-rules` surfaces the find-one choice (failing to find is always legal,
 *   CR 701.18b), reveals the found card, puts it into the hand, and shuffles through the match PRNG
 *   (ADR-006). The ordinary [ActivatedAbility.effect] is a no-op — the search is the whole resolution.
 */
val lorienRevealed: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Lórien Revealed",
                manaCost = ManaCost.parse("{3}{U}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context -> drawCards(state, context.controller, LORIEN_REVEALED_DRAW) }
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.Mana(ManaCost.parse("{1}")), AbilityCost.DiscardSelf),
                    effect = ResolutionEffect { state, _ -> state },
                    zoneScope = AbilityZoneScope.Hand,
                    librarySearch = LibrarySearch(LibrarySearchFilter.ISLAND_CARD),
                ),
            )
    }

/**
 * Unfathomable Truths — `{4}{U}` Instant. "Devoid. Draw three cards and create a 0/1 colorless Eldrazi
 * Spawn creature token with 'Sacrifice this token: Add {C}.'" One resolution performing both printed
 * instructions in order: draw [UNFATHOMABLE_TRUTHS_DRAW] (CR 120.1), then create the [eldraziSpawnToken]
 * under the caster (CR 707.2) — the same token Malevolent Rumble makes, so the definition is shared.
 *
 * Devoid (CR 702.114a) is a characteristic-defining ability, not a resolution instruction: it is carried
 * as [Keyword.DEVOID] on the printed characteristics, which makes
 * [PrintedCharacteristics.colors] the empty set (CR 105.4) despite the `{U}` in the cost. Nothing in the
 * pool yet reads a card's colour, so the ability is unobservable in play — it is encoded anyway so the
 * printed characteristics are not quietly wrong.
 */
val unfathomableTruths: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Unfathomable Truths",
                manaCost = ManaCost.parse("{4}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
                keywords = persistentSetOf(Keyword.DEVOID),
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context ->
                val drawn = drawCards(state, context.controller, UNFATHOMABLE_TRUTHS_DRAW)
                createToken(drawn, context.controller, eldraziSpawnToken)
            }
    }

/**
 * Pursue the Past — `{R}{W}` Sorcery. "You gain 2 life. You may discard a card. If you do, draw two
 * cards. Flashback `{2}{R}{W}`." Three mechanisms, all published primitives:
 * - the ordinary resolution gains [PURSUE_THE_PAST_LIFEGAIN] life (CR 119.3), which the engine runs
 *   first — the printed order;
 * - the loot half is an *optional cost-then-draw at spell resolution* (CR 601.3b):
 *   [OptionalCostThenDraw]`([PURSUE_THE_PAST_DRAW], [discard])`. Unlike Highway Robbery the card offers
 *   the discard mode only, so `mtg-rules` surfaces exactly "decline or discard a card", then the hand
 *   selection, then the draw. The discard routes through the CR 614/616 framework, so a discarded
 *   madness card is exiled instead (CR 702.35a);
 * - the flashback half is [CastingPermission.Flashback]`({2}{R}{W})` (CR 702.34), cast from the
 *   graveyard and exiled as it leaves the stack (CR 702.34e) — which is why the card reads as two
 *   Gates-deck draw spells in one slot.
 */
val pursueThePast: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Pursue the Past",
                manaCost = ManaCost.parse("{R}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context -> gainLife(state, context.controller, PURSUE_THE_PAST_LIFEGAIN) }
        override val optionalCostThenDraw =
            OptionalCostThenDraw(
                drawCount = PURSUE_THE_PAST_DRAW,
                modes = persistentListOf(OptionalCostMode.DiscardCard),
            )
        override val castingPermissions = listOf(CastingPermission.Flashback(ManaCost.parse("{2}{R}{W}")))
    }
