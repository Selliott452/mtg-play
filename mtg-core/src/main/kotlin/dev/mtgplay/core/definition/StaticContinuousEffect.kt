package dev.mtgplay.core.definition

import dev.mtgplay.core.card.Keyword
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * One continuous effect a permanent's static ability generates (CR 604.3, CR 611.2), expressed as
 * card-definition data — an Aura's "enchanted X gets/has …". Additive, flagged core (P4.1).
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This is the *declaration* of
 * what the ability does; the *classification into CR 613 layers* and the *application* are rules
 * logic and live in `mtg-rules`. Core says a modifier is +N/+N and grants a keyword; rules decides
 * that grants are layer 6 and P/T modifiers are layer 7c (docs/design/layer-system.md §2).
 *
 * The effect is active exactly while its source permanent is on the battlefield with its
 * [affects] set non-empty — for an Aura, while it is attached to a legal object (CR 604.3). There
 * is no resolution-generated, floating, or duration-bounded effect in the MVP pool, so this
 * carries no timestamp or duration: the timestamp is the source's battlefield-entry order and
 * duration is uniformly "while the static ability is active" (docs/design/layer-system.md §2, §3).
 *
 * @property affects which objects the effect modifies (CR 611.2c); [AffectedSet.Enchanted] for
 *   every MVP effect — the one object the Aura enchants.
 * @property grantedKeywords keyword abilities the affected object gains (CR 613.3 layer 6); the
 *   in-game keyword set unions these on (Rancor grants trample).
 * @property grantedManaAbilities mana abilities the affected object gains (CR 613.3 layer 6); how
 *   an Abundant-Growth-enchanted land gains "{T}: add one mana of any color".
 * @property powerMod the layer-7c power modifier (CR 613.3 sublayer 7c), possibly [Magnitude.Zero].
 * @property toughnessMod the layer-7c toughness modifier (CR 613.3 sublayer 7c), possibly
 *   [Magnitude.Zero].
 */
data class StaticContinuousEffect(
    val affects: AffectedSet = AffectedSet.Enchanted,
    val grantedKeywords: PersistentSet<Keyword> = persistentSetOf(),
    val grantedManaAbilities: PersistentList<ManaAbility> = persistentListOf(),
    val powerMod: Magnitude = Magnitude.Zero,
    val toughnessMod: Magnitude = Magnitude.Zero,
)
