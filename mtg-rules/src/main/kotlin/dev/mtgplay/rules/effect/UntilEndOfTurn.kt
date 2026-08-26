package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.TimedContinuousEffect
import dev.mtgplay.rules.engine.emit

/**
 * Effect primitive: creates an **until-end-of-turn** continuous effect on the battlefield permanent
 * [affected] (CR 611.2, CR 514.2) — the published building block a "target creature gets +X/+X until
 * end of turn" or "target permanent gains hexproof until end of turn" resolution composes (ADR-003;
 * Timberwatch Elf is the first client). `FW-DURATION`, docs/design/duration.md §5.3.
 *
 * **The magnitudes arrive already snapshotted, and that is the point** (CR 608.2h, CR 611.2d). A
 * card whose modifier is "+X/+X, where X is the number of Elves on the battlefield" performs that
 * count *in its own resolution effect* and hands the result here as an `Int`. This function has no
 * way to accept a state-reading function, so the CR 613.3c live-recount semantics of
 * [dev.mtgplay.core.definition.Magnitude.Dynamic] — correct for a static Aura, wrong for a resolved
 * pump — cannot be reached by accident: playing a fourth Gate after the ability resolved does not
 * grow an already-created effect (docs/gauntlet-card-triage.md T16).
 *
 * **Timestamp** (CR 613.7d): allocated from the state's monotonic id sequence, so the new effect
 * orders correctly against Aura timestamps in the CR 613.7 within-layer sort (docs/design/duration.md
 * §4). Deterministic and replay-safe by construction (ADR-006) — no randomness is involved.
 *
 * The effect is stored, never written into the object: a bonus written onto a
 * [dev.mtgplay.core.state.GameObject] would have to be stripped on every zone move (CR 400.7) and
 * would carry no layer or timestamp, making it unorderable against an Aura. Characteristics stay
 * computed on read, in one place (docs/design/layer-system.md §5).
 *
 * Fails loudly if [affected] is not on the battlefield: every caller arrives after the CR 608.2b
 * re-check has confirmed its target is still a legal battlefield permanent (ADR-005), so a missing
 * one is an engine defect, not a rules case. A grant-nothing, modify-nothing effect classifies into
 * no implemented CR 613 layer and is refused by [ContinuousModification] itself — the same gate that
 * refuses it at application, applied at creation.
 *
 * @param affected the permanent the effect modifies; its identity is fixed now (CR 611.2c).
 * @param modification what the effect does: layer-6 grants and snapshotted layer-7c modifiers.
 * @param sourceCard the printed identity that created the effect, for narration and replay.
 * @param source the resolving object's own id (CR 113.7c last-known information), or `null`.
 */
fun applyUntilEndOfTurn(
    state: GameState,
    affected: ObjectId,
    modification: ContinuousModification,
    sourceCard: CardRef,
    source: ObjectId? = null,
): GameState = state.storingContinuousEffect(affected, modification, EffectDuration.UntilEndOfTurn, sourceCard, source)

/**
 * Effect primitive: creates a continuous effect on the battlefield permanent [affected] that has **no
 * duration at all** (CR 611.2b) — it lasts as long as the game does. `FW-TYPECHANGE`; Kenku
 * Artificer's "that artifact becomes a 0/0 Homunculus artifact creature with flying" is the first
 * client, and the only reason this is a second published verb rather than a duration parameter on
 * [applyUntilEndOfTurn] is that a card author must have to *name* the duration they mean.
 *
 * **The mistake this exists to make impossible.** CR 611.2b says an effect with no stated duration
 * lasts until the game ends, and until now the engine could only represent "until end of turn" — so
 * the only way to encode a permanent type change was as a turn-long one, a card that plays correctly
 * right up to the cleanup step and then silently un-does itself. That failure leaves no trace in any
 * log; the artifact simply stops being a creature between turns, and a `+1/+1` counter it is carrying
 * becomes inert (CR 122.1a) rather than illegal. Everything else about the effect — its CR 613.7d
 * timestamp, its store, its layer classification — is identical to a timed one, which is why they
 * share [applyContinuousEffect] and differ in exactly one argument.
 *
 * Nothing ends the effect. It survives the CR 514.2 cleanup turn-based action (the `when` there is
 * exhaustive over [EffectDuration], so this member had to be answered explicitly), and it keeps naming
 * [affected] even after that object has left the battlefield — at which point it applies to nothing,
 * because the permanent that returns is a different object (CR 400.7).
 *
 * @param affected the permanent the effect modifies; its identity is fixed now (CR 611.2c).
 * @param modification what the effect does: the layer-4 type change, layer-6 grants, layer-7b set-P/T
 *   and snapshotted layer-7c modifiers.
 * @param sourceCard the printed identity that created the effect, for narration and replay.
 * @param source the resolving object's own id (CR 113.7c last-known information), or `null`.
 */
fun applyIndefinitely(
    state: GameState,
    affected: ObjectId,
    modification: ContinuousModification,
    sourceCard: CardRef,
    source: ObjectId? = null,
): GameState = state.storingContinuousEffect(affected, modification, EffectDuration.Indefinite, sourceCard, source)

/**
 * The shared body of [applyUntilEndOfTurn] and [applyIndefinitely]: store one
 * [TimedContinuousEffect] with the given [duration] and narrate it.
 *
 * Private, and the two public verbs above are the vocabulary (ADR-003). A single public function
 * taking a duration would read as a menu at every call site; the point of separate verbs is that
 * choosing "no duration" is a decision a card definition states in words, not a default it can fall
 * into by leaving an argument off.
 */
private fun GameState.storingContinuousEffect(
    affected: ObjectId,
    modification: ContinuousModification,
    duration: EffectDuration,
    sourceCard: CardRef,
    source: ObjectId?,
): GameState {
    val state = this
    require(state.sharedZones.battlefield.any { it.id == affected }) {
        "CR 611.2c: a continuous effect's affected object is fixed when the effect begins, " +
            "but $affected is not on the battlefield"
    }
    val (timestampId, allocated) = state.allocateObjectId()
    val effect =
        TimedContinuousEffect(
            affected = affected,
            modification = modification,
            duration = duration,
            timestamp = timestampId.value,
            createdOnTurn = state.turn.number,
            source = source,
            sourceCard = sourceCard,
        )
    return allocated
        .copy(timedEffects = allocated.timedEffects.adding(effect))
        .emit(
            GameEvent.ContinuousEffectCreated(
                sourceCard = sourceCard,
                affected = affected,
                powerMod = modification.powerMod,
                toughnessMod = modification.toughnessMod,
            ),
        )
}
