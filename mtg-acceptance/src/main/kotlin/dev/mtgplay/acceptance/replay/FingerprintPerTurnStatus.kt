package dev.mtgplay.acceptance.replay

import dev.mtgplay.acceptance.invariant.ZoneResidence

/*
 * The per-turn and doesn't-untap halves of an object's fingerprint line (`FW-TAPUNTAP`), split out of
 * Fingerprint.kt for the reason that file splits everything else: `appendResidence` had reached
 * detekt's cyclomatic budget and Fingerprint.kt its function budget. The split is mechanical — this is
 * the tail of one digest, not a second one — and it must stay in step with the residence line it
 * belongs to.
 */

/**
 * Appends the battlefield-only per-turn activation records and the doesn't-untap marker of [residence]
 * to this fingerprint line. Called from `appendResidence`, inside its battlefield-only branch.
 *
 * **CR 602.5b.** A source that has spent an "Activate only once each turn" allowance is a different
 * position from one that has not: the ability is not enumerated for the rest of the turn, so two states
 * differing only in the record differ in their action space (ADR-005) and must hash apart. Both records
 * are digested rather than one, because
 * [dev.mtgplay.core.state.GameObject.manaAbilitiesActivatedThisTurn] and
 * [dev.mtgplay.core.state.GameObject.activatedAbilitiesActivatedThisTurn] index different ability lists
 * on the same definition — index 0 names a different ability in each.
 *
 * **CR 502.2.** A permanent held down by a "doesn't untap during its controller's next untap step"
 * effect (Sleep of the Dead) is a different position from an identically tapped one that will untap
 * normally.
 *
 * Every token is **omitted at its default**, which is the value every object on an ordinary board
 * carries, so adding these three moved no existing fingerprint. The mana record was undigested before
 * this packet, which was a gap of the same shape rather than a deliberate exclusion.
 */
internal fun StringBuilder.appendPerTurnAndUntapStatus(residence: ZoneResidence) {
    val manaUsed = residence.obj.manaAbilitiesActivatedThisTurn
    if (manaUsed.isNotEmpty()) append(":manaUsed=").append(manaUsed.sorted().joinToString("+"))
    val abilitiesUsed = residence.obj.activatedAbilitiesActivatedThisTurn
    if (abilitiesUsed.isNotEmpty()) append(":abilUsed=").append(abilitiesUsed.sorted().joinToString("+"))
    if (residence.obj.skipsNextUntapStep) append(":noUntap")
}
