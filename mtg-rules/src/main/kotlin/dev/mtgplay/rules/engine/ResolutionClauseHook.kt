package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.ResolutionClauses
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.definition.requireAtMostOneClause
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionClauses
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.rules.AdvanceResult

/*
 * The post-resolution clause hook (`FW-CLAUSEHOOK`, docs/design/resolution-clause-hook.md).
 *
 * Five clause types — the CR 701.16 library reveal, the CR 701.14a private library look, the CR 601.3b
 * optional cost-then-draw, the CR 601.2c draw-then-discard, and the CR 701.18 library search
 * (`P-SEARCH`, docs/design/library-search.md §2) — are parts of a resolution the engine
 * *orchestrates* rather than runs as a pure effect, because each needs a mid-resolution decision. Until
 * this packet they hung off `StackEntry.Spell` alone, so an ability that resolved could carry none of
 * them: Faerie Seer's "When this creature enters, scry 2" is the same CR 701.17a clause as Preordain's,
 * and the engine could encode one and not the other.
 *
 * The generalisation is two functions, and they are the whole hook. [orchestrateResolutionClauses] runs
 * whichever clause the resolving object declares, whatever kind of object it is; [completeClauseResolution]
 * is how that object then leaves the stack, and is the *only* place the three paths differ — CR 608.2m puts
 * a spell's card in a graveyard, CR 113.7a makes an ability simply cease to exist. Every orchestrator
 * therefore takes a plain [StackEntry] and finishes through [completeClauseResolution], so there is one
 * implementation of each clause rather than a spell copy and an ability copy.
 *
 * The mid-resolution pauses themselves needed no generalisation at all: each is keyed on a `pending*`
 * record and re-derives its request from the state (ADR-004), so [resolvingClauseEntry] replacing an
 * `as? StackEntry.Spell` cast is the entire change on the resume path.
 */

/**
 * Runs the post-resolution clause the resolving [entry] declares (CR 608.2c), pausing for whatever
 * decision that clause needs, or — for an object with no clause — completes the resolution now.
 *
 * Called after the object's ordinary effect has run, from all three resolution paths. At most one clause
 * can be declared, so the `when` below is a dispatch and not a sequencing rule — running two in field order
 * would be a silent approximation of an ordering no card states. [ActivatedAbility] and [TriggeredAbility]
 * gate that at construction; `SpellDefinition` is an interface with no `init` and cannot, so the gate is
 * re-checked here, which covers all three carriers uniformly.
 */
internal fun orchestrateResolutionClauses(
    state: GameState,
    entry: StackEntry,
): AdvanceResult {
    val clauses: ResolutionClauses = entry.resolutionClauses
    requireAtMostOneClause(clauses) { "the resolving ${entry.resolutionSourceCard.name}" }
    val reveal = clauses.libraryReveal
    val look = clauses.libraryLook
    val costDraw = clauses.optionalCostThenDraw
    val drawDiscard = clauses.drawThenDiscard
    val search = clauses.librarySearch
    val handReveal = clauses.handRevealChoice
    val opponentDiscard = clauses.eachOpponentDiscards
    val optionalDraw = clauses.optionalDraw
    val permanents = clauses.permanentSelection
    val manaThenDraw = clauses.optionalManaThenDraw
    val typeReveal = clauses.chosenTypeReveal
    return when {
        optionalDraw != null -> orchestrateOptionalDraw(state, entry, optionalDraw)
        // CR 601.3b: "you may pay {B}. If you do, draw a card" (Nihil Spellbomb).
        manaThenDraw != null -> orchestrateOptionalManaThenDraw(state, entry, manaThenDraw)
        // CR 701.3a: "target player exiles a card from their graveyard" (Relic of Progenitus) — the
        // clause carries no data, so the flag itself is the dispatch.
        clauses.targetPlayerExilesFromGraveyard != null -> orchestrateGraveyardExileChoice(state, entry)
        // CR 609.4: "choose creature or land", then reveal and partition (Winding Way).
        typeReveal != null -> orchestrateChosenTypeReveal(state, entry, typeReveal)
        reveal != null -> orchestrateLibraryReveal(state, entry, reveal)
        look != null -> orchestrateLibraryLook(state, entry, look)
        costDraw != null -> orchestrateOptionalCostDraw(state, entry, costDraw)
        drawDiscard != null -> orchestrateDrawThenDiscard(state, entry, drawDiscard)
        search != null -> orchestrateLibrarySearch(state, entry, search)
        handReveal != null -> orchestrateHandRevealChoice(state, entry, handReveal)
        opponentDiscard != null -> orchestrateEachOpponentDiscards(state, entry, opponentDiscard)
        permanents != null -> orchestratePermanentSelection(state, entry, permanents)
        else -> lateClauseOrCompletion(state, entry, clauses)
    }
}

/**
 * The tail of [orchestrateResolutionClauses]: the clauses that arrived last, and the completion when
 * none is declared.
 *
 * Split out only so the dispatch stays inside detekt's complexity budget — the same shape as the splits
 * in `PendingDecision.kt`, `DecisionView.kt`, `SingleOptionApplication.kt`, the CLI menu family, and the
 * protocol codec. The order here is a **continuation** of the chain above and must not be reasoned
 * about separately; at most one clause may be declared (the caller has already required it), so the
 * order is documentation rather than precedence.
 */
private fun lateClauseOrCompletion(
    state: GameState,
    entry: StackEntry,
    clauses: ResolutionClauses,
): AdvanceResult {
    val tapOrUntap = clauses.optionalTapOrUntap
    val chosenColor = clauses.chosenColorEffect
    val opponentSacrifice = clauses.eachOpponentSacrifices
    return when {
        tapOrUntap != null -> orchestrateTapOrUntap(state, entry, tapOrUntap)
        // CR 701.17a: "each opponent sacrifices a creature of their choice" (Extract a Confession) —
        // the second clause whose decider is not the resolving object's controller.
        opponentSacrifice != null -> orchestrateEachOpponentSacrifices(state, entry, opponentSacrifice)
        // CR 700.2 / CR 615.1: "sources of the color of your choice" (Prismatic Strands) — the colour
        // is named on resolution, so the clause pauses here rather than at CR 601.2b.
        chosenColor != null -> orchestrateChosenColor(state, entry)
        else -> completeClauseResolution(state, entry)
    }
}

/**
 * Completes the resolution of [entry] once its clauses are done — **the one place the three resolution
 * paths differ**, and the reason the clause orchestration itself can be written once:
 * - a **spell** puts its card off the stack into its owner's graveyard, or into exile for a flashback
 *   cast (CR 608.2m, CR 400.7, CR 702.34e), and narrates that move;
 * - a **triggered** or **activated ability** is not a card (CR 113.7a), so it simply ceases to exist —
 *   a bare stack removal plus its resolved event, deliberately not the spell's graveyard move.
 *
 * Either way the active player then receives priority in a fresh round (CR 117.3b).
 */
internal fun completeClauseResolution(
    state: GameState,
    entry: StackEntry,
): AdvanceResult =
    when (entry) {
        is StackEntry.Spell -> completeInstantSorceryResolution(state, entry)
        is StackEntry.Ability -> ceaseTriggeredAbility(state, entry)
        is StackEntry.ActivatedAbilityOnStack -> ceaseActivatedAbility(state, entry)
    }

/**
 * The CR 113.7a cessation of a resolved triggered ability: it is removed from the stack and nothing
 * moves anywhere, because the ability was never a card. Shared by the ordinary resolution and by the
 * resume after any clause the ability carried.
 */
internal fun ceaseTriggeredAbility(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult {
    val ceased = state.updateStack { it.removingAt(it.lastIndex) }
    return grantPriorityRound(
        ceased.emit(GameEvent.TriggeredAbilityResolved(entry.trigger.controller, entry.trigger.sourceCard)),
    )
}

/** The CR 113.7a cessation of a resolved activated ability — the counterpart of [ceaseTriggeredAbility]. */
internal fun ceaseActivatedAbility(
    state: GameState,
    entry: StackEntry.ActivatedAbilityOnStack,
): AdvanceResult {
    val ceased = state.updateStack { it.removingAt(it.lastIndex) }
    return grantPriorityRound(ceased.emit(GameEvent.AbilityResolved(entry.controller, entry.sourceCard)))
}

/**
 * The stack object an open mid-resolution clause pause belongs to (CR 608.1): the top of the stack.
 * Fails loudly on an empty stack — a pause that outlived its resolving object is an engine defect, not a
 * position to guess at. The generalisation of the `as? StackEntry.Spell` cast every clause orchestrator
 * used to make on resume.
 */
internal fun resolvingClauseEntry(state: GameState): StackEntry =
    state.sharedZones.stack.lastOrNull()
        ?: error("CR 608.1: a mid-resolution clause pause requires a resolving object on top of the stack")
