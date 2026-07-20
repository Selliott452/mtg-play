package dev.mtgplay.core.definition

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.Target
import kotlinx.collections.immutable.PersistentList

/**
 * What a resolving spell knows beyond the game state (CR 608.2): who controls it and what it
 * targets.
 *
 * The casting pipeline records both on the stack entry at cast time (CR 601.2c) and hands them
 * to the [ResolutionEffect] here. Phase 5 grows this record — not the effect signature — with
 * the cast's linked information (what was discarded to an additional cost, the chosen mode),
 * per the docs/decklists.md design consequence that cost-payment results are part of the
 * spell's cast record.
 *
 * @property controller the spell's controller (CR 601.2 — the player who cast it).
 * @property targets the chosen targets, in the order chosen; empty for an untargeted spell.
 *   On resolution every entry is still legal — a spell whose targets have all become illegal
 *   never resolves (CR 608.2b), so the effect never sees one.
 */
data class ResolutionContext(
    val controller: PlayerId,
    val targets: PersistentList<Target>,
)
