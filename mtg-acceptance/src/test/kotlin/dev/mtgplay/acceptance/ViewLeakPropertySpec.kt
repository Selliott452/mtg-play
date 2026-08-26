package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
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
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.SeatView
import dev.mtgplay.rules.StackEntryView
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.viewFor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
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
 * answer from the raw state, on every target pause, for every seat.
 *
 * `FW-MULTITGT` widens that third half twice over, and neither widening relaxes anything. The zone check
 * now runs on the **multi-target** request kind as well — a kind-specific guard would have skipped it
 * silently — and [checkMultiTargetBounds] adds the ADR-005 half a multi-target option list needs and a
 * single-target one cannot have: the offered bounds are satisfiable, and no object is offered twice, so
 * the engine's distinct-index rule really is CR 601.2c's same-object rule.
 * answer from the raw state, on every `ChooseTargets` pause, for every seat.
 *
 * **`FW-NONCTRLDEC` adds the fourth half, and it is the sharpest one.** Until this packet every decision
 * was answered by the resolving object's controller, so "the deciding seat may see its options" and "the
 * controller may see its options" were the same sentence. Refurbished Familiar's "each opponent discards
 * a card" separates them: the decider is an **opponent** of the controller, and the options are that
 * opponent's own hand, which the controller may not see (CR 402.1). [checkHiddenOptionOwnership] pins the
 * ruling — *the enumerated options of a decision belong to `id.seat` and to no other seat* — as a
 * property over every pause, checking both that no other seat's view names an option card and that the
 * count-only [dev.mtgplay.rules.PendingOpponentDiscardView] carries no identity at all.
 *
 * **`FW-HIDDENCHOICE` extends the oracle rather than relaxing it.** Duress and Mesmeric Fiend make an
 * opponent *reveal* their hand, and CR 701.16a makes those cards public to the table for as long as the
 * reveal is open. The forbidden-name oracle is therefore taught about an open reveal in [publicNames],
 * exactly as it already knows about a library reveal — a name the printed card publishes was never
 * secret, and an oracle that called it a leak would be asserting the wrong game. Nothing is removed from
 * the forbidden set that a card does not itself publish.
 *
 * The Madness-vs-Bogles corpus contains none of the packet's cards, so a **second** corpus drives the
 * hidden-choice and non-controller-decision cards through the identical [checkPause], because a property
 * no game reaches proves nothing.
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

        "CR 701.7a + CR 701.16a: the hidden-choice corpus reaches both new pauses and leaks neither" {
            var pausesChecked = 0
            var opponentDiscardPauses = 0
            var handRevealPauses = 0

            for (seed in 0L until HIDDEN_CHOICE_SEEDS) {
                val game =
                    ScriptedGame
                        .start(hiddenChoiceConfig(seed))
                        .playUntilOverOrBound(
                            RandomLegalResponder(seed),
                            turnCap = REAL_CARD_TURN_CAP,
                            maxDecisions = LEAK_DECISION_CAP,
                        )
                for (pause in game.pauses) {
                    checkPause(pause.state, pause.request)
                    pausesChecked += 1
                    if (pause.state.pendingOpponentDiscard != null) opponentDiscardPauses += 1
                    if (pause.state.pendingHandReveal != null) handRevealPauses += 1
                }
            }

            println(
                "HIDDEN CHOICE CORPUS: $pausesChecked pauses, " +
                    "$opponentDiscardPauses opponent-discard pauses, $handRevealPauses hand-reveal pauses",
            )
            // The property is only worth anything if the corpus actually reaches the new pauses.
            (opponentDiscardPauses > 0).shouldBeTrue()
            (handRevealPauses > 0).shouldBeTrue()
        }
    })

/** Seeds for the hidden-choice corpus; a handful reaches both pauses reliably. */
private const val HIDDEN_CHOICE_SEEDS: Long = 6L

/**
 * A two-seat config built to reach the packet's two new pauses: [alice] plays the non-controller and
 * hidden-choice cards (Refurbished Familiar, Duress, Mesmeric Fiend) and [bob] holds a hand worth
 * revealing and discarding from. Both libraries are legal 60-card lists of defined cards.
 */
private fun hiddenChoiceConfig(seed: Long): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to hiddenChoiceLibrary(), bob to revealTargetLibrary()),
        definitions = MvpCards.definitions,
        startingPlayer = null,
    )

/** [alice]'s library: the packet's opponent-facing cards, on a Swamp base that can cast them. */
private fun hiddenChoiceLibrary(): List<CardRef> =
    repeatCard("Refurbished Familiar", 8) +
        repeatCard("Duress", 8) +
        repeatCard("Mesmeric Fiend", 8) +
        repeatCard("Swamp", 36)

/** [bob]'s library: a mix of creature and noncreature spells, so both restrictions find a legal choice. */
private fun revealTargetLibrary(): List<CardRef> =
    repeatCard("Grizzly Bears", 8) +
        repeatCard("Lightning Bolt", 8) +
        repeatCard("Rancor", 8) +
        repeatCard("Mountain", 36)

/** [count] copies of the card named [name] (CR 100.2a decklists are multisets). */
private fun repeatCard(
    name: String,
    count: Int,
): List<CardRef> = List(count) { CardRef(name) }

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
    // ADR-005 + ADR-007: a decision's options belong to its own seat and to no other (FW-NONCTRLDEC).
    checkHiddenOptionOwnership(state, request)
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
    val named = namesTheViewItselfCarries(view)

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
 * Every printed identity [view] itself names, rebuilt from its public lists — the independent half of
 * [checkCardTable]. Split out when `W10-D`'s explore clause pushed that function past detekt's
 * complexity budget; the split changes nothing about the oracle's independence, which is the property
 * that matters: nothing here consults `visibleCardRefs`.
 */
private fun namesTheViewItselfCarries(view: SeatView): Set<CardRef> {
    val named = mutableSetOf<CardRef>()
    view.battlefield.forEach { named += it.card }
    view.exile.forEach { named += it.card }
    view.stack.forEach { named += stackEntryViewName(it) }
    view.pendingTriggers.forEach { named += it.sourceCard }
    view.pendingReveal?.revealed?.forEach { named += it.card }
    // CR 701.40a (`W10-D`): an open explore has revealed the top card of a library, so the view
    // legitimately names it and the table must describe it.
    view.pendingExplore?.let { named += it.revealed.card }
    // CR 701.16a (`FW-HIDDENCHOICE`): an open hand reveal makes those cards public to every seat, so the
    // view legitimately names them and the table must describe them.
    view.pendingHandReveal?.revealed?.forEach { named += it.card }
    view.players.forEach { player ->
        player.graveyard.forEach { named += it.card }
        when (val hand = player.hand) {
            is HandView.Revealed -> hand.cards.forEach { named += it.card }
            is HandView.Concealed -> Unit
        }
    }
    return named
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
    // `FW-MULTITGT`: both target request kinds are checked. Leaving this as a `ChooseTargets`-only
    // guard would have made every multi-target pause skip the ADR-007 property silently — an option
    // list is information whatever the arity of the answer.
    val options =
        when (request) {
            is DecisionRequest.ChooseTargets -> request.options
            is DecisionRequest.ChooseMultipleTargets -> {
                checkMultiTargetBounds(request)
                request.options
            }
            else -> return
        }
    val hiddenZoneIds =
        state.players.values
            .flatMap { it.library + it.hand }
            .mapTo(mutableSetOf()) { it.id }
    val graveyardObjects =
        state.players.values
            .flatMap { it.graveyard }
            .associateBy { it.id }
    val views = state.players.keys.map { viewFor(state, it) }

    for (option in options) {
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

/**
 * ADR-005 on a multi-target option list (CR 601.2c), checked on every such pause of the corpus.
 *
 * A multi-target request is where an enumeration most easily offers an **illegal combination**, and
 * exactly two things stop it: the bounds must be satisfiable from the options actually offered, and no
 * object may be offered twice — because the engine enforces "the same target can't be chosen multiple
 * times for any one instance of the word 'target'" as distinct *indices*, which is only the same rule
 * while the option list is duplicate-free. Both are asserted here from the request itself, so a future
 * enumeration branch that started emitting one object twice would fail the corpus rather than quietly
 * letting an agent point two targets at one card.
 */
private fun checkMultiTargetBounds(request: DecisionRequest.ChooseMultipleTargets) {
    request.options.distinct().size shouldBe request.options.size
    (request.minimumCount >= 0).shouldBeTrue()
    (request.minimumCount <= request.maximumCount).shouldBeTrue()
    (request.maximumCount <= request.options.size).shouldBeTrue()
    request.options.shouldNotBeEmpty()
}

/**
 * The `FW-NONCTRLDEC` ruling, pinned as a property (docs/design/exile-and-return.md §6.1): **the
 * enumerated options of a decision belong to `id.seat` and to no other seat.**
 *
 * Checked on an each-opponent discard (CR 701.7a), the first request whose deciding seat is neither the
 * priority holder nor the resolving object's controller, and whose options are the decider's **own
 * hand** — a hidden zone (CR 402.1).
 *
 * Two halves, and they fail independently:
 *
 * *Half one — no other seat's view names an option card.* The controller's view must not carry the
 * identity of any card in the deciding opponent's hand. Checked against the view's whole card table
 * rather than against the discard projection alone, because a leak that arrived through some *other*
 * field would be exactly as bad and exactly as invisible. Cards the controller may legitimately see by
 * another route are excluded via [publicNames], so a coincidence is never a false alarm.
 *
 * *Half two — the projection carries no identity at all.* [dev.mtgplay.rules.PendingOpponentDiscardView]
 * is count-only by construction, for every seat including the deciding one. Asserting the count matches
 * the request while no card crosses is what would notice a future "convenience" field being added to it.
 */
private fun checkHiddenOptionOwnership(
    state: GameState,
    request: DecisionRequest,
) {
    val pending = state.pendingOpponentDiscard ?: return
    val decider = pending.decider
    val controller = pending.controller

    // Half two: the projection is count-only, in every seat's view, and agrees with the real pause.
    state.players.keys.forEach { seat ->
        val projected = viewFor(state, seat).pendingOpponentDiscard.shouldNotBeNull()
        projected.decider shouldBe decider
        projected.controller shouldBe controller
        projected.count shouldBe pending.count
        projected.remainingCount shouldBe pending.remaining.size
    }

    // Half one: the deciding seat's hand names never reach the controller's view, by any route.
    if (request !is DecisionRequest.ChooseOpponentDiscards) return
    request.id.seat shouldBe decider
    val controllerJson = seatUpdateMessage(viewFor(state, controller)).encode()
    val secret = hiddenNames(state, decider, controller)
    request.options
        .map { it.card.name }
        .filter { it in secret }
        .forEach { name -> controllerJson.contains("\"$name\"") shouldBe false }
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
    return names + namesAnOpenRevealPublishes(state)
}

/**
 * The names an **open reveal** puts on the table (CR 701.16, CR 701.40a, CR 701.16a) — the clauses that
 * make a card in an otherwise-hidden zone legitimately observable for as long as the pause lasts.
 *
 * Every clause here widens the **oracle**, not the view: it records what a printed card published, so a
 * name the card itself gave away is not counted as a leak. Split out of [publicNames] when `W10-D`'s
 * explore clause pushed it past detekt's complexity budget; the oracle stays independent of
 * `visibleCardRefs`, which is the property that makes the pair worth having.
 */
private fun namesAnOpenRevealPublishes(state: GameState): Set<String> {
    val names = mutableSetOf<String>()
    // CR 701.16: the reveal selection's cards are the top of a library and are public while it is open.
    val reveal = state.pendingRevealSelection
    if (reveal != null) {
        val library = state.players.getValue(reveal.decider).library
        reveal.revealedIds.forEach { id -> library.firstOrNull { it.id == id }?.let { names += it.card.name } }
    }
    // CR 701.40a (`W10-D`): an explore *reveals* the top card of the exploring permanent's controller's
    // library, so that one card is observable while the placement decision is open — from inside a
    // library, the one zone the seat view otherwise never discloses.
    val explore = state.pendingExplore
    if (explore != null) {
        state.players
            .getValue(explore.decider)
            .library
            .firstOrNull { it.id == explore.revealed }
            ?.let { names += it.card.name }
    }
    // CR 701.16a (`FW-HIDDENCHOICE`): a hand told to reveal itself is public to the table while the
    // reveal is open, so Duress's and Mesmeric Fiend's target's cards are legitimately observable. Every
    // other hand stays secret.
    val handReveal = state.pendingHandReveal
    if (handReveal != null) {
        state.players
            .getValue(handReveal.revealer)
            .hand
            .forEach { names += it.card.name }
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
