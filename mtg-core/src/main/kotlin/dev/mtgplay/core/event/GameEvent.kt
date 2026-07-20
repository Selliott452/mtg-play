package dev.mtgplay.core.event

/**
 * The root of the typed game-event hierarchy.
 *
 * Events are the engine's observability channel (ADR-006): every transition appends the events
 * describing what happened to [dev.mtgplay.core.state.GameState.events], for replay display,
 * debugging, and drivers. They are **derived, never load-bearing**: rules logic must not read
 * them, and replay reconstructs state from `(MatchConfig, List<Decision>)`, never from events.
 *
 * Later packets grow this hierarchy *in this file* — events are nouns, so they live in core
 * even though the engine in `mtg-rules` emits them. P1.1 defines no concrete events: the only
 * operation core itself provides (object-id allocation) is bookkeeping rather than an
 * observable game happening, so the first emitters arrive with the engine skeleton (P1.2).
 */
sealed interface GameEvent
