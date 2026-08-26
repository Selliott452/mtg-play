package dev.mtgplay.core.definition

/**
 * The condition a **post-resolution clause** is conditional on (CR 608.2c) — the "if this spell was
 * bargained" in Torch the Tower's *"instead it deals 3 damage to that permanent **and you scry 1**"*.
 * Additive, flagged core (`W9-D`).
 *
 * **Every [ResolutionClauses] member is unconditional, and that is a real gap rather than a style.**
 * A clause is the part of a resolution the engine *orchestrates* around a pause (ADR-004), and the
 * orchestration runs it whenever the object resolves. A card whose scry happens only on one branch of
 * its own text therefore had two wrong encodings available and no right one: always scrying gives the
 * unbargained caster a look they did not pay for, and dropping the scry deletes half the reason the
 * card is bargained. Neither is a card. Declaring the condition beside the clause is the third option,
 * and it is the shape of every "if you did, [clause]" rider.
 *
 * **Not an [InterveningIf].** That one is CR 603.4 and governs whether a triggered ability *fires* — a
 * two-check rule about the stack, whose whole observable value is the trigger that never goes on it.
 * This governs whether an already-resolving object runs a clause, is checked exactly once, and cannot
 * stop anything from resolving. Folding them together would put a stack rule on an object that is past
 * the stack.
 *
 * Sealed for the reason [InterveningIf] and [CastCondition] are: a card printing a condition the engine
 * does not implement must break the rules-side `when` at compile time rather than defaulting to true
 * and running a clause the card does not print.
 */
sealed interface ClauseCondition {
    /**
     * "…if this spell was bargained" (CR 702.166b) — Torch the Tower's scry. True exactly when the
     * resolving **spell** paid its [SpellDefinition.optionalAdditionalCost]
     * ([dev.mtgplay.core.state.StackEntry.Spell.optionalCostPaid]).
     *
     * The spell-side twin of [InterveningIf.SourcePaidOptionalAdditionalCost], and named for the *cost
     * family* for that member's reason: a card declares at most one optional additional cost, so on a
     * bargain card this means "if it was bargained" and can mean nothing else. The two differ in what
     * they read the flag off — that one reads a permanent the spell became (CR 400.7 having severed the
     * link, so the flag is carried across), this one reads the spell's own cast record while the spell
     * is still on the stack, where no bridge is needed.
     *
     * **False for every ability**, which is a ruling and not a fallback: an ability is not cast
     * (CR 602.2a), has no additional costs of this kind, and a card that tried to gate an ability's
     * clause on one would be asking a question with no answer. The engine says so out loud rather than
     * quietly resolving it to `false`.
     */
    data object SpellPaidOptionalAdditionalCost : ClauseCondition
}
