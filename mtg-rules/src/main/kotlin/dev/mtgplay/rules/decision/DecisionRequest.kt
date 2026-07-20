package dev.mtgplay.rules.decision

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * A typed request for one player decision — what the engine returns when it cannot proceed
 * without a choice (ADR-004).
 *
 * Every member names the deciding [seat], carries a stable [id], and enumerates its legal
 * options with stable indices (ADR-005): a [Decision] answers by selecting index(es), never by
 * constructing an action. Indices are stable within a request; they are not comparable across
 * requests. Later packets add members (targets, blocker order, mulligans, payment plans, …);
 * drivers `when` over the hierarchy exhaustively, so a new member breaks their compilation
 * rather than falling through silently.
 */
sealed interface DecisionRequest {
    /** The stable identity of this request (ADR-004); see [DecisionRequestId] for the scheme. */
    val id: DecisionRequestId

    /** The seat that must decide; only this player's answer is meaningful. */
    val seat: PlayerId get() = id.seat

    /**
     * A priority window (CR 117): [seat] holds priority and must pick one of [options] by
     * index. In P1.2 the only option ever enumerated is [PriorityOption.Pass] — there is no
     * auto-pass in the engine, so every window surfaces, even with only one option
     * (convenience auto-responders belong in drivers).
     *
     * @property options the enumerated legal options (ADR-005); never empty — passing is
     *   always legal.
     */
    data class ChooseAction(
        override val id: DecisionRequestId,
        val options: List<PriorityOption>,
    ) : DecisionRequest {
        init {
            require(options.isNotEmpty()) { "CR 117.3d: passing is always legal; options cannot be empty" }
        }
    }

    /**
     * The cleanup-step discard down to maximum hand size (CR 402.2, CR 514.1): [seat] must
     * select exactly [count] cards from [options] — their hand, one entry per card — by index.
     *
     * @property options one entry per card in [seat]'s hand, in hand order; indices are stable
     *   within this request (ADR-005).
     * @property count how many cards must be discarded: hand size minus maximum hand size.
     */
    data class ChooseDiscards(
        override val id: DecisionRequestId,
        val options: List<Option>,
        val count: Int,
    ) : DecisionRequest {
        init {
            require(count in 1..options.size) {
                "CR 514.1: discard count must be between 1 and hand size ${options.size}, was $count"
            }
        }

        /**
         * One discardable card in the deciding player's hand.
         *
         * @property objectId the hand object that would be discarded.
         * @property card the printed identity, for display; the object itself is reborn with a
         *   new id in the graveyard if discarded (CR 400.7).
         */
        data class Option(
            val objectId: ObjectId,
            val card: CardRef,
        )
    }
}
