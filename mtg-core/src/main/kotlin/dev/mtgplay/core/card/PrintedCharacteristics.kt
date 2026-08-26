package dev.mtgplay.core.card

import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * A card's printed characteristics: name, mana cost, type line, and printed power/toughness
 * (the characteristics of CR 109.3, restricted to what the MVP pool needs).
 *
 * Printed values only — what the physical card says. In-game characteristics are *computed*
 * from these by the continuous-effect layer system (CR 613) in Phase 4; nothing here is ever
 * modified in place (ADR-002). Deliberately unmodeled until a card needs them: color indicator
 * (CR 204), loyalty (CR 209), defense (CR 210), and rules text as data — rules meaning lives
 * in the card DSL (ADR-003), not here.
 *
 * The type-line sets use the insertion-ordered persistent implementations; see the
 * deterministic-iteration rule on [dev.mtgplay.core.state.GameState].
 *
 * @property name the exact printed (oracle) card name (CR 201); never blank.
 * @property manaCost the printed mana cost (CR 202), or `null` for a card with no mana cost —
 *   a land, e.g. Absence of a cost is not the same as `{0}`.
 * @property supertypes the printed supertypes (CR 205.4), possibly empty.
 * @property cardTypes the printed card types (CR 205.2, CR 300.1); never empty.
 * @property subtypes the printed subtypes (CR 205.3), possibly empty.
 * @property powerToughness the printed power/toughness box; present exactly for creature cards
 *   (CR 208.1).
 * @property keywords the printed keyword abilities (CR 702), possibly empty (additive, flagged,
 *   P3.1). Printed values only — in-game keywords are computed by the layer system (CR 613,
 *   layer 6) in Phase 4, which adds aura-granted keywords; combat reads these only through the
 *   effective-keyword accessor in `mtg-rules`.
 * @property evasions the printed evasion abilities that are ability text rather than named keywords
 *   (CR 509.1b), possibly empty (additive, flagged, P5.3). Silhana Ledgewalker's "can't be blocked
 *   except by creatures with flying" lives here; nothing in the MVP pool grants or removes an
 *   evasion, so combat reads these straight from the printed characteristics.
 * @property protections the printed protection abilities, one per quality (CR 702.16), possibly
 *   empty (additive, flagged, `FW-PROTECT`). Guardian of the Guildpact's "protection from
 *   monocolored" is printed; Mask of Law and Grace's is *granted*, and arrives instead through the
 *   layer-6 union in `mtg-rules` — so, like [keywords], the rules read these only through the
 *   effective-protection accessor and never straight off the card.
 *
 *   **Not a [Keyword] member**, which is the one place protection cannot follow hexproof's path:
 *   [Keyword] is a parameterless enum and protection carries a quality (CR 702.16a). CR 702.16g
 *   makes "protection from black and from red" two abilities rather than one, so this is a set of
 *   qualities; CR 702.16m's redundancy of repeated instances then falls out of the set for free.
 * @property definedColors the colors an **effect** gave this object outright (CR 111.4), overriding the
 *   CR 202.2 derivation from [manaCost]; `null` for every card, which is every object whose colors are
 *   derived. Additive, flagged core (`FW-COPYTOKEN`).
 *
 *   **A token's characteristics are *defined*, not printed, and this is where the type stopped being
 *   able to say so.** Sacred Cat's embalm token is "a white Zombie Cat with **no mana cost**"
 *   (CR 702.90a), and colour was derived from the mana cost with exactly one exception — the
 *   CR 702.114a devoid CDA — so "white and costless" had no representation at all. The engine would
 *   have had to call the token colourless, which is not a cosmetic difference: it decides whether
 *   protection from white stops it, whether a colour-based prevention shield covers it, and whether a
 *   Red Elemental Blast can point at it.
 *
 *   **It is an override rather than a replacement of the derivation**, so no card changes: a card's
 *   colour is still CR 202.2's function of its cost, devoid still wins over that, and the only objects
 *   that carry this are the ones the rules say have their colours defined for them. A `null` here and
 *   an empty set are therefore different values and both meaningful — `null` is "derive", empty is
 *   "defined colourless".
 */
data class PrintedCharacteristics(
    val name: String,
    val manaCost: ManaCost?,
    val supertypes: PersistentSet<Supertype>,
    val cardTypes: PersistentSet<CardType>,
    val subtypes: PersistentSet<Subtype>,
    val powerToughness: PrintedPowerToughness?,
    val keywords: PersistentSet<Keyword> = persistentSetOf(),
    val evasions: PersistentSet<Evasion> = persistentSetOf(),
    val protections: PersistentSet<Quality> = persistentSetOf(),
    val definedColors: PersistentSet<Color>? = null,
) {
    init {
        require(name.isNotBlank()) { "card name must not be blank" }
        require(cardTypes.isNotEmpty()) { "CR 300.1: a card has at least one card type (card \"$name\")" }
        val isCreature = CardType.CREATURE in cardTypes
        require(isCreature == (powerToughness != null)) {
            "CR 208.1: creature cards, and only creature cards, have printed power/toughness (card \"$name\"); " +
                "non-creature cards with a P/T box (e.g. Vehicles) are outside the MVP pool and unsupported"
        }
    }

    /** The card's mana value (CR 203.3); a card with no mana cost has mana value 0. */
    val manaValue: Int get() = manaCost?.manaValue ?: 0

    /**
     * The card's colors, derived from its mana cost (CR 202.2); a card with no mana cost is
     * colorless — the empty set (CR 105.4). A card with [Keyword.DEVOID] is colorless whatever its
     * mana cost says (CR 702.114a), which is why the derivation reads the printed keywords too.
     *
     * [definedColors] overrides the derivation outright, and only a **token** may set it (CR 111.4).
     */
    val colors: Set<Color>
        get() =
            definedColors
                ?: if (Keyword.DEVOID in keywords) emptySet() else (manaCost?.colors ?: emptySet())

    /**
     * Whether the card has the subtype [subtype] (CR 205.3) — the **single** predicate every subtype
     * read in the engine goes through, rather than testing `subtype in subtypes` directly.
     *
     * It is a function rather than a set membership because of [Keyword.CHANGELING] (CR 702.73a):
     * "this card is every creature type". A changeling's printed subtype line says `Shapeshifter` and
     * the card is nonetheless an Elf, a Goblin and a Dragon, so the true subtype set is not the printed
     * one and cannot be precomputed — [Subtype] is a value class over an open word space, so "every
     * creature type" has no finite value to expand into.
     *
     * **Only creature types** (CR 205.3m), which is the half that is easy to lose. Changeling grants
     * creature types and nothing else, so this answers `false` for a land type on a changeling: a
     * Shapeshifter is not a Forest for Gingerbread Cabin's count and not a Mountain for Fireblast's
     * sacrifice cost. [Subtype.isCreatureType] draws that line and fails loudly on a word it cannot
     * categorise, so the gate can never be silently skipped.
     *
     * **Printed, and correct in every zone.** CR 702.73a says changeling "works everywhere, even
     * outside the game", which is exactly why it belongs here beside [colors]/[Keyword.DEVOID] rather
     * than only in the battlefield layer system: a changeling card in a library, a hand or a graveyard
     * is an Elf there too, and the library-search, cost-reduction and graveyard reads all get the right
     * answer from this one accessor. `mtg-rules` layers a *granted* changeling on top of this for
     * battlefield objects (`hasSubtype` in `EffectiveCharacteristics.kt`); nothing in the pool grants
     * one, so today the two answers coincide.
     */
    fun hasSubtype(subtype: Subtype): Boolean =
        subtype in subtypes ||
            (Keyword.CHANGELING in keywords && subtype.isCreatureType())
}
