package dev.mtgplay.core.definition

/**
 * How many targets one instance of the word "target" demands (CR 115.1, CR 601.2c) — the *cardinality*
 * half of a targeting line, separated from [TargetSpec]'s *noun* half. Additive, flagged core
 * (`FW-MULTITGT`, docs/design/multi-target.md §2).
 *
 * Magic prints exactly two shapes and this hierarchy has exactly two members: a fixed demand ("target
 * creature", "two target creatures") and a bounded optional one ("up to two target cards", "up to one
 * target creature"). Both collapse to the pair [minimum]/[maximum], which is what every rules-side
 * consumer actually reads — but they are kept as distinct members rather than as a raw `IntRange`
 * because the two carry different *rules* consequences that a range would flatten:
 *
 * - [minimum] is the CR 601.2c castability gate. A spell whose targets cannot all be chosen cannot be
 *   cast at all, so "two target creatures" is simply not an option with one creature on the
 *   battlefield, while "up to two target cards" is castable with an empty graveyard.
 * - [minimum] is also the CR 608.2b divider. An object that chose **no** targets because it was
 *   allowed to still resolves and does everything it says that is not about a target (Rooftop
 *   Percher's "You gain 3 life" with two empty graveyards); an object that chose no targets because
 *   none were legal for a *required* instance does not resolve at all.
 *
 * **This is a count, never a set.** The rule that the same object cannot be chosen twice for one
 * instance of the word "target" (CR 601.2c) is not expressed here — it is a property of the *choice*,
 * enforced as index distinctness on the answer and re-checked on the recorded targets
 * (docs/design/multi-target.md §3).
 *
 * **One instance of the word "target", not the whole card.** A card printing two separate instances
 * ("target creature and target land") needs a *list* of targeting lines, which no card in the gauntlet
 * prints and which this type deliberately does not model — see docs/design/multi-target.md §7.
 */
sealed interface TargetCount {
    /** The fewest targets that must be chosen; below it the object cannot be cast or activated (CR 601.2c). */
    val minimum: Int

    /** The most targets that may be chosen (CR 115.1). */
    val maximum: Int

    /**
     * "Target creature", "two target creatures" (CR 115.1): exactly [count] targets, no fewer and no
     * more. [Exactly]`(1)` is the overwhelmingly common line and the value [TargetCount.ONE] names;
     * [Exactly]`(0)` is [TargetCount.NONE], the count of [TargetSpec.None].
     *
     * @property count how many targets are demanded; never negative.
     */
    data class Exactly(
        val count: Int,
    ) : TargetCount {
        init {
            require(count >= 0) { "CR 115.1: a target count is never negative, got $count" }
        }

        override val minimum: Int get() = count
        override val maximum: Int get() = count
    }

    /**
     * "Up to two target cards from graveyards", "up to one target creature" (CR 115.1): between zero
     * and [limit] targets, chosen by the object's controller as it is cast, activated, or put on the
     * stack. Faerie Macabre and Blood Fountain are the first clients.
     *
     * Choosing **fewer** than [limit] — zero included — is a real choice and not a failure: the object
     * is still cast, still resolves, and still does its non-targeting instructions (CR 608.2b, the
     * [minimum] divider on the hierarchy KDoc).
     *
     * @property limit the most targets that may be chosen; at least one, since "up to zero targets" is
     *   [TargetSpec.None] spelled the long way.
     */
    data class UpTo(
        val limit: Int,
    ) : TargetCount {
        init {
            require(limit >= 1) { "CR 115.1: an 'up to N' target count needs N of at least 1, got $limit" }
        }

        override val minimum: Int get() = 0
        override val maximum: Int get() = limit
    }

    companion object {
        /** The count of every targeting line that says "target &lt;noun&gt;" once (CR 115.1). */
        val ONE: TargetCount = Exactly(1)

        /** The count of [TargetSpec.None]: an object that targets nothing chooses nothing. */
        val NONE: TargetCount = Exactly(0)
    }
}
