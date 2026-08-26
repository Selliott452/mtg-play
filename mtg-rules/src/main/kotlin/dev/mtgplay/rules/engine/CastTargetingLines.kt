package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/*
 * The **targeting lines** of a cast (CR 115.3, CR 601.2c), and the mode settlement that decides how many
 * there are (CR 601.2b). Additive (`W9-B`).
 *
 * **Why "lines" and not "the targets".** Before this packet a spell had one instance of the word
 * "target" and the engine could ask about it once. A modal card with an arity above one has one instance
 * *per chosen mode* — CR 115.3 says so explicitly, and adds that the same object may be chosen once for
 * each — so a "choose up to two" cast asks two independent questions with two option lists, and its
 * answers cannot be flattened without losing which answer belonged to which bullet.
 *
 * Three call sites need that split and they are in three different files (the gathering cursor here, the
 * CR 601.2c re-validation in `CastingPipeline.kt`, the CR 608.2b fizzle and the resolution fold in
 * `StackResolution.kt`). Splitting this file out of `CastGathering.kt` when detekt's function budget
 * tripped put all three behind one vocabulary rather than three local conventions — which is the whole
 * point, since a disagreement between them would be a card that targets one way and resolves another.
 */

/**
 * The settled modes a cast starts with (CR 601.2b): the empty list for a card with no modes, the empty
 * list again for a modal card with no choosable mode at all, and `null` \u2014 "still to be chosen" \u2014
 * otherwise.
 *
 * The middle case is only reachable for an "up to N" card, because [someModeIsCastable] refuses to
 * enumerate the cast of a card that must choose a mode and cannot. For that card choosing **no** modes
 * is the legal answer and the only one, so surfacing a request over an empty option list would be a
 * decision with nothing in it \u2014 the same rule that settles a vacuous target choice silently.
 */
internal fun initialChosenModes(
    state: GameState,
    definition: SpellDefinition,
    caster: PlayerId,
    cardObjectId: ObjectId,
): PersistentList<Int>? =
    when {
        definition.modes.isEmpty() -> persistentListOf()
        castableModes(state, definition, caster, Chooser.Spell(cardObjectId)).isEmpty() -> persistentListOf()
        else -> null
    }

/**
 * Settles every targeting line of [cast] that has nothing to choose from, and settles
 * [PendingCast.chosenTargets] once every line is answered (CR 601.2c).
 *
 * **The one place the CR 601.2c cursor moves**, shared by the cast's opening, the mode answer, and every
 * target answer \u2014 so "which line is asked next" is written once and the three paths cannot disagree.
 * A modal card has one line per chosen mode (CR 115.3: each bullet is its own instance of the word
 * "target"); an ordinary card has exactly one, which is why the two travel the same code.
 *
 * `modeTargets.size` is the cursor: a vacuous line is recorded as an empty selection and the cursor
 * moves on, so a "choose up to two" card whose first mode has no legal target still asks about its
 * second. When the cursor reaches the end, the flattened concatenation becomes [PendingCast.chosenTargets]
 * and the CR 601.2c stage is done.
 */
internal fun advanceTargetingLines(
    state: GameState,
    cast: PendingCast,
    definition: SpellDefinition,
): PendingCast {
    val modes = cast.chosenModes ?: return cast
    val specs = effectiveTargetSpecs(definition, modes, cast.castingPermission)
    var settled = cast
    while (settled.modeTargets.size < specs.size &&
        targetChoiceIsVacuous(
            state,
            specs[settled.modeTargets.size],
            cast.caster,
            Chooser.Spell(cast.cardObjectId),
        )
    ) {
        settled = settled.copy(modeTargets = settled.modeTargets.adding(persistentListOf()))
    }
    return if (settled.modeTargets.size < specs.size) {
        settled
    } else {
        settled.copy(chosenTargets = settled.modeTargets.flatten().toPersistentList())
    }
}

/**
 * Records the chosen modes on the open [PendingCast] (CR 601.2b, CR 700.2) and suspends for whatever the
 * cast needs next \u2014 which for every modal card in the pool is the first chosen mode's target choice
 * (CR 601.2c).
 *
 * [modeIndices] are the modes' **printed** indices, in chosen order, translated from the option indices
 * by the caller, because the printed indices are what the cast record and the replay log carry.
 *
 * The targeting cursor starts here and only here: [advanceTargetingLines] settles any leading line with
 * nothing to choose from, so no empty target request is ever surfaced, and leaves the cursor on the first
 * line that is a real choice. This is the single point at which the CR 601.2b answer determines the
 * CR 601.2c questions \u2014 questions plural, since each chosen mode brings its own (CR 115.3).
 */
internal fun applyChosenModes(
    state: GameState,
    modeIndices: List<Int>,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.chosenModes == null) { "CR 601.2b: this cast's modes are already chosen" }
    val card =
        objectInZone(state, cast.caster, cast.source, cast.cardObjectId)
            ?: error("CR 601.2b: pending cast's card ${cast.cardObjectId} is not in ${cast.caster}'s ${cast.source}")
    val definition = spellDefinitionOf(state, card.card)
    val moded = cast.copy(chosenModes = modeIndices.toPersistentList())
    return pauseForNextCastDecision(state.copy(pendingCast = advanceTargetingLines(state, moded, definition)))
}

/**
 * The targets [entry] chose, split into one list per targeting line (CR 115.3) — the recorded per-mode
 * split for a modal spell, and the whole flat list as a single line for an ordinary one.
 *
 * The one place the two shapes are reconciled, so every per-line reader (the CR 601.2c re-validation,
 * the CR 608.2b fizzle, the resolution fold) asks the same question and cannot disagree about what a
 * non-modal spell's "lines" are.
 */
internal fun targetLinesOf(entry: StackEntry.Spell): List<List<Target>> =
    if (entry.definition.modes.isEmpty()) {
        // `W9-C`: a non-modal card may still print "target" more than once (Searing Blaze), in which
        // case the flat recorded list is sliced by the lines' own fixed widths. A single-line card
        // slices to exactly itself, so the two shapes stay one code path.
        targetsByLine(targetLinesOf(entry.definition, entry.chosenModes, entry.castVia), entry.targets)
    } else {
        entry.modeTargets
    }

/**
 * Whether every targeting line of [cast] has been answered (CR 601.2c) — the gate paired with
 * [targetsRequestFor], reading the same line list and the same cursor rule so the two can never
 * disagree about whether a question is still owed.
 *
 * The two shapes record their answers differently and that is the only difference: a modal card keeps
 * one list per chosen mode (`modeTargets`), an ordinary one appends to a flat list that is sliced back
 * into lines by their fixed widths (`W9-C`).
 */
internal fun castTargetLinesSettled(
    definition: SpellDefinition,
    cast: PendingCast,
): Boolean =
    if (definition.modes.isEmpty()) {
        targetLinesSettled(
            targetLinesOf(definition, cast.chosenModes.orEmpty(), cast.castingPermission),
            cast.chosenTargets,
        )
    } else {
        cast.chosenModes?.let {
            cast.modeTargets.size == effectiveTargetSpecs(definition, it, cast.castingPermission).size
        } ?: false
    }
