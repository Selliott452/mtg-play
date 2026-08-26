package dev.mtgplay.acceptance.replay

import dev.mtgplay.core.state.GameState

/*
 * The mid-resolution and gathering pauses of the replay fingerprint, split out of `Fingerprint.kt` when
 * `W10-D`'s explore pause pushed that file past detekt's function budget.
 *
 * The seam is the one the budget happened to fall on and is also a real one: everything here digests a
 * **pause** — a position the engine is stopped at, waiting to be told something — while what stays in
 * `Fingerprint.kt` digests the board. Two positions that differ only in what a paused clause is holding
 * are different positions, and a replay that could not tell them apart would confirm a divergence rather
 * than catch it.
 */

// The P6.2a mid-resolution / gathering pauses, each digested by cause (whose choice, which object).
internal fun StringBuilder.appendP62aPendingPositions(state: GameState) {
    append("|pendingColour=").append(state.pendingColorChoice?.let { it.decider.seat.toString() } ?: "-")
    append("|pendingActivation=")
    append(
        state.pendingActivation?.let {
            "${it.activator.seat}:${it.sourceObjectId.value}:${it.abilityIndex}:" +
                (it.chosenDiscard?.joinToString("+") { id -> id.value.toString() } ?: "-") + ":" +
                (it.chosenTargets?.joinToString("+") { target -> renderTarget(target) } ?: "-") + ":" +
                // CR 602.1: the chosen-object cost components decide what the activation will do to the
                // board and what its payment plan may tap, so two gatherings differing only in them are
                // different positions. Added by `FW-TAPUNTAP`, which also picked up the sacrifice half.
                (it.chosenSacrifice?.joinToString("+") { id -> id.value.toString() } ?: "-") + ":" +
                (it.chosenReturn?.joinToString("+") { id -> id.value.toString() } ?: "-")
        } ?: "-",
    )
    append("|pendingOptDiscard=")
    append(
        state.pendingOptionalDiscardDraw?.let { "${it.decider.seat}:${it.drawCount}:${it.awaitingDiscard}" } ?: "-",
    )
    appendRevealedLibraryPositions(state)
}

/**
 * The two pauses that hold a **revealed card still sitting in a library** — CR 701.16's reveal selection
 * and CR 701.40a's explore. Split out of [appendP62aPendingPositions] when `W10-D` pushed it past
 * detekt's complexity budget, and the seam is a real one: these are the positions where a library's top
 * is public, so two states differing only in *which* card is showing are genuinely different positions
 * and must digest differently.
 */
private fun StringBuilder.appendRevealedLibraryPositions(state: GameState) {
    append("|pendingExplore=")
    append(
        state.pendingExplore?.let { "${it.decider.seat}:${it.exploring.value}:${it.revealed.value}" } ?: "-",
    )
    append("|pendingReveal=")
    append(
        state.pendingRevealSelection?.let {
            "${it.decider.seat}:${it.revealedIds.joinToString("+") { id -> id.value.toString() }}" +
                ":${it.keptIds.joinToString("+") { id -> id.value.toString() }}"
        } ?: "-",
    )
}

// Digests the in-progress cast's gathered-so-far choices (CR 601.2), which govern how the pipeline runs.
