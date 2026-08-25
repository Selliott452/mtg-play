package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameState

/**
 * [Invariant.ENTRY_TRIGGER_DETECTION]: every object that entered the battlefield fired the
 * enters-the-battlefield abilities its definition declares (CR 603.6a).
 *
 * **The defect this exists for.** An object reaches the battlefield by one of four transitions — a
 * resolving permanent spell, a played land (CR 305.1), a token's creation (CR 111.4), and a return
 * from the graveyard — and each one has to remember, on its own, to call the CR 603.6a detector.
 * Two of the four did not. The gauntlet triage records the played-land half as **T18**; the token
 * half had the same shape and the same silence. What makes the gap worth an invariant rather than
 * just a fix is that it is *undetectable from the state*: a trigger that never fired enqueues
 * nothing, emits nothing, and throws nothing, so the land simply arrives and the game continues
 * being wrong in a way no other invariant, no test assertion and no crash can see. Every ETB land
 * and every ETB token in the gauntlet would have looked perfectly encoded while doing nothing.
 *
 * `announceBattlefieldEntry` closes it structurally on the engine side by making "narrate the entry"
 * and "fire CR 603.6a" one indivisible step. This is the acceptance-side backstop for the residual
 * risk that structure cannot cover: a *fifth* entry path that adds an object to the battlefield and
 * goes through neither.
 *
 * **The property.** Over the whole game so far, for each printed card, the number of
 * enters-the-battlefield triggers that *should* have fired equals the number that demonstrably did:
 *
 * ```
 * entries(card) * etbAbilities(card)  ==  placedOnStack(card) + stillPending(card)
 * ```
 *
 * Both sides are read off derived observability — the [GameState.events] log and
 * [GameState.pendingTriggers] — which is exactly where a checker may read them; ADR-006 forbids
 * *rules* logic from consulting the event log, and this is not rules logic. The right-hand side is
 * complete because a fired trigger has nowhere else to go: CR 603.3b puts every pending trigger on
 * the stack at the next priority grant, and a targeting trigger with no legal target is still placed
 * (carrying no targets) rather than dropped, so nothing fired ever leaves the log unrecorded.
 *
 * Inequality is a violation in **both** directions, and the two catch different bugs. Too few means
 * an entry path skipped the detector — T18 itself. Too many means a trigger fired that no entry
 * accounts for: either an entry path that announces nothing (the fifth-path risk), or the detector
 * running twice on one entry.
 *
 * **Why only some cards are measured.** [GameEvent.TriggeredAbilityPutOnStack] carries the source
 * *card*, not the source object or the condition that fired, so a placement can only be attributed
 * to a condition when the card has just one kind. The tally therefore covers cards whose triggered
 * abilities are *all* battlefield-scoped [TriggerCondition.EnteredBattlefieldSelf] — every ETB land,
 * every ETB token, Cartouche of Solidarity, Abundant Growth — and skips a card that mixes ETB with
 * another condition, whose placements are not separable. That is a coverage limit, not a soundness
 * one: a skipped card is never wrongly accused, and the T18 shape (a land whose only ability is its
 * ETB trigger) sits squarely inside the covered set. Giving the event a source [ObjectId] would
 * retire the restriction and let the check run per object; it is deliberately left as a follow-up
 * rather than a protocol change smuggled into a fix.
 */
internal fun checkEntryTriggerDetection(state: GameState): List<Violation> {
    val expectedPerEntry = etbOnlyCards(state)
    if (expectedPerEntry.isEmpty()) return emptyList()
    val tally = tallyEntriesAndPlacements(state, expectedPerEntry.keys)
    state.pendingTriggers.forEach { trigger ->
        if (trigger.sourceCard in expectedPerEntry) {
            tally.accountedFor[trigger.sourceCard] = (tally.accountedFor[trigger.sourceCard] ?: 0) + 1
        }
    }
    return expectedPerEntry.keys
        .sortedBy { it.name }
        .mapNotNull { card ->
            val entries = tally.entries[card] ?: 0
            val declared = expectedPerEntry.getValue(card)
            val expected = entries * declared
            val observed = tally.accountedFor[card] ?: 0
            if (expected == observed) {
                null
            } else {
                val diagnosis =
                    if (observed < expected) {
                        "a trigger was lost — an entry path skipped the CR 603.6a detector"
                    } else {
                        "a trigger fired that no entry accounts for — an entry announced nothing, " +
                            "or the detector ran twice"
                    }
                Violation(
                    Invariant.ENTRY_TRIGGER_DETECTION,
                    "CR 603.6a: ${card.name} entered the battlefield $entries time(s) declaring $declared " +
                        "enters-the-battlefield ability/abilities, so $expected trigger(s) should have fired, " +
                        "but $observed were put on the stack or are pending — $diagnosis",
                )
            }
        }
}

/**
 * The registered cards whose triggered abilities are *all* battlefield-scoped
 * [TriggerCondition.EnteredBattlefieldSelf], mapped to how many of them there are — the cards whose
 * [GameEvent.TriggeredAbilityPutOnStack] events can be attributed to an entry unambiguously. A card
 * with no triggered ability at all is excluded: it expects nothing and can be tallied by nothing.
 */
private fun etbOnlyCards(state: GameState): Map<CardRef, Int> =
    state.definitions
        .entries
        .mapNotNull { (card, definition) ->
            val abilities = definition.triggeredAbilities
            val allEtb =
                abilities.isNotEmpty() &&
                    abilities.all {
                        it.zoneScope == TriggerZoneScope.Battlefield &&
                            it.condition == TriggerCondition.EnteredBattlefieldSelf
                    }
            if (allEtb) card to abilities.size else null
        }.toMap()

/** The per-card entry and placement counts gathered in one pass over the event log. */
private class EntryTally {
    val entries = mutableMapOf<CardRef, Int>()
    val accountedFor = mutableMapOf<CardRef, Int>()
}

/**
 * Counts, in a single pass over [GameState.events], how many times each card in [tracked] entered
 * the battlefield and how many of its triggers were put on the stack.
 *
 * The three entry announcements are the three ways the log says "an object of this card is now on
 * the battlefield": [GameEvent.PermanentEntered] for a resolved permanent spell and for a return
 * from the graveyard, [GameEvent.LandPlayed] for the CR 305.1 special action, and
 * [GameEvent.TokenCreated] for a token. Each entry emits exactly one of them, so they never
 * double-count a single arrival.
 */
private fun tallyEntriesAndPlacements(
    state: GameState,
    tracked: Set<CardRef>,
): EntryTally {
    val tally = EntryTally()

    fun countEntry(card: CardRef) {
        if (card in tracked) tally.entries[card] = (tally.entries[card] ?: 0) + 1
    }
    state.events.forEach { event ->
        when (event) {
            is GameEvent.PermanentEntered -> countEntry(event.card)
            is GameEvent.LandPlayed -> countEntry(event.card)
            is GameEvent.TokenCreated -> countEntry(event.name)
            is GameEvent.TriggeredAbilityPutOnStack ->
                if (event.sourceCard in tracked) {
                    tally.accountedFor[event.sourceCard] = (tally.accountedFor[event.sourceCard] ?: 0) + 1
                }

            else -> Unit
        }
    }
    return tally
}
