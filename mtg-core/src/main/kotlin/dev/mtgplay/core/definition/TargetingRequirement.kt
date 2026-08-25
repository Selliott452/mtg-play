package dev.mtgplay.core.definition

import dev.mtgplay.core.card.Subtype

/**
 * A CR 601.2c targeting **requirement** a battlefield permanent's static ability imposes on its
 * controller's opponents (CR 604.3) — Standard Bearer's "While an opponent is choosing targets as part
 * of casting a spell they control or activating an ability they control, that player must choose at
 * least one Flagbearer on the battlefield if able." Additive, flagged core (`W8-G`,
 * docs/design/protection.md §8).
 *
 * **A requirement is the opposite shape to a restriction, and conflating the two is the mistake this
 * type exists to prevent.** Hexproof and protection *remove* objects from an opponent's enumeration;
 * this *narrows* it to a set the opponent would rather not choose from. CR 601.2c gives them a strict
 * precedence — "the player chooses targets so that they obey the maximum possible number of such
 * effects **without violating any rules or effects that say that an object or player can't be chosen as
 * a target**" — which is what the printed words "if able" are pointing at. So a Flagbearer that is
 * hexproof to the chooser, or protected from their spell, simply is not a legal choice and the
 * requirement is unsatisfiable and therefore inert. `mtg-rules` gets that ordering for free by applying
 * this to the *already restriction-filtered* pool.
 *
 * **It is a requirement about a subtype, not about the permanent that generates it.** Standard Bearer
 * does not say "must target this creature"; it says "must choose at least one Flagbearer", so a second
 * Flagbearer — including one the *chooser* controls, and including a changeling (CR 702.73a) — satisfies
 * it just as well. That is why the payload is a [Subtype] rather than a self-reference, and why two
 * Standard Bearers impose one effective constraint rather than two.
 *
 * **Not a [StaticContinuousEffect], and deliberately.** This is a CR 613.11 continuous effect that
 * "affects game rules rather than objects", applied after every other continuous effect and belonging to
 * no CR 613 layer at all — `Layers.kt` reserves slots for layers 1–7 and refuses effects it cannot place,
 * so routing this through the layer collector would mean either a wrong slot or a loud refusal. It is
 * declared on [CardDefinition] beside [CardDefinition.spellCostReductions] instead, which is the
 * established shape for "a permanent's static ability changes what somebody else may do".
 *
 * **Blocking requirements are a different framework.** Lure's "all creatures able to block this creature
 * do so" is CR 509.1c, a constraint on the declare-blockers turn-based action rather than on CR 601.2c
 * target choice; it shares this type's *name* and none of its machinery.
 *
 * @property subtype the subtype at least one of which must be chosen if able (CR 205.3) —
 *   `Subtype("Flagbearer")`. Read through the changeling-aware seam, so a Rooftop Percher on the
 *   battlefield is a Flagbearer and is offered as one (CR 702.73a).
 */
data class TargetingRequirement(
    val subtype: Subtype,
)
