package dev.mtgplay.core.definition

import dev.mtgplay.core.card.Subtype

/**
 * A description of the battlefield permanents a card's text picks out — "an Urza's Power-Plant you
 * control", "each Elf on the battlefield" (CR 109.4, CR 205.3). The declaration half of a
 * board-state count; `mtg-rules` owns deciding which objects match it (ADR-009), reading the
 * permanent's **layered** subtypes so a type-changing effect is honoured for free.
 *
 * Declarative data rather than a predicate lambda, for the same three reasons
 * [PermanentRestriction] is an enum: a card definition is data (ADR-003); the value takes part in
 * structural equality inside `SourceClassKey`, which is what lets two identically-conditioned mana
 * sources collapse into one payment class (docs/design/mana-payment.md §2); and it is
 * serialisable onto the wire and renderable in the CLI.
 *
 * Deliberately narrow. It carries exactly the two axes the pool's cards print — a subtype and who
 * controls the permanent — and nothing else. Card type, keyword, power and zone are *absent*
 * rather than stubbed: cost-modification.md §10 (C1) plans a general `ObjectPredicate` covering
 * them, and this is its seed, not a competitor. Widening it is a matter of adding a property and
 * breaking the rules-side matcher, which is the intended way a gap is found.
 *
 * @property subtype the subtype every matching permanent must have (CR 205.3) — `Subtype("Elf")`,
 *   `Subtype("Urza's Tower")`. Note the Urza land types are printed **hyphenated**:
 *   the type line reads `Land — Urza's Power-Plant`, not `Urza's Power Plant`, which is the card's
 *   *name*. Getting that wrong is silent — the count would simply always be zero.
 * @property controlledByYou whether the permanent must be controlled by the object whose text this
 *   is (Urza's Mine's "**you control**"), or may be controlled by anyone (Priest of Titania's
 *   "each Elf **on the battlefield**").
 */
data class PermanentFilter(
    val subtype: Subtype,
    val controlledByYou: Boolean,
)
