package dev.mtgplay.core.definition

/**
 * The "as long as …" clause of a **conditional** static ability (CR 604.3) — the condition a
 * [StaticContinuousEffect] must satisfy for its continuous effect to be active at all. Additive,
 * flagged core (`FW-CONDSTATIC`).
 *
 * Goblin Tomb Raider's "**as long as you control an artifact**, this creature gets +1/+0 and has
 * haste" is the pool's first. The difference from an unconditional static is not cosmetic: CR 604.3
 * makes such an ability's effect start and stop applying as the condition becomes true and false,
 * *continuously* and with no trigger and no stack — so the artifact leaving the battlefield takes the
 * haste away mid-turn, and a second artifact arriving gives it back, without anything being placed on
 * the stack or any player receiving priority.
 *
 * Declarative data rather than a predicate lambda, for the reasons [PermanentFilter] and
 * [PermanentRestriction] already are: a card definition is data (ADR-003), the value takes part in
 * structural equality, and it is serialisable and renderable. `mtg-core` states the condition;
 * `mtg-rules` decides whether it holds (ADR-009 — no game-rule decisions in core).
 *
 * Sealed so `mtg-rules` evaluates every shape exhaustively and a new condition breaks compilation
 * rather than being silently treated as true — which is the specific silent failure worth naming here,
 * because a condition that is quietly always-true turns a conditional ability into an unconditional
 * one and the card still *looks* right in every log.
 */
sealed interface StaticCondition {
    /**
     * "As long as you control [atLeast] or more permanents matching [filter]" (CR 604.3) — Goblin Tomb
     * Raider's "as long as you control an artifact" is `atLeast = 1` over a filter constrained to
     * [dev.mtgplay.core.card.CardType.ARTIFACT].
     *
     * Deliberately the same `(filter, atLeast)` pair [EntersTapped.UnlessYouControl] already uses for
     * Gingerbread Cabin's "unless you control three or more other Forests". The two clauses are the
     * same board-state question asked at different moments — one as a CR 614.1c replacement while the
     * permanent enters, one continuously while it is on the battlefield (CR 604.3) — and sharing the
     * shape is what lets both count through the one `countMatchingPermanents` seam instead of two.
     *
     * "You" is the ability's source's controller, which is its owner across this pool (no layer-2
     * control-changing effect exists).
     *
     * @property filter which permanents count (CR 109.4); its own `controlledByYou` axis carries the
     *   "you control" half, so a condition over *every* permanent on the battlefield is expressible
     *   without a second member.
     * @property atLeast how many are needed; "an artifact" is 1. Never below 1 — a threshold of zero
     *   is satisfied by the empty board, i.e. an unconditional ability written the long way round.
     */
    data class YouControl(
        val filter: PermanentFilter,
        val atLeast: Int = 1,
    ) : StaticCondition {
        init {
            require(atLeast >= 1) {
                "CR 604.3: an \"as long as you control …\" condition needs at least one permanent; " +
                    "a threshold of $atLeast is satisfied by an empty battlefield, which is an " +
                    "unconditional ability rather than a conditional one"
            }
        }
    }
}
