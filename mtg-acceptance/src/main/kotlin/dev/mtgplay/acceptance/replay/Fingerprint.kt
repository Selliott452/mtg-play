package dev.mtgplay.acceptance.replay

import dev.mtgplay.acceptance.invariant.ZoneResidence
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
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
 * attachment — not their computed values: computed P/T and keywords are a pure function of state
 * the digest already covers, so two states with different continuous effects already differ in
 * which Auras are attached where and thus hash apart, without re-implementing layer logic here
 * (docs/design/layer-system.md §5).
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
        val cast = state.pendingCast
        append("|pendingCast=")
        if (cast == null) {
            append('-')
        } else {
            append(cast.caster.seat)
            append(':').append(cast.cardObjectId.value)
            append(':').append(cast.chosenTargets?.joinToString(",") { renderTarget(it) } ?: "-")
            // Cast-from-elsewhere (P5.2): the source zone, the permission (by cause), and the settled
            // additional-exile selection are all rules-relevant to how the pipeline will execute.
            append(':').append(cast.source.name)
            append(':').append(cast.castingPermission?.let { it::class.simpleName } ?: "-")
            append(':').append(cast.additionalExileCost?.joinToString("+") { it.value.toString() } ?: "-")
        }
        // The resolved-madness yes/no and the CR 616.1 replacement choice are pause points digested by
        // cause (which card, whose choice), not by any computed value.
        append("|pendingMadness=")
        append(state.pendingMadness?.let { "${it.owner.seat}:${it.exiledObjectId.value}" } ?: "-")
        append("|pendingReplacement=")
        append(state.pendingReplacement?.let { "${it.player.seat}:${it.objectId.value}" } ?: "-")
        state.players.entries
            .sortedBy { it.key.seat }
            .forEach { (seat, player) ->
                append("|seat=").append(seat.seat)
                append(",life=").append(player.life)
                append(",manaPool=").append(player.manaPool.joinToString("+") { it.name })
                append(",priority=").append(player.priorityStatus.name)
                append(",drewFromEmpty=").append(player.attemptedDrawFromEmptyLibrary)
                append(",answered=").append(player.decisionsAnswered)
            }
        ZoneResidence.of(state).forEach { appendResidence(it) }
        appendStackAndTriggers(state)
    }

// Digests one object's residence line: its zone, id, and printed card, plus its battlefield-only
// statuses (tapped, marked damage, summoning sickness, and the Aura-attachment cause, §5).
private fun StringBuilder.appendResidence(residence: ZoneResidence) {
    append("|@").append(residence.zone)
    append('=').append(residence.obj.id.value)
    append(':').append(residence.obj.card.name)
    if (residence.obj.tapped) append(":tapped")
    // Marked damage (CR 120.3d) and summoning sickness (CR 302.6) are rules-relevant only on the
    // battlefield; off it they are meaningless bookkeeping and left out.
    if (residence.zone == ZoneId.Battlefield) {
        if (residence.obj.damageMarked != 0) append(":dmg=").append(residence.obj.damageMarked)
        if (residence.obj.summoningSick) append(":sick")
        // The attachment *cause* (CR 303.4), not the computed continuous-effect values it implies:
        // two states differing in continuous effects necessarily differ in which Auras are attached
        // where, so they hash apart without re-implementing layer logic (docs/design/layer-system.md §5).
        residence.obj.attachedTo?.let { append(":att=").append(it.value) }
    }
    // The madness marker (CR 702.35a) is an exile-only status — a card waiting on its reflexive cast.
    if (residence.zone == ZoneId.Exile && residence.obj.awaitingMadness) append(":madness")
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
            }
            is StackEntry.Ability -> append("|ability=").append(renderTrigger(entry.trigger))
        }
    }
    state.pendingTriggers.forEach { append("|pending=").append(renderTrigger(it)) }
}

// A canonical descriptor of a fired triggered ability (CR 603.3): its source, controller, condition,
// and the trigger's linked information — never the resolution effect, which has reference identity
// only (ADR-009) and is excluded from the digest like every card definition.
private fun renderTrigger(trigger: PendingTrigger): String =
    buildString {
        append(trigger.sourceCard.name)
        append('@').append(trigger.sourceId.value)
        append(':').append(trigger.controller.seat)
        append(':').append(trigger.ability.condition::class.simpleName ?: "?")
        append(":amt=").append(trigger.amount)
        append(":subj=").append(trigger.subject?.value ?: "-")
    }

private fun renderTarget(target: Target): String =
    when (target) {
        is Target.Player -> "player${target.id.seat}"
        is Target.Permanent -> "permanent${target.id.value}"
    }

// A canonical descriptor of the in-progress combat (CR 506–511): attackers (id>defender), blocks
// (blocker>attacker), the blocked-attacker set (CR 509.1h), the per-attacker damage-assignment
// orders, the recorded trample assignments (CR 702.19), and the two damage-step flags.
private fun renderCombat(combat: CombatState): String =
    buildString {
        append("atk[")
        append(combat.attackers.joinToString(",") { "${it.attacker.value}>${it.defendingPlayer.seat}" })
        append("]blk[")
        append(combat.blocks?.joinToString(",") { "${it.blocker.value}>${it.attacker.value}" } ?: "-")
        append("]blocked[")
        append(combat.blockedAttackers.joinToString(",") { it.value.toString() })
        append("]ord[")
        append(
            combat.blockerOrder.entries.joinToString(";") { (attacker, order) ->
                "${attacker.value}:${order.joinToString(">") { it.value.toString() }}"
            },
        )
        append("]trample[")
        append(
            combat.trampleAssignments.entries.joinToString(";") { (attacker, toPlayer) ->
                "${attacker.value}:$toPlayer"
            },
        )
        append("]fs=").append(combat.firstStrikeDamageDealt)
        append(",rd=").append(combat.regularDamageDealt)
    }
