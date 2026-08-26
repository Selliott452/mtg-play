package dev.mtgplay.core.definition

import dev.mtgplay.core.card.CardType

/**
 * An "each opponent sacrifices a [cardType] of their choice" clause (CR 701.17a) — Extract a Confession.
 * Card-definition data, additive and flagged core (`FW-NONCTRLDEC`, `W9-B`).
 *
 * A [ResolutionClauses] member for the reason every member here is one: it needs a mid-resolution pause
 * the [ResolutionEffect] signature cannot express (ADR-004). The **second** member whose pause belongs to
 * a player who is not the resolving object's controller, after [EachOpponentDiscards], and the second to
 * walk "each opponent" as a queue rather than treating a two-player pool as a special case.
 *
 * **It is not [EachOpponentDiscards] with a different verb**, and the differences are not cosmetic:
 *
 * - the option list is **public** (CR 400.2 — the battlefield), where a discard's is the deciding
 *   opponent's hidden hand. So the ADR-007 question that dominated `FW-NONCTRLDEC`'s design does not
 *   arise here at all; what remains is the ADR-005 half, that the *opponent* chooses and the engine must
 *   enumerate their choices;
 * - it is **narrowable by a cost paid a whole CR 601 stage earlier**, which nothing else in the engine
 *   is. See [narrowingWhenOptionalCostPaid].
 *
 * **"Of their choice" is load-bearing** (CR 701.17a): the sacrificing player picks, not the controller,
 * and not the engine. An opponent with no matching permanent is skipped rather than asked — an impossible
 * sacrifice simply does not happen — which needs no separate decision, exactly as an empty hand needs
 * none for a discard.
 *
 * @property cardType the kind of permanent each opponent sacrifices — Extract a Confession's creature
 *   (CR 205.2a).
 * @property narrowing how the choice is narrowed when the resolving object's optional additional cost was
 *   **not** paid — [SacrificeNarrowing.ANY] for a plain "a creature of their choice".
 * @property narrowingWhenOptionalCostPaid how it is narrowed when that cost **was** paid (CR 601.2b) —
 *   Extract a Confession's "instead each opponent sacrifices a creature with the greatest power among
 *   creatures they control".
 *
 *   **The pair is the printed "instead", encoded as a pair rather than as a flag**, because the card
 *   prints two whole clauses and picks between them; a boolean "narrow if paid" would say the same thing
 *   for this card and the wrong thing for the next one, which might narrow in the unpaid case or narrow
 *   differently in each. Defaulting to [narrowing] makes a card with no such cost carry one rule, which
 *   is what "a card with no linked cost is never narrowed" means in code.
 */
data class EachOpponentSacrifices(
    val cardType: CardType,
    val narrowing: SacrificeNarrowing = SacrificeNarrowing.ANY,
    val narrowingWhenOptionalCostPaid: SacrificeNarrowing = narrowing,
)

/**
 * How an [EachOpponentSacrifices] clause narrows the sacrificing player's choice (CR 701.17a). Additive,
 * flagged core (`W9-B`).
 *
 * **A narrowing filters the enumeration; it never collapses it** (ADR-005). [GREATEST_POWER] is the point
 * of the type: "a creature with the greatest power among creatures they control" is still a *choice*
 * whenever two creatures tie at the top, and a board of two 3/3s and a 1/1 offers the opponent two real
 * answers with different consequences (one may be enchanted, one may be a blocker they need). An engine
 * that picked for them would delete a line of play; an engine that offered the 1/1 would enumerate an
 * illegal one. Both are review-blocking, which is why this is an enumeration filter and not a resolution
 * rule.
 */
enum class SacrificeNarrowing {
    /** No narrowing: any matching permanent the player controls (CR 701.17a). */
    ANY,

    /**
     * Only a permanent with the greatest power among the matching permanents that player controls
     * (CR 701.17a). Power is the **effective** power at the moment of the choice (CR 613), not the
     * printed one, so a creature carrying a `+1/+1` counter or an Aura is compared as it actually is.
     */
    GREATEST_POWER,
}
