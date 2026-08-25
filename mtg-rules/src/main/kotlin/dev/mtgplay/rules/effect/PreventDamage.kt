package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PreventionEffect
import dev.mtgplay.core.state.TimedPreventionEffect
import dev.mtgplay.rules.engine.emit

/*
 * The two **global** prevention effects a resolution can create (CR 615), as published primitives
 * (ADR-003). Additive, flagged (`FW-PREVENT2`), and the second half of the framework
 * docs/design/protection.md §3 Part B sized: `FW-PREVENT` built the *application point*
 * (`engine/Prevention.kt`), and these build the two things that can be stored for it to read.
 *
 * **Why these are not [applyUntilEndOfTurn] with a different payload.** That primitive fixes an
 * affected object (CR 611.2c) and produces a CR 613-layered characteristic change. Neither card here
 * has an affected object — Prismatic Strands' shield covers every permanent and both players, Flaring
 * Pain's disabler covers nothing at all — and neither changes any characteristic of anything. They go
 * to their own store, which is what [dev.mtgplay.core.state.PreventionEffect] exists to justify.
 *
 * Both are created **without a CR 613.7d timestamp**, and that is a ruling: prevention effects do not
 * compose, so there is no within-layer order for one to decide (see [TimedPreventionEffect]).
 */

/**
 * Effect primitive: **prevent all damage that sources of [color] would deal this turn** (CR 615.1) —
 * Prismatic Strands. The published building block a colour-shield resolution composes.
 *
 * The colour is a value by the time it arrives here, snapshotted by the CR 609.4 choice that the
 * resolution clause made ([dev.mtgplay.core.definition.ChosenColorEffect]) — exactly as
 * [applyUntilEndOfTurn]'s magnitudes are. There is deliberately nowhere to put a colour that is
 * re-read later: the choice is made once, as the spell resolves, and does not follow a source that
 * changes colour afterwards.
 *
 * **Creating a shield is always legal and never fails**, however the board looks and whether or not a
 * CR 615.9 disabler is already out. Flaring Pain does not stop Prismatic Strands resolving; it stops
 * the shield *applying* (`engine/Prevention.kt`), which is the difference CR 615.9 draws and the
 * reason this function has no gate.
 *
 * @param color the chosen colour whose sources' damage is prevented.
 * @param sourceCard the printed identity that created the effect, for narration and replay.
 * @param source the resolving object's own id (CR 113.7c last-known information), or `null`.
 */
fun preventDamageFromColorThisTurn(
    state: GameState,
    color: Color,
    sourceCard: CardRef,
    source: ObjectId? = null,
): GameState = addPreventionEffect(state, PreventionEffect.PreventDamageFromColor(color), sourceCard, source)

/**
 * Effect primitive: **damage can't be prevented this turn** (CR 615.9) — Flaring Pain. The published
 * building block a prevention-off resolution composes.
 *
 * **It removes nothing.** CR 615.9 says a prevention effect that can't be applied "simply doesn't do
 * anything", so this adds a fact rather than deleting shields: a shield created after it still exists,
 * still expires at CR 514.2, and still does nothing while this is in force. Modelling it as a purge of
 * the store would get the same life totals this turn and the wrong answer for a shield created later,
 * and would make the effect's own duration meaningless.
 *
 * Stacking is a no-op by construction: a second copy is a second store entry, and the predicate that
 * reads them is a presence test.
 *
 * @param sourceCard the printed identity that created the effect, for narration and replay.
 * @param source the resolving object's own id (CR 113.7c last-known information), or `null`.
 */
fun damageCannotBePreventedThisTurn(
    state: GameState,
    sourceCard: CardRef,
    source: ObjectId? = null,
): GameState = addPreventionEffect(state, PreventionEffect.DamageCantBePrevented, sourceCard, source)

/**
 * The shared store append: records [effect] as an until-end-of-turn global effect and narrates it.
 * Both primitives differ only in the payload, so the bookkeeping — the duration, the turn stamp, the
 * last-known source, the event — is written once.
 */
private fun addPreventionEffect(
    state: GameState,
    effect: PreventionEffect,
    sourceCard: CardRef,
    source: ObjectId?,
): GameState {
    val created =
        TimedPreventionEffect(
            effect = effect,
            duration = EffectDuration.UntilEndOfTurn,
            createdOnTurn = state.turn.number,
            source = source,
            sourceCard = sourceCard,
        )
    return state
        .copy(preventionEffects = state.preventionEffects.adding(created))
        .emit(GameEvent.PreventionEffectCreated(sourceCard, effect))
}
