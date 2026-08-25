package dev.mtgplay.core.state

import dev.mtgplay.core.mana.Color

/**
 * A **global, turn-scoped effect read at the CR 615 damage-prevention application point** — the
 * payload of a [TimedPreventionEffect]. Additive, flagged core (`FW-PREVENT2`,
 * docs/design/protection.md §3 Part B, whose "second clause" this fills).
 *
 * **Why this is a store rather than a [TimedContinuousEffect].** Every member of
 * [ContinuousModification] modifies *one named object*: [TimedContinuousEffect.affected] is a single
 * [dev.mtgplay.core.identity.ObjectId] fixed when the effect begins (CR 611.2c), which is right for
 * "target creature gets +2/+2" and expressible for nothing here. Prismatic Strands prevents damage
 * **from** sources of a colour **to everything** — every permanent and both players, and from spells
 * as well as from creatures — and Flaring Pain names no object at all. Neither has an affected object
 * to put in that field, and neither classifies into a CR 613 layer, which is precisely what
 * [ContinuousModification]'s own `init` refuses to represent. So they get their own store, read at
 * their own application point.
 *
 * Sealed, so [dev.mtgplay.core.state.GameState]'s consumers in `mtg-rules` handle every member
 * exhaustively: a prevention shape the engine does not implement breaks the `when` at compile time
 * rather than quietly failing to prevent anything.
 *
 * **The two members are not two prevention effects; one is prevention's off-switch.** That they share
 * a store is deliberate and is the framework's central claim: CR 615.9 is resolved *at the moment
 * damage would be dealt*, against the same board, by the same function, and putting the disabler
 * anywhere else would mean two places could disagree about whether a given point of damage happens.
 */
sealed interface PreventionEffect {
    /**
     * A CR 615.1 **prevention shield**: prevent all damage that sources of [color] would deal
     * (Prismatic Strands' "Prevent all damage that sources of the color of your choice would deal this
     * turn").
     *
     * **Unbounded in every direction the card is**, which is the shape a "protection from [color]
     * granted to your creatures" model would get wrong three times over (docs/design/protection.md §0,
     * Disagreement 1): it protects *every* permanent and *both* players, not the caster's creatures; it
     * is keyed on the **source's** colour rather than on any recipient's characteristics; and it
     * catches damage from spells and abilities, not only from creatures.
     *
     * The colour is a stored value rather than a choice re-made later: CR 609.4 fixes it as the spell
     * resolves, and it does not change for the rest of the turn even if the chosen colour becomes
     * irrelevant.
     *
     * @property color the colour whose sources' damage is prevented (CR 105.1). Colourless is not a
     *   member of [Color] (CR 105.4 — it is the absence of colour), so a colourless source is never
     *   caught by this shield, which is the printed card's own reading.
     */
    data class PreventDamageFromColor(
        val color: Color,
    ) : PreventionEffect

    /**
     * CR 615.9 — **damage can't be prevented** (Flaring Pain's whole text).
     *
     * > 615.9. Some replacement effects and prevention effects are worded to say that damage "can't
     * > be prevented". A prevention effect that can't be applied simply doesn't do anything.
     *
     * So this does not *remove* prevention effects from the store, delete shields, or race them on
     * timestamps: it makes every prevention effect fail to apply while it is present, whenever it was
     * created and whoever created it. That includes prevention effects created **after** it — a
     * Prismatic Strands cast in response to a Flaring Pain still resolves and still creates its
     * shield, and the shield still does nothing this turn.
     *
     * **It also turns off protection's damage prevention** (CR 702.16e), and that is the rule rather
     * than an accident of implementation: protection prevents damage, so with this in force a red
     * creature's combat damage to a blocker with protection from red is dealt in full. The *other*
     * three letters of protection are untouched — CR 702.16b targeting, CR 702.16c attachment, and
     * CR 702.16f blocking are not prevention and Flaring Pain says nothing about them.
     *
     * A `data object` because the effect is parameterless: CR 615.9 admits no quality, no colour, and
     * no controller. A card preventing only *some* prevention would be a different member.
     */
    data object DamageCantBePrevented : PreventionEffect
}
