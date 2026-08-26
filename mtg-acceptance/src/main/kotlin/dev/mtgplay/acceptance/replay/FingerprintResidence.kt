package dev.mtgplay.acceptance.replay

import dev.mtgplay.acceptance.invariant.ZoneResidence
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.zone.ZoneId
import kotlinx.collections.immutable.PersistentMap

/*
 * The per-object residence line of the fingerprint — one object's zone, identity, and the zone-scoped
 * statuses that make two otherwise identical boards different positions. Split out of Fingerprint.kt by
 * `W10-C` for the reason FingerprintPerTurnStatus.kt was split out before it: adding the third counter
 * kind pushed `appendResidence` past detekt's cyclomatic budget, and pulling the counter loop out of it
 * then pushed Fingerprint.kt past its function budget. Split rather than suppressed, twice, along the
 * seam the digest already had — Fingerprint.kt owns the *state-wide* digest (seats, pending positions,
 * the stack) and this owns the *per-object* one.
 *
 * The two halves must stay in step: a status that changes what a position is and is digested by neither
 * makes two different positions hash the same, which is the one failure a replay fingerprint cannot
 * survive.
 */

// Digests one object's residence line: its zone, id, and printed card, plus its battlefield-only
// statuses (tapped, marked damage and its deathtouch record, summoning sickness, the Aura-attachment
// cause, and — through [appendCounters] — its counters, §5) and its exile-only ones.
internal fun StringBuilder.appendResidence(residence: ZoneResidence) {
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
        appendCounters(residence.obj.counters)
        appendPerTurnAndUntapStatus(residence)
    }
    if (residence.zone == ZoneId.Exile) {
        // The madness marker (CR 702.35a) is an exile-only status — a card waiting on its reflexive cast.
        if (residence.obj.awaitingMadness) append(":madness")
        // The plotted-turn marker (CR 702.140) is an exile-only status gating the free cast.
        residence.obj.plottedTurn?.let { append(":plotted=").append(it) }
    }
}

// Digests a permanent's counter multiset (CR 122.1), battlefield-only state that changes what the
// permanent *is* — its power and toughness (CR 613.4c), its keywords (CR 122.1b), and, for an inert
// counter, whatever a static ability counting it turns on (a Spacecraft is a creature at seven). Two
// positions differing only in counters must therefore hash apart. Digested as the *cause* (the
// multiset), not the computed P/T it implies, for the same reason the Aura attachment is
// (docs/design/layer-system.md §5). Iterated in the map's own deterministic order.
//
// Split out of [appendResidence] when `W10-C` added the third counter kind and pushed that function
// past detekt's cyclomatic budget — split rather than suppressed, and along the seam the digest already
// had: the residence line owns the object's *statuses*, this owns its counters, and the `when` stays
// exhaustive so a fourth counter kind still fails to compile until it is answered.
private fun StringBuilder.appendCounters(counters: PersistentMap<Counter, Int>) {
    for ((kind, count) in counters) {
        val tag =
            when (kind) {
                is Counter.PowerToughness -> "%+d/%+d".format(kind.power, kind.toughness)
                is Counter.KeywordCounter -> kind.keyword.name
                Counter.Charge -> "charge"
            }
        append(":ctr=").append(tag).append('x').append(count)
    }
}
