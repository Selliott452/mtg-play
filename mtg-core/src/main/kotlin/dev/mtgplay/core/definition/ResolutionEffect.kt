package dev.mtgplay.core.definition

import dev.mtgplay.core.state.GameState

/**
 * A spell's resolution instructions (CR 608.2c): a **pure** function from the pre-resolution
 * state and the spell's [ResolutionContext] to the post-resolution state.
 *
 * The contract every card definition's resolution must satisfy:
 * - Pure and deterministic (ADR-002, ADR-006): no mutation, no ambient randomness — any
 *   randomness draws from the state's own PRNG and returns the successor state.
 * - It performs only the spell's instructions. The engine owns everything around it: the
 *   CR 608.2b target re-check happens before, and moving the spell's card to its owner's
 *   graveyard (CR 608.2m) happens after — an effect never moves the resolving card itself.
 * - It may append [dev.mtgplay.core.event.GameEvent]s describing what it did (derived
 *   observability, ADR-006), built from published effect primitives (`mtg-rules`); it never
 *   reads the event log.
 * - A choice mid-resolution (none exist in the P2.1 fixture pool) is **not** made by calling
 *   back into a player — it must surface as a `DecisionRequest` (ADR-004), machinery that
 *   arrives with the phase that first needs it and fails loudly until then.
 */
fun interface ResolutionEffect {
    /** Resolves the spell against [state], returning the successor state (CR 608.2c). */
    fun resolve(
        state: GameState,
        context: ResolutionContext,
    ): GameState
}
