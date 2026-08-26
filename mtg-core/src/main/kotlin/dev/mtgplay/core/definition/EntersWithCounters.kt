package dev.mtgplay.core.definition

import dev.mtgplay.core.state.Counter

/**
 * A permanent's printed "this permanent **enters with** N counters on it" self-replacement (CR 614.1c,
 * CR 121.2) — Nyxborn Hydra's "This permanent enters with X `+1/+1` counters on it". Additive, flagged
 * core (`W10-C`).
 *
 * **A replacement effect, not an ability, and the difference is observable.** CR 614.1c modifies the
 * *entering event* itself: the permanent is never on the battlefield without the counters, so nothing
 * ever sees the smaller creature. A triggered-ability encoding ("when this enters, put N counters on
 * it") would put a 0/1 Hydra on the battlefield, let the state-based actions check it, let both players
 * respond to the trigger, and only then grow it — three differences, of which the first is lethal:
 * CR 704.5f would put a 0/0 into a graveyard before the counters arrived. That is the wrong-card-that-
 * looks-right this type exists to prevent.
 *
 * Declared as a property on [CardDefinition] rather than as a [ReplacementEffect] member, in the shape
 * [EntersTapped] set for the other CR 614.1c self-replacement: [ReplacementEffect] hangs off the
 * castable [SpellDefinition] refinement, and a permanent may enter from places other than a cast.
 *
 * **Core/rules split (ADR-009).** This says *how many and of what kind*; `mtg-rules` owns applying it at
 * the moment of entry, which is the same seam `entersTapped` is read at.
 *
 * @property counter which kind of counter the permanent enters with (CR 122.1).
 * @property amount how many (CR 614.1c).
 */
data class EntersWithCounters(
    val counter: Counter,
    val amount: CounterAmount,
)

/**
 * How many counters a CR 614.1c "enters with" replacement places. Sealed so `mtg-rules` evaluates every
 * shape exhaustively and a new one breaks compilation rather than silently placing none.
 */
sealed interface CounterAmount {
    /**
     * A constant printed on the card ("enters with three `+1/+1` counters on it").
     *
     * Unused by the gauntlet pool, which prints only the variable form, and present because it is the
     * *ordinary* shape of the rule: leaving it out would make [AnnouncedX] look like the general case
     * and invite the next card to be squeezed into it.
     *
     * @property amount how many counters; at least one.
     */
    data class Fixed(
        val amount: Int,
    ) : CounterAmount {
        init {
            require(amount >= 1) { "CR 614.1c: an enters-with-counters replacement places at least one counter" }
        }
    }

    /**
     * **The value of X announced when the spell was cast** (CR 107.3, CR 601.2b) — Nyxborn Hydra's
     * "enters with X `+1/+1` counters".
     *
     * The number is read from the *cast record* rather than recomputed from the card's mana cost, for
     * the reason [ResolutionContext.chosenX] states: CR 202.3b makes X zero everywhere but the stack, so
     * the printed cost cannot answer. It is therefore the announcement of whichever cost the spell was
     * actually cast for — a Hydra cast for its bestow cost `{X}{G}{G}` enters with the X announced
     * *there*, which is the same rule read through a different cost and needs no second member.
     *
     * A permanent that reaches the battlefield without a cast (a token, a reanimation, a
     * return-to-battlefield effect) has no announcement to read, and X is zero for it (CR 107.3b).
     * `mtg-rules` places no counters in that case rather than guessing, which is the CR's own answer.
     */
    data object AnnouncedX : CounterAmount
}
