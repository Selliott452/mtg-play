package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState

/**
 * Whether the permanent [definition] entering the battlefield under [controller]'s control enters
 * **tapped** (CR 614.1c) — the self-replacement effect resolved at the moment of entry.
 *
 * **When this is read is the whole point.** A CR 614.1c replacement modifies the entering event
 * itself, so its condition is evaluated exactly as the permanent enters and never again: the answer
 * becomes the object's tapped status and stops being a question. Gingerbread Cabin entering with two
 * Forests out is tapped forever after, even if a third Forest arrives a moment later, because the
 * clause replaced an event rather than creating a continuous effect.
 *
 * That instant also settles "other" for free. The entering permanent has not joined the battlefield
 * when this runs — both call sites compute the tapped flag while constructing the object — so
 * [countMatching] cannot see it, and "three or more **other** Forests" needs no exclusion to be
 * exact. The alternative, counting after the object is added and subtracting one, would be right
 * only for the cards that print "other" and silently wrong for any that does not.
 *
 * A `null` [definition] (an unregistered card) enters untapped, the CR 110.5a default.
 *
 * Exhaustive over [EntersTapped], so a new printed shape breaks compilation here rather than
 * defaulting to untapped.
 */
internal fun entersTappedNow(
    state: GameState,
    controller: PlayerId,
    definition: CardDefinition?,
): Boolean =
    when (val clause = definition?.entersTapped ?: EntersTapped.Never) {
        EntersTapped.Never -> false
        EntersTapped.Always -> true
        // "…unless you control N or more" — tapped exactly when the count falls short (CR 614.1c).
        is EntersTapped.UnlessYouControl -> countMatching(state, controller, clause.filter) < clause.atLeast
    }
