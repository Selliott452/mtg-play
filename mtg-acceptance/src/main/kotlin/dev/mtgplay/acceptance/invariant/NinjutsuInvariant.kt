package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.state.GameState

/**
 * [Invariant.NINJUTSU_COST_VALID]: an open ninjutsu gathering (CR 702.49a) names a cost its activator
 * could actually pay. Added with `FW-NINJUTSU`.
 *
 * `GameState`'s own construction check already covers what a core noun can see — the ninja is still in
 * the activator's hand, and the returned creature is still on the battlefield and still a declared
 * attacker. The property it *cannot* see is the one the whole framework turns on, because it is a rules
 * judgement rather than a shape: the returned creature must be **unblocked** (CR 509.1h) and the
 * activator must control it.
 *
 * Both halves fail silently rather than loudly if they ever break, which is why they are worth an
 * invariant. A gathering that named a *blocked* attacker would execute perfectly — the mana would be
 * paid, the creature would go back to hand, the ninja would arrive — and the only trace would be a
 * ninjutsu that was never legal, which no test asserting on the resulting board would notice. The same
 * goes for returning an attacker somebody else controls. Enumeration is supposed to make both
 * unreachable (ADR-005), so this is the backstop on that claim rather than a second implementation of it.
 *
 * "Blockers have been declared" is checked with them, because [dev.mtgplay.core.state.CombatState.blocks]
 * being `null` means no attacker is either blocked or unblocked yet, so *every* pairing would be illegal
 * in that window.
 */
internal fun checkNinjutsuCost(state: GameState): List<Violation> {
    val pending = state.pendingNinjutsu ?: return emptyList()
    val combat = state.turn.combat
    return buildList {
        if (combat == null) {
            add(
                Violation(
                    Invariant.NINJUTSU_COST_VALID,
                    "CR 702.49a: a ninjutsu gathering is open outside an engaged combat",
                ),
            )
            return@buildList
        }
        if (combat.blocks == null) {
            add(
                Violation(
                    Invariant.NINJUTSU_COST_VALID,
                    "CR 509.1h: a ninjutsu gathering is open before blockers were declared, when no " +
                        "attacker is unblocked and the ability cannot be activated at all",
                ),
            )
        }
        if (pending.returnedAttacker in combat.blockedAttackers) {
            add(
                Violation(
                    Invariant.NINJUTSU_COST_VALID,
                    "CR 702.49a: a ninjutsu gathering returns ${pending.returnedAttacker}, which is a " +
                        "*blocked* attacker; the cost returns an unblocked one",
                ),
            )
        }
        val controlled =
            state.sharedZones.battlefield.any {
                it.id == pending.returnedAttacker && it.owner == pending.activator
            }
        if (!controlled) {
            add(
                Violation(
                    Invariant.NINJUTSU_COST_VALID,
                    "CR 702.49a: a ninjutsu gathering returns ${pending.returnedAttacker}, which " +
                        "${pending.activator} does not control",
                ),
            )
        }
    }
}
