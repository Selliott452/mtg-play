package dev.mtgplay.core.state

import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * *What* a resolution-generated continuous effect does to the object it affects (CR 611.2) — the
 * payload half of a [TimedContinuousEffect], separated from the *when* and *to what*. Additive,
 * flagged core (`FW-DURATION`, docs/design/duration.md §5.1).
 *
 * The counterpart of [dev.mtgplay.core.definition.StaticContinuousEffect]'s grant/modifier fields,
 * with the one difference that defines this framework: the modifiers are **plain integers, already
 * snapshotted** (CR 608.2h, CR 611.2d). A spell or ability that sets a value using a variable
 * calculates it once, on resolution, and the effect it creates keeps that value for its whole
 * duration — so "+X/+X where X is the number of Elves" is a number by the time it reaches here.
 * There is deliberately nowhere to put a [dev.mtgplay.core.definition.Magnitude.Dynamic], whose
 * live-recount semantics (CR 613.3c) are correct for a static Aura and silently wrong for a resolved
 * pump (docs/gauntlet-card-triage.md T16).
 *
 * @property grantedKeywords keyword abilities the affected object gains (CR 613.3 layer 6).
 * @property grantedEvasions block-legality restrictions the affected object gains (CR 613.3 layer 6,
 *   CR 509.1b) — Gingerbrute's "this creature can't be blocked this turn except by creatures with
 *   haste". A layer-6 ability grant like [grantedKeywords], and its own field for the same reason
 *   [dev.mtgplay.core.definition.StaticContinuousEffect.grantedProtections] is: the restriction is not
 *   a named CR 702 keyword and so cannot be a [Keyword] member.
 *
 *   The **static** counterpart deliberately has no such field. No card in the gauntlet grants an
 *   evasion through a permanent's static ability, and an always-empty field would be an untested
 *   branch of the layer-6 union — the same call
 *   [dev.mtgplay.core.definition.StaticContinuousEffect] makes in the other direction for mana
 *   abilities, which no *timed* effect grants (docs/design/duration.md §5.1).
 * @property powerMod the snapshotted layer-7c power modifier (CR 613.3 sublayer 7c); may be negative
 *   ("gets -2/-0") or zero.
 * @property toughnessMod the snapshotted layer-7c toughness modifier; may be negative or zero.
 */
data class ContinuousModification(
    val grantedKeywords: PersistentSet<Keyword> = persistentSetOf(),
    val powerMod: Int = 0,
    val toughnessMod: Int = 0,
    val grantedEvasions: PersistentSet<Evasion> = persistentSetOf(),
) {
    init {
        require(
            grantedKeywords.isNotEmpty() ||
                grantedEvasions.isNotEmpty() ||
                powerMod != 0 ||
                toughnessMod != 0,
        ) {
            "CR 613: a continuous effect that grants nothing and modifies no power or toughness " +
                "classifies into no implemented layer; an unimplemented effect kind must fail " +
                "loudly, never be represented (docs/design/duration.md §5.1)"
        }
    }
}
