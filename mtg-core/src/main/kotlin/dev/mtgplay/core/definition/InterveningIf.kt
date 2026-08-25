package dev.mtgplay.core.definition

/**
 * The "intervening if" clause of a triggered ability (CR 603.4) — the condition in "When this creature
 * enters, **if it was kicked**, …". Additive, flagged core (`FW-OPTCOST`).
 *
 * **CR 603.4 is a two-check rule, and that is the whole reason this is a declaration rather than an
 * `if` inside the effect.** "A triggered ability with an intervening 'if' clause … doesn't trigger at
 * all unless the clause's condition is true. If the ability triggered, the clause is checked again as
 * the ability resolves; if it's not true at that time, the ability is removed from the stack and does
 * nothing." Putting the test in the [ResolutionEffect] implements only the second check — the ability
 * still goes on the stack, still gets ordered against other triggers, and can still be responded to,
 * none of which should have happened. [TriggeredAbility]'s own KDoc recorded that gap; this closes it
 * for the one shape the pool prints.
 *
 * For a "was it kicked" condition the two checks can never disagree — kicked-ness is fixed when the
 * spell is cast and the permanent that resulted cannot un-kick — so the observable difference is
 * entirely in the *first* check: an unkicked Goblin Bushwhacker puts **no** ability on the stack, so no
 * trigger is ordered, no priority round opens for it, and the enumerated action space is smaller by
 * exactly the responses that ability would have invited. Implementing only the resolution half would
 * have been invisible in the final board state and wrong in the action space, which is the ADR-005
 * defect this engine cares most about.
 *
 * Sealed for the reason [CastCondition] is: a card printing a condition the engine does not implement
 * Sealed, for the reason [CastCondition] is: a card printing a condition the engine does not implement
 * must break the rules-side `when` at compile time rather than defaulting to true and firing a trigger
 * the rules forbid.
 */
sealed interface InterveningIf {
    /**
     * "…if it was kicked" (CR 702.33f, CR 603.4) — Goblin Bushwhacker. True exactly when the permanent
     * this ability's source is entered the battlefield from a spell whose kicker cost was paid
     * ([dev.mtgplay.core.state.GameObject.kickedWhenCast]).
     *
     * **"It" is the permanent, not the spell, and the linked information is what bridges them.** The
     * spell and the permanent it becomes are different objects (CR 400.7), so nothing about the cast
     * survives the zone change on its own; CR 702.33f is the rule that makes "was it kicked" readable
     * afterwards, and the engine implements it by carrying the flag onto the entering permanent.
     */
    data object SourceWasKicked : InterveningIf

    /**
     * "…if you control **another** creature named [name]" (CR 603.4, CR 201.2) — Faerie Miscreant's
     * *"When this creature enters, if you control another creature named Faerie Miscreant, draw a
     * card."* Additive, flagged core (`W8-E`).
     *
     * **The first condition whose two CR 603.4 checks can genuinely disagree**, which is what makes the
     * clause worth declaring rather than folding into the effect. [SourceWasKicked] reads a fact fixed
     * at the moment the permanent entered and frozen thereafter, so its resolution check is a formality;
     * this one reads the *live* battlefield. Play a second Miscreant while the first one's trigger is on
     * the stack and it starts holding; kill the other Miscreant in response and it stops. Both are
     * ordinary lines against a two-Miscreant board, and only a declared clause gets them right in both
     * directions — an `if` inside the [ResolutionEffect] would implement the resolution check alone and
     * would put an ability on the stack that CR 603.4 says never triggered.
     *
     * **"Another" is by object, not by name** (CR 109.1): the exclusion is the ability's own source
     * object, so a second copy of the same card counts and the source never counts itself. Once the
     * source has left the battlefield there is nothing left to exclude, and every creature you control
     * with the name is "another" one — which is the CR-correct reading and is why the check is written
     * as an id exclusion rather than as "at least two".
     *
     * **A name comparison** (CR 201.2), the pool's first: names are compared as printed text, and the
     * engine reads the *printed* name of each battlefield permanent's card. No effect in the gauntlet
     * changes a permanent's name, so printed and current names coincide; the day one does, this is the
     * site that must start reading a computed name.
     *
     * @property name the printed card name the condition counts (CR 201.2).
     */
    data class YouControlAnotherCreatureNamed(
        val name: String,
    ) : InterveningIf {
        init {
            require(name.isNotBlank()) { "CR 201.2: a name condition names a card, was blank" }
        }
    }

    /**
     * "…if its evoke cost was paid" (CR 702.74a, CR 603.4) — Mulldrifter's self-sacrifice trigger. True
     * exactly when the permanent this ability's source is entered the battlefield from a spell cast via
     * [CastingPermission.Evoke] ([dev.mtgplay.core.state.GameObject.evokedWhenCast]). Additive
     * (`W8-D`).
     *
     * [SourceWasKicked]'s twin, down to the linked-information bridge, and worth stating separately
     * rather than generalising the two into a "cast with a permission" condition: they answer questions
     * about different keywords with different consequences, and a card printing both would need to tell
     * them apart. What they share is the *shape* — a fact about the spell, read off the permanent — and
     * that shape is now witnessed twice, which is when it stops being a special case.
     *
     * Like the kicker condition the two CR 603.4 checks can never disagree: evoked-ness is fixed as the
     * permanent enters and nothing can change it. So the whole observable effect is again in the
     * **firing** check — a hard-cast Mulldrifter puts *no* sacrifice ability on the stack, so nothing is
     * ordered against its draw trigger and no priority round opens for it.
     */
    data object SourceWasEvoked : InterveningIf
}
