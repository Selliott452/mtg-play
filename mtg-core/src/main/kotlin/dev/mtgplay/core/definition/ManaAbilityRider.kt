package dev.mtgplay.core.definition

/**
 * A **non-mana** effect an intrinsic [ManaAbility] performs alongside adding its mana (CR 605.1a) —
 * Elves of Deep Shadow's "`{T}`: Add `{B}`. This creature deals 1 damage to you." Additive, flagged
 * core (`W8-B`).
 *
 * **The rider does not stop it being a mana ability, and that is the whole point.** CR 605.1a defines
 * an activated mana ability by three negatives and one positive: it does not require a target, it
 * *could* add mana to a player's mana pool as it resolves, and it is not a loyalty ability. Nothing
 * there says a mana ability may do *only* that. A rider that damages its own controller satisfies all
 * three, so the ability stays stackless (CR 605.3a), keeps resolving inside CR 601.2g in the middle of
 * another cost's payment, and is never a thing an opponent may respond to. Encoding Elves of Deep
 * Shadow as an ordinary [ActivatedAbility] to get the rider would be the silent wrongness this type
 * exists to prevent: it would put a mana ability on the stack, take it out of the payment planner
 * entirely, and delete the card's only line of play.
 *
 * **Declarative rather than a lambda**, for [ObjectPredicate]'s reason and one sharper one. The rider
 * rides on [dev.mtgplay.rules.decision.ProductionAlternative], which is a component of the
 * payment-equivalence key [dev.mtgplay.rules.decision.SourceClassKey]; a lambda has no structural
 * equality, so two identical Elves would stop being payment-equivalent and every enumerated plan over
 * them would double (ADR-005). It is also what lets an agent see, from the enumerated option alone,
 * that a plan costs it life.
 *
 * **Sealed and exhaustively matched in `mtg-rules`.** A rider shape the pool does not print breaks
 * compilation rather than being approximated — which matters because the shapes differ in whether
 * they can *fail*: this one cannot (life may legally go to 0, CR 704.5a follows at the next
 * state-based check), while a rider that discarded or sacrificed would need a decision the CR 601.2g
 * window has nowhere to pause for (docs/design/mana-payment.md §11.1).
 */
sealed interface ManaAbilityRider {
    /**
     * "This creature deals [amount] damage to you" (CR 120.1) — the source permanent deals the damage,
     * and "you" is the ability's activator, who is also the source's controller.
     *
     * Damage, not life loss, and the distinction is observable: it has a source (CR 120.1), so it can
     * be prevented (CR 615) and it can be stopped by protection (CR 702.16e). Routing it through the
     * published `dealDamage` primitive rather than a bare life subtraction is what keeps those true.
     *
     * **It never makes the ability unusable.** There is no life check before activating: a player at 1
     * life may tap Elves of Deep Shadow, go to 0, and lose to CR 704.5a at the next state-based-action
     * check (CR 704.3). Gating enumeration on life would remove a legal line, which ADR-005 makes a
     * defect rather than a kindness.
     *
     * @property amount the damage dealt (Elves of Deep Shadow's is 1); at least 1 — a zero-damage
     *   rider is no rider at all (CR 120.8) and would be declared by omitting it.
     */
    data class DamageToController(
        val amount: Int,
    ) : ManaAbilityRider {
        init {
            require(amount >= 1) { "CR 120.8: a damage rider deals at least 1; omit it otherwise, was $amount" }
        }
    }
}
