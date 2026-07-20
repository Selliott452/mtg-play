package dev.mtgplay.rules.engine

import dev.mtgplay.rules.decision.PriorityOption

/**
 * Enumerates the legal options for the player holding priority (ADR-005) — the single source of
 * legality truth: what this returns is exactly what the engine will accept, and nothing else is
 * representable.
 *
 * In P1.2 the only legal option is [PriorityOption.Pass] — no cards are castable, no lands
 * playable, no abilities activatable — so the enumeration is constant. P2.1 grows the signature
 * to consult the game state (castable spells, playable lands) and this remains the one place
 * legality lives.
 */
internal fun legalPriorityOptions(): List<PriorityOption> = listOf(PriorityOption.Pass)
