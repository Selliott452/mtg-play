package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CounterAmount
import dev.mtgplay.core.definition.EntersWithCounters
import dev.mtgplay.core.state.Counter
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * The counters a permanent whose [definition] prints a CR 614.1c "enters with N counters" clause enters
 * the battlefield **with** — the counter multiset the entering [dev.mtgplay.core.state.GameObject] is
 * constructed carrying. Empty for every permanent with no such clause. Additive (`W10-C`).
 *
 * **[entersTappedNow]'s sibling, read at the same instant and for the same reason.** Both are CR 614.1c
 * self-replacements: they modify the *entering event*, so their answer becomes a property of the object
 * and stops being a question. That instant is the whole of the correctness here, and more sharply than
 * for the tapped status. A Nyxborn Hydra cast for X = 3 is a 3/4 the first time anything looks at it —
 * including the CR 704.5f state-based-action check, which would put the 0/1 body's *counterless* self
 * into a graveyard if the counters arrived a moment later as a triggered ability would deliver them.
 * There is no window in which the smaller creature exists, no trigger to respond to, and nothing on the
 * stack (CR 614.1a).
 *
 * @param chosenX the value of X announced when the spell that became this permanent was cast (CR 107.3,
 *   CR 601.2b), or `0` for a permanent that reached the battlefield without a cast — a token, a
 *   reanimation, a land played. That zero is CR 107.3b's own answer rather than a fallback: there was no
 *   announcement, so X is zero, and a [CounterAmount.AnnouncedX] clause places nothing.
 */
internal fun entersWithCountersNow(
    definition: CardDefinition?,
    chosenX: Int,
): PersistentMap<Counter, Int> = countersFrom(definition?.entersWithCounters, chosenX)

/**
 * The counter multiset one CR 614.1c "enters with N counters" [clause] places, empty for `null`.
 *
 * Split out of [entersWithCountersNow] by `W11` because the clause has a **second source**: a card
 * whose own definition prints none can still be put onto the battlefield by an effect that says "with
 * three `+1/+1` counters on it" (Throne of the Dead Three), and CR 614.1c is the same rule either way.
 * Reading it from the clause rather than from the definition is what lets the two be *added* at the
 * entry site instead of one of them winning.
 */
internal fun countersFrom(
    clause: EntersWithCounters?,
    chosenX: Int,
): PersistentMap<Counter, Int> {
    if (clause == null) return persistentMapOf()
    val amount =
        when (val declared = clause.amount) {
            is CounterAmount.Fixed -> declared.amount
            // CR 107.3: the number announced for whichever cost the spell was actually cast for — a
            // Hydra cast for its bestow cost reads the X announced there, which is the same rule.
            CounterAmount.AnnouncedX -> chosenX
        }
    // CR 614.1c with CR 122.1: X may be announced as zero, and a permanent that would enter with zero
    // counters simply enters with none. Not a clamp of a negative number — an announcement is
    // non-negative by construction (CR 601.2b) — so anything below zero here is an engine defect.
    require(amount >= 0) {
        "CR 601.2b: an enters-with-counters clause resolved to $amount counters; an announced value of " +
            "X is non-negative and a printed amount is at least one"
    }
    return if (amount == 0) persistentMapOf() else persistentMapOf(clause.counter to amount)
}
