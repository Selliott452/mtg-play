package dev.mtgplay.core.definition

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import kotlinx.collections.immutable.PersistentList

/**
 * A declarative test over one game object's **printed** characteristics (CR 109.3), plus the zone
 * scope a count of such objects is taken in. Additive, flagged core (`FW-COST`,
 * docs/design/cost-modification.md §6).
 *
 * **Declarative rather than a lambda, deliberately** (design note §6, open question 2). A
 * `fun interface` would be the shorter shape and [Magnitude.Dynamic] is the precedent for one, but a
 * lambda has no structural equality, and this engine leans on structural equality of
 * definition-derived values in two places that would fail silently: the payment-equivalence key
 * [dev.mtgplay.rules.decision.SourceClassKey] decides whether two mana sources collapse into one
 * enumerated option (ADR-005), and the acceptance-module replay fingerprint digests
 * definition-derived state (ADR-006). A data-class predicate is also serialisable, comparable, and
 * renderable in a CLI menu; a lambda is none of those.
 *
 * **Printed characteristics, not layered ones — a named seam.** Every evaluation reads
 * `state.definitions[obj.card].characteristics`, which is the printed type line, *not* the in-game
 * one the CR 613 layer system would compute. That is correct today only because the engine has no
 * layer-4 (type-changing) effect at all and
 * [dev.mtgplay.rules.LayeredCharacteristics] does not even carry card types; it matches the
 * identical argument the enchant-restriction and sacrifice-cost readers already make
 * (docs/design/layer-system.md §6). **When the first type-changing effect arrives, evaluation must
 * route through the layer engine** — this KDoc and `mtg-rules`' `countMatching` are the one place
 * to change.
 */
sealed interface ObjectPredicate {
    /** Matches every object; the identity of [And]. */
    data object Anything : ObjectPredicate

    /** Matches an object whose printed card types include [cardType] (CR 205.2). */
    data class HasCardType(
        val cardType: CardType,
    ) : ObjectPredicate

    /** Matches an object whose printed subtypes include [subtype] (CR 205.3). */
    data class HasSubtype(
        val subtype: Subtype,
    ) : ObjectPredicate

    /** Matches exactly the objects [predicate] does not — "a **non**-Human creature". */
    data class Not(
        val predicate: ObjectPredicate,
    ) : ObjectPredicate

    /** Matches an object every member of [predicates] matches; an empty list matches everything. */
    data class And(
        val predicates: PersistentList<ObjectPredicate>,
    ) : ObjectPredicate

    /**
     * Matches an object **any** member of [predicates] matches; an empty list matches nothing.
     *
     * The member that reads a card's "instant and sorcery card" as the disjunction it is: no card is
     * simultaneously an instant and a sorcery (CR 205.2a), so the English "and" enumerates two accepted
     * types rather than demanding both, and encoding it as [And] would make the count permanently zero.
     */
    data class AnyOf(
        val predicates: PersistentList<ObjectPredicate>,
    ) : ObjectPredicate
}

/**
 * The zone a count of matching objects is taken over, scoped to the player asking (CR 400.1). Only
 * the two zones the cost-modification pool reads are members: a scope nothing counts is a scope
 * nothing can get wrong.
 *
 * Control is ownership throughout the MVP pool — no layer-2 control-changing effect exists — so
 * "you control" and "yours" are the same test (`obj.owner == seat`).
 */
enum class CountScope {
    /** The battlefield permanents the counting player controls (CR 403). */
    BATTLEFIELD_YOU_CONTROL,

    /** The cards in the counting player's graveyard (CR 404). */
    YOUR_GRAVEYARD,
}
