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

/** A canonical descriptor of a target (CR 115.1): a player by seat, or a permanent by object id. */
internal fun renderTarget(target: Target): String =
    when (target) {
        is Target.Player -> "player${target.id.seat}"
        is Target.Permanent -> "permanent${target.id.value}"
    }
