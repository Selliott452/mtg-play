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
