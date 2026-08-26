package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.DeathReplacement
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.TimedDeathReplacement
import dev.mtgplay.rules.engine.damageIsPrevented
import dev.mtgplay.rules.engine.emit
import kotlinx.collections.immutable.toPersistentSet

/**
 * Effect primitive: creates a **delayed replacement effect** saying that each permanent in [affected],
 * if it would die this turn, is exiled instead (CR 614.1a, CR 603.7a, CR 700.4) — the published building
 * block a "if a permanent dealt damage by this would die this turn, exile it instead" rider composes
 * (ADR-003; Torch the Tower is the first client). `W9-D`.
 *
 * **A primitive rather than a fold left to the card**, for the reason every effect primitive here is one
 * and one that is specific to this shape. A card definition cannot express this at all: the replacement
 * has to be *read* at four different battlefield-to-graveyard sites deep inside `mtg-rules`
 * (`DeathReplacements.kt` lists them), each of which a card can neither see nor wrap. What a card can do
 * is name the permanents and the duration, and that is exactly this signature.
 *
 * **Why it is not a [dev.mtgplay.core.definition.ReplacementEffect] on the definition.** That list is for
 * replacements a card carries wherever it sits, watching its own zone changes; this one is created by a
 * resolution, watches other objects, and ends at CR 514.2. See [TimedDeathReplacement] for the argument
 * in full.
 *
 * The affected ids are the permanents' **battlefield** ids and are fixed now (CR 614.1a). A permanent
 * that leaves the battlefield and returns is a new object (CR 400.7) that this no longer watches, which
 * is the correct reading and comes free from using ids rather than a card predicate. An id that is not on
 * the battlefield is *not* an error — a caller may legitimately have damaged something that has since
 * died to a state-based action — and the replacement simply never catches it.
 *
 * A no-op for an empty [affected]: a rider that watches nothing is what "the damage was all prevented"
 * looks like, and creating a store entry for it would be an entry nothing could ever match.
 *
 * @param affected the permanents the replacement watches, by battlefield object id.
 * @param sourceCard the printed identity that created the replacement, for narration and replay.
 * @param source the resolving object's own id (CR 113.7c last-known information), or `null`.
 */
fun exileInsteadOfDyingThisTurn(
    state: GameState,
    affected: Collection<ObjectId>,
    sourceCard: CardRef,
    source: ObjectId? = null,
): GameState {
    if (affected.isEmpty()) return state
    val created =
        TimedDeathReplacement(
            effect = DeathReplacement.ExileInstead,
            affected = affected.toPersistentSet(),
            duration = EffectDuration.UntilEndOfTurn,
            createdOnTurn = state.turn.number,
            source = source,
            sourceCard = sourceCard,
        )
    return state
        .copy(deathReplacements = state.deathReplacements.adding(created))
        .emit(GameEvent.DeathReplacementCreated(sourceCard, created.affected.toList()))
}

/**
 * Effect primitive: [source] deals [amount] damage to the permanent [recipient], and — **only if that
 * damage was actually dealt** — the permanent is exiled instead of dying for the rest of the turn
 * (CR 120, CR 614.1a). Torch the Tower's whole body.
 *
 * A primitive rather than two calls in the card, because the "only if" is a fact a card cannot see. CR
 * 614.1a's rider says *"a permanent **dealt damage** by this spell"*, and CR 615.6 says prevented damage
 * is never dealt at all — so a creature under Prismatic Strands' red shield is not dealt damage by Torch
 * the Tower, is not one of the permanents the rider names, and must go to the graveyard normally when
 * something else kills it later. [dev.mtgplay.rules.engine.damageIsPrevented] is `internal` to
 * `mtg-rules` and the [GameEvent] log is not a rules input, so a card composing [dealDamage] with
 * [exileInsteadOfDyingThisTurn] by hand would have no way to ask the question and would silently mark a
 * permanent it never touched. Asking it here, once, is the only correct place.
 *
 * Zero damage is the same case for CR 120.8's reason rather than CR 615's, and takes the same branch: it
 * is not dealt, so nothing is watched.
 *
 * The replacement's [TimedDeathReplacement.sourceCard] is [source]'s own [DamageSource.card] rather than a
 * separate parameter: CR 614.1a's rider is created by the thing that dealt the damage, so a signature that
 * let the two disagree would let a card narrate a replacement as coming from something else.
 *
 * @param recipient the permanent being damaged; a permanent rather than a [Target] because a player
 *   cannot die and a rider about dying has nothing to say about one.
 */
fun dealDamageThenExileIfItWouldDie(
    state: GameState,
    source: DamageSource,
    recipient: ObjectId,
    amount: Int,
): GameState {
    val target = Target.Permanent(recipient)
    // CR 120.8 and CR 615.6: damage that is not dealt makes this permanent no part of the rider's set.
    val dealt = amount > 0 && !damageIsPrevented(state, source, target)
    val damaged = dealDamage(state, source, target, amount)
    return if (dealt) {
        exileInsteadOfDyingThisTurn(damaged, listOf(recipient), source.card, source.objectId)
    } else {
        damaged
    }
}
