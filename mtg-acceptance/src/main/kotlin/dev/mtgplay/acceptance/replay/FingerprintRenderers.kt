package dev.mtgplay.acceptance.replay

import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.Target

/*
 * Small canonical-descriptor renderers factored out of Fingerprint.kt so that file stays within its
 * function budget. Each renders one rules-relevant fragment of the [Fingerprint] pre-image.
 */

/**
 * A canonical descriptor of a fired triggered ability (CR 603.3): its source, controller, condition, and
 * the trigger's linked information — never the resolution effect, which has reference identity only
 * (ADR-009) and is excluded from the digest like every card definition.
 */
internal fun renderTrigger(trigger: PendingTrigger): String =
    buildString {
        append(trigger.sourceCard.name)
        append('@').append(trigger.sourceId.value)
        append(':').append(trigger.controller.seat)
        append(':').append(trigger.ability.condition::class.simpleName ?: "?")
        append(":amt=").append(trigger.amount)
        append(":subj=").append(trigger.subject?.value ?: "-")
    }

/** The pre-game mulligan phase (CR 103.4/103.5) by cause: whose decision, count, and stage, or "-". */
internal fun renderPendingMulligan(state: GameState): String =
    state.pendingMulligan?.let { "${it.deciding.seat}:${it.mulliganCount}:${it.stage.name}" } ?: "-"

/**
 * A canonical descriptor of a target (CR 115.1): a player by seat, a permanent by object id, or a spell
 * on the stack by its stack-residence object id. The two object kinds are rendered under distinct
 * prefixes because their ids live in different zones and could otherwise collide in a fingerprint.
 */
internal fun renderTarget(target: Target): String =
    when (target) {
        is Target.Player -> "player${target.id.seat}"
        is Target.Permanent -> "permanent${target.id.value}"
        is Target.SpellOnStack -> "spell${target.id.value}"
    }

/**
 * Digests the running resolution-generated continuous effects (CR 611.2, `FW-DURATION`,
 * docs/design/duration.md §7), in store order — which is creation order, which is timestamp order,
 * because the store is append-only, so the token is order-stable without a sort.
 *
 * This is the first cause with no residence line. docs/design/layer-system.md §5's rule is to digest
 * the *cause* of a continuous effect and never its computed values, and an Aura's cause is its
 * `attachedTo`, already covered. A timed effect hangs off no object at all: without a token of its
 * own, two states differing only in whether a pump resolved would hash identically and a replay
 * divergence there would be invisible.
 *
 * The snapshotted modifiers **are** the cause here, not a computed value (CR 608.2h, CR 611.2d): they
 * were frozen at creation and no state the digest already covers determines them any more — the Gate
 * count that produced a `+3/+3` may since have changed. Digesting them therefore follows §5's rule
 * rather than excepting it, and omitting them would let two differently-sized pumps hash alike.
 * `createdOnTurn` is left out on the same principle: it equals `turn.number` for every effect the
 * store may legally hold, so it can never vary independently.
 */
internal fun StringBuilder.appendTimedEffects(state: GameState) {
    state.timedEffects.forEach { effect ->
        append("|timed=").append(effect.affected.value)
        append(':').append(effect.modification.powerMod)
        append('/').append(effect.modification.toughnessMod)
        append(':').append(effect.modification.grantedKeywords.joinToString("+") { it.name })
        append(':').append(effect.duration::class.simpleName)
        append('@').append(effect.timestamp)
        append(':').append(effect.sourceCard.name)
        append('@').append(effect.source?.value ?: "-")
    }
}
