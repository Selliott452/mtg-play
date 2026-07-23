package dev.mtgplay.rules.decision

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.Target

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

    /**
     * The target choice of a cast in progress (CR 601.2c): [seat] is casting [card] and must
     * pick one of [options] — the engine-enumerated legal targets (ADR-005) — by index.
     * Surfaced only for a spell that targets, and only when at least one legal target exists
     * (a cast with none is excluded from enumeration, so this request is never empty).
     *
     * Single-select because every targeted spell in the P2.1–P2.2 pool chooses exactly one
     * target; multi-target specs arrive as a sibling shape when a card needs them.
     *
     * @property cardObjectId the hand object being cast (still in hand — see
     *   [dev.mtgplay.core.state.PendingCast]).
     * @property card the printed identity, for display.
     * @property options the legal targets, in deterministic enumeration order.
     */
    data class ChooseTargets(
        override val id: DecisionRequestId,
        val cardObjectId: ObjectId,
        val card: CardRef,
        val options: List<Target>,
    ) : DecisionRequest {
        init {
            require(options.isNotEmpty()) {
                "CR 601.2c: a targets request is only surfaced when a legal target exists (ADR-005)"
            }
        }
    }

    /**
     * The payment choice of a cast in progress (CR 601.2g–h): [seat] must pick one of
     * [options] — the enumerated distinct payment plans for the spell's cost — by index.
     *
     * Always surfaced, even when exactly one plan exists (architect decision, P2.1): a
     * uniform decision sequence keeps replay logs canonical, the same rationale as the
     * no-auto-pass rule (ADR-004).
     *
     * @property cardObjectId the hand object being cast (still in hand — see
     *   [dev.mtgplay.core.state.PendingCast]).
     * @property card the printed identity, for display.
     * @property options the distinct payment plans, in the deterministic order defined by
     *   docs/design/mana-payment.md; never empty (an unaffordable cast is never enumerated).
     */
    data class ChoosePaymentPlan(
        override val id: DecisionRequestId,
        val cardObjectId: ObjectId,
        val card: CardRef,
        val options: List<PaymentPlan>,
    ) : DecisionRequest {
        init {
            require(options.isNotEmpty()) {
                "CR 601.2g: a payment request is only surfaced when a payment plan exists (ADR-005)"
            }
        }
    }

    /**
     * The declare-attackers turn-based action (CR 508.1): the active [seat] declares which of
     * its eligible creatures attack, choosing a subset of [options] by index (a
     * [Decision.MultiSelect]). Additive, flagged (P3.1).
     *
     * The empty selection is legal — the active player may attack with nothing (CR 508.8 then
     * skips the declare-blockers and combat-damage steps) — so [options] itself may be empty and,
     * unlike the casting requests, a surfaced request need not offer anything. Every option is an
     * independently legal attacker (untapped, not summoning sick, CR 508.1a), so any subset is a
     * legal declaration; the only cross-option rule is that indices are distinct.
     *
     * @property options one entry per eligible attacker, in battlefield order; indices stable
     *   within this request (ADR-005).
     */
    data class DeclareAttackers(
        override val id: DecisionRequestId,
        val options: List<Option>,
    ) : DecisionRequest {
        /**
         * One creature that may be declared as an attacker.
         *
         * @property attacker the eligible battlefield creature (CR 508.1a).
         * @property card its printed identity, for display.
         * @property defendingPlayer the player it would attack (CR 508.1) — the sole opponent in
         *   a two-player game.
         */
        data class Option(
            val attacker: ObjectId,
            val card: CardRef,
            val defendingPlayer: PlayerId,
        )
    }

    /**
     * The declare-blockers turn-based action (CR 509.1): the defending [seat] declares which of
     * its creatures block which attackers, choosing a subset of the legal (blocker, attacker)
     * pairings [options] by index (a [Decision.MultiSelect]). Additive, flagged (P3.1).
     *
     * The empty selection is legal (block nothing), so [options] may be empty. Every option is an
     * independently legal block (an untapped defending creature, and — CR 509.1b — a flyer only
     * where the attacker's evasion permits); the cross-option rule the engine enforces is that no
     * blocker is chosen twice (CR 509.1a — a creature blocks at most one attacker in the MVP
     * pool).
     *
     * @property options one entry per legal (blocker, attacker) pairing, in a deterministic order
     *   (blocker battlefield order, then attacker declaration order); indices stable within this
     *   request (ADR-005).
     */
    data class DeclareBlockers(
        override val id: DecisionRequestId,
        val options: List<Option>,
    ) : DecisionRequest {
        /**
         * One legal block: [blocker] blocking [attacker].
         *
         * @property blocker the defending creature that would block (CR 509.1a).
         * @property blockerCard the blocker's printed identity, for display.
         * @property attacker the declared attacker it would block.
         * @property attackerCard the attacker's printed identity, for display.
         */
        data class Option(
            val blocker: ObjectId,
            val blockerCard: CardRef,
            val attacker: ObjectId,
            val attackerCard: CardRef,
        )
    }

    /**
     * The damage-assignment-order choice for one multi-blocked attacker (CR 509.2): the
     * attacking [seat] orders [attacker]'s blockers, answering with a [Decision.MultiSelect]
     * whose indices are a **permutation** of all of [options] — the order damage will be
     * assigned in (CR 510.1c). Additive, flagged (P3.1).
     *
     * Surfaced only for an attacker blocked by two or more creatures (CR 509.2); a single block
     * needs no order. One request per such attacker, in attacker-declaration order.
     *
     * @property attacker the multi-blocked attacker whose blockers are being ordered.
     * @property options that attacker's blockers, in a deterministic order; the answer permutes
     *   them. Always two or more.
     */
    data class OrderBlockers(
        override val id: DecisionRequestId,
        val attacker: ObjectId,
        val options: List<Option>,
    ) : DecisionRequest {
        init {
            require(options.size >= MINIMUM_ORDERED_BLOCKERS) {
                "CR 509.2: only an attacker blocked by two or more creatures is ordered, got ${options.size}"
            }
        }

        /**
         * One blocker of the attacker being ordered.
         *
         * @property blocker the blocking creature.
         * @property card its printed identity, for display.
         */
        data class Option(
            val blocker: ObjectId,
            val card: CardRef,
        )

        private companion object {
            const val MINIMUM_ORDERED_BLOCKERS: Int = 2
        }
    }

    /**
     * The order-simultaneous-triggers choice (CR 603.3b): [seat] controls two or more triggered
     * abilities that fired at once and must choose the order to put them on the stack, answering with
     * a [Decision.MultiSelect] whose indices are a **permutation** of all of [options]. Additive,
     * flagged (P5.1).
     *
     * The order chosen is the order the triggers are put on the stack: index 0 is put first (so it is
     * on the bottom of this batch and resolves last), the last index is put on top (resolves first).
     * Surfaced only when one controller has two or more simultaneous triggers; a single trigger is put
     * on the stack automatically with no decision (`mtg-rules`). One request orders all of a single
     * controller's triggers; the next controller's, if any, are ordered by the active player's window
     * or a later request in APNAP order.
     *
     * @property options one entry per simultaneous trigger this controller must order, in a
     *   deterministic order (their fire order); the answer permutes them. Always two or more.
     */
    data class OrderTriggers(
        override val id: DecisionRequestId,
        val options: List<Option>,
    ) : DecisionRequest {
        init {
            require(options.size >= MINIMUM_ORDERED_TRIGGERS) {
                "CR 603.3b: only two or more simultaneous triggers are ordered, got ${options.size}"
            }
        }

        /**
         * One triggered ability to be ordered.
         *
         * @property sourceCard the printed identity of the ability's source (CR 603), for display.
         * @property description a short human description of the trigger, for display.
         */
        data class Option(
            val sourceCard: CardRef,
            val description: String,
        )

        private companion object {
            const val MINIMUM_ORDERED_TRIGGERS: Int = 2
        }
    }

    /**
     * A yes/no choice (CR 601.3b, CR 702.35b): [seat] answers a [Decision.SingleSelect] whose index
     * is **1 to accept, 0 to decline** — the two options a "you may" offers. Additive, flagged (P5.2).
     *
     * The first use is madness's reflexive cast (CR 702.35b): as the reflexive trigger resolves, the
     * card's owner may cast it for its madness cost. The request is surfaced only when the "yes" is
     * actually playable (a legal target and an affordable madness cost exist, ADR-005), so both answers
     * are legal — accepting opens the cast, declining puts the card into the graveyard.
     *
     * @property prompt a short human description of the choice, for display (ADR-005).
     * @property cardObjectId the object the choice concerns (the exiled madness card), for the driver.
     * @property card its printed identity, for display.
     */
    data class ChooseYesNo(
        override val id: DecisionRequestId,
        val prompt: String,
        val cardObjectId: ObjectId,
        val card: CardRef,
    ) : DecisionRequest {
        companion object {
            /** The [Decision.SingleSelect] index that declines a yes/no (CR 601.3b "may"). */
            const val DECLINE: Int = 0

            /** The [Decision.SingleSelect] index that accepts a yes/no. */
            const val ACCEPT: Int = 1

            /** How many options a yes/no offers — decline and accept. */
            const val OPTION_COUNT: Int = 2
        }
    }

    /**
     * An additional-cost card-exile selection (CR 601.2b, CR 702.139a): [seat] is casting [card] via a
     * cost that exiles [count] *other* cards from a zone, and picks exactly [count] of [options] by
     * index (a [Decision.MultiSelect]). Additive, flagged (P5.2). Escape's "exile two other cards from
     * your graveyard" is the first client.
     *
     * Surfaced only when at least [count] cards are available (the cast is otherwise not enumerated,
     * ADR-005), so a legal selection always exists; every option is independently exilable, so any
     * distinct subset of size [count] is legal.
     *
     * @property cardObjectId the object being cast (still in its source zone — see
     *   [dev.mtgplay.core.state.PendingCast]).
     * @property card the printed identity, for display.
     * @property options the cards that may be exiled to pay the cost, in zone order; indices stable
     *   within this request (ADR-005).
     * @property count how many must be exiled.
     */
    data class ChooseCardsToExile(
        override val id: DecisionRequestId,
        val cardObjectId: ObjectId,
        val card: CardRef,
        val options: List<Option>,
        val count: Int,
    ) : DecisionRequest {
        init {
            require(count in 1..options.size) {
                "CR 601.2b: exile count must be between 1 and available ${options.size}, was $count"
            }
        }

        /**
         * One card that may be exiled to pay the cost.
         *
         * @property objectId the object that would be exiled.
         * @property card its printed identity, for display.
         */
        data class Option(
            val objectId: ObjectId,
            val card: CardRef,
        )
    }

    /**
     * The CR 616.1 replacement-ordering choice: two or more replacement effects would each modify one
     * event, and the affected [seat] chooses which to apply first, answering a [Decision.SingleSelect]
     * by index. Additive, flagged (P5.2). No real MVP card pair produces this; a fixture with two
     * discard→exile replacements exercises it. After the chosen one is applied the event is
     * re-evaluated for any still applicable (CR 614.5 — each applies once per event).
     *
     * @property options one entry per applicable replacement, in a deterministic order; indices stable
     *   within this request (ADR-005). Always two or more.
     */
    data class ChooseReplacement(
        override val id: DecisionRequestId,
        val options: List<Option>,
    ) : DecisionRequest {
        init {
            require(options.size >= MINIMUM_ORDERED_REPLACEMENTS) {
                "CR 616.1: a replacement choice is surfaced only for two or more applicable replacements, " +
                    "got ${options.size}"
            }
        }

        /**
         * One applicable replacement effect the affected player may apply first.
         *
         * @property description a short human description of the replacement, for display (ADR-005).
         */
        data class Option(
            val description: String,
        )

        private companion object {
            const val MINIMUM_ORDERED_REPLACEMENTS: Int = 2
        }
    }
}
