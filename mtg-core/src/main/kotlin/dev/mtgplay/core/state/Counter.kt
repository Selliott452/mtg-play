package dev.mtgplay.core.state

import dev.mtgplay.core.card.Keyword

/**
 * A kind of counter that can be placed on a permanent (CR 122.1) — the *marker's identity*, not how
 * many of it an object carries. Additive, flagged core (`FW-COUNTERS`). A counter is not an object
 * and has no characteristics (CR 122.1); counters with the same description are interchangeable,
 * which is exactly why this is a value used as a multiset key on
 * [GameObject.counters] rather than a per-counter object with an id.
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This says only *what a counter
 * is*. That a `+1/+1` counter adds to power and toughness in CR 613 sublayer 7c, that a keyword
 * counter grants its keyword in layer 6, and that CR 704.5q annihilates opposing `+1/+1` and
 * `-1/-1` counters are all rules decisions and live in `mtg-rules`.
 *
 * Sealed so the layer walk and the state-based-action check handle every kind exhaustively: a new
 * counter kind (stun, shield, loyalty, poison — CR 122.1c–j, none of which the gauntlet pool
 * prints) breaks compilation at the application sites rather than being silently ignored.
 */
sealed interface Counter {
    /**
     * A `+X/+Y` counter (CR 122.1a): it adds [power] to the object's power and [toughness] to its
     * toughness, in CR 613 sublayer 7c (CR 613.4c). Negative components are the `-X/-Y` spelling of
     * the same rule — `-1/-1` is `PowerToughness(-1, -1)`, and Wall of Roots' `-0/-1` is
     * `PowerToughness(0, -1)`.
     *
     * The two components are independent signed integers rather than an enum of the three printed
     * kinds, because CR 122.1a states one rule over all of them and "counters with the same
     * description are interchangeable" is then structural equality for free. A `+1/+1` and a
     * `-0/-1` counter on the same creature are two distinct entries in the multiset, which is what
     * CR 704.5q needs (it annihilates `+1/+1` against `-1/-1` **only**, never against `-0/-1`).
     *
     * @property power what one such counter adds to power (CR 122.1a); may be negative or zero.
     * @property toughness what one such counter adds to toughness; may be negative or zero.
     */
    data class PowerToughness(
        val power: Int,
        val toughness: Int,
    ) : Counter {
        init {
            require(power != 0 || toughness != 0) {
                "CR 122.1a: a +X/+Y counter modifies power or toughness; a +0/+0 counter is not a P/T counter"
            }
        }
    }

    /**
     * A keyword counter (CR 122.1b): it causes the object to gain [keyword], in CR 613 layer 6
     * (CR 613.1f) — Unexpected Fangs' lifelink counter.
     *
     * CR 122.1b closes the list of keywords a keyword counter may be, and [KEYWORD_COUNTER_KEYWORDS]
     * is that list intersected with the keywords this engine models; anything else fails loudly here
     * rather than minting a counter no layer could honour.
     *
     * @property keyword the keyword the object gains while it has this counter (CR 122.1b).
     */
    data class KeywordCounter(
        val keyword: Keyword,
    ) : Counter {
        init {
            require(keyword in KEYWORD_COUNTER_KEYWORDS) {
                "CR 122.1b: $keyword is not a keyword a keyword counter can be; the rule's list is " +
                    "flying, first strike, double strike, deathtouch, decayed, exalted, haste, hexproof, " +
                    "indestructible, lifelink, menace, reach, shadow, trample and vigilance"
            }
        }
    }

    /**
     * A **charge counter** (CR 122.1): a counter with a name and no intrinsic rules meaning of its own.
     * Additive, flagged core (`W10-C`) — Pinnacle Kill-Ship's Station counters.
     *
     * **The first counter kind that does nothing.** [PowerToughness] modifies P/T in CR 613 sublayer 7c
     * and [KeywordCounter] grants a keyword in layer 6, so each of them reaches the layer walk on its
     * own. A charge counter reaches no layer at all: CR 122.1 makes it a marker, CR 122.6 lets abilities
     * *count* it, and nothing else in the rules mentions it. What the counters do on a Spacecraft is
     * written on the Spacecraft — "it's an artifact creature at 7+" is a static ability of the permanent
     * whose condition reads the count (see
     * [dev.mtgplay.core.definition.StaticCondition.CountersOnSelf]), not a property of the counter.
     *
     * That is why it is a `data object` rather than a `data class` carrying a name. CR 122.1 admits any
     * word, but a counter kind with no rules meaning is distinguished only by the abilities that read
     * it, and two differently-named inert counters would be indistinguishable to every one of them in
     * this pool. A named member joins this type with the first card that puts two different inert
     * counter kinds on one permanent; until then a name would be a field nothing reads.
     *
     * It is deliberately **not** [PowerToughness]`(0, 0)`, which that member's own `init` refuses: a
     * `+0/+0` counter is not a P/T counter, and encoding an inert counter as one would put it into
     * sublayer 7c where CR 704.5q could then annihilate it against a `-1/-1` counter — a wrong game that
     * would look right until a Spacecraft lost a Station counter to a shrink effect.
     */
    data object Charge : Counter

    companion object {
        /** The `+1/+1` counter (CR 122.1a) — one of the two CR 704.5q annihilates against each other. */
        val PLUS_ONE_PLUS_ONE: PowerToughness = PowerToughness(1, 1)

        /** The `-1/-1` counter (CR 122.1a) — the other half of the CR 704.5q pair. */
        val MINUS_ONE_MINUS_ONE: PowerToughness = PowerToughness(-1, -1)

        /**
         * The `-0/-1` counter (CR 122.1a) Wall of Roots and Vine Trellis pay as a mana-ability cost.
         * Modelled by `FW-COUNTERS` because CR 122.1a covers it with no extra machinery, and placed by
         * a card since `FW-MANACOST`, which built the two things it was waiting on: a mana-ability cost
         * shape that is neither `{T}` nor sacrifice ([ManaAbilityCost.PutCounterOnSelf]) and the
         * CR 602.5b once-each-turn activation limit. Deliberately *not* interchangeable with
         * [MINUS_ONE_MINUS_ONE]: CR 704.5q does not touch it.
         */
        val MINUS_ZERO_MINUS_ONE: PowerToughness = PowerToughness(0, -1)

        /**
         * The keywords CR 122.1b permits a keyword counter to be, restricted to the keywords this
         * engine models. The rule's full list also names double strike, deathtouch, decayed, exalted,
         * menace and shadow, none of which is a [Keyword] member yet; each joins this set with the
         * packet that adds it, so a keyword counter can never outrun the keyword's rules effect.
         */
        val KEYWORD_COUNTER_KEYWORDS: Set<Keyword> =
            setOf(
                Keyword.FLYING,
                Keyword.FIRST_STRIKE,
                Keyword.HASTE,
                Keyword.HEXPROOF,
                Keyword.INDESTRUCTIBLE,
                Keyword.LIFELINK,
                Keyword.REACH,
                Keyword.TRAMPLE,
                Keyword.VIGILANCE,
            )
    }
}
