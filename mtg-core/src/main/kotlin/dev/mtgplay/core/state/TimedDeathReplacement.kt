package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import kotlinx.collections.immutable.PersistentSet

/**
 * What a **delayed** replacement effect does to a permanent that would die (CR 614.1a, CR 700.4) — the
 * payload a [TimedDeathReplacement] carries. Additive, flagged core (`W9-D`).
 *
 * Sealed with exactly one member so `mtg-rules` applies it exhaustively: a second death replacement
 * ("regenerate it instead", "put it on top of its owner's library instead") must break the application
 * `when` at compile time rather than defaulting into the exile this one performs.
 */
sealed interface DeathReplacement {
    /**
     * "…exile it instead" (CR 614.1a, CR 701.3a) — Torch the Tower's *"If a permanent dealt damage by
     * Torch the Tower would die this turn, exile it instead."*
     *
     * A `data object` because there is nothing to vary: the replacement names one destination and the
     * *which permanents* half is [TimedDeathReplacement.affected], not part of the payload.
     */
    data object ExileInstead : DeathReplacement
}

/**
 * One **delayed** replacement effect created by the resolution of a spell or ability (CR 614.1a,
 * CR 603.7a), watching a set of *other* objects for the rest of a duration, held in
 * [GameState.deathReplacements] until that duration expires. Additive, flagged core (`W9-D`).
 *
 * **The third store that hangs off no object**, after [TimedContinuousEffect] (`FW-DURATION`) and
 * [TimedPreventionEffect] (`FW-PREVENT2`), and a separate one from both for the same reason those two
 * are separate from each other: what it modifies, and where the engine reads it, are different.
 * A [TimedContinuousEffect] classifies into a CR 613 layer and is read when characteristics are
 * computed; a [TimedPreventionEffect] is read at the CR 615 damage-prevention application point; this
 * is read at the CR 614 interception point of a **battlefield-to-graveyard move**, which is neither.
 *
 * **Why the card's own [dev.mtgplay.core.definition.ReplacementEffect] list cannot hold it.** That list
 * declares replacements a card carries *wherever it sits*, watching **its own** zone changes — madness's
 * discard-to-exile, flashback's leave-stack-to-exile. This effect is created by a *resolution*, applies
 * to permanents the card is not and never was, and ends on a clock. Nothing about it is a property of
 * the printed card, so it lives here in `core/state` beside the other things a [GameState] contains.
 *
 * **The affected set is fixed when the effect is created** (CR 614.1a's "a permanent dealt damage by
 * this spell" is settled by the time the spell finishes resolving), and it is a set of live
 * [ObjectId]s rather than a predicate. That is the CR 400.7 answer rather than a shortcut: a permanent
 * that leaves the battlefield and comes back is a **new object** and was not dealt damage by anything,
 * so an id set stops applying to it exactly when the rules say it should. A predicate over cards would
 * keep applying and would be wrong.
 *
 * **No timestamp**, for [TimedPreventionEffect]'s reason: CR 613.7d timestamps order effects within a
 * CR 613 layer, and this is not a layer effect. Two death replacements on one permanent would need the
 * CR 616.1 *affected player's choice*, not a timestamp — and the first pair that can genuinely disagree
 * is what must add that choice; today the pool prints one shape, so any number of them agree.
 *
 * **Core/rules split (ADR-009).** This is the record of what was created; deciding that a move is a
 * death, intercepting it, and ending the effect at CR 514.2 are all `mtg-rules`.
 *
 * @property effect what this replacement does to a death it catches.
 * @property affected the permanents this replacement watches (CR 614.1a), by their **battlefield**
 *   object ids. A set rather than a list because membership is the only question ever asked of it and
 *   an order would imply a precedence there is none of.
 * @property duration how long the replacement lasts (CR 611.2). The gauntlet's one printing is "this
 *   turn" (CR 514.2), and the type is shared with [TimedContinuousEffect] and [TimedPreventionEffect]
 *   so the cleanup step ends all three through the same exhaustive `when`.
 * @property createdOnTurn the [Turn.number] this replacement was created on, stored so the duration
 *   contract is machine-checkable exactly as [TimedContinuousEffect.createdOnTurn] is: an
 *   [EffectDuration.UntilEndOfTurn] replacement surviving into a later turn means the CR 514.2 wear-off
 *   failed.
 * @property source the object whose resolution created the replacement, as last-known information
 *   (CR 113.7c); `null` where the engine has none. Narration and replay only — the replacement does not
 *   depend on the source still existing, which is the whole point of a *delayed* effect.
 * @property sourceCard the printed identity behind [source] (CR 113.7c), which resolves from the
 *   definition registry whatever zone the source has since reached.
 */
data class TimedDeathReplacement(
    val effect: DeathReplacement,
    val affected: PersistentSet<ObjectId>,
    val duration: EffectDuration,
    val createdOnTurn: Int,
    val source: ObjectId?,
    val sourceCard: CardRef,
) {
    init {
        require(createdOnTurn >= 1) { "CR 500: a turn number is at least 1, was $createdOnTurn" }
        require(affected.isNotEmpty()) {
            "CR 614.1a: a delayed death replacement watches at least one permanent, but its affected set is empty"
        }
    }
}
