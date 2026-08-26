package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.Responder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.LIGHTNING_BOLT_DAMAGE
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption

/*
 * Deterministic drivers for the real-card bolt-duel suites (P2.2) and the P2.3 stack-corner
 * scenarios: the burn policy, seed searches, single-action script helpers, and the
 * death-mid-stack duel that pins the CR 704.5a game-ends-first behaviour.
 */

/**
 * The deterministic burn policy: play a land whenever one is enumerated, otherwise cast the
 * first enumerated spell, otherwise pass; always target an opponent (never itself); take the
 * first payment plan; discard lowest-indexed. Purely a function of the request, so a game it
 * drives is a reproducible decision script (ADR-006) — the scripted way to accumulate Bolts
 * until the CR 704.5a state-based action ends the game.
 */
internal val BURN_OPPONENT: Responder =
    Responder { request, _ ->
        when (request) {
            is DecisionRequest.ChooseAction -> {
                val playLand = request.options.indexOfFirst { it is PriorityOption.PlayLand }
                val cast = request.options.indexOfFirst { it is PriorityOption.CastSpell }
                val pass = request.options.indexOfFirst { it is PriorityOption.Pass }
                val index =
                    when {
                        playLand >= 0 -> playLand
                        cast >= 0 -> cast
                        else -> pass
                    }
                Decision.SingleSelect(request.id, index)
            }
            is DecisionRequest.ChooseTargets -> {
                val index = request.options.indexOfFirst { it != Target.Player(request.seat) }
                check(index >= 0) { "no opponent target among ${request.options}" }
                Decision.SingleSelect(request.id, index)
            }
            // CR 601.2b: the burn decks hold no modal card, so no mode is ever chosen.
            is DecisionRequest.ChooseModes ->
                error("the burn policy casts no modal card, but a mode request surfaced: $request")
            is DecisionRequest.ChoosePaymentPlan -> Decision.SingleSelect(request.id, 0)
            // CR 601.2b: no burn card in this scenario has an {X} cost, so an announcement is
            // unreachable; fail loudly rather than guessing a value that would change the damage.
            is DecisionRequest.ChooseXValue ->
                error("this scenario casts no {X} spell, but an X announcement surfaced: $request")
            is DecisionRequest.ChooseDiscards ->
                Decision.MultiSelect(request.id, (0 until request.count).toList())
            // The burn decks hold no creatures: attack and block with nothing (CR 508.1 / 509.1);
            // a blocker-order request (CR 509.2) is therefore unreachable.
            is DecisionRequest.DeclareAttackers -> Decision.MultiSelect(request.id, emptyList())
            is DecisionRequest.DeclareBlockers -> Decision.MultiSelect(request.id, emptyList())
            is DecisionRequest.OrderBlockers ->
                error("the burn policy never blocks, but a blocker-order request surfaced: $request")
            is DecisionRequest.AssignTrampleDamage ->
                error("the burn policy has no tramplers, but a trample-assignment request surfaced: $request")
            is DecisionRequest.OrderTriggers ->
                error("the burn policy fires no triggers, but a trigger-order request surfaced: $request")
            is DecisionRequest.ChooseYesNo ->
                error("the burn policy casts no madness cards, but a yes/no request surfaced: $request")
            // ChooseDiscards is handled above; the other sized selections are cost/ability choices the burn
            // policy never reaches.
            is DecisionRequest.SizedSelection ->
                error("the burn policy pays no cost selections, but a sized-selection request surfaced: $request")
            // CR 601.2b/701.60a: collect evidence is a cast cost the burn pool does not print.
            is DecisionRequest.SummedSelection ->
                error("the burn policy collects no evidence, but a summed-selection request surfaced: $request")
            // CR 601.2c: the burn pool prints no multi-target line, so one surfacing is a defect.
            is DecisionRequest.RangedSelection ->
                error("the burn policy casts no multi-target spell, but one surfaced: $request")
            is DecisionRequest.ChooseReplacement ->
                error("the burn policy discards no two-replacement cards, but one surfaced: $request")
            is DecisionRequest.ChooseColor ->
                error("the burn policy casts no colour-choosing permanents, but a colour request surfaced: $request")
            is DecisionRequest.ChooseFromRevealed ->
                error("the burn policy resolves no reveal effects, but a reveal request surfaced: $request")
            is DecisionRequest.ChooseCostMode ->
                error("the burn policy resolves no cost-then-draw spells, but a cost-mode request surfaced: $request")
            is DecisionRequest.ChooseFromLibrary ->
                error("the burn policy activates no library searches, but a find-library request surfaced: $request")
            is DecisionRequest.ChooseLibraryArrangement ->
                error("the burn policy resolves no library looks, but an arrangement request surfaced: $request")
            is DecisionRequest.ChooseCounterPayment ->
                error("the burn policy casts no counters, but an unless-pay request surfaced: $request")
            is DecisionRequest.ChooseRevealedHandCard ->
                error("the burn policy casts no hand-reveal spells, but a revealed-hand request surfaced: $request")
            is DecisionRequest.ChooseTapOrUntap ->
                error("the burn policy resolves no tap-or-untap clause, but one surfaced: $request")
            is DecisionRequest.ChooseOptionalManaPayment ->
                error("the burn policy plays no pay-then-draw permanents, but one surfaced: $request")
            is DecisionRequest.ChooseGraveyardCardToExile ->
                error("the burn policy activates no graveyard-exile abilities, but one surfaced: $request")
            is DecisionRequest.ChooseRevealedCardType ->
                error("the burn policy casts no type-choosing reveal spells, but one surfaced: $request")
            is DecisionRequest.MulliganRequest ->
                error("the burn policy runs mulligan-free games, but a mulligan request surfaced: $request")
        }
    }

/**
 * The first seed in `0..999` whose game from [configOf] deals [seat] an opening hand
 * satisfying [predicate] — a deterministic search (ADR-006: the engine is a pure function of
 * the config), so scripted suites can rely on a hand shape without hardcoding a magic seed.
 */
internal fun seedWithOpeningHand(
    seat: PlayerId,
    configOf: (Long) -> MatchConfig,
    predicate: (List<String>) -> Boolean,
): Long {
    for (seed in 0L..999L) {
        val started = DefaultGameEngine().start(configOf(seed))
        val state =
            when (started) {
                is AdvanceResult.NeedsDecision -> started.state
                is AdvanceResult.GameOver -> continue
            }
        val hand =
            state.players
                .getValue(seat)
                .hand
                .map { it.card.name }
        if (predicate(hand)) return seed
    }
    error("no seed in 0..999 deals $seat a qualifying opening hand")
}

/**
 * The first seed in `0..999` whose game from [configOf] deals *both* seats opening hands
 * satisfying [predicate] — the two-seat sibling of [seedWithOpeningHand], for scripts that
 * choreograph both players (the P2.3 stack scenarios).
 */
internal fun seedWithOpeningHands(
    configOf: (Long) -> MatchConfig,
    predicate: (aliceHand: List<String>, bobHand: List<String>) -> Boolean,
): Long {
    for (seed in 0L..999L) {
        val started = DefaultGameEngine().start(configOf(seed))
        val state =
            when (started) {
                is AdvanceResult.NeedsDecision -> started.state
                is AdvanceResult.GameOver -> continue
            }

        fun handOf(seat: PlayerId): List<String> =
            state.players
                .getValue(seat)
                .hand
                .map { it.card.name }
        if (predicate(handOf(alice), handOf(bob))) return seed
    }
    error("no seed in 0..999 deals both seats qualifying opening hands")
}

/**
 * A life total from which any single Lightning Bolt is lethal (CR 704.5a): reached from 20
 * starting life by exactly six 3-damage hits. The death-mid-stack grind brings *both* players
 * here, so whichever stacked Bolt resolves first ends the game — the sharpest pin on "the game
 * ends at the first lethal resolution, never the second".
 */
internal const val BOLT_LETHAL_LIFE: Int = STARTING_LIFE - 6 * LIGHTNING_BOLT_DAMAGE

/** Runaway guard for the death-mid-stack life grind; far above its real decision count. */
internal const val GRIND_DECISION_CAP: Int = 4_000

/** The seat opposing [seat] in the two-player acceptance fixture. */
internal fun opponentOf(seat: PlayerId): PlayerId = if (seat == alice) bob else alice

/** How many untapped Mountains [seat] owns on the battlefield. */
internal fun untappedMountains(
    state: GameState,
    seat: PlayerId,
): Int = state.sharedZones.battlefield.count { it.owner == seat && !it.tapped && it.card == CardRef("Mountain") }

/** Whether [seat]'s hand holds at least one Lightning Bolt. */
internal fun holdsBoltInHand(
    state: GameState,
    seat: PlayerId,
): Boolean =
    state.players
        .getValue(seat)
        .hand
        .any { it.card == CardRef("Lightning Bolt") }

/**
 * The death-mid-stack grind policy: play a land whenever one is enumerated; on the seat's own
 * turn with the stack empty, Bolt the opponent — but only while the hit leaves the opponent at
 * or above [BOLT_LETHAL_LIFE], and only while keeping one Mountain untapped in reserve (the
 * mana the later duel's *responder* needs on the opponent's turn); otherwise pass. Casting only
 * onto an empty stack makes the grind strictly sequential — every Bolt resolves before the next
 * is considered — so both life totals step down 3 at a time from 20 to exactly
 * [BOLT_LETHAL_LIFE], never past it. Purely a function of `(request, state)`, so any game it
 * drives is a reproducible decision script (ADR-006).
 */
internal val GRIND_TO_BOLT_RANGE: Responder =
    Responder { request, state ->
        when (request) {
            is DecisionRequest.ChooseAction -> {
                val playLand = request.options.indexOfFirst { it is PriorityOption.PlayLand }
                val cast = request.options.indexOfFirst { it is PriorityOption.CastSpell }
                val pass = request.options.indexOfFirst { it is PriorityOption.Pass }
                val opponent = opponentOf(request.seat)
                val opponentStaysInRange =
                    state.players.getValue(opponent).life >= BOLT_LETHAL_LIFE + LIGHTNING_BOLT_DAMAGE
                val shouldCast =
                    cast >= 0 &&
                        request.seat == state.turn.activePlayer &&
                        state.sharedZones.stack.isEmpty() &&
                        opponentStaysInRange &&
                        untappedMountains(state, request.seat) >= 2
                val index =
                    when {
                        playLand >= 0 -> playLand
                        shouldCast -> cast
                        else -> pass
                    }
                Decision.SingleSelect(request.id, index)
            }
            is DecisionRequest.ChooseTargets -> {
                val index = request.options.indexOfFirst { it != Target.Player(request.seat) }
                check(index >= 0) { "no opponent target among ${request.options}" }
                Decision.SingleSelect(request.id, index)
            }
            // CR 601.2b: the burn decks hold no modal card, so no mode is ever chosen.
            is DecisionRequest.ChooseModes ->
                error("the burn policy casts no modal card, but a mode request surfaced: $request")
            is DecisionRequest.ChoosePaymentPlan -> Decision.SingleSelect(request.id, 0)
            // CR 601.2b: no burn card in this scenario has an {X} cost, so an announcement is
            // unreachable; fail loudly rather than guessing a value that would change the damage.
            is DecisionRequest.ChooseXValue ->
                error("this scenario casts no {X} spell, but an X announcement surfaced: $request")
            is DecisionRequest.ChooseDiscards ->
                Decision.MultiSelect(request.id, (0 until request.count).toList())
            // The burn decks hold no creatures: attack and block with nothing (CR 508.1 / 509.1);
            // a blocker-order request (CR 509.2) is therefore unreachable.
            is DecisionRequest.DeclareAttackers -> Decision.MultiSelect(request.id, emptyList())
            is DecisionRequest.DeclareBlockers -> Decision.MultiSelect(request.id, emptyList())
            is DecisionRequest.OrderBlockers ->
                error("the grind policy never blocks, but a blocker-order request surfaced: $request")
            is DecisionRequest.AssignTrampleDamage ->
                error("the grind policy has no tramplers, but a trample-assignment request surfaced: $request")
            is DecisionRequest.OrderTriggers ->
                error("the grind policy fires no triggers, but a trigger-order request surfaced: $request")
            is DecisionRequest.ChooseYesNo ->
                error("the grind policy casts no madness cards, but a yes/no request surfaced: $request")
            // ChooseDiscards is handled above; the other sized selections are cost/ability choices the grind
            // policy never reaches.
            is DecisionRequest.SizedSelection ->
                error("the grind policy pays no cost selections, but a sized-selection request surfaced: $request")
            // CR 601.2b/701.60a: collect evidence is a cast cost the grind pool does not print.
            is DecisionRequest.SummedSelection ->
                error("the grind policy collects no evidence, but a summed-selection request surfaced: $request")
            // CR 601.2c: the grind pool prints no multi-target line, so one surfacing is a defect.
            is DecisionRequest.RangedSelection ->
                error("the grind policy casts no multi-target spell, but one surfaced: $request")
            is DecisionRequest.ChooseReplacement ->
                error("the grind policy discards no two-replacement cards, but one surfaced: $request")
            is DecisionRequest.ChooseColor ->
                error("the grind policy casts no colour-choosing permanents, but a colour request surfaced: $request")
            is DecisionRequest.ChooseFromRevealed ->
                error("the grind policy resolves no reveal effects, but a reveal request surfaced: $request")
            is DecisionRequest.ChooseCostMode ->
                error("the grind policy resolves no cost-then-draw spells, but a cost-mode request surfaced: $request")
            is DecisionRequest.ChooseFromLibrary ->
                error("the grind policy activates no library searches, but a find-library request surfaced: $request")
            is DecisionRequest.ChooseLibraryArrangement ->
                error("the grind policy resolves no library looks, but an arrangement request surfaced: $request")
            is DecisionRequest.ChooseCounterPayment ->
                error("the grind policy casts no counters, but an unless-pay request surfaced: $request")
            is DecisionRequest.ChooseRevealedHandCard ->
                error("the grind policy casts no hand-reveal spells, but a revealed-hand request surfaced: $request")
            is DecisionRequest.ChooseTapOrUntap ->
                error("the grind policy resolves no tap-or-untap clause, but one surfaced: $request")
            is DecisionRequest.ChooseOptionalManaPayment ->
                error("the grind policy plays no pay-then-draw permanents, but one surfaced: $request")
            is DecisionRequest.ChooseGraveyardCardToExile ->
                error("the grind policy activates no graveyard-exile abilities, but one surfaced: $request")
            is DecisionRequest.ChooseRevealedCardType ->
                error("the grind policy casts no type-choosing reveal spells, but one surfaced: $request")
            is DecisionRequest.MulliganRequest ->
                error("the grind policy runs mulligan-free games, but a mulligan request surfaced: $request")
        }
    }

/**
 * Takes the CR 116.2a play-land special action from the pending priority window: selects the
 * first enumerated [PriorityOption.PlayLand]. Fails loudly if the game is not paused on a
 * window offering one.
 */
internal fun playLand(game: ScriptedGame) {
    val window =
        game.pendingRequest as? DecisionRequest.ChooseAction
            ?: error("playing a land requires a priority window, was ${game.pendingRequest}")
    val index = window.options.indexOfFirst { it is PriorityOption.PlayLand }
    check(index >= 0) { "CR 305.1: no play-land option enumerated in ${window.options}" }
    game.apply(Decision.SingleSelect(window.id, index))
}

/**
 * Casts a Lightning Bolt targeting [target] from the pending priority window, answering the
 * whole CR 601.2 gathering — the cast option, the target (CR 601.2c), and the sole payment
 * plan (CR 601.2g; the pool is always empty at a window, so tapping a Mountain is the only
 * plan) — and returns the stack object id the cast produced ([GameEvent.SpellCast]). Fails
 * loudly at any unexpected pause.
 */
internal fun castBoltAt(
    game: ScriptedGame,
    target: PlayerId,
): ObjectId {
    val window =
        game.pendingRequest as? DecisionRequest.ChooseAction
            ?: error("casting requires a priority window, was ${game.pendingRequest}")
    val castIndex =
        window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef("Lightning Bolt") }
    check(castIndex >= 0) { "no Lightning Bolt cast enumerated in ${window.options}" }
    game.apply(Decision.SingleSelect(window.id, castIndex))
    val targets =
        game.pendingRequest as? DecisionRequest.ChooseTargets
            ?: error("expected the CR 601.2c targets request, was ${game.pendingRequest}")
    val targetIndex = targets.options.indexOf(Target.Player(target))
    check(targetIndex >= 0) { "player $target is not among the legal targets ${targets.options}" }
    game.apply(Decision.SingleSelect(targets.id, targetIndex))
    val payment =
        game.pendingRequest as? DecisionRequest.ChoosePaymentPlan
            ?: error("expected the CR 601.2g payment request, was ${game.pendingRequest}")
    game.apply(Decision.SingleSelect(payment.id, 0))
    return game.state.events
        .filterIsInstance<GameEvent.SpellCast>()
        .last()
        .objectId
}

/**
 * The finished death-mid-stack duel a suite asserts against: the game (over, CR 704.5a), the
 * config that produced it (for replay, ADR-006), the stack object ids of the two duel Bolts,
 * and the event-log size at the duel's start (so assertions can slice off the grind).
 */
internal data class DeathMidStackOutcome(
    val game: ScriptedGame,
    val config: MatchConfig,
    val initiatorBoltId: ObjectId,
    val responderBoltId: ObjectId,
    val eventsBeforeDuel: Int,
)

// Both opening hands hold two Mountains and two Bolts, so the grind starts promptly on both
// sides; computed once and shared by every duel run (deterministic, ADR-006).
private val deathDuelSeed: Long by lazy {
    seedWithOpeningHands({ seed -> burnConfig(seed, startingPlayer = alice) }) { aliceHand, bobHand ->
        listOf(aliceHand, bobHand).all { hand ->
            hand.count { it == "Mountain" } >= 2 && hand.count { it == "Lightning Bolt" } >= 2
        }
    }
}

// Whether the death-mid-stack duel can start: the initiator's own precombat main, empty stack,
// initiator holding priority, a Bolt in each hand, and an untapped Mountain on each side.
private fun duelReady(
    state: GameState,
    initiator: PlayerId,
    responder: PlayerId,
): Boolean =
    state.turn.activePlayer == initiator &&
        state.turn.phase == TurnPhase.PRECOMBAT_MAIN &&
        state.sharedZones.stack.isEmpty() &&
        state.players.getValue(initiator).priorityStatus == PriorityStatus.HOLDS_PRIORITY &&
        holdsBoltInHand(state, initiator) &&
        holdsBoltInHand(state, responder) &&
        untappedMountains(state, initiator) >= 1 &&
        untappedMountains(state, responder) >= 1

/**
 * Drives the P2.3 death-mid-stack duel to its game-over state: both players are ground down to
 * [BOLT_LETHAL_LIFE] with [GRIND_TO_BOLT_RANGE], then [initiator] casts Lightning Bolt at the
 * responder, the responder answers with a Bolt at the initiator, and everyone passes. LIFO
 * resolution (CR 608.1) makes the *responder's* Bolt resolve first, and the CR 704.5a
 * state-based action — checked before the next priority grant (CR 704.3) — ends the game with
 * the initiator's own Bolt still on the stack, unresolved.
 */
internal fun deathMidStackDuel(initiator: PlayerId): DeathMidStackOutcome {
    val responder = opponentOf(initiator)
    val config = burnConfig(deathDuelSeed, startingPlayer = alice)
    val game = ScriptedGame.start(config)
    var decisions = 0
    while (game.state.players.values
            .any { it.life > BOLT_LETHAL_LIFE }
    ) {
        check(!game.isOver) { "the life grind must stop short of ending the game" }
        check(decisions < GRIND_DECISION_CAP) { "the life grind did not converge within $GRIND_DECISION_CAP decisions" }
        game.respond(GRIND_TO_BOLT_RANGE)
        decisions += 1
    }
    game.passUntil { state -> duelReady(state, initiator, responder) }
    val eventsBeforeDuel = game.state.events.size
    val initiatorBoltId = castBoltAt(game, responder)
    // CR 117.3b: the initiator retains priority after casting; passing opens the responder's
    // window (CR 117.3d) — the response the duel is about.
    game.pass()
    val responderBoltId = castBoltAt(game, initiator)
    // The responder retains priority, passes; the initiator passes; all have passed in
    // succession (CR 117.4), so the top of the stack — the responder's Bolt — resolves.
    game.pass()
    game.pass()
    check(game.isOver) { "CR 704.5a: the LIFO-first resolution must end the game" }
    return DeathMidStackOutcome(game, config, initiatorBoltId, responderBoltId, eventsBeforeDuel)
}
