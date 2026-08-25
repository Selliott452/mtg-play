package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.GameState

/*
 * The CR 702.2 deathtouch seams. Deathtouch is not a flag combat consults once — it is a rewrite of
 * what the word *lethal* means — so the keyword is read at three decision points and each reads it
 * here rather than re-deriving it (CONVENTIONS.md: one definition, many consumers).
 */

/**
 * Whether the battlefield object [id] has deathtouch right now (CR 702.2) — the single seam every
 * lethality decision consults, so a granted deathtouch (CR 613 layer 6) is honoured at all of them.
 *
 * There are three such decisions:
 * - [assignBlockedDamage]'s CR 510.1c lethal-damage assignment — with deathtouch, **1** is lethal to
 *   each blocker (CR 702.2b), so the rest of the attacker's power is free to go elsewhere;
 * - the CR 702.19b trample excess, which is the same arithmetic seen from the other end;
 * - the CR 704.5h state-based action, which reaches the fact through the recorded
 *   [dev.mtgplay.core.state.GameObject.dealtDeathtouchDamage] rather than through this predicate,
 *   because by then the source may be gone — which is exactly why the fact is recorded when the damage
 *   lands ([sourceHasDeathtouch]) instead of being re-read at the check.
 *
 * A missed site here is not a mis-played keyword but a mis-enumerated action set (ADR-005): the
 * trample assignment's option list is the range `0..excess`, so a wrong excess offers illegal amounts
 * or hides legal ones.
 */
internal fun hasDeathtouch(
    state: GameState,
    id: ObjectId,
): Boolean = Keyword.DEATHTOUCH in effectiveKeywords(state, id)

/**
 * Whether the damage [source] is a source with deathtouch (CR 702.2b) — asked once, at the moment the
 * damage is marked, so the CR 704.5h condition survives the source's departure (CR 113.7a).
 *
 * **Lenient by design.** A [DamageSource] names an id and a printed card, and the id may name nothing
 * on the battlefield: a spell resolving from the stack, an ability whose source has already died. Only
 * a *permanent* can have a layered keyword, so a source that is not a battlefield object simply has no
 * deathtouch, and answering `false` is the correct reading rather than a swallowed error. Combat's own
 * sources are always present when their damage is computed (CR 510.2 simultaneity), so the strict case
 * is unreachable there anyway.
 */
internal fun sourceHasDeathtouch(
    state: GameState,
    source: DamageSource,
): Boolean =
    source.objectId?.let { id ->
        state.sharedZones.battlefield.any { it.id == id } && hasDeathtouch(state, id)
    } ?: false
