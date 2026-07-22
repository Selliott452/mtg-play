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
 */
data class PrintedCharacteristics(
    val name: String,
    val manaCost: ManaCost?,
    val supertypes: PersistentSet<Supertype>,
    val cardTypes: PersistentSet<CardType>,
    val subtypes: PersistentSet<Subtype>,
    val powerToughness: PrintedPowerToughness?,
    val keywords: PersistentSet<Keyword> = persistentSetOf(),
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
     * colorless — the empty set (CR 105.4).
     */
    val colors: Set<Color> get() = manaCost?.colors ?: emptySet()
}
