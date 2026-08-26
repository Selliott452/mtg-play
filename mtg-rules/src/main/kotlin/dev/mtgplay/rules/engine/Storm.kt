package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult

/*
 * Storm (CR 702.40) and the spell-copying primitive it is the first client of (CR 707.10). `W9-C`,
 * docs/design/dependent-targets.md §4.
 *
 * > CR 702.40a — "Storm is a triggered ability that functions on the stack. 'Storm' means 'When you cast
 * > this spell, copy it for each spell cast before it this turn. If the spell has any targets, you may
 * > choose new targets for any of the copies.'"
 * > CR 707.10a — "To copy a spell … means to put a copy of it onto the stack; a copy of a spell isn't
 * > cast."
 *
 * Three facts do all the work here, and each of them is load-bearing:
 *
 * **1. It is a *cast* trigger, so the copies resolve before the original.** The trigger fires at
 * CR 601.2i, which puts it on the stack **above** the spell that produced it; it resolves first, and the
 * copies it makes are placed above the original too. The reading order of the card ("You gain 3 life.
 * Storm.") is the exact reverse of the resolution order, and nothing in the encoding says so — it falls
 * out of where a cast trigger goes.
 *
 * **2. The count is fixed when the trigger fires, not when it resolves.** CR 702.40a says "each spell cast
 * before it this turn", and the trigger is on the stack for a whole priority round in which either player
 * may cast more spells. So the number rides on [PendingTrigger.amount] as linked information (CR 608.2h),
 * and [resolveStormTrigger] never re-reads the turn's tally. Getting this wrong is not a rounding error:
 * it would let an opponent *increase* a storm count by responding to it.
 *
 * **3. Every player's spells count, and copies do not.** [Turn.spellsCastThisTurn] is a game-wide tally
 * incremented at CR 601.2i, so an opponent's turn-one cantrip grows the storm count exactly as the
 * caster's own does. A copy is *created* on the stack and never cast (CR 707.10a), so it never reaches
 * the increment — which is what stops a storm spell from feeding its own count.
 *
 * ## Why the copy is a primitive rather than a fold left to the card
 *
 * "Put N copies of this spell onto the stack" cannot be written as a [ResolutionEffect] by a card
 * definition, and not merely because `mtg-cards` would rather not: a stack entry is engine state that
 * only `mtg-rules` may build, the copies need freshly allocated object ids (CR 400.7), and every one of
 * them has to be marked as a non-card so the census, the zone-residence invariant and the CR 608.2m
 * graveyard move all treat it correctly. Leaving that to a card would be five chances to forget.
 *
 * ## The one clause this deliberately does not implement
 *
 * "If the spell has any targets, you may choose new targets for any of the copies" (CR 702.40a) needs a
 * per-copy target decision — N optional re-targetings, each with its own CR 601.2c enumeration. Weather
 * the Storm targets nothing, so for the gauntlet's only storm card the clause is **vacuous rather than
 * approximated**, which is why the card is encodable at all. [requireCopyableWithoutRetargeting] refuses a
 * targeting storm spell loudly instead of silently copying the original's targets, which would be a
 * plausible-looking wrong card (PLAN.md §7) — copying a Grapeshot's targets is precisely what makes a
 * storm spell good or bad.
 */

/**
 * The storm trigger for a spell that has just finished being cast (CR 702.40a, CR 601.2i), or `null` for a
 * card without the keyword.
 *
 * The fired trigger is **synthesized** rather than detected, exactly as madness's reflexive ability is
 * (`Replacements.kt`): storm is an ability of the spell on the stack, and the trigger detector scans
 * battlefields and graveyards, not the stack. Its source is the spell's own stack object, so the
 * resolution can find the spell it must copy by id.
 *
 * [PendingTrigger.amount] carries the count, fixed here and never recomputed — see the file header, fact 2.
 * [spellsCastBeforeThis] is the tally *before* the increment for this very cast, which is what "cast before
 * it this turn" means.
 */
internal fun stormTriggerFor(
    entry: StackEntry.Spell,
    spellsCastBeforeThis: Int,
): PendingTrigger? {
    if (!entry.definition.storm) return null
    requireCopyableWithoutRetargeting(entry.definition)
    return PendingTrigger(
        sourceId = entry.obj.id,
        sourceCard = entry.obj.card,
        controller = entry.controller,
        ability =
            TriggeredAbility(
                condition = TriggerCondition.StormCast,
                // CR 702.40a: the whole ability is the copying, which is the engine's move (CR 707.10)
                // rather than a card's [ResolutionEffect] — the same shape madness's reflexive cast has.
                effect = UNRESOLVED_STORM_EFFECT,
                zoneScope = TriggerZoneScope.Stack,
            ),
        amount = spellsCastBeforeThis,
        subject = entry.obj.id,
    )
}

/**
 * Fails loudly if [definition] has storm and also targets (CR 702.40a) — the gate on the unimplemented
 * "you may choose new targets for any of the copies" clause.
 *
 * See the file header: for an untargeted storm spell the clause is vacuous and the encoding is exact; for
 * a targeting one it is a real per-copy decision, and copying the original's targets instead would be a
 * different card. A modal storm spell is refused for the same reason at one remove — its targeting line
 * belongs to a chosen mode, so "does it target?" has no single answer here.
 */
internal fun requireCopyableWithoutRetargeting(definition: SpellDefinition) {
    require(definition.modes.isEmpty()) {
        "CR 702.40a: ${definition.characteristics.name} has storm and modes; whether its copies would " +
            "need new targets depends on the chosen mode, which is not a question this gate can answer " +
            "(`W9-C`)"
    }
    require(definition.targetSpec == TargetSpec.None) {
        "CR 702.40a: ${definition.characteristics.name} has storm and targets, so its copies need the " +
            "'you may choose new targets for any of the copies' decision — a per-copy CR 601.2c choice " +
            "this engine does not have. Copying the original's targets instead would be a different " +
            "card (`W9-C`)"
    }
}

/**
 * The placeholder effect of a synthesized storm ability. Never invoked: [resolveStormTrigger] intercepts
 * the ability before [AbilityResolution]'s ordinary path reaches its effect, exactly as the madness
 * reflexive trigger's placeholder is intercepted. It fails loudly rather than returning the state
 * unchanged, so a routing mistake surfaces as a crash instead of a silently storm-less spell.
 */
private val UNRESOLVED_STORM_EFFECT =
    ResolutionEffect { _, _ ->
        error("CR 702.40a: a storm trigger is resolved by the engine's copy path, not by its effect")
    }

/**
 * Resolves a storm trigger (CR 702.40a, CR 707.10a): puts [StackEntry.Ability.trigger]`.amount` copies of
 * the spell it names onto the stack, then the trigger ceases to exist (CR 113.7a).
 *
 * **A count of zero is the ordinary case and is not a special one** — the first spell of a turn has a
 * storm count of zero, makes no copies, and the trigger resolves having done nothing. That is the printed
 * card, not a degenerate path.
 *
 * **The spell may no longer be there**, and the answer is to do nothing. A storm trigger sits above its own
 * spell, so both players get priority before it resolves and the spell beneath it can be countered
 * (CR 701.5a). CR 707.10a can only copy an object that exists, and the trigger has no target to fizzle on
 * (CR 608.2b is not the mechanism) — so a storm trigger over a countered spell resolves and copies nothing.
 * The engine reaches that answer by looking the spell up rather than by asserting it is present.
 *
 * Copies are placed one at a time on top of the stack, so they resolve most-recent-first; every ordering of
 * identical copies is the same game, and fixing one keeps replay logs canonical (ADR-006).
 */
internal fun resolveStormTrigger(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult {
    val copies = entry.trigger.amount
    val original =
        state.sharedZones.stack
            .filterIsInstance<StackEntry.Spell>()
            .firstOrNull { it.obj.id == entry.trigger.sourceId }
    val removed = state.updateStack { it.removingAt(it.lastIndex) }
    val copied =
        if (original == null) {
            removed
        } else {
            (1..copies).fold(removed) { current, _ -> copySpellOnStack(current, original) }
        }
    return grantPriorityRound(copied)
}

/**
 * Puts one copy of the spell [original] onto the stack (CR 707.10a) — the spell-copying primitive.
 *
 * **What is copied is the copiable values of the original** (CR 707.2): its printed card identity and the
 * cast record that makes it the spell it is — the definition it was cast from, its chosen modes, its
 * announced X, and the choices linked to its cost. What is *not* copied is anything about the original's
 * residence: the copy gets a **fresh object id** (CR 400.7), so it is a separately targetable and
 * separately counterable object, and it is marked [StackEntry.Spell.isCopy], which is what makes it not a
 * card (CR 707.10a).
 *
 * **[StackEntry.Spell.castVia] is deliberately carried over.** A flashbacked spell's copy is a copy of a
 * spell that was cast with flashback — but the exile-instead-of-graveyard clause it implies is unreachable
 * for a copy, which never goes to a zone at all. Dropping it would be equally correct today and would
 * silently diverge the moment anything else reads the permission, so the record is kept whole.
 *
 * **Targets are copied verbatim, which is exact only because [requireCopyableWithoutRetargeting] has
 * already refused a targeting storm spell.** The copiable-values rule does copy the original's targets
 * (CR 707.10a), and CR 702.40a then *offers* new ones; without that second half the first would be a
 * different card, so the gate above is what makes this line honest rather than convenient.
 */
internal fun copySpellOnStack(
    state: GameState,
    original: StackEntry.Spell,
): GameState {
    val (id, allocated) = state.allocateObjectId()
    val copy = original.copy(obj = original.obj.copy(id = id), isCopy = true)
    return allocated
        .updateStack { it.adding(copy) }
        .emit(GameEvent.SpellCopied(original.controller, original.obj.id, original.obj.card, id))
}
