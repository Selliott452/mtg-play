package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaCost

/**
 * A "counter target spell **unless its controller pays** [cost]" clause (CR 701.5, CR 118.3a) — Force
 * Spike's `{1}`, Spell Pierce's `{2}`. Additive, flagged core (`FW-COUNTER`,
 * docs/design/countering-spells.md §7.1).
 *
 * Declarative rather than a [ResolutionEffect] because the payment is a **decision**, and ADR-004
 * forbids a callback: a choice made mid-resolution surfaces as a `DecisionRequest`. So the definition
 * states the clause and the engine orchestrates it, exactly as [LibraryReveal], [LibraryLook],
 * [OptionalCostThenDraw], and [DrawThenDiscard] are orchestrated.
 *
 * Three rules facts the shape encodes:
 * - **The decider is the *target's* controller, not the counter's** (CR 118.3a) — the first decision in
 *   this engine made by someone other than the resolving object's controller. It is not a cast and
 *   grants nobody priority, so the counter's controller cannot respond to it.
 * - **[cost] is a new payment printed on the counter**, not a re-read of what the target cost or what
 *   was paid for it. Nothing about the target's cost is inspected; docs/design/countering-spells.md §1.1
 *   records that the upstream brief had this backwards.
 * - **Declining and being unable to pay are the same answer** (CR 118.3a): the spell is countered
 *   either way. Paying makes the counter resolve having done nothing, and it still goes to its owner's
 *   graveyard as a *resolved* spell — not a countered one.
 *
 * @property cost the mana the target spell's controller must pay to save it (CR 118.3a).
 */
data class CounterUnlessPaid(
    val cost: ManaCost,
)
