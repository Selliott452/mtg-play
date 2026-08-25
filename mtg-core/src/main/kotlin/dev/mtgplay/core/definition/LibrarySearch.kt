package dev.mtgplay.core.definition

import dev.mtgplay.core.card.Subtype
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/**
 * A "search your library for a matching card, put it somewhere, then shuffle" effect (CR 701.18) —
 * Ash Barrens' basic landcycling, Contaminated Landscape's sacrifice ability, Crop Rotation. Additive,
 * flagged core (P6.2c; the [destination] axis added by `P-SEARCH`). Card-definition *declaration*;
 * `mtg-rules` surfaces the up-to-one selection among the matching library cards (searching your own
 * library always permits failing to find, CR 701.18b), moves the found card to [destination], and
 * shuffles the library through the match PRNG (ADR-006 — the shuffle consumes seeded entropy, so replay
 * reproduces the new order).
 *
 * **A post-resolution clause since `P-SEARCH`.** This is one of the [ResolutionClauses] a resolving
 * object may carry, not a field of [ActivatedAbility] alone. It was ability-shaped, which is why the
 * first three clients were all activated abilities and why Crop Rotation — a *spell* that searches —
 * could not be encoded at all. Being a clause also fixes an ordering defect the ability-only path had:
 * the search ran **instead of** the ordinary [ActivatedAbility.effect] rather than after it, which was
 * invisible only because every client's effect was a no-op.
 *
 * @property find which library cards the search may find (CR 701.18).
 * @property destination where the found card goes (CR 701.18); [LibrarySearchDestination.REVEALED_TO_HAND]
 *   for every cycling search.
 * @property optional whether the printed line says "**you may** search" (CR 601.3b) — Gatecreeper Vine's
 *   *"you may search your library for a basic land card or a Gate card, reveal it, put it into your
 *   hand, then shuffle."* Additive, flagged core (`W8-E`); `false` for every mandatory search, which is
 *   every cycling ability and every other client.
 *
 *   **Not the same as CR 701.18b's always-available "fail to find", and the shuffle is the difference.**
 *   Every search of your own library lets you find nothing, and the engine has always offered that; but
 *   "then shuffle" is a separate instruction that happens *anyway*, so failing to find still randomises
 *   your library. Declining a "you may search" never begins the search at all, so nothing shuffles.
 *   Collapsing the two would quietly hand a free shuffle to a seat that had just arranged its top cards
 *   with Brainstorm or Ponder, or quietly deny one to a seat that wanted it — wrong in both directions,
 *   which is why the decline is its own enumerated index rather than a reading of the find-none one.
 */
data class LibrarySearch(
    val find: LibrarySearchFilter,
    val destination: LibrarySearchDestination = LibrarySearchDestination.REVEALED_TO_HAND,
    val optional: Boolean = false,
)

/**
 * Where a [LibrarySearch] puts the card it finds (CR 701.18) — the axis that separates the cycling
 * family ("reveal it, put it into your hand") from the ramp family ("put it onto the battlefield
 * tapped"). Additive, flagged core (`P-SEARCH`).
 *
 * **The reveal is a property of the destination, not a separate flag, and the CR is why.** A search
 * that ends in a *hidden* zone (CR 400.2 — the hand) is unverifiable unless the card is shown, so every
 * printed one says "reveal it": Ash Barrens, Lórien Revealed, Expedition Map, Generous Ent. A search
 * that ends on the *battlefield* is self-evidently public the moment it lands, so no printed one says
 * "reveal" — Contaminated Landscape and Crop Rotation both omit the word. Folding the reveal into the
 * destination therefore records a rule rather than a coincidence, and makes "put it into your hand
 * without revealing it" unexpressible, which is correct: no card in the gauntlet prints it.
 *
 * An enum so `mtg-rules` interprets it exhaustively; a graveyard or exile destination, and a
 * search of *another player's* library (CR 701.18a), are the extension points.
 */
enum class LibrarySearchDestination {
    /**
     * "…reveal it, put it into your hand" (CR 701.16a, CR 701.18) — every cycling search, Expedition
     * Map, Land Grant. The found card is revealed to **all** players and then joins the hidden hand.
     */
    REVEALED_TO_HAND,

    /**
     * "…put that card onto the battlefield" (CR 701.18, CR 400.7) — Crop Rotation. The found card
     * enters untapped by the CR 110.5a default, unless its own CR 614.1c "enters tapped" clause says
     * otherwise: a Crop Rotation that finds a Bridge land still gets a tapped Bridge, because a
     * replacement effect on the entering permanent is not overridden by the effect that moved it.
     */
    BATTLEFIELD,

    /**
     * "…put it onto the battlefield **tapped**" (CR 701.18, CR 110.5b) — the Landscape cycle. The
     * instruction fixes the entering permanent's status, so a land whose own clause would have let it
     * enter untapped still arrives tapped.
     */
    BATTLEFIELD_TAPPED,
}

/**
 * Which library cards a [LibrarySearch] may find (CR 701.18) — the noun half of "a basic land card",
 * "an Island card", "a land card", "a basic Plains, Island, or Swamp card". Additive, flagged core
 * (P6.2c; widened to two axes by `P-SEARCH`).
 *
 * **Every search in the gauntlet is a land search**, so the card type (CR 205.2, CR 305) is a constant
 * of this shape rather than an axis: a match must be a land card. What varies is two genuinely
 * independent things, and they are separate properties for the reason `GraveyardCardRestriction` and
 * `GraveyardScope` are separate (docs/design/graveyard-targeting.md §4) — folding them together
 * multiplies out into a member per pairing, which is exactly what killed the closed enum this replaced.
 * That enum had three members for three cards; the Landscape cycle prints three *more* distinct
 * three-type pairings, and the real cycle has ten.
 *
 * A data class rather than a sealed hierarchy because there is nothing left for `mtg-rules` to `when`
 * over: the two axes are read directly and no case can fall through. A card-type axis (for a future
 * "search your library for a creature card") is the extension point, and adding one is a property here
 * plus the matcher line that reads it.
 *
 * @property basic whether the found card must have the **Basic** supertype (CR 205.4, CR 305.6) — Ash
 *   Barrens' "a basic land card" and the Landscapes' "a basic Plains, Island, or Swamp card". `false`
 *   for typecycling, which names a land *subtype* and never the basic land (CR 702.28b), so a nonbasic
 *   land with the type is an equally legal find.
 * @property landTypes the land types a match may have (CR 205.3b); a card matches when it has **at
 *   least one** of them. Empty means no land-type requirement at all — Expedition Map's bare "a land
 *   card". A set rather than a single type because the Landscapes name three.
 * @property combination how the two axes combine (CR 701.18) — [LibrarySearchAxisCombination.ALL] for
 *   every card printed before `W8-E`, [LibrarySearchAxisCombination.ANY] for Gatecreeper Vine.
 */
data class LibrarySearchFilter(
    val basic: Boolean = false,
    val landTypes: PersistentSet<Subtype> = persistentSetOf(),
    val combination: LibrarySearchAxisCombination = LibrarySearchAxisCombination.ALL,
) {
    init {
        require(combination == LibrarySearchAxisCombination.ALL || (basic && landTypes.isNotEmpty())) {
            "CR 701.18: a disjunctive search filter needs both axes to say something, got " +
                "basic=$basic landTypes=$landTypes"
        }
    }

    companion object {
        /** The Plains land type (CR 205.3b). */
        val PLAINS: Subtype = Subtype("Plains")

        /** The Island land type (CR 205.3b). */
        val ISLAND: Subtype = Subtype("Island")

        /** The Swamp land type (CR 205.3b). */
        val SWAMP: Subtype = Subtype("Swamp")

        /** The Mountain land type (CR 205.3b). */
        val MOUNTAIN: Subtype = Subtype("Mountain")

        /** The Forest land type (CR 205.3b). */
        val FOREST: Subtype = Subtype("Forest")

        /**
         * A **land card** (CR 205.2, CR 305): the card type alone, basic and nonbasic alike. Expedition
         * Map's and Crop Rotation's "a land card". The widest filter there is — it demands neither the
         * Basic supertype nor any land type, so an artifact land and a typeless utility land are both
         * legal finds.
         */
        val LAND_CARD: LibrarySearchFilter = LibrarySearchFilter()

        /**
         * A **basic land card** (CR 205.4, CR 305.6): a land card with the Basic supertype, whatever its
         * land type. Ash Barrens' basic landcycling.
         */
        val BASIC_LAND_CARD: LibrarySearchFilter = LibrarySearchFilter(basic = true)

        /** An **Island card** (CR 205.3b, CR 702.28b) — Lórien Revealed's islandcycling. */
        val ISLAND_CARD: LibrarySearchFilter = LibrarySearchFilter(landTypes = persistentSetOf(ISLAND))

        /** A **Swamp card** (CR 205.3b, CR 702.28b) — Troll of Khazad-dûm's swampcycling. */
        val SWAMP_CARD: LibrarySearchFilter = LibrarySearchFilter(landTypes = persistentSetOf(SWAMP))

        /** A **Forest card** (CR 205.3b, CR 702.28b) — Generous Ent's forestcycling, Land Grant's search. */
        val FOREST_CARD: LibrarySearchFilter = LibrarySearchFilter(landTypes = persistentSetOf(FOREST))

        /**
         * A **basic land card of one of [types]** (CR 205.3b, CR 205.4) — the Landscape cycle's "a basic
         * Plains, Island, or Swamp card". Both axes at once, which is the pairing the closed enum could
         * not express without a member per cycle member.
         */
        fun basicOneOf(types: Set<Subtype>): LibrarySearchFilter =
            LibrarySearchFilter(basic = true, landTypes = types.toPersistentSet())

        /**
         * A **basic land card or a card of one of [types]** (CR 205.3b, CR 205.4, CR 701.18) —
         * Gatecreeper Vine's "a basic land card **or** a Gate card". The disjunctive pairing, and the
         * reason [LibrarySearchAxisCombination] exists.
         */
        fun basicOrOneOf(types: Set<Subtype>): LibrarySearchFilter =
            LibrarySearchFilter(
                basic = true,
                landTypes = types.toPersistentSet(),
                combination = LibrarySearchAxisCombination.ANY,
            )
    }
}

/**
 * How a [LibrarySearchFilter]'s two axes combine (CR 701.18) — the "and" of "a basic Plains, Island, or
 * Swamp card" against the "or" of "a basic land card or a Gate card". Additive, flagged core (`W8-E`).
 *
 * **A third axis would not have expressed this.** Every filter before Gatecreeper Vine narrowed a land
 * search by *conjunction*: each property it named had to hold. Gatecreeper Vine names two alternatives,
 * and neither is a narrowing of the other — a basic Forest is not a Gate and a Gate is not basic — so
 * there is no set of `(basic, landTypes)` values that selects their union. Making the combination
 * explicit keeps both readings on one type, with the conjunctive one as the default so no existing card
 * changes meaning.
 */
enum class LibrarySearchAxisCombination {
    /**
     * Every named axis must hold (CR 701.18) — Ash Barrens' "a basic land card", the Landscapes' "a
     * basic Plains, Island, or Swamp card". The default, and what every filter meant before `W8-E`.
     */
    ALL,

    /**
     * At least one named axis must hold (CR 701.18) — Gatecreeper Vine's "a basic land card or a Gate
     * card". Meaningful only when both axes say something, which [LibrarySearchFilter]'s `init`
     * requires: a disjunction with one empty side is the other side, spelled confusingly.
     */
    ANY,
}
