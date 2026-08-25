package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.drawCards
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's blue library-manipulation one- and two-drops: Brainstorm, Ponder, Preordain, and Impulse.
 * These are the four cards the card-selection packet dropped by name — "each needs a library-ordering, scry,
 * or choose-a-card-type decision the engine cannot yet enumerate (ADR-005)" — and they are here because
 * `FW-LIBLOOK` (docs/design/library-look.md) is that framework.
 *
 * Every mechanism is a published DSL primitive (ADR-003): each card is one [LibraryLook] clause plus, where
 * the printed text draws first, an ordinary [ResolutionEffect]. Between them they exercise all four
 * [LibraryLookMode]s, both pool sources, the mandatory keep, the optional PRNG shuffle (ADR-006), and the
 * after-the-look draw — which is exactly why they are the framework's demonstration set.
 *
 * Two things this header once said are no longer true, and both were corrected by reading the oracle text.
 * **Lead the Stampede** is not "a public *reveal* with a variable keep-all-matching": its current oracle
 * text is a look with an *optional* keep, so it is a fifth [LibraryLookMode] on this same framework and it
 * lives in RevealAndBottom.kt beside Ancient Stirrings and Augur of Bolas. And **Winding Way** is not
 * blocked on `FW-MODAL`, which has landed: its "Choose creature or land" happens as the spell *resolves*,
 * where a [dev.mtgplay.core.definition.SpellMode] is chosen at CR 601.2b during casting, so modality is the
 * wrong shape for it rather than the missing one. It stays absent, and what it actually needs — a
 * resolution-time card-type choice with a mandatory keep-all — is recorded in CardSelection.kt's header.
 */

/** The cards Brainstorm draws before the placement (CR 120.1). */
const val BRAINSTORM_DRAW: Int = 3

/** The hand cards Brainstorm then puts on top of the library, in any order. */
const val BRAINSTORM_TO_TOP: Int = 2

/** The cards Ponder looks at and reorders (CR 701.14a). */
const val PONDER_LOOK: Int = 3

/** How deep Preordain scries (CR 701.17a). */
const val PREORDAIN_SCRY: Int = 2

/** The cards Impulse looks at (CR 701.14a). */
const val IMPULSE_LOOK: Int = 4

/** The cards a resolving library-look cantrip draws after the look (CR 120.1) — Ponder's and Preordain's one. */
const val LIBRARY_LOOK_CANTRIP_DRAW: Int = 1

/**
 * Brainstorm — `{U}` Instant. "Draw three cards, then put two cards from your hand on top of your library in
 * any order." The two halves are sequenced by *where* they live, which is the framework's one structural
 * subtlety: the draw is the ordinary [SpellDefinition.resolution], which the engine runs first, and the
 * placement is the [LibraryLook] clause, which it orchestrates afterwards. So Brainstorm needs no
 * [LibraryLook.thenDraw] at all — the printed "then" is already expressed.
 *
 * [LibraryLookMode.HandToTop] is the framework's hand-sourced mode: the pool is the whole hand (private to
 * its owner, CR 402), exactly [BRAINSTORM_TO_TOP] of it are placed topmost-first, and every other card stays
 * in hand in its existing order. Each placed card changes zone, so it is reborn with a fresh object id
 * (CR 400.7). The enumerated options are the ordered pairs of hand cards, which is what "in any order"
 * means — the ordering is a real decision, not a convention the engine picks (ADR-005).
 */
val brainstorm: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Brainstorm",
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context -> drawCards(state, context.controller, BRAINSTORM_DRAW) }
        override val libraryLook = LibraryLook(mode = LibraryLookMode.HandToTop(BRAINSTORM_TO_TOP))
    }

/**
 * Ponder — `{U}` Sorcery. "Look at the top three cards of your library, then put them back in any order. You
 * may shuffle. Draw a card." Three instructions, all carried by one clause:
 * - [LibraryLookMode.ReorderTop]`(3)` is the pure ordering: the cards are *looked at* (CR 701.14a — seen by
 *   their controller and by no other player, so nothing is revealed and the opponent's view carries a count
 *   only) and all three go back on top in the chosen order, `3! = 6` enumerated outcomes;
 * - [LibraryLook.optionalShuffle] is the CR 601.3b "you may", surfaced as a yes/no after the ordering.
 *   Accepting shuffles the library through the match-owned PRNG (ADR-006), which discards the order just
 *   chosen — that is what the card says, and the engine does not quietly optimise it away;
 * - [LibraryLook.thenDraw] is the trailing "Draw a card.", which happens after both.
 */
val ponder: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Ponder",
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val libraryLook =
            LibraryLook(
                mode = LibraryLookMode.ReorderTop(PONDER_LOOK),
                optionalShuffle = true,
                thenDraw = LIBRARY_LOOK_CANTRIP_DRAW,
            )
    }

/**
 * Preordain — `{U}` Sorcery. "Scry 2, then draw a card." The minimal scry card, and the one the framework
 * was built against (gauntlet-card-triage.md). [LibraryLookMode.Scry]`(2)` is CR 701.17a exactly: look at the
 * top two, then put **any number** of them on the bottom in any order and the rest on top in any order —
 * a free partition *and* an ordering within each group, which is `(2 + 1)! = 6` enumerated outcomes, not the
 * four a partition-only reading would give. The "then" is [LibraryLook.thenDraw], so the draw sees the
 * arranged library rather than the pre-scry one.
 */
val preordain: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Preordain",
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val libraryLook =
            LibraryLook(
                mode = LibraryLookMode.Scry(PREORDAIN_SCRY),
                thenDraw = LIBRARY_LOOK_CANTRIP_DRAW,
            )
    }

/**
 * Impulse — `{1}{U}` Instant. "Look at the top four cards of your library. Put one of them into your hand and
 * the rest on the bottom of your library in any order."
 *
 * [LibraryLookMode.OneToHandRestToBottom]`(4)` is where the framework's **mandatory** keep lives. The reveal
 * clause's [dev.mtgplay.core.definition.LibraryReveal.toHandCount] is documented as a *maximum* — every
 * reveal in the older pool is a "you may"/"up to", so its request always offers a decline — and using it here
 * would enumerate an option the card does not allow. Instead the mode enumerates no arrangement with an empty
 * hand, so the illegal decline has no index at all: legality is defined by the enumeration (ADR-005). The
 * only empty-handed outcome is the honest one, a library with nothing left to look at.
 */
val impulse: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Impulse",
                manaCost = ManaCost.parse("{1}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val libraryLook = LibraryLook(mode = LibraryLookMode.OneToHandRestToBottom(IMPULSE_LOOK))
    }
