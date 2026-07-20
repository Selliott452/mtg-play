package dev.mtgplay.acceptance.replay

import dev.mtgplay.acceptance.invariant.ZoneResidence
import dev.mtgplay.core.state.GameState
import java.security.MessageDigest

/**
 * A deterministic, order-stable digest of the rules-relevant content of a [GameState] (ADR-006).
 *
 * Two states with equal fingerprints are equal in everything the rules care about; this is the
 * "final state hash" a replay asserts against (PLAN.md §2.2). The digest covers zones (each
 * object's id and printed card, in zone order), life totals, priority standing, the empty-draw
 * flag, answered-decision counts, the turn position, the object-id counter, and the PRNG state.
 *
 * The [event log][GameState.events] is deliberately excluded: events are derived observability
 * (ADR-006), so they are fingerprinted separately and compared on their own, keeping "the game
 * reached the same state" and "the game narrated the same story" as two independent assertions.
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
        append("|nextObjectId=").append(state.nextObjectId)
        append("|rng=").append(state.rng.state)
        state.players.entries
            .sortedBy { it.key.seat }
            .forEach { (seat, player) ->
                append("|seat=").append(seat.seat)
                append(",life=").append(player.life)
                append(",priority=").append(player.priorityStatus.name)
                append(",drewFromEmpty=").append(player.attemptedDrawFromEmptyLibrary)
                append(",answered=").append(player.decisionsAnswered)
            }
        ZoneResidence.of(state).forEach { residence ->
            append("|@").append(residence.zone)
            append('=').append(residence.obj.id.value)
            append(':').append(residence.obj.card.name)
        }
    }
