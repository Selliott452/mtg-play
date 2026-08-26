package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.core.state.Target
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/*
 * The two questions a cast asks about its **targets** before it asks anybody anything (CR 601.2c): what
 * the target record starts as, and which card the open cast is casting. Split from CastGathering.kt so
 * each file stays inside detekt's function budget.
 *
 * Both grew a case in `W9-C`, and it is the same case: a card may print the word "target" more than once
 * (`TargetLines.kt`), so "the cast's targets" is no longer one question with one answer, and the record a
 * cast opens with cannot be settled from a single spec.
 */

/**
 * The settled-targets value a cast starts with for [spec] (CR 601.2c): the empty list for a spell that
 * targets nothing — there is no choice to surface — and `null`, meaning "still to be chosen", for every
 * spec that demands a target.
 *
 * Shared by [beginCastGathering] and [applyChosenModes] because a modal cast reaches the same question
 * twice: once for the card (whose answer is always "unknown", since a modal card has no spec of its
 * own), and again for the mode it settled on.
 */
internal fun initialTargetsFor(
    state: GameState,
    spec: TargetSpec,
    caster: PlayerId,
    self: ObjectId,
): PersistentList<Target>? =
    if (targetChoiceIsVacuous(state, spec, caster, Chooser.Spell(self))) persistentListOf() else null

/** The printed identity of the card an open [cast] is casting, wherever its source zone is (CR 601.2a). */
internal fun castCardRef(
    state: GameState,
    cast: PendingCast,
): CardRef =
    objectInZone(state, cast.caster, cast.source, cast.cardObjectId)
        ?.card
        ?: error("CR 601.2: pending cast's card ${cast.cardObjectId} is not in ${cast.caster}'s ${cast.source}")
