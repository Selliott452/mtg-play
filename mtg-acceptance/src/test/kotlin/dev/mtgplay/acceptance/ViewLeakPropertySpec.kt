package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.protocol.ServerMessage
import dev.mtgplay.protocol.decodeServerMessage
import dev.mtgplay.protocol.encode
import dev.mtgplay.protocol.seatUpdateMessage
import dev.mtgplay.protocol.toDomain
import dev.mtgplay.rules.DecisionView
import dev.mtgplay.rules.HandView
import dev.mtgplay.rules.SeatView
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.viewFor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The crown-jewel leak property of P7.1 (ADR-007 + ADR-008): across real Madness-vs-Bogles
 * matchup-corpus games, at **every** pause, a seat's serialized filtered view never leaks the
 * opponent's hidden hand card names or library contents, and both views (each carrying the deciding
 * seat's request) round-trip through the strict protocol codec unchanged.
 *
 * The forbidden-name oracle is derived independently from the raw [GameState]'s genuinely public
 * zones (not from the view under test), so a `viewFor` bug that copied a hidden card into the wrong
 * seat's view is caught: a hidden, deck-distinctive card name absent from every public zone would
 * appear in the opponent's JSON. Basic-land and otherwise-public names are excluded from the
 * forbidden set, so a coincidental public occurrence is never a false alarm.
 */
class ViewLeakPropertySpec :
    StringSpec({
        "ADR-007: no seat view leaks the opponent's hidden hand or library across the matchup corpus" {
            var pausesChecked = 0
            var bytesScanned = 0L
            val startNanos = System.nanoTime()

            for (seed in 0L until LEAK_SEEDS) {
                val game =
                    ScriptedGame
                        .start(mvpMatchupConfig(seed))
                        .playUntilOverOrBound(
                            RandomLegalResponder(seed),
                            turnCap = REAL_CARD_TURN_CAP,
                            maxDecisions = LEAK_DECISION_CAP,
                        )
                for (pause in game.pauses) {
                    bytesScanned += checkPause(pause.state, pause.request)
                    pausesChecked += 1
                }
            }

            val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
            println(
                "VIEW LEAK PROPERTY: $LEAK_SEEDS seeds, $pausesChecked pauses checked, " +
                    "$bytesScanned opponent-view bytes scanned, runtime ${elapsedMillis}ms",
            )
            // A returned count is itself proof: every pause was checked with no leak and no round-trip failure.
            (pausesChecked > 0).shouldBeTrue()
        }
    })

// A modest corpus sample: full real-card games serialized at every pause on both seats stay well
// under a couple of seconds while covering mulligans, casts, combat, reveals, and searches.
private const val LEAK_SEEDS: Long = 8L
private const val LEAK_DECISION_CAP: Int = 60_000

/**
 * Checks one pause: both seats' filtered views (each carrying its decision context, so the deciding
 * seat's request is round-tripped with its view) survive the codec, the opponent view carries no
 * hidden information, and no owner-hidden card name appears in the opponent's JSON. Returns the
 * opponent-view bytes scanned.
 */
private fun checkPause(
    state: GameState,
    request: DecisionRequest,
): Long {
    var bytes = 0L
    val seats = state.players.keys.toList()
    for (owner in seats) {
        val opponent = seats.single { it != owner }
        val ownerView = viewFor(state, owner)
        val opponentView = viewFor(state, opponent)

        // Round-trip both views through the strict codec, wrapped in their seat-update envelopes (ADR-008).
        roundTrips(ownerView).shouldBeTrue()
        roundTrips(opponentView).shouldBeTrue()

        // Structural guarantee: the opponent never gets the owner's hand contents...
        opponentView.players
            .single { it.seat == owner }
            .hand
            .shouldBeInstanceOf<HandView.Concealed>()
        // ...nor another seat's request options — only the deciding seat holds a full request.
        if (request.seat != opponent) {
            opponentView.pendingDecision.shouldBeInstanceOf<DecisionView.Elsewhere>()
        }

        // Byte-scan defense in depth: no owner-hidden card name appears in the opponent's JSON.
        val opponentJson = seatUpdateMessage(opponentView).encode()
        bytes += opponentJson.length
        hiddenNames(state, owner, opponent).forEach { name ->
            opponentJson.contains("\"$name\"") shouldBe false
        }
    }
    // The deciding seat's request rides inside its own view as a ToDecide, which was round-tripped above.
    (viewFor(state, request.seat).pendingDecision as DecisionView.ToDecide).request shouldBe request
    return bytes
}

/** Whether [view] survives view -> seat-update message -> JSON -> message -> view unchanged. */
private fun roundTrips(view: SeatView): Boolean {
    val message = seatUpdateMessage(view)
    val back = decodeServerMessage(message.encode())
    return back is ServerMessage.SeatUpdate && back == message && back.view.toDomain() == view
}

/**
 * The card names that are genuinely secret to [opponent] — present in [owner]'s hand or library but
 * absent from every zone [opponent] may legitimately see. Derived from the raw state, independently
 * of any view, so it is a true oracle for the byte-scan.
 */
private fun hiddenNames(
    state: GameState,
    owner: PlayerId,
    opponent: PlayerId,
): Set<String> {
    val ownerState = state.players.getValue(owner)
    val hidden = (ownerState.hand + ownerState.library).mapTo(mutableSetOf()) { it.card.name }
    return hidden - publicNames(state, owner, opponent)
}

/**
 * Every card name [opponent] may legitimately observe: the shared public zones, both graveyards,
 * revealed cards, and — because a name [opponent] holds or could reveal from its own library is not
 * a leak of [owner]'s card — [opponent]'s own hand and library.
 */
private fun publicNames(
    state: GameState,
    owner: PlayerId,
    opponent: PlayerId,
): Set<String> {
    val opponentState = state.players.getValue(opponent)
    val names = mutableSetOf<String>()
    state.sharedZones.battlefield.forEach { names += it.card.name }
    state.sharedZones.exile.forEach { names += it.card.name }
    state.sharedZones.stack.forEach { names += stackEntryName(it) }
    state.players
        .getValue(owner)
        .graveyard
        .forEach { names += it.card.name }
    opponentState.graveyard.forEach { names += it.card.name }
    opponentState.hand.forEach { names += it.card.name }
    opponentState.library.forEach { names += it.card.name }
    val reveal = state.pendingRevealSelection
    if (reveal != null) {
        val library = state.players.getValue(reveal.decider).library
        reveal.revealedIds.forEach { id -> library.firstOrNull { it.id == id }?.let { names += it.card.name } }
    }
    return names
}

/** The printed name a stack entry contributes (a spell's card, or an ability's source). */
private fun stackEntryName(entry: StackEntry): String =
    when (entry) {
        is StackEntry.Spell -> entry.obj.card.name
        is StackEntry.Ability -> entry.trigger.sourceCard.name
        is StackEntry.ActivatedAbilityOnStack -> entry.sourceCard.name
    }
