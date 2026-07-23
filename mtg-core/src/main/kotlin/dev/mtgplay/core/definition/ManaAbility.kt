package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaType
import kotlinx.collections.immutable.PersistentList

/**
 * An intrinsic activated mana ability with cost `{T}` (CR 605.1a, CR 106.11a): "tap this
 * permanent: add one mana of one of [options]".
 *
 * Activating it taps the source and adds exactly **one** mana of the activator's chosen option
 * to their pool, resolving immediately — no stack, no priority round (CR 605.3). A basic
 * Mountain is `ManaAbility([RED])`; an "add one mana of any color" source lists all five
 * colors; a `{C}` producer lists [ManaType.COLORLESS]. One-mana production is exactly the MVP
 * pool's shape (docs/decklists.md); multi-mana or costed abilities (Eldrazi Spawn's
 * sacrifice) arrive with the composite-cost work in Phase 5 as new vocabulary, not by
 * stretching this type.
 *
 * The option list's order is the canonical enumeration order for payment plans (see
 * docs/design/mana-payment.md), so definitions should list options in WUBRG-then-colorless
 * order.
 *
 * The activation cost is `{T}` by default; [viaSacrifice] flips it to "Sacrifice this permanent"
 * instead (CR 605.1a) — Malevolent Rumble's Eldrazi Spawn token, "Sacrifice this token: Add {C}". A
 * sacrifice-cost mana ability is still a mana ability (no stack, CR 605.3) and is payable during
 * payment enumeration like tap-for-mana; the engine sacrifices the source rather than tapping it, and
 * such a source is usable whether or not it is tapped.
 *
 * @property options the mana types the activator may choose to add, exactly one per
 *   activation; never empty, no duplicates.
 * @property viaSacrifice whether activating this ability sacrifices the source instead of tapping it
 *   (CR 605.1a) — Eldrazi Spawn's `{C}`. Additive, flagged core (P6.2a). `false` for an ordinary `{T}`
 *   mana ability.
 */
data class ManaAbility(
    val options: PersistentList<ManaType>,
    val viaSacrifice: Boolean = false,
) {
    init {
        require(options.isNotEmpty()) { "CR 605.1a: a mana ability adds mana; options cannot be empty" }
        require(options.distinct().size == options.size) { "mana options must be distinct, got $options" }
    }
}
