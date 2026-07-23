package dev.mtgplay.core.definition

import dev.mtgplay.core.card.Subtype

/**
 * A non-mana cost component: sacrifice [count] permanents of subtype [subtype] the caster controls
 * (CR 601.2h, CR 701.17). Additive, flagged core (P6.2a). Card-definition *declaration*; `mtg-rules`
 * owns whether it can be paid (enough matching permanents), surfaces the selection decision, and
 * performs the sacrifice during payment.
 *
 * The MVP pool needs exactly the subtype-Mountain shape: Fireblast's alternative cost "sacrifice two
 * Mountains rather than pay this spell's mana cost" ([count]`= 2`) and Lava Dart's flashback cost
 * "Sacrifice a Mountain" ([count]`= 1`). Control is ownership in the MVP pool. Predicated on a single
 * printed subtype, which is all the pool prints; a richer permanent predicate is the extension point.
 *
 * @property count how many permanents must be sacrificed (at least 1).
 * @property subtype the subtype every sacrificed permanent must have (CR 205.3), e.g. `Mountain`.
 */
data class SacrificeRequirement(
    val count: Int,
    val subtype: Subtype,
) {
    init {
        require(count >= 1) { "CR 601.2h: a sacrifice cost sacrifices at least one permanent, was $count" }
    }
}
