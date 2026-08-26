package dev.mtgplay.core.definition

import dev.mtgplay.core.state.Counter

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
     * "As long as this permanent has [atLeast] or more [counter] counters on it" (CR 604.3, CR 122.6) —
     * Pinnacle Kill-Ship's **7+**, the threshold Station's counters climb towards. Additive, flagged
     * core (`W10-C`).
     *
     * **The first condition that reads the source rather than the board**, and the reason it is worth a
     * member of its own rather than a widening of [YouControl]: the question is not "how many permanents
     * match a filter" but "how many counters of one kind does *this object* carry". No
     * [PermanentFilter] can say it — the filter's axes are name, subtype, card type, keyword and
     * controller — and a filter that could would be counting objects, not counters.
     *
     * **Continuous re-evaluation is the whole of its correctness** (CR 604.3), and counters are what
     * make that observable in a way [YouControl] never quite did. A Spacecraft's charge counters go up
     * mid-turn, in the middle of a resolution, with no player receiving priority; the instant the
     * seventh lands the permanent *is* an artifact creature, and the instant an effect removed one it
     * would stop being one. Because characteristics are computed on read and never cached
     * (docs/design/layer-system.md §5), that continuity costs nothing here and would be impossible to
     * retrofit onto a triggered-ability encoding of the same text, which would fire once and then be
     * wrong for the rest of the game.
     *
     * "At least", never "exactly": every printing of the form is a threshold ("7+"), and an exact count
     * would make a Spacecraft stop being a creature the moment an eighth counter arrived.
     *
     * @property counter which kind of counter is counted (CR 122.1) — the Spacecraft's
     *   [dev.mtgplay.core.state.Counter.Charge].
     * @property atLeast how many are needed; never below 1, for [YouControl.atLeast]'s reason — a
     *   threshold of zero is satisfied by a permanent with no counters at all, i.e. an unconditional
     *   ability written the long way round.
     */
    data class CountersOnSelf(
        val counter: Counter,
        val atLeast: Int,
    ) : StaticCondition {
        init {
            require(atLeast >= 1) {
                "CR 604.3: a counter-threshold condition needs at least one counter; a threshold of " +
                    "$atLeast is satisfied by a permanent carrying none, which is an unconditional " +
                    "ability rather than a conditional one"
            }
        }
    }

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
