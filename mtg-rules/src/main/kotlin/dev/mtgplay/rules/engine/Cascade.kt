package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.shuffled
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCascade
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/*
 * Cascade (CR 702.85, `W9-G`) — Maelstrom Colossus.
 *
 * CR 702.85a spells the keyword out in full: *"When you cast this spell, exile cards from the top of your
 * library until you exile a nonland card whose mana value is less than this spell's mana value. You may
 * cast that card without paying its mana cost if the resulting spell's mana value is less than this
 * spell's mana value. Then put all cards exiled this way that weren't cast on the bottom of your library
 * in a random order."* Four halves, and this file is all four:
 *
 * 1. **The cast trigger**, [detectCascadeTrigger] — fired at CR 601.2i beside the battlefield cast
 *    triggers, from the **stack** ([TriggerZoneScope.Stack]), because CR 702.85a says cascade "functions
 *    only while the spell with cascade is on the stack". It carries the cascading spell's mana value as
 *    linked information, captured while the spell is certainly still there (CR 608.2h).
 * 2. **The dig**, [exileUntilCascadeHit] — the engine's first "exile from a library until a predicate
 *    holds". Every other library effect in the pool takes a *fixed* count.
 * 3. **The free cast**, [resolveCascadeTrigger] onward — the rebound flow (CR 702.88b) with one card
 *    swapped: a yes/no, then [beginCastGathering] from exile via [CastingPermission.Cascade] at `{0}`.
 *    docs/gauntlet-deferred-ten.md recorded that the engine had "no cast-without-paying path at all";
 *    it had two, and this is the third client of the same one.
 * 4. **The bottoming**, [bottomCascadeExiles] — a seeded shuffle of a known set (ADR-006), and the one
 *    step that has to happen **after** a whole nested CR 601 pipeline. See [PendingCascade].
 *
 * **What this is not.** CR 702.85b's "as you cascade" window and CR 702.85c's multiple instances are both
 * absent: no card in the pool prints either, the first needs a decision point between the dig and the
 * may-cast, and the second needs a count rather than
 * [dev.mtgplay.core.definition.SpellDefinition.cascade]'s boolean.
 */

/**
 * The synthesized ability a cascading spell's cast trigger carries (CR 702.85a). Its effect is never run
 * — everything cascade does involves a mid-resolution player choice, so it is the engine's flow
 * (ADR-004) — so it is a no-op, and the condition is what [resolveAbility] dispatches on. The exact
 * shape madness (CR 702.35b) and rebound (CR 702.88b) already use.
 */
private val cascadeAbility =
    TriggeredAbility(
        condition = TriggerCondition.CascadeCast,
        effect = ResolutionEffect { state, _ -> state },
        zoneScope = TriggerZoneScope.Stack,
    )

/**
 * Fires the cascade trigger of the spell [castEntry] that has just finished casting (CR 601.2i,
 * CR 702.85a). A no-op for a spell whose card does not print the keyword, which is every card but one.
 *
 * **Not part of [detectCastTriggers], and the difference is which object is watching.** That detector
 * scans **battlefield permanents** for a [TriggerCondition.SpellCast] ability and fires them for *any*
 * qualifying cast; cascade is an ability of the **spell being cast**, functioning from the stack, and
 * fires exactly once for exactly that spell. Folding it into the battlefield scan would have meant
 * scanning the stack there too and then explaining why one member of [TriggerCondition] is matched
 * against a different object from all the others.
 *
 * The trigger carries the cascading spell's **mana value** as its [PendingTrigger.amount] — the number
 * CR 702.85a's "lesser mana value" comparison is made against. It is captured here rather than read when
 * the trigger resolves because by then the spell may have been countered, and CR 608.2h then wants its
 * last-known value; capturing it while it is indisputably on the stack is that value. Read through
 * [spellManaValue], so an `{X}` spell's announced value counts (CR 202.3b) exactly as it would for any
 * other question about how big the spell is.
 */
internal fun detectCascadeTrigger(
    state: GameState,
    castEntry: StackEntry.Spell,
): GameState {
    if (!castEntry.definition.cascade) return state
    return enqueuePendingTrigger(
        state,
        PendingTrigger(
            sourceId = castEntry.obj.id,
            sourceCard = castEntry.obj.card,
            controller = castEntry.controller,
            ability = cascadeAbility,
            amount = spellManaValue(state, castEntry.obj.id),
        ),
    )
}

/**
 * Resolves a cascade trigger (CR 702.85a): the ability leaves the stack (CR 113.7a), the dig runs, and
 * the engine either suspends on the free-cast yes/no or goes straight to the bottoming. Called from
 * [resolveAbility] when the resolving ability's condition is [TriggerCondition.CascadeCast].
 *
 * Three outcomes, and the third is the one an implementation forgets:
 * - a nonland card of lesser mana value was exiled **and** can be cast right now — the controller is
 *   asked;
 * - one was exiled but cannot be cast (no legal target for it, ADR-005 — offering a cast whose targets
 *   do not exist is an enumerated-but-illegal action) — nothing is asked and everything is bottomed;
 * - none was exiled at all, because the library ran out — likewise. CR 702.85a has no "fail to find"
 *   clause; a library that ends is simply a library with no qualifying card left in it, and the whole of
 *   it goes back on the bottom in a random order.
 */
internal fun resolveCascadeTrigger(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult {
    check(state.sharedZones.stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    val trigger = entry.trigger
    val controller = trigger.controller
    val ceased =
        state
            .updateStack { it.removingAt(it.lastIndex) }
            .emit(GameEvent.TriggeredAbilityResolved(controller, trigger.sourceCard))
    val dig = exileUntilCascadeHit(ceased, controller, trigger.amount)
    val opened =
        dig.state.copy(
            pendingCascade =
                PendingCascade(
                    controller = controller,
                    exiledObjectIds = dig.exiled,
                    candidateObjectId = castableCascadeHit(dig.state, controller, dig.hit),
                ),
        )
    val pending = opened.pendingCascade ?: error("the cascade record was just opened")
    return if (pending.candidateObjectId != null) {
        AdvanceResult.NeedsDecision(opened, pendingCascadeRequest(opened))
    } else {
        grantPriorityRound(bottomCascadeExiles(opened))
    }
}

/**
 * The exiled card [hit] if [seat] could legally cast it for free right now (CR 702.85a's "you **may**
 * cast that card"), or `null` — for a dig that found nothing, or found a card with no legal target.
 *
 * The castability gate is ADR-005 in the direction that matters most: a yes/no whose "yes" dead-ends
 * mid-pipeline is worse than a missing option, and CR 702.85a's "may" makes declining legal anyway, so a
 * cascade into an uncastable card is simply a cascade that casts nothing. Cost is not a gate in practice
 * — `{0}` always yields a payment plan — but it is checked through the same [targetsAndCostAvailable] the
 * madness and rebound viability checks use, so the three cannot drift.
 *
 * Timing is deliberately **not** checked, for madness's reason (CR 702.35b) and rebound's: the cast
 * happens as the cascade ability resolves, not from a priority window, so a sorcery is castable here on
 * an opponent's turn with a full stack.
 */
private fun castableCascadeHit(
    state: GameState,
    seat: PlayerId,
    hit: ObjectId?,
): ObjectId? =
    // A `null` [hit] matches nothing: an object id is never null, so the dig that found nothing falls
    // out of the same lookup the dig that found something uses.
    state.sharedZones.exile
        .firstOrNull { it.id == hit }
        ?.takeIf { exiled ->
            val definition = state.definitions[exiled.card] as? SpellDefinition
            definition != null &&
                targetsAndCostAvailable(state, seat, definition, CastingPermission.Cascade, exiled.id)
        }?.id

/** What one cascade dig produced: the state after it, every card it exiled, and the qualifying card. */
private data class CascadeDig(
    val state: GameState,
    val exiled: PersistentList<ObjectId>,
    val hit: ObjectId?,
)

/**
 * The dig (CR 702.85a): exiles cards from the top of [seat]'s library, one at a time, **until** one of
 * them is a nonland card whose mana value is less than [threshold] — that card is the [CascadeDig.hit] —
 * or the library runs out. Every exiled card is a new object in the shared exile zone (CR 400.7), face
 * up, narrated by one [GameEvent.CardsExiledFromLibrary] in the order exiled.
 *
 * **The engine's first library effect with no count.** Every other one — a draw, a mill, a surveil,
 * Reckless Impulse's exile — takes a fixed number and stops. This one is bounded only by the library and
 * by a predicate over what it turns up, which is why it is written as an explicit loop over a shrinking
 * library rather than a `take(n)`.
 *
 * **Mana value off the card, not off the stack** (CR 202.3b): a card in a library has `{X}` = 0, so the
 * comparison uses the printed cost exactly as [dev.mtgplay.core.card.PrintedCharacteristics.manaValue]
 * gives it. That is also why CR 702.85a's second condition — "if the resulting spell's mana value is less
 * than this spell's mana value" — needs no separate check: the free cast fixes X at 0 (CR 601.2b), so the
 * resulting spell's mana value is the one already compared here.
 *
 * **A card with no definition is skipped**, and that is a recorded consequence of the engine's standing
 * rule rather than a decision taken here: an unregistered card is inert (P2.1), so the engine knows
 * neither its card types nor its mana cost and could not tell a land from a cheap spell. It therefore
 * cannot satisfy the predicate and the dig continues past it. The gauntlet registry defines every card in
 * the thirteen decklists, so a real match never meets one; a library that did would see cascade dig
 * deeper than the printed card says, which is why this is stated rather than silently relied upon.
 */
private fun exileUntilCascadeHit(
    state: GameState,
    seat: PlayerId,
    threshold: Int,
): CascadeDig {
    var current = state
    val exiledIds = mutableListOf<ObjectId>()
    val exiledCards = mutableListOf<CardRef>()
    var hit: ObjectId? = null
    while (hit == null) {
        val top = current.player(seat).library.firstOrNull() ?: break
        val (exileId, allocated) = current.allocateObjectId()
        current =
            allocated
                .updatePlayer(seat) { it.copy(library = it.library.removingAt(0)) }
                .updateExile { it.adding(GameObject(id = exileId, card = top.card, owner = top.owner)) }
        exiledIds += exileId
        exiledCards += top.card
        if (isCascadeHit(current, top.card, threshold)) hit = exileId
    }
    val narrated =
        if (exiledCards.isEmpty()) current else current.emit(GameEvent.CardsExiledFromLibrary(seat, exiledCards))
    return CascadeDig(narrated, exiledIds.toPersistentList(), hit)
}

/**
 * Whether [card] satisfies cascade's predicate (CR 702.85a): it is a **nonland** card whose mana value is
 * **strictly** less than [threshold]. An unregistered card satisfies nothing (see [exileUntilCascadeHit]).
 *
 * "Nonland" is the printed card type (CR 205.2) rather than castability: an artifact land is a land and
 * does not stop the dig, and an uncastable-right-now nonland does stop it — the predicate is about what
 * the card *is*, and whether the cast then happens is a separate question CR 702.85a asks afterwards.
 */
private fun isCascadeHit(
    state: GameState,
    card: CardRef,
    threshold: Int,
): Boolean {
    val characteristics = state.definitions[card]?.characteristics ?: return false
    return CardType.LAND !in characteristics.cardTypes && characteristics.manaValue < threshold
}

/**
 * The yes/no free cast the open [GameState.pendingCascade] is waiting on (CR 702.85a). A pure function of
 * the state (ADR-004), and derivable only while the candidate is still set — once the controller says
 * yes, the candidate is cleared and this state stops being a pause point, which is what keeps the
 * question from being re-asked while the free cast gathers its own decisions.
 */
internal fun pendingCascadeRequest(state: GameState): DecisionRequest.ChooseYesNo {
    val pending = state.pendingCascade ?: error("no cascade cast choice is pending")
    val candidate =
        pending.candidateObjectId ?: error("CR 702.85a: this cascade has no free-cast choice outstanding")
    val exiled =
        state.sharedZones.exile.firstOrNull { it.id == candidate }
            ?: error("CR 702.85a: the pending cascade card $candidate is not in exile")
    return DecisionRequest.ChooseYesNo(
        id = DecisionRequestId(pending.controller, state.player(pending.controller).decisionsAnswered),
        prompt = "cast ${exiled.card.name} from exile without paying its mana cost (cascade)",
        cardObjectId = candidate,
        card = exiled.card,
    )
}

/**
 * Applies the controller's cascade yes/no (CR 702.85a): [accept] `true` opens a cast of the exiled card
 * from exile for `{0}`, and [accept] `false` goes straight to the bottoming.
 *
 * On **yes** the candidate is cleared from the pending record but the record itself stays open, carrying
 * the exiled ids across the nested cast — the bottoming is CR 702.85a's *last* step and cannot run until
 * the cast it follows has completed. [priorityAfterCast] performs it. On **no** the card simply joins the
 * others on the bottom, which is what "all cards exiled this way that weren't cast" means for a declined
 * cascade.
 */
internal fun applyCascadeCastChoice(
    state: GameState,
    accept: Boolean,
): AdvanceResult {
    val pending = state.pendingCascade ?: error("no cascade cast choice is pending")
    val candidate =
        pending.candidateObjectId ?: error("CR 702.85a: this cascade has no free-cast choice outstanding")
    val answered = state.copy(pendingCascade = pending.copy(candidateObjectId = null))
    if (!accept) return grantPriorityRound(bottomCascadeExiles(answered))
    // The controller casts as the cascade ability resolves; they hold priority for the gathering (CR 601.2).
    val casting = answered.updatePlayer(pending.controller) { it.copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY) }
    return beginCastGathering(casting, pending.controller, candidate, CastSource.EXILE, CastingPermission.Cascade)
}

/**
 * Cascade's last act (CR 702.85a): puts every card the open [GameState.pendingCascade] exiled **that is
 * still in exile** on the bottom of its owner's library **in a random order**, clears the record, and
 * emits [GameEvent.CardsPutOnBottomInRandomOrder]. Fails loudly with no cascade open — every caller has
 * just been inside one.
 *
 * **"That weren't cast" is read off the state, not tracked.** A card the controller cast has left exile
 * for the stack (CR 601.2a) and is therefore not in the filtered list; one that was never cast is still
 * sitting there. Keeping a separate "was cast" flag would be a second source of truth that could disagree
 * with the zone.
 *
 * **The randomisation is the match PRNG** (ADR-006, CR 702.85a): the exiled cards are shuffled among
 * themselves through [shuffled] and appended to the library in that order, so a replay of the same seed
 * reproduces the same bottom. This is a genuine consumer of seeded entropy — the first outside a
 * whole-library shuffle — and there is no other sanctioned source.
 *
 * Each card is reborn as a new library object (CR 400.7). Nothing is emitted for an empty set: a cascade
 * that exiled only the card it cast puts nothing back.
 */
internal fun bottomCascadeExiles(state: GameState): GameState {
    val pending = state.pendingCascade ?: error("CR 702.85a: no cascade is waiting to bottom its exiled cards")
    val cleared = state.copy(pendingCascade = null)
    // CR 702.85a: "all cards exiled this way that weren't cast" — a cast card has left exile (CR 601.2a).
    val remaining =
        pending.exiledObjectIds
            .mapNotNull { id -> cleared.sharedZones.exile.firstOrNull { it.id == id } }
            .toPersistentList()
    if (remaining.isEmpty()) return cleared
    // ADR-006: the order is drawn from the match-owned PRNG and its successor rides on the state.
    val (ordered, nextRng) = remaining.shuffled(cleared.rng)
    val moved =
        ordered.fold(cleared.copy(rng = nextRng)) { current, exiled ->
            val (libraryId, allocated) = current.allocateObjectId()
            allocated
                .updateExile { zone -> zone.removingAt(zone.indexOfFirst { it.id == exiled.id }) }
                // CR 401.1: the last index is the bottom of a library.
                .updatePlayer(pending.controller) {
                    it.copy(library = it.library.adding(GameObject(libraryId, exiled.card, exiled.owner)))
                }
        }
    // The event lists the cards in the order they were *exiled*: the identities are public (they were
    // exiled face up) but the order they were placed in is not (GameEvent.CardsPutOnBottomInRandomOrder).
    return moved.emit(
        GameEvent.CardsPutOnBottomInRandomOrder(
            pending.controller,
            pending.exiledObjectIds.mapNotNull { id -> remaining.firstOrNull { it.id == id }?.card },
        ),
    )
}
