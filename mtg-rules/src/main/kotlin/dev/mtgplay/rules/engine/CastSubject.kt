package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.PendingCast

/**
 * The three things that together say *which cast* is being priced or bounded (CR 601.2): what card, by
 * what permission, from which object. Additive with `FW-X`.
 *
 * **A bundle rather than three parameters, because they are never meaningful apart.** [totalCost] is
 * the engine's one cost function and it has four call sites that must agree exactly — cast legality,
 * permission legality, request derivation, and execution — and every one of them passes these three
 * together. Splitting them across a parameter list made it possible, in principle, to price one card's
 * definition against another's permission; the type makes that unrepresentable, and it is what let
 * `FW-X` add two more inputs (the CR 601.2b announcements) without the signature becoming a hazard.
 *
 * The paying **seat** is deliberately *not* a member: a cost belongs to a cast, but who pays it is a
 * separate question the same three values are asked for by different callers.
 *
 * @property definition the card being cast.
 * @property permission the alternative permission this cast uses (CR 601.2f), or `null` for a normal
 *   cast at the printed cost from the hand.
 * @property castObjectId the object being cast, excluded from every zone count the cost reduction takes
 *   (CR 601.2a — the card has left its source zone by the time the cost is determined); `null` where the
 *   caller has none.
 */
internal data class CastSubject(
    val definition: SpellDefinition,
    val permission: CastingPermission? = null,
    val castObjectId: ObjectId? = null,
)

/**
 * The [CastSubject] an open [PendingCast] describes, given the [definition] its card resolves to. The
 * one conversion, so a gathering's price and its execution's price are built from the same three values
 * by construction rather than by two call sites agreeing.
 */
internal fun PendingCast.subject(definition: SpellDefinition): CastSubject =
    CastSubject(definition, castingPermission, cardObjectId)

/**
 * The CR 601.2b cost announcements settled while casting: whether the kicker is being paid
 * (CR 702.33a) and what value was announced for the variable symbol (CR 107.3b). Additive with `FW-X`.
 *
 * The pair travels together for the same reason [CastSubject]'s three do — every cost computation needs
 * both, and the *order* they are settled in is a contract (kicker first, because the affordable values
 * of X depend on the kicker answer). [NONE] is the "nothing announced" value every card without either
 * keyword uses, and it is what the legality gates price against, because it is always the cheapest
 * announcement available.
 */
internal data class CostAnnouncements(
    val kicked: Boolean = false,
    val chosenX: Int = 0,
) {
    init {
        require(chosenX >= 0) { "CR 601.2b: an announced value of X is non-negative, was $chosenX" }
    }

    companion object {
        /**
         * No kicker and X = 0 — the cheapest announcement any cast admits, and therefore the one cast
         * legality prices against (CR 601.2b: declining a kicker is always legal, and a larger X only
         * ever costs more).
         */
        val NONE: CostAnnouncements = CostAnnouncements()
    }
}

/** The [CostAnnouncements] an open [PendingCast] has settled so far; unannounced fields read as their
 * cheapest value, which is what the legality gates want and what the pipeline asserts against.
 */
internal fun PendingCast.announcements(): CostAnnouncements =
    CostAnnouncements(kicked = kicked ?: false, chosenX = chosenX ?: 0)
