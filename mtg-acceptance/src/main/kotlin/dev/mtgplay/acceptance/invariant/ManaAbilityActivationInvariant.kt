package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.zone.ZoneId

/*
 * The [Invariant.MANA_ABILITY_ACTIVATION_SCOPE] check (CR 602.5b). The per-turn activation record is
 * the state `FW-MANACOST` added, and the checker's charter is that new state gets a machine-checked
 * well-formedness property in the same packet — otherwise a leaked record shows up as a mana source
 * that silently stops working, which is the quietest possible failure.
 *
 * Its own file rather than another function in InvariantChecker.kt for the reason the checker's header
 * gives: it takes the minimal data (the residence list plus the definitions) so corruption a real
 * `GameState` cannot express is still directly testable.
 */

/**
 * [Invariant.MANA_ABILITY_ACTIVATION_SCOPE]: every per-turn mana-ability activation record in
 * [residences] is well-formed. Two arms, each a distinct rule:
 *
 * 1. **Battlefield-only scope** (CR 400.7). A mana ability is activated from the battlefield, and the
 *    fresh object born of any zone move is a new object with no history. So a record on a card in a
 *    graveyard, a hand or exile means some zone move copied state it should have dropped — the same
 *    failure shape [Invariant.COUNTER_SCOPE]'s second arm catches for counters.
 * 2. **Every recorded index names a restricted printed ability.** The record indexes the card's
 *    *printed* mana abilities, and only an ability that prints "Activate only once each turn"
 *    (CR 602.5b) is ever recorded — the engine writes nothing for an unrestricted one, which is what
 *    keeps the field empty on ordinary boards and their replay fingerprints unchanged. An index past
 *    the end, or one naming an unrestricted ability, means the executor and the availability filter
 *    disagree about which ability was spent, and the source would silently stop producing.
 *
 * There is deliberately **no** "the record is empty outside a turn" arm: the reset happens as a turn
 * begins (CR 500.1), so a record is legitimately present at every pause within the turn that made it.
 */
internal fun checkManaAbilityActivationScope(
    residences: List<ZoneResidence>,
    definitions: Map<CardRef, CardDefinition>,
): List<Violation> =
    buildList {
        for (residence in residences) {
            val obj = residence.obj
            val recorded = obj.manaAbilitiesActivatedThisTurn
            if (recorded.isEmpty()) continue
            if (residence.zone != ZoneId.Battlefield) {
                add(
                    Violation(
                        Invariant.MANA_ABILITY_ACTIVATION_SCOPE,
                        "CR 400.7: object ${obj.id.value} carries the per-turn mana-ability record " +
                            "$recorded in ${residence.zone}, but a zone move makes a new object with no history",
                    ),
                )
            }
            val printed = definitions[obj.card]?.manaAbilities.orEmpty()
            for (index in recorded) {
                val ability = printed.getOrNull(index)
                if (ability == null || !ability.oncePerTurn) {
                    add(
                        Violation(
                            Invariant.MANA_ABILITY_ACTIVATION_SCOPE,
                            "CR 602.5b: object ${obj.id.value} (${obj.card.name}) records an activation of " +
                                "printed mana ability $index, which is ${if (ability == null) {
                                    "not printed on it"
                                } else {
                                    "not restricted to one activation each turn"
                                }}",
                        ),
                    )
                }
            }
        }
    }

/**
 * [Invariant.ACTIVATED_ABILITY_ACTIVATION_SCOPE]: every per-turn **non-mana** activated-ability record
 * in [residences] is well-formed. Additive (`FW-TAPUNTAP`) — Quirion Ranger's "Activate only once each
 * turn".
 *
 * The exact two arms [checkManaAbilityActivationScope] applies, over the sibling record and the sibling
 * ability list: the record is battlefield-only (CR 400.7), and every recorded index names a printed
 * ability that really does carry the CR 602.5b restriction. It is a separate check rather than a
 * parameterised one because the two records index *different* lists on the same definition, so a shared
 * body would have to be told which — and the moment it was told the wrong one, the failure would be a
 * silently unenforced restriction rather than a compile error.
 *
 * A record that leaks past its scope shows up as an ability that silently stops being activatable,
 * which is the quietest possible failure and exactly what the checker exists to catch.
 */
internal fun checkActivatedAbilityActivationScope(
    residences: List<ZoneResidence>,
    definitions: Map<CardRef, CardDefinition>,
): List<Violation> =
    buildList {
        for (residence in residences) {
            val obj = residence.obj
            val recorded = obj.activatedAbilitiesActivatedThisTurn
            if (recorded.isEmpty()) continue
            if (residence.zone != ZoneId.Battlefield) {
                add(
                    Violation(
                        Invariant.ACTIVATED_ABILITY_ACTIVATION_SCOPE,
                        "CR 400.7: object ${obj.id.value} carries the per-turn activated-ability record " +
                            "$recorded in ${residence.zone}, but a zone move makes a new object with no history",
                    ),
                )
            }
            val printed = definitions[obj.card]?.activatedAbilities.orEmpty()
            for (index in recorded) {
                val ability = printed.getOrNull(index)
                if (ability == null || !ability.oncePerTurn) {
                    add(
                        Violation(
                            Invariant.ACTIVATED_ABILITY_ACTIVATION_SCOPE,
                            "CR 602.5b: object ${obj.id.value} (${obj.card.name}) records an activation of " +
                                "printed activated ability $index, which is ${if (ability == null) {
                                    "not printed on it"
                                } else {
                                    "not restricted to one activation each turn"
                                }}",
                        ),
                    )
                }
            }
        }
    }

/**
 * [Invariant.SKIPS_NEXT_UNTAP_SCOPE]: the "doesn't untap during its controller's next untap step"
 * marker (CR 502.2) is carried only by battlefield permanents. Additive (`FW-TAPUNTAP`) — Sleep of the
 * Dead.
 *
 * One arm, and it is [checkManaAbilityActivationScope]'s first: tapped-ness is a battlefield-only
 * status (CR 110.5) and so is anything that qualifies it, and the fresh object born of any zone move is
 * a new object with no history (CR 400.7). A marker surviving a bounce would hold down a creature that
 * had, in rules terms, never been Slept.
 *
 * There is deliberately **no** "the marker is cleared by some turn" arm. It names its controller's
 * *next* untap step, however many turns away that is, so it is legitimately present at every pause in
 * between — the same reason its sibling has no cross-turn arm.
 */
internal fun checkSkipsNextUntapScope(residences: List<ZoneResidence>): List<Violation> =
    buildList {
        for (residence in residences) {
            val obj = residence.obj
            if (obj.skipsNextUntapStep && residence.zone != ZoneId.Battlefield) {
                add(
                    Violation(
                        Invariant.SKIPS_NEXT_UNTAP_SCOPE,
                        "CR 110.5: object ${obj.id.value} (${obj.card.name}) carries the doesn't-untap " +
                            "marker in ${residence.zone}, but tapped status is battlefield-only and a zone " +
                            "move makes a new object with no history",
                    ),
                )
            }
        }
    }
