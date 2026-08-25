package dev.mtgplay.acceptance.replay

import dev.mtgplay.acceptance.invariant.ZoneResidence
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.zone.ZoneId
import java.security.MessageDigest

/**
 * A deterministic, order-stable digest of the rules-relevant content of a [GameState] (ADR-006).
 *
 * Two states with equal fingerprints are equal in everything the rules care about; this is the
 * "final state hash" a replay asserts against (PLAN.md §2.2). The digest covers zones (each
 * object's id, printed card, tapped status, and — on the battlefield — marked damage, summoning
 * sickness, and Aura attachment, in zone order), the stack entries (a spell's cast record — controller
 * and targets, CR 601.2 — and a triggered ability's whole content, CR 113.7a/P5.1), the fired-but-
 * unplaced triggers waiting to be put on the stack (CR 603.3b), life totals, mana pools, priority
 * standing, the empty-draw flag, answered-decision counts, any cast gathering decisions, the turn
 * position and its land-drop count (CR 305.2), the combat state (CR 506–511) when in combat, the
 * object-id counter, and the PRNG state. A token is digested as the ordinary battlefield object it is;
 * it is not a card, but its id and name appear in the residence line like any other object.
 *
 * Continuous-effect (CR 613) characteristics are digested by their **cause** — an object's
 * attachment, and a running timed effect's own record — not their computed values: computed P/T and
 * keywords are a pure function of state the digest already covers, so two states with different
 * continuous effects already differ in which Auras are attached where, or in what the
 * [dev.mtgplay.core.state.GameState.timedEffects] store holds, and thus hash apart without
 * re-implementing layer logic here (docs/design/layer-system.md §5, docs/design/duration.md §7).
 *
 * The [event log][GameState.events] is deliberately excluded: events are derived observability
 * (ADR-006), so they are fingerprinted separately and compared on their own, keeping "the game
 * reached the same state" and "the game narrated the same story" as two independent assertions.
 * The definition registry is likewise excluded: definitions are static match configuration, not
 * game progress.
 *
 * @property value the hex-encoded SHA-256 of the canonical state descriptor.
 */
@JvmInline
value class Fingerprint(
    val value: String,
)

/**
 * Computes the [Fingerprint] of [state] — the digest of its rules-relevant content, excluding the
 * event log. Deterministic: equal states always produce equal fingerprints, and the canonical
 * ordering (ascending seat order, then shared zones) means the digest never depends on incidental
 * map or iteration order.
 */
fun fingerprint(state: GameState): Fingerprint {
    val bytes = canonicalDescriptor(state).toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return Fingerprint(digest.joinToString("") { byte -> "%02x".format(byte) })
}

/**
 * The canonical, human-readable pre-image the [fingerprint] hashes. Exposed for debugging and for
 * tests that want to compare descriptors directly rather than opaque hashes. Field labels and
 * per-object `id=card` encoding keep the layout unambiguous and stable across equal states.
 */
internal fun canonicalDescriptor(state: GameState): String =
    buildString {
        val turn = state.turn
        append("turn=").append(turn.activePlayer.seat)
        append('/').append(turn.number)
        append('/').append(turn.phase.name)
        append('/').append(turn.step?.name ?: "-")
        // CR 305.2: the land-drop count is rules-relevant — it gates the play-land action.
        append("|landsPlayed=").append(turn.landsPlayedThisTurn)
        // CR 506–511: combat progress is rules-relevant while in the combat phase; absent otherwise.
        append("|combat=").append(turn.combat?.let(::renderCombat) ?: "-")
        append("|nextObjectId=").append(state.nextObjectId)
        append("|rng=").append(state.rng.state)
        appendPendingPositions(state)
        appendSeats(state)
        ZoneResidence.of(state).forEach { appendResidence(it) }
        appendStackAndTriggers(state)
        appendTimedEffects(state)
    }

// Digests every gathering/pause position by cause (which card, whose choice), never by a computed value.
private fun StringBuilder.appendPendingPositions(state: GameState) {
    appendPendingCast(state.pendingCast)
    // The resolved-madness yes/no and the CR 616.1 replacement choice are pause points by cause.
    append("|pendingMadness=")
    append(state.pendingMadness?.let { "${it.owner.seat}:${it.exiledObjectId.value}" } ?: "-")
    append("|pendingReplacement=")
    append(state.pendingReplacement?.let { "${it.player.seat}:${it.objectId.value}" } ?: "-")
    // The plot special action's payment pause (P6.2a): whose action, which card.
    append("|pendingPlot=")
    append(state.pendingPlot?.let { "${it.caster.seat}:${it.cardObjectId.value}" } ?: "-")
    // CR 702.49a: a ninjutsu activation's payment pause (`FW-NINJUTSU`) — whose activation, which ninja,
    // and which attacker its cost will return. The attacker is load-bearing rather than decorative: it
    // decides which creature survives and, via CR 702.49d, which player the ninja enters attacking, so two
    // otherwise identical pauses that name different attackers are genuinely different positions.
    append("|pendingNinjutsu=")
    append(
        state.pendingNinjutsu?.let {
            "${it.activator.seat}:${it.ninjaObjectId.value}:${it.returnedAttacker.value}"
        } ?: "-",
    )
    // CR 601.3b: the bare optional-draw clause's yes/no pause (`FW-OPTDRAW`) — whose choice, how many.
    append("|pendingOptDraw=")
    append(state.pendingOptionalDraw?.let { "${it.decider.seat}:${it.drawCount}" } ?: "-")
    appendP62aPendingPositions(state)
    appendP62cPendingPositions(state)
    // The pre-game mulligan phase (CR 103.4/103.5): whose decision, count, and stage.
    append("|pendingMulligan=").append(renderPendingMulligan(state))
}

// The P6.2c mid-resolution pauses (Highway Robbery, Faithless Looting, Ash Barrens search), each digested by
// cause (whose choice, and the chosen mode where one has been picked).
private fun StringBuilder.appendP62cPendingPositions(state: GameState) {
    append("|pendingCostDraw=")
    append(
        state.pendingOptionalCostDraw?.let {
            "${it.decider.seat}:${it.chosenMode?.let { mode -> mode::class.simpleName } ?: "-"}"
        } ?: "-",
    )
    append("|pendingResDiscard=")
    append(state.pendingResolutionDiscard?.let { "${it.decider.seat}:${it.count}" } ?: "-")
    append("|pendingLibrarySearch=")
    append(state.pendingLibrarySearch?.let { "${it.decider.seat}" } ?: "-")
    // CR 609.4: an untargeted mid-resolution permanent selection (Snap, Azorius Chancery) — whose
    // choice, which action, and the already-clamped bounds. The options are re-derived from the
    // resolving object's own clause, which the stack digest already covers.
    append("|pendingPermSel=")
    append(
        state.pendingPermanentSelection?.let {
            "${it.decider.seat}:${it.action.name}:${it.minimum}..${it.maximum}"
        } ?: "-",
    )
    // CR 701.14a/701.17a: a private look — whose, over which objects, and which of its two stages. The
    // object ids are the position; the fingerprint is engine-internal and never a per-seat channel, so
    // digesting them here is not the disclosure the seat view withholds.
    append("|pendingLibraryLook=")
    append(
        state.pendingLibraryLook?.let {
            "${it.decider.seat}:${it.poolIds.joinToString("+") { id -> id.value.toString() }}:${it.awaitingShuffle}"
        } ?: "-",
    )
    appendFrameworkPendingPositions(state)
}

// The framework pauses added after P6.2c: a triggered ability's CR 603.3d target choice (`FW-ABILTGT`)
// and a resolving counter's CR 118.3a unless-pay (`FW-COUNTER`). Split out of
// [appendP62cPendingPositions] to keep that function inside detekt's complexity budget.
private fun StringBuilder.appendFrameworkPendingPositions(state: GameState) {
    // CR 603.3d: a triggered ability choosing its targets as it is put on the stack — whose choice, and
    // for which source. Which trigger is being placed is the front of that controller's pending group.
    append("|pendingTrigTargets=")
    append(
        state.pendingTriggerTargets?.let {
            "${it.controller.seat}:${it.sourceCard.name}@${it.sourceId.value}"
        } ?: "-",
    )
    // CR 118.3a: a resolving counter's unless-pay pause — who is being asked, for how much, and which
    // spell hangs on the answer. The countered spell's id is its stack residence (CR 400.7), so this
    // token distinguishes two counters aimed at two copies of the same card.
    append("|pendingCounterPay=")
    append(
        state.pendingCounterPayment?.let {
            "${it.decider.seat}:${it.cost.render()}:${it.counteredObjectId.value}"
        } ?: "-",
    )
}

// The P6.2a mid-resolution / gathering pauses, each digested by cause (whose choice, which object).
private fun StringBuilder.appendP62aPendingPositions(state: GameState) {
    append("|pendingColour=").append(state.pendingColorChoice?.let { it.decider.seat.toString() } ?: "-")
    append("|pendingActivation=")
    append(
        state.pendingActivation?.let {
            "${it.activator.seat}:${it.sourceObjectId.value}:${it.abilityIndex}:" +
                (it.chosenDiscard?.joinToString("+") { id -> id.value.toString() } ?: "-") + ":" +
                (it.chosenTargets?.joinToString("+") { target -> renderTarget(target) } ?: "-") + ":" +
                // CR 602.1: the chosen-object cost components decide what the activation will do to the
                // board and what its payment plan may tap, so two gatherings differing only in them are
                // different positions. Added by `FW-TAPUNTAP`, which also picked up the sacrifice half.
                (it.chosenSacrifice?.joinToString("+") { id -> id.value.toString() } ?: "-") + ":" +
                (it.chosenReturn?.joinToString("+") { id -> id.value.toString() } ?: "-")
        } ?: "-",
    )
    append("|pendingOptDiscard=")
    append(
        state.pendingOptionalDiscardDraw?.let { "${it.decider.seat}:${it.drawCount}:${it.awaitingDiscard}" } ?: "-",
    )
    append("|pendingReveal=")
    append(
        state.pendingRevealSelection?.let {
            "${it.decider.seat}:${it.revealedIds.joinToString("+") { id -> id.value.toString() }}" +
                ":${it.keptIds.joinToString("+") { id -> id.value.toString() }}"
        } ?: "-",
    )
}

// Digests the in-progress cast's gathered-so-far choices (CR 601.2), which govern how the pipeline runs.
private fun StringBuilder.appendPendingCast(cast: dev.mtgplay.core.state.PendingCast?) {
    append("|pendingCast=")
    if (cast == null) {
        append('-')
        return
    }
    append(cast.caster.seat)
    append(':').append(cast.cardObjectId.value)
    // CR 601.2b (`FW-MODAL`): the chosen mode decides both the spell's targeting line and its
    // resolution, so two paused casts differing only in their mode are genuinely different states and
    // must not digest alike. Digested before the targets, the order they are settled in.
    append(':').append(cast.chosenModes?.joinToString("+") { it.toString() } ?: "-")
    append(':').append(cast.chosenTargets?.joinToString(",") { renderTarget(it) } ?: "-")
    // Cast-from-elsewhere (P5.2) and the P6.2a cost selections all shape how the pipeline executes.
    append(':').append(cast.source.name)
    append(':').append(cast.castingPermission?.let { it::class.simpleName } ?: "-")
    append(':').append(cast.additionalExileCost?.joinToString("+") { it.value.toString() } ?: "-")
    append(':').append(cast.sacrificeCost?.joinToString("+") { it.value.toString() } ?: "-")
    append(':').append(cast.tapCost?.joinToString("+") { it.value.toString() } ?: "-")
    append(':').append(cast.optionalCostTaken?.toString() ?: "-")
    append(':').append(cast.optionalCostObjects?.joinToString("+") { it.value.toString() } ?: "-")
    append(':').append(cast.additionalDiscard?.joinToString("+") { it.value.toString() } ?: "-")
}

// Digests each seat's rules-relevant scalars in ascending seat order.
private fun StringBuilder.appendSeats(state: GameState) {
    state.players.entries
        .sortedBy { it.key.seat }
        .forEach { (seat, player) ->
            append("|seat=").append(seat.seat)
            append(",life=").append(player.life)
            append(",manaPool=").append(player.manaPool.joinToString("+") { it.name })
            append(",priority=").append(player.priorityStatus.name)
            append(",drewFromEmpty=").append(player.attemptedDrawFromEmptyLibrary)
            append(",answered=").append(player.decisionsAnswered)
            // CR 603.2: the per-turn draw count gates a per-turn draw trigger (Sneaky Snacker).
            append(",drawsThisTurn=").append(player.drawsThisTurn)
        }
}

// Digests one object's residence line: its zone, id, and printed card, plus its battlefield-only
// statuses (tapped, marked damage and its deathtouch record, summoning sickness, the Aura-attachment
// cause, and counters, §5) and its exile-only ones.
private fun StringBuilder.appendResidence(residence: ZoneResidence) {
    append("|@").append(residence.zone)
    append('=').append(residence.obj.id.value)
    append(':').append(residence.obj.card.name)
    if (residence.obj.tapped) append(":tapped")
    // Marked damage (CR 120.3d) and summoning sickness (CR 302.6) are rules-relevant only on the
    // battlefield; off it they are meaningless bookkeeping and left out.
    if (residence.zone == ZoneId.Battlefield) {
        // CR 704.5h: *which source* dealt the damage is a cause the amount cannot carry, and it decides
        // whether the creature is destroyed at the next check — two positions differing only in whether
        // a point of damage came from a deathtoucher are genuinely different positions. It is appended
        // inside this branch because the two always travel together: GameObject's own construction
        // guarantee is that the record never exists without the damage it describes.
        if (residence.obj.damageMarked != 0) {
            append(":dmg=").append(residence.obj.damageMarked)
            if (residence.obj.dealtDeathtouchDamage) append(":deathtouched")
        }
        if (residence.obj.summoningSick) append(":sick")
        // The attachment *cause* (CR 303.4), not the computed continuous-effect values it implies:
        // two states differing in continuous effects necessarily differ in which Auras are attached
        // where, so they hash apart without re-implementing layer logic (docs/design/layer-system.md §5).
        residence.obj.attachedTo?.let { append(":att=").append(it.value) }
        // The as-enters chosen colour (CR 614.12) is rules-relevant — it fixes a triggered mana ability's
        // output (Utopia Sprawl).
        residence.obj.chosenColor?.let { append(":colour=").append(it.name) }
        // Counters (CR 122.1) are battlefield-only state that changes what the permanent *is* — its
        // power and toughness (CR 613.4c) and its keywords (CR 122.1b) — so two positions differing
        // only in counters must hash apart. Digested as the *cause* (the multiset), not the computed
        // P/T it implies, for the same reason the Aura attachment is
        // (docs/design/layer-system.md §5). Iterated in the map's own deterministic order.
        for ((kind, count) in residence.obj.counters) {
            val tag =
                when (kind) {
                    is Counter.PowerToughness -> "%+d/%+d".format(kind.power, kind.toughness)
                    is Counter.KeywordCounter -> kind.keyword.name
                }
            append(":ctr=").append(tag).append('x').append(count)
        }
        appendPerTurnAndUntapStatus(residence)
    }
    if (residence.zone == ZoneId.Exile) {
        // The madness marker (CR 702.35a) is an exile-only status — a card waiting on its reflexive cast.
        if (residence.obj.awaitingMadness) append(":madness")
        // The plotted-turn marker (CR 702.140) is an exile-only status gating the free cast.
        residence.obj.plottedTurn?.let { append(":plotted=").append(it) }
    }
}

// Digests the stack entries and the fired-but-unplaced triggers (CR 405.2, CR 603.3b): a spell's card
// object is already covered by the residences, so only its cast record is added here; a triggered
// ability has no card residence, so its whole content (and each pending trigger's) is digested here.
private fun StringBuilder.appendStackAndTriggers(state: GameState) {
    state.sharedZones.stack.forEach { entry ->
        when (entry) {
            is StackEntry.Spell -> {
                append("|spell=").append(entry.obj.id.value)
                append(":controller=").append(entry.controller.seat)
                append(":targets=").append(entry.targets.joinToString(",") { renderTarget(it) })
                // The permission a spell was cast via (P5.2) governs how it leaves the stack (flashback's
                // exile-instead), so it is part of the cast record the fingerprint covers.
                append(":via=").append(entry.castVia?.let { it::class.simpleName } ?: "-")
                // The additional-discard linked information (P6.2a) the resolution reads (Grab the Prize).
                append(":disc=").append(entry.discardedForCost.joinToString("+") { it.name })
            }
            // CR 603.3d / CR 602.2b: an ability's chosen targets are part of its stack record, and the
            // CR 608.2b re-check reads them, so they are load-bearing for replay.
            is StackEntry.Ability ->
                append("|ability=")
                    .append(renderTrigger(entry.trigger))
                    .append(":targets=")
                    .append(entry.targets.joinToString(",") { renderTarget(it) })
            is StackEntry.ActivatedAbilityOnStack ->
                append("|activated=")
                    .append(entry.sourceCard.name)
                    .append('@')
                    .append(entry.sourceId.value)
                    .append(':')
                    .append(entry.controller.seat)
                    .append(":targets=")
                    .append(entry.targets.joinToString(",") { renderTarget(it) })
        }
    }
    state.pendingTriggers.forEach { append("|pending=").append(renderTrigger(it)) }
}
