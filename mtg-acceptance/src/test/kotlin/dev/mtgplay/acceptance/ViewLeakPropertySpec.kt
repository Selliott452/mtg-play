package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.protocol.ServerMessage
import dev.mtgplay.protocol.decodeServerMessage
import dev.mtgplay.protocol.encode
import dev.mtgplay.protocol.seatUpdateMessage
import dev.mtgplay.protocol.toDomain
import dev.mtgplay.rules.DecisionView
import dev.mtgplay.rules.HandView
import dev.mtgplay.rules.SeatView
import dev.mtgplay.rules.StackEntryView
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.viewFor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
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
 *
 * The byte-scan covers [SeatView.cards] for free — a card-table key is a quoted card name in the very
 * JSON it scans — so shipping more of the definition registry than a seat may see fails here. P8.2 adds
 * the complementary **completeness** half: the table must describe every card the view itself names
 * (tokens included, CR 111) and nothing else. Both halves are computed from the view's own public
 * lists, independently of the production collector.
 *
 * `FW-ZONETGT` adds a third half, and it is where ADR-005 and ADR-007 have to agree: an *option list* is
 * information too. Once a target can name a card in a zone other than the battlefield or the stack, the
 * question "may the deciding seat be told this option exists?" stops being answered by the request
 * machinery and starts being answered by the **zone** (CR 400.2). [checkTargetOptionZones] pins that
 * answer from the raw state, on every `ChooseTargets` pause, for every seat.
 */
class ViewLeakPropertySpec :
    StringSpec({
        "ADR-007: no seat view leaks the opponent's hidden hand or library across the matchup corpus" {
            var pausesChecked = 0
            var bytesScanned = 0L
            var tokensDescribed = 0
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
                    val scan = checkPause(pause.state, pause.request)
                    bytesScanned += scan.bytesScanned
                    tokensDescribed += scan.tokensDescribed
                    pausesChecked += 1
                }
            }

            val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
            println(
                "VIEW LEAK PROPERTY: $LEAK_SEEDS seeds, $pausesChecked pauses checked, " +
                    "$bytesScanned opponent-view bytes scanned, $tokensDescribed token entries described, " +
                    "runtime ${elapsedMillis}ms",
            )
            // A returned count is itself proof: every pause was checked with no leak and no round-trip failure.
            (pausesChecked > 0).shouldBeTrue()
            // CR 111: the corpus really does create tokens, so the card-table checks above are exercised on them.
            (tokensDescribed > 0).shouldBeTrue()
        }
    })

// A modest corpus sample: full real-card games serialized at every pause on both seats stay well
// under a couple of seconds while covering mulligans, casts, combat, reveals, and searches.
private const val LEAK_SEEDS: Long = 8L
private const val LEAK_DECISION_CAP: Int = 60_000

/** What one checked pause contributed to the corpus totals. */
private data class PauseScan(
    val bytesScanned: Long,
    val tokensDescribed: Int,
)

/**
 * Checks one pause: both seats' filtered views (each carrying its decision context, so the deciding
 * seat's request is round-tripped with its view) survive the codec, the opponent view carries no
 * hidden information, no owner-hidden card name appears in the opponent's JSON, and each view's card
 * table describes exactly the cards that view names (P8.2).
 */
private fun checkPause(
    state: GameState,
    request: DecisionRequest,
): PauseScan {
    var bytes = 0L
    var tokens = 0
    val seats = state.players.keys.toList()
    for (owner in seats) {
        val opponent = seats.single { it != owner }
        val ownerView = viewFor(state, owner)
        val opponentView = viewFor(state, opponent)

        // The card table describes what each view names, and only that (ADR-007, CR 111 for tokens).
        tokens += checkCardTable(state, ownerView)
        checkCardTable(state, opponentView)

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
    // ADR-005 + ADR-007: every target the seat is offered lives in a zone it may see (FW-ZONETGT).
    checkTargetOptionZones(state, request)
    return PauseScan(bytesScanned = bytes, tokensDescribed = tokens)
}

/**
 * The card-table contract of one view (P8.2, docs/design/seat-view-definitions.md): every printed
 * identity the view itself names and the match defines is described, nothing the view does **not**
 * name is described, and each entry carries the definition's own characteristics plus the CR 111
 * token fact. The named set is rebuilt here from the view's public lists, independently of the
 * production collector, so a collector that widened its scope is caught rather than confirmed.
 * Returns how many of the entries are tokens.
 */
private fun checkCardTable(
    state: GameState,
    view: SeatView,
): Int {
    val named = mutableSetOf<CardRef>()
    view.battlefield.forEach { named += it.card }
    view.exile.forEach { named += it.card }
    view.stack.forEach { named += stackEntryViewName(it) }
    view.pendingTriggers.forEach { named += it.sourceCard }
    view.pendingReveal?.revealed?.forEach { named += it.card }
    view.players.forEach { player ->
        player.graveyard.forEach { named += it.card }
        when (val hand = player.hand) {
            is HandView.Revealed -> hand.cards.forEach { named += it.card }
            is HandView.Concealed -> Unit
        }
    }

    // Completeness: no card the seat can see is left undescribed (an undefined ref is inert, P2.1).
    named.filter { state.definitions.containsKey(it) }.forEach { ref -> view.cards.containsKey(ref) shouldBe true }
    // Scope: the table never discloses an identity the view does not already name (ADR-007).
    (view.cards.keys - named).shouldBeEmpty()
    // Fidelity: the described characteristics and the token fact are the engine's own.
    view.cards.forEach { (ref, card) ->
        val definition = state.definitions.getValue(ref)
        card.characteristics shouldBe definition.characteristics
        card.isToken shouldBe (definition is TokenDefinition)
    }
    return view.cards.count { it.value.isToken }
}

/**
 * The ADR-005/ADR-007 agreement on a target option list (`FW-ZONETGT`,
 * docs/design/graveyard-targeting.md §3), pinned as **two halves that fail together**.
 *
 * *Half one — no option names a hidden-zone object.* CR 400.2 makes a library and a hand hidden zones,
 * so an option naming a card in either would hand the deciding seat information the CR never gave it,
 * and the byte-scan above would not catch it: the seat is *allowed* to know its own hand, and an
 * opponent's library card would arrive as an id rather than as a quoted name. This is checked over the
 * raw state's zone contents, not over the [dev.mtgplay.core.state.Target] subtype, so it stays a true
 * property if a future member is ever added.
 *
 * *Half two — every option's identity is already in both seats' card tables.* Half one is only safe
 * because the graveyard is public and `visibleCardRefs` feeds **both** seats' graveyards into
 * [SeatView.cards]. If that ever narrowed — to the viewer's own graveyard, say — half one would still
 * pass while a seat received an option it could not name. So the non-deciding seat is checked too, and
 * it is the load-bearing one.
 *
 * Options that name a player, a battlefield permanent, or a spell on the stack are public by
 * construction (CR 400.2, CR 405) and contribute nothing to either half.
 */
private fun checkTargetOptionZones(
    state: GameState,
    request: DecisionRequest,
) {
    if (request !is DecisionRequest.ChooseTargets) return
    val hiddenZoneIds =
        state.players.values
            .flatMap { it.library + it.hand }
            .mapTo(mutableSetOf()) { it.id }
    val graveyardObjects =
        state.players.values
            .flatMap { it.graveyard }
            .associateBy { it.id }
    val views = state.players.keys.map { viewFor(state, it) }

    for (option in request.options) {
        val named =
            when (option) {
                // Public by construction: a player, a battlefield permanent, a face-up stack object.
                is Target.Player, is Target.Permanent, is Target.SpellOnStack -> null
                is Target.CardInGraveyard -> {
                    hiddenZoneIds.contains(option.id) shouldBe false
                    graveyardObjects[option.id].shouldNotBeNull().card
                }
            }
        if (named != null && state.definitions.containsKey(named)) {
            views.forEach { view -> view.cards.containsKey(named) shouldBe true }
        }
    }
}

/** The printed identity a [StackEntryView] names (CR 405): a spell's card, or an ability's source. */
private fun stackEntryViewName(entry: StackEntryView): CardRef =
    when (entry) {
        is StackEntryView.SpellOnStack -> entry.card
        is StackEntryView.TriggeredAbilityOnStack -> entry.sourceCard
        is StackEntryView.ActivatedAbilityOnStack -> entry.sourceCard
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
