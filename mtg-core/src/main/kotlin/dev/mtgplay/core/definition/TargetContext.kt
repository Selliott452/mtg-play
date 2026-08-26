package dev.mtgplay.core.definition

import dev.mtgplay.core.state.Target
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * The answers an object has already given while it is being cast, activated, or put on the stack, at the
 * moment one of its targeting lines is enumerated (CR 601.2b–c) — the input that makes a target's legality
 * depend on something other than the board. Additive, flagged core (`W9-C`,
 * docs/design/dependent-targets.md §1).
 *
 * **Why this exists at all.** Until now `legalTargets` was a pure function of the board, the deciding
 * player, and the choosing object: every [TargetSpec] and every [PermanentRestriction] could be answered
 * from those three. Two printed lines in the gauntlet break that, and they break it in two different
 * directions:
 *
 * - Gorilla Shaman's "Destroy target noncreature artifact **with mana value X**" depends on the value
 *   announced for the ability's own variable cost (CR 107.3, [chosenX]) — a *cost announcement* the
 *   object made at CR 601.2b, before it chose anything to point at.
 * - Searing Blaze's "1 damage to target player … and 1 damage to target creature **that player**
 *   controls" depends on the answer given to its own **earlier targeting line** ([earlierTargets]) — the
 *   dependence that forces a card's several instances of the word "target" to be gathered in printed
 *   order rather than in one shot.
 *
 * Both are the same shape from the enumerator's side — "here is what has been settled so far" — so they
 * are one type rather than two parameters threaded separately. A third dependence (a chosen mode, a chosen
 * colour) becomes a third property here, and every restriction that ignores it keeps ignoring it.
 *
 * **[NONE] is the honest default, not a convenience.** An enumeration made with no context is one made for
 * an object that has announced nothing: [chosenX] is zero, which is what CR 202.3b says an unannounced X
 * is worth, and [earlierTargets] is empty, which makes a dependent restriction name *nothing* rather than
 * everything (see [PermanentRestriction.CREATURE_CONTROLLED_BY_TARGETED_PLAYER]). Both defaults fail
 * closed, so a call site that forgets to thread the context under-offers — a missing option, which a test
 * catches — rather than over-offering an illegal one, which ADR-005 treats as the worse defect.
 *
 * **It is not part of a spell's or ability's *record*.** Once the object is on the stack its announcements
 * live where they already lived — [dev.mtgplay.core.state.StackEntry.Spell.chosenX], the entry's own
 * `targets` list — and the CR 608.2b re-check rebuilds a context from *those* rather than carrying one
 * forward. There is therefore no second source of truth for what was announced.
 *
 * @property chosenX the value announced for the object's variable cost (CR 107.3, CR 601.2b); zero for an
 *   object whose cost carries no [dev.mtgplay.core.mana.ManaSymbol.X], and zero before the announcement.
 * @property earlierTargets the targets already chosen for this object's **preceding** targeting lines
 *   (CR 601.2c), in printed line order and then in the order chosen; empty while the first line is being
 *   enumerated and for every object printing the word "target" only once.
 */
data class TargetContext(
    val chosenX: Int = 0,
    val earlierTargets: PersistentList<Target> = persistentListOf(),
) {
    init {
        require(chosenX >= 0) { "CR 601.2b: an announced value of X is non-negative, was $chosenX" }
    }

    companion object {
        /** The context of an object that has announced nothing and chosen nothing yet. */
        val NONE: TargetContext = TargetContext()
    }
}
