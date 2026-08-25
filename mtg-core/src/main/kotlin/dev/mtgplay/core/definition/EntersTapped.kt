package dev.mtgplay.core.definition

/**
 * Whether a permanent enters the battlefield tapped (CR 614.1c) — the self-replacement effect printed
 * as "This land enters tapped", or conditionally as "This land enters tapped unless you control three
 * or more other Forests".
 *
 * A **self-replacement effect, not an ability**: it modifies the entering event itself, so it never
 * uses the stack, generates no trigger, and cannot be responded to. Declared on [CardDefinition]
 * rather than as a [ReplacementEffect] member because [ReplacementEffect] lives on the castable
 * [SpellDefinition] refinement and a land is never cast (CR 305.1).
 *
 * Sealed, so `mtg-rules` interprets it exhaustively and a new printed shape breaks compilation rather
 * than being silently approximated. It replaced a plain `Boolean`, whose KDoc had promised exactly
 * that: a conditional clause "is not expressible here; such a card stays unencoded rather than being
 * approximated by `true` or `false`". Gingerbread Cabin is the card that made good on the promise.
 */
sealed interface EntersTapped {
    /** The CR 110.5a default: the permanent enters untapped. Every permanent that prints no clause. */
    data object Never : EntersTapped

    /**
     * "This land enters tapped" unconditionally (CR 614.1c) — the Bridge artifact lands, Idyllic
     * Beachfront, the snow duals.
     */
    data object Always : EntersTapped

    /**
     * "This permanent enters tapped **unless** you control [atLeast] or more permanents matching
     * [filter]" — Gingerbread Cabin's "unless you control three or more other Forests".
     *
     * **"Other" is structural, not a flag.** The condition is evaluated as the permanent enters, at
     * which point the object is not yet on the battlefield, so a count of the permanents you control
     * cannot include the entering permanent itself. Every printing of this template says "other" for
     * exactly that reason, and the engine gets it right by construction rather than by remembering to
     * subtract one — which is why there is no `excludingSelf` property to set wrongly.
     *
     * @property filter which permanents count (CR 109.4, CR 205.3) — `Subtype("Forest")`, controlled
     *   by you.
     * @property atLeast how many of them let the permanent enter untapped; at least one.
     */
    data class UnlessYouControl(
        val filter: PermanentFilter,
        val atLeast: Int,
    ) : EntersTapped {
        init {
            require(atLeast >= 1) {
                "CR 614.1c: an 'unless you control N' clause counts at least one permanent, was $atLeast"
            }
        }
    }
}
