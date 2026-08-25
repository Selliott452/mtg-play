package dev.mtgplay.rules.decision

import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
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
     * A [Decision.MultiSelect] request asking for a fixed-size distinct subset of its options — the
     * shape shared by every "choose exactly N cards/permanents" cost or discard selection (CR 601.2b/h,
     * CR 602.2b, CR 514.1). Grouping them under one sub-interface lets drivers and the enumeration probe
     * handle "a sized selection" uniformly rather than one branch per request kind. [optionCount] is the
     * number of options and [requiredCount] the exact number that must be chosen.
     */
    sealed interface SizedSelection : DecisionRequest {
        /** How many options this selection offers. */
        val optionCount: Int

        /** Exactly how many must be chosen. */
        val requiredCount: Int
    }

    /**
     * A [Decision.MultiSelect] request answered with a **permutation** of all of its options — a full
     * ordering (CR 509.2 blocker order, CR 603.3b trigger order). Grouping them lets drivers and the
     * probe handle "an ordering" uniformly. [permutationSize] is how many options the answer permutes.
     */
    sealed interface PermutationSelection : DecisionRequest {
        /** How many options the answer must permute. */
        val permutationSize: Int
    }

    /**
     * A [Decision.SingleSelect] request whose answer is one of some real options **or** a single extra
     * "opt-out" index at the end — the shape shared by every "keep/find/pay one, or none" choice (CR 701.16
     * keep-one, CR 601.3b cost-mode, CR 701.18 find-one). Grouping them lets drivers and the enumeration
     * probe handle "a choice-count select" uniformly (a uniform pick over `0 until choiceCount`), exactly as
     * [SizedSelection] groups the fixed-size subset selections. [choiceCount] is the total number of legal
     * indices — the real options plus the one opt-out. Application still dispatches per leaf, since each
     * opt-out (keep none / decline / find none) resolves differently.
     */
    sealed interface ChoiceCountSelection : DecisionRequest {
        /** The number of legal indices: the real options plus the one trailing opt-out index. */
        val choiceCount: Int
    }

    /**
     * A [Decision.SingleSelect] request answered by picking exactly one of its **own** enumerated options,
     * with **no** opt-out index — the shape shared by a target choice (CR 601.2c), a payment plan
     * (CR 601.2g), a trample assignment (CR 702.19e), an as-enters colour (CR 614.12), a replacement
     * ordering (CR 616.1), and a library arrangement (CR 701.17a). Grouping them lets drivers, the CLI
     * menus, and the enumeration probe handle "pick one of these" uniformly rather than one branch per
     * request kind, exactly as [SizedSelection], [PermutationSelection], and [ChoiceCountSelection] group
     * their own shapes. [optionCount] is the number of legal indices, all of which are real options.
     *
     * The deliberate non-members: [ChooseAction], whose priority window every driver special-cases
     * (a pass is not just "option 0"); [ChooseYesNo], whose two indices are a fixed decline/accept pair
     * rather than an enumerated list; and every [ChoiceCountSelection], whose last index is an opt-out and
     * therefore is *not* one of its options — treating those two as the same shape is precisely the bug
     * that would let a driver decline a mandatory choice.
     */
    sealed interface SingleOptionSelection : DecisionRequest {
        /** How many options this request offers; every index in `0 until optionCount` is a real option. */
        val optionCount: Int
    }

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
    ) : SizedSelection {
        override val optionCount: Int get() = options.size
        override val requiredCount: Int get() = count

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
    ) : SingleOptionSelection {
        override val optionCount: Int get() = options.size

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
    ) : SingleOptionSelection {
        override val optionCount: Int get() = options.size

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
    ) : PermutationSelection {
        override val permutationSize: Int get() = options.size

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
     * The trample damage-assignment choice for one blocked trampling attacker (CR 702.19e): the
     * attacking [seat] chooses how much of [attacker]'s combat damage above lethal is dealt to the
     * defending player, answering a [Decision.SingleSelect] whose index **is** the amount assigned
     * to the player. Additive, flagged (P5.3).
     *
     * Surfaced only for an attacker that is blocked, has at least one surviving blocker, has trample
     * among its effective keywords, and has strictly positive above-lethal excess — so the choice is
     * real (a range of two or more). The engine has already committed at least lethal to every
     * surviving blocker (CR 510.1c), so only the excess is the player's to give: the options are the
     * integers `0..excess`, and whatever is not assigned to the player overkills a blocker
     * (outcome-irrelevant, collapsed deterministically). A blocked trampler with **no** surviving
     * blockers assigns all its damage to the player with no choice (CR 702.19g), so no request is
     * surfaced there. One request per such attacker, in attacker-declaration order, per combat-damage
     * step (a first-striker's trample assignment happens in its step).
     *
     * @property attacker the blocked trampling attacker whose excess is being assigned.
     * @property attackerCard the attacker's printed identity, for display.
     * @property defendingPlayer the player the excess may be assigned to (CR 508.1's defender).
     * @property options the assignable amounts to the player, the integers `0..excess` in order; the
     *   answer's index is the chosen amount. Always two or more (excess is positive when surfaced).
     */
    data class AssignTrampleDamage(
        override val id: DecisionRequestId,
        val attacker: ObjectId,
        val attackerCard: CardRef,
        val defendingPlayer: PlayerId,
        val options: List<Int>,
    ) : SingleOptionSelection {
        override val optionCount: Int get() = options.size

        init {
            require(options == options.indices.toList()) {
                "CR 702.19e: trample options are the amounts 0..excess in order, got $options"
            }
            require(options.size >= MINIMUM_TRAMPLE_OPTIONS) {
                "CR 702.19e: a trample assignment is surfaced only with positive excess, got ${options.size} option(s)"
            }
        }

        private companion object {
            /** Surfaced only when both 0 and at least 1 are assignable (positive excess). */
            const val MINIMUM_TRAMPLE_OPTIONS: Int = 2
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
    ) : PermutationSelection {
        override val permutationSize: Int get() = options.size

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
    ) : SizedSelection {
        override val optionCount: Int get() = options.size
        override val requiredCount: Int get() = count

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
     * A non-mana sacrifice cost selection (CR 601.2h): [seat] is casting [card] via a cost that
     * sacrifices [count] permanents matching a predicate (Fireblast's two Mountains, Lava Dart's
     * Mountain), and picks exactly [count] of [options] by index (a [Decision.MultiSelect]). Additive,
     * flagged (P6.2a).
     *
     * Surfaced only when at least [count] matching permanents are available (the cast is otherwise not
     * enumerated, ADR-005), so a legal selection always exists; every option is independently
     * sacrificeable, so any distinct subset of size [count] is legal.
     *
     * @property cardObjectId the object being cast (still in its source zone — see
     *   [dev.mtgplay.core.state.PendingCast]).
     * @property card the printed identity, for display.
     * @property options the permanents that may be sacrificed to pay the cost, in battlefield order;
     *   indices stable within this request (ADR-005).
     * @property count how many must be sacrificed.
     */
    data class ChooseSacrifices(
        override val id: DecisionRequestId,
        val cardObjectId: ObjectId,
        val card: CardRef,
        val options: List<Option>,
        val count: Int,
    ) : SizedSelection {
        override val optionCount: Int get() = options.size
        override val requiredCount: Int get() = count

        init {
            require(count in 1..options.size) {
                "CR 601.2h: sacrifice count must be between 1 and available ${options.size}, was $count"
            }
        }

        /**
         * One permanent that may be sacrificed to pay the cost.
         *
         * @property objectId the battlefield object that would be sacrificed.
         * @property card its printed identity, for display.
         */
        data class Option(
            val objectId: ObjectId,
            val card: CardRef,
        )
    }

    /**
     * An additional discard cost selection (CR 601.2b): [seat] is casting [card] via a cost that
     * discards [count] cards (Grab the Prize's "discard a card"), and picks exactly [count] of
     * [options] — their remaining hand — by index (a [Decision.MultiSelect]). Additive, flagged
     * (P6.2a).
     *
     * Surfaced only when at least [count] cards are available (the card being cast has already moved to
     * the stack, so it is not among the options; the cast is otherwise not enumerated, ADR-005), so a
     * legal selection always exists. Every option is independently discardable, so any distinct subset
     * of size [count] is legal. A discarded card with madness is exiled instead (CR 702.35a), routing
     * through the same discard framework as the cleanup discard.
     *
     * @property cardObjectId the object being cast (already on the stack when the cost is paid — see
     *   [dev.mtgplay.core.state.PendingCast]).
     * @property card the printed identity, for display.
     * @property options the hand cards that may be discarded to pay the cost, in hand order; indices
     *   stable within this request (ADR-005).
     * @property count how many must be discarded.
     */
    data class ChooseCardsToDiscardForCost(
        override val id: DecisionRequestId,
        val cardObjectId: ObjectId,
        val card: CardRef,
        val options: List<Option>,
        val count: Int,
    ) : SizedSelection {
        override val optionCount: Int get() = options.size
        override val requiredCount: Int get() = count

        init {
            require(count in 1..options.size) {
                "CR 601.2b: discard count must be between 1 and available ${options.size}, was $count"
            }
        }

        /**
         * One hand card that may be discarded to pay the cost.
         *
         * @property objectId the hand object that would be discarded.
         * @property card its printed identity, for display.
         */
        data class Option(
            val objectId: ObjectId,
            val card: CardRef,
        )
    }

    /**
     * An **intrinsic** sacrifice additional cost selection (CR 601.2b, performed at CR 601.2h): [seat]
     * is casting [card], whose printed text is "As an additional cost to cast this spell, sacrifice a
     * land / an artifact or creature" (Eviscerator's Insight, Reckoner's Bargain, Crop Rotation, Raze),
     * and picks exactly [count] of [options] by index (a [Decision.MultiSelect]). Additive, flagged
     * (`FW-ADDSAC`).
     *
     * The sibling of [ChooseCardsToDiscardForCost] and deliberately its shape. Distinct from
     * [ChooseSacrifices], which is the *permission*-side sacrifice cost (Fireblast's two Mountains,
     * Lava Dart's flashback Mountain): those two costs have different filters and a card may in
     * principle carry both, so they are two selections rather than one widened request — the same
     * separation [ChooseCardsToDiscardForCost] keeps from [ChooseAbilityDiscard].
     *
     * Surfaced only when at least [count] matching permanents are available (the cast is otherwise not
     * enumerated, ADR-005), so a legal selection always exists, and every option is independently
     * sacrificeable.
     *
     * **It is a cost.** The permanents are sacrificed inside the transition that completes the cast
     * (CR 601.2h) — no player receives priority between this answer and the sacrifice, and it cannot be
     * responded to. They are sacrificed *after* the mana payment, so a land answered here may still be
     * tapped for mana by the payment plan that follows (docs/design/mana-payment.md §2.2).
     *
     * @property cardObjectId the object being cast (still in its source zone — see
     *   [dev.mtgplay.core.state.PendingCast]).
     * @property card the printed identity, for display.
     * @property options the permanents that may be sacrificed to pay the cost, in battlefield order;
     *   indices stable within this request (ADR-005).
     * @property count how many must be sacrificed.
     */
    data class ChooseSacrificesForCost(
        override val id: DecisionRequestId,
        val cardObjectId: ObjectId,
        val card: CardRef,
        val options: List<Option>,
        val count: Int,
    ) : SizedSelection {
        override val optionCount: Int get() = options.size
        override val requiredCount: Int get() = count

        init {
            require(count in 1..options.size) {
                "CR 601.2b: sacrifice count must be between 1 and available ${options.size}, was $count"
            }
        }

        /**
         * One permanent that may be sacrificed to pay the additional cost.
         *
         * @property objectId the battlefield object that would be sacrificed.
         * @property card its printed identity, for display.
         */
        data class Option(
            val objectId: ObjectId,
            val card: CardRef,
        )
    }

    /**
     * The pre-game London-mulligan decisions (CR 103.4/103.5): the keep-or-mulligan choice and the
     * put-cards-on-the-bottom choice. Grouped under one sealed sub-interface so a driver may handle
     * "a mulligan decision" as a single branch (they only occur in the pre-game phase, never during
     * a turn); a `when` over [DecisionRequest] stays exhaustive whether it matches the leaves or the
     * group. Additive, flagged (P6.1).
     */
    sealed interface MulliganRequest : DecisionRequest

    /**
     * The pre-game keep-or-mulligan choice (CR 103.4): [seat] answers a [Decision.SingleSelect]
     * whose index is **[KEEP] (0) to keep the drawn hand** or **[MULLIGAN] (1) to mulligan** —
     * shuffle it into the library and draw a fresh hand. Additive, flagged (P6.1).
     *
     * Both answers are always legal: a player may keep any hand, and London mulligans have no
     * hard limit (a player who has mulliganed to an empty keep still may mulligan). Surfaced once
     * per pending keep decision; a mulligan re-surfaces it, a keep after N mulligans leads to
     * [ChooseCardsToBottom] (when N > 0).
     *
     * @property mulligansTaken how many mulligans [seat] has already taken (for display); 0 before
     *   their first decision.
     */
    data class ChooseMulligan(
        override val id: DecisionRequestId,
        val mulligansTaken: Int,
    ) : MulliganRequest {
        init {
            require(mulligansTaken >= 0) { "mulligans taken must be non-negative, was $mulligansTaken" }
        }

        companion object {
            /** The [Decision.SingleSelect] index that keeps the drawn hand (CR 103.4). */
            const val KEEP: Int = 0

            /** The [Decision.SingleSelect] index that takes a mulligan (CR 103.4). */
            const val MULLIGAN: Int = 1

            /** How many options a mulligan choice offers — keep and mulligan. */
            const val OPTION_COUNT: Int = 2
        }
    }

    /**
     * The put-cards-on-the-bottom choice of the London mulligan (CR 103.5): having kept after one
     * or more mulligans, [seat] selects exactly [count] cards from [options] — their hand, one
     * entry per card — by index (a [Decision.MultiSelect]). Additive, flagged (P6.1).
     *
     * The selection **order is the bottoming order**: the cards are placed on the bottom of the
     * library in the order selected, so the first selected ends up above the last selected at the
     * bottom (documented so a driver's order is meaningful and replay-stable, ADR-006).
     *
     * @property options one entry per card in [seat]'s hand, in hand order; indices stable within
     *   this request (ADR-005).
     * @property count how many cards must be bottomed: the number of mulligans taken, capped at the
     *   hand size.
     */
    data class ChooseCardsToBottom(
        override val id: DecisionRequestId,
        val options: List<Option>,
        val count: Int,
    ) : MulliganRequest {
        init {
            require(count in 1..options.size) {
                "CR 103.5: bottom count must be between 1 and hand size ${options.size}, was $count"
            }
        }

        /**
         * One card in the deciding player's hand that may be put on the bottom of the library.
         *
         * @property objectId the hand object that would be bottomed.
         * @property card its printed identity, for display.
         */
        data class Option(
            val objectId: ObjectId,
            val card: CardRef,
        )
    }

    /**
     * A "discard a card" cost selection of an activated ability (CR 602.2b): [seat] is activating an
     * ability of [sourceObjectId] whose cost discards [count] cards (Blood token's "Discard a card"), and
     * picks exactly [count] of [options] — their hand — by index (a [Decision.MultiSelect]). Additive,
     * flagged (P6.2a).
     *
     * Surfaced only when at least [count] cards are available (the activation is otherwise not
     * enumerated, ADR-005). A discarded card with madness is exiled instead (CR 702.35a), routing through
     * the same discard framework as every cost discard.
     *
     * @property sourceObjectId the ability's source, for display.
     * @property card the source's printed identity, for display.
     * @property options the hand cards that may be discarded to pay the cost, in hand order.
     * @property count how many must be discarded.
     */
    data class ChooseAbilityDiscard(
        override val id: DecisionRequestId,
        val sourceObjectId: ObjectId,
        val card: CardRef,
        val options: List<Option>,
        val count: Int,
    ) : SizedSelection {
        override val optionCount: Int get() = options.size
        override val requiredCount: Int get() = count

        init {
            require(count in 1..options.size) {
                "CR 602.2b: discard count must be between 1 and available ${options.size}, was $count"
            }
        }

        /**
         * One hand card that may be discarded to pay the ability's cost.
         *
         * @property objectId the hand object that would be discarded.
         * @property card its printed identity, for display.
         */
        data class Option(
            val objectId: ObjectId,
            val card: CardRef,
        )
    }

    /**
     * A chosen-permanent sacrifice cost selection of an activated ability (CR 602.1, CR 701.17): [seat]
     * is activating an ability of [sourceObjectId] whose cost sacrifices [count] permanents matching a
     * filter (Krark-Clan Shaman's "Sacrifice an artifact", Makeshift Munitions' "Sacrifice an artifact
     * or creature"), and picks exactly [count] of [options] by index (a [Decision.MultiSelect]).
     * Additive, flagged (`FW-ADDSAC`).
     *
     * The sibling of [ChooseAbilityDiscard], and deliberately its shape rather than a new one: a cost
     * with a chosen object is a fixed-size subset selection over an engine-enumerated option list,
     * whatever the object is.
     *
     * Surfaced only when at least [count] candidates are available (the activation is otherwise not
     * enumerated, ADR-005), so a legal selection always exists. Every option is *independently*
     * completable — the option list is filtered to candidates that leave the ability's mana component
     * payable once reserving them is accounted for (docs/design/mana-payment.md §2.2) — so no answer
     * here can dead-end the activation.
     *
     * @property sourceObjectId the ability's source, for display.
     * @property card the source's printed identity, for display.
     * @property options the permanents that may be sacrificed to pay the cost, in battlefield order;
     *   indices stable within this request (ADR-005).
     * @property count how many must be sacrificed.
     */
    data class ChooseAbilitySacrifice(
        override val id: DecisionRequestId,
        val sourceObjectId: ObjectId,
        val card: CardRef,
        val options: List<Option>,
        val count: Int,
    ) : SizedSelection {
        override val optionCount: Int get() = options.size
        override val requiredCount: Int get() = count

        init {
            require(count in 1..options.size) {
                "CR 602.1: sacrifice count must be between 1 and available ${options.size}, was $count"
            }
        }

        /**
         * One permanent that may be sacrificed to pay the ability's cost.
         *
         * @property objectId the battlefield object that would be sacrificed.
         * @property card its printed identity, for display.
         */
        data class Option(
            val objectId: ObjectId,
            val card: CardRef,
        )
    }

    /**
     * An "as this permanent enters, choose a colour" choice (CR 614.12): [seat] is resolving a
     * permanent that chooses a colour as it enters (Utopia Sprawl) and picks one of [options] — the
     * five colours in WUBRG order — by index (a [Decision.SingleSelect]). Additive, flagged (P6.2a).
     * The chosen colour is stored on the entering object and read by its triggered mana ability. Both
     * (all five) answers are always legal.
     *
     * @property cardObjectId the resolving object (the top of the stack) the choice concerns, for display.
     * @property card the printed identity, for display.
     * @property options the choosable colours, in WUBRG order (CR 105.1); the answer's index selects one.
     */
    data class ChooseColor(
        override val id: DecisionRequestId,
        val cardObjectId: ObjectId,
        val card: CardRef,
        val options: List<Color>,
    ) : SingleOptionSelection {
        override val optionCount: Int get() = options.size

        init {
            require(options.isNotEmpty()) { "CR 614.12: a colour choice offers at least one colour" }
        }
    }

    /**
     * The discard selection of an accepted optional "you may discard a card; if you do, draw N" clause
     * (CR 601.3b, CR 701.8): [seat] accepted the "may" and picks exactly [count] card(s) — Melded
     * Moxite's one — from their hand [options] by index (a [Decision.MultiSelect]). Additive, flagged
     * (P6.2a). A discarded card with madness is exiled instead (CR 702.35a), routing through the same
     * discard framework as every cost discard.
     *
     * @property options the hand cards that may be discarded, in hand order; indices stable (ADR-005).
     * @property count how many must be discarded.
     */
    data class ChooseOptionalDiscard(
        override val id: DecisionRequestId,
        val options: List<Option>,
        val count: Int,
    ) : SizedSelection {
        override val optionCount: Int get() = options.size
        override val requiredCount: Int get() = count

        init {
            require(count in 1..options.size) {
                "CR 701.8: discard count must be between 1 and hand size ${options.size}, was $count"
            }
        }

        /**
         * One hand card that may be discarded.
         *
         * @property objectId the hand object that would be discarded.
         * @property card its printed identity, for display.
         */
        data class Option(
            val objectId: ObjectId,
            val card: CardRef,
        )
    }

    /**
     * A "put one of these revealed cards into your hand, or none" choice (CR 701.16): [seat] revealed
     * cards from the top of their library and may keep up to one matching card (Malevolent Rumble's
     * permanent card). Answered with a [Decision.SingleSelect] whose index is one of [options] to keep
     * that card, or the extra "keep none" index ([options].size). Additive, flagged (P6.2a). Surfaced
     * only when at least one matching card was revealed; keeping none is always legal ("you may").
     *
     * @property options the revealed cards that may be put into the hand, in reveal (top-first) order;
     *   index `options.size` means "keep none".
     */
    data class ChooseFromRevealed(
        override val id: DecisionRequestId,
        val options: List<Option>,
    ) : ChoiceCountSelection {
        init {
            require(options.isNotEmpty()) {
                "CR 701.16: a keep-one choice is surfaced only when a matching card was revealed"
            }
        }

        /** How many selectable indices this request has: one per keepable card, plus the "keep none" index. */
        override val choiceCount: Int get() = options.size + 1

        /** The [Decision.SingleSelect] index meaning "keep none of the revealed cards". */
        val keepNoneIndex: Int get() = options.size

        /**
         * One revealed card that may be put into the hand.
         *
         * @property objectId the revealed library object that would move to the hand.
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
    ) : SingleOptionSelection {
        override val optionCount: Int get() = options.size

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

    /**
     * The mode choice of an optional cost-then-draw clause at spell resolution (CR 601.3b): [seat] may
     * decline, or choose one of [options] — the performable cost modes (Highway Robbery's discard-a-card or
     * sacrifice-a-land) — answering a [Decision.SingleSelect] whose index names a mode, or the extra decline
     * index ([options].size). Additive, flagged (P6.2c). Surfaced only when at least one mode is performable;
     * declining is always legal ("you may").
     *
     * @property prompt a short human description of the choice, for display (ADR-005).
     * @property options the performable cost modes, in the clause's printed order; index [options].size
     *   declines.
     */
    data class ChooseCostMode(
        override val id: DecisionRequestId,
        val prompt: String,
        val options: List<OptionalCostMode>,
    ) : ChoiceCountSelection {
        init {
            require(options.isNotEmpty()) {
                "CR 601.3b: a cost-mode choice is surfaced only when a mode is performable"
            }
        }

        /** How many selectable indices this request has: one per performable mode, plus the decline index. */
        override val choiceCount: Int get() = options.size + 1

        /** The [Decision.SingleSelect] index meaning "decline the optional cost" (CR 601.3b "may"). */
        val declineIndex: Int get() = options.size
    }

    /**
     * The cost-object selection of an accepted optional cost-then-draw mode (CR 601.3b): [seat] chose to
     * discard a card or sacrifice a land (Highway Robbery) and picks exactly one of [options] — their hand
     * cards, or their controlled lands — by index (a [Decision.MultiSelect]). Additive, flagged (P6.2c). A
     * discarded madness card is exiled instead (CR 702.35a), routing through the same discard framework as
     * every cost discard. Surfaced only when the chosen cost is performable, so a legal selection exists.
     *
     * @property options the objects that may pay the chosen cost, in zone order; indices stable (ADR-005).
     */
    data class ChooseOptionalCostObject(
        override val id: DecisionRequestId,
        val options: List<Option>,
    ) : SizedSelection {
        override val optionCount: Int get() = options.size
        override val requiredCount: Int get() = 1

        init {
            require(options.isNotEmpty()) {
                "CR 601.3b: a cost-object selection is surfaced only when the chosen cost is performable"
            }
        }

        /**
         * One object that may pay the chosen cost — a hand card to discard, or a controlled land to sacrifice.
         *
         * @property objectId the object that would be discarded or sacrificed.
         * @property card its printed identity, for display.
         */
        data class Option(
            val objectId: ObjectId,
            val card: CardRef,
        )
    }

    /**
     * The mandatory resolution-time discard of a "draw N, then discard M" spell (CR 601.2c, CR 701.8): [seat]
     * must discard exactly [count] cards from [options] — their hand — by index (a [Decision.MultiSelect]).
     * Additive, flagged (P6.2c). Faithless Looting's "then discard two cards". A discarded madness card is
     * exiled instead (CR 702.35a), routing through the same discard framework as the cleanup discard. Unlike
     * the cleanup discard this fires mid-resolution while the spell is on the stack; unlike the optional
     * discard-then-draw it is not optional and may remove more than one card.
     *
     * @property options one entry per card in [seat]'s hand, in hand order; indices stable (ADR-005).
     * @property count how many cards must be discarded — the clause's M, clamped to the current hand size.
     */
    data class ChooseResolutionDiscards(
        override val id: DecisionRequestId,
        val options: List<Option>,
        val count: Int,
    ) : SizedSelection {
        override val optionCount: Int get() = options.size
        override val requiredCount: Int get() = count

        init {
            require(count in 1..options.size) {
                "CR 601.2c: resolution discard count must be between 1 and hand size ${options.size}, was $count"
            }
        }

        /**
         * One hand card that must be discarded to the resolution.
         *
         * @property objectId the hand object that would be discarded.
         * @property card its printed identity, for display.
         */
        data class Option(
            val objectId: ObjectId,
            val card: CardRef,
        )
    }

    /**
     * A "put one of these library cards into your hand, or find none" choice of a library search (CR 701.18):
     * [seat] searched their library and may find up to one matching card (Ash Barrens' basic land card).
     * Answered with a [Decision.SingleSelect] whose index is one of [options] to find that card, or the extra
     * "find none" index ([options].size). Additive, flagged (P6.2c). Surfaced only when at least one matching
     * card is in the library; failing to find is always legal (a search of your own library, CR 701.18b). The
     * found card is revealed (public, [dev.mtgplay.core.event.GameEvent.CardsRevealed]); the library is
     * shuffled through the match PRNG afterwards (ADR-006).
     *
     * @property options the matching library cards that may be found, in library (top-first) order; index
     *   [options].size means "find none".
     */
    data class ChooseFromLibrary(
        override val id: DecisionRequestId,
        val options: List<Option>,
    ) : ChoiceCountSelection {
        init {
            require(options.isNotEmpty()) {
                "CR 701.18: a find-one choice is surfaced only when a matching card is in the library"
            }
        }

        /** How many selectable indices this request has: one per findable card, plus the "find none" index. */
        override val choiceCount: Int get() = options.size + 1

        /** The [Decision.SingleSelect] index meaning "find none of the matching cards" (CR 701.18b). */
        val findNoneIndex: Int get() = options.size

        /**
         * One library card that may be found and put into the hand.
         *
         * @property objectId the library object that would move to the hand.
         * @property card its printed identity, for display (a search reveals the found card, CR 701.18).
         */
        data class Option(
            val objectId: ObjectId,
            val card: CardRef,
        )
    }

    /**
     * The arrangement choice of a private library look (CR 701.14, CR 701.17): [seat] has looked at [pool]
     * — the top cards of their own library, or cards from their own hand — and picks one of [options], the
     * enumerated complete arrangements of that pool across the hand, the top of the library, and the
     * bottom of the library, by index (a [Decision.SingleSelect]). Additive, flagged
     * (`FW-LIBLOOK`, docs/design/library-look.md §5).
     *
     * **[pool] is private to [seat]** (CR 701.14a: a look is seen by its controller and no other player).
     * Every non-deciding seat receives [DecisionRequest]-less `Elsewhere` rather than this request, which
     * is the whole information-hiding mechanism — there is no redaction inside the request itself.
     *
     * The enumeration *is* the legality rule (ADR-005): a mandatory keep is expressed by enumerating no
     * arrangement with an empty hand, so no illegal decline exists as an index. Each option is a **total**
     * assignment — every pool index appears exactly once across the three lists — in a deterministic,
     * seed-independent order (docs/design/library-look.md §4.3). Always non-empty: even an empty pool
     * (a look at an empty library) has the one empty arrangement, and the engine surfaces it rather than
     * collapsing the decision (ADR-004).
     *
     * @property prompt a short human description of the clause, for display (ADR-005).
     * @property pool the looked-at cards, in pool order — top-first for a library look, hand order for a
     *   hand pool. [Option]'s index lists refer to positions in this list.
     * @property options the enumerated complete arrangements, in the deterministic order defined by
     *   docs/design/library-look.md §4.3; indices are stable within this request.
     */
    data class ChooseLibraryArrangement(
        override val id: DecisionRequestId,
        val prompt: String,
        val pool: List<PoolCard>,
        val options: List<Option>,
    ) : SingleOptionSelection {
        override val optionCount: Int get() = options.size

        init {
            require(options.isNotEmpty()) {
                "CR 701.14a: a look always has at least one legal arrangement (the empty one)"
            }
            require(options.all { it.isTotalOver(pool.size) }) {
                "CR 701.17a: every arrangement assigns each of the ${pool.size} looked-at card(s) exactly once"
            }
        }

        /**
         * One looked-at card.
         *
         * @property objectId the object in its source zone; it keeps this id unless the chosen arrangement
         *   moves it to another zone (CR 400.7).
         * @property card its printed identity — private to the deciding seat (CR 701.14a).
         */
        data class PoolCard(
            val objectId: ObjectId,
            val card: CardRef,
        )

        /**
         * One complete arrangement of the pool. Each list holds **indices into [pool]**, and together they
         * partition `0 until pool.size` exactly once.
         *
         * @property toHand the cards put into the deciding seat's hand, in the order they enter it
         *   (CR 400.7 — each becomes a new object unless it was already in the hand).
         * @property toTop the cards put on top of the library, **topmost first**.
         * @property toBottom the cards put on the bottom of the library, in placement order — the first
         *   ends up above the last, the convention [ChooseCardsToBottom] already documents (CR 103.5).
         */
        data class Option(
            val toHand: List<Int>,
            val toTop: List<Int>,
            val toBottom: List<Int>,
        ) {
            /** Whether this arrangement assigns each of [poolSize] pool indices exactly once (CR 701.17a). */
            fun isTotalOver(poolSize: Int): Boolean {
                val all = toHand + toTop + toBottom
                return all.size == poolSize && all.toSet() == (0 until poolSize).toSet()
            }
        }
    }

    /**
     * The "unless its controller pays [cost]" payment of a resolving counter (CR 701.5, CR 118.3a) — Force
     * Spike, Spell Pierce. [seat] is the **targeted spell's** controller, not the counter's: the one
     * request in the hierarchy whose decider is normally not the resolving object's controller. Additive,
     * flagged (`FW-COUNTER`, docs/design/countering-spells.md §7.1).
     *
     * **One fused request, not a yes/no followed by a payment request.** A separate yes/no would have to
     * offer "yes" to a player who cannot pay — an option that dead-ends mid-flow, which ADR-005 forbids
     * ("illegal actions are unrepresentable rather than rejected"). Fusing makes the option set exactly the
     * legal answers: [Option.Decline] at index 0, then one [Option.Pay] per enumerated payment plan. With
     * no affordable plan the request holds a single option and is still surfaced, per the
     * [ChoosePaymentPlan] precedent — a uniform decision sequence keeps replay logs canonical (ADR-004).
     *
     * A member of [SingleOptionSelection] and **not** of [ChoiceCountSelection] on purpose: declining is a
     * genuine enumerated answer with a game consequence (the spell is countered), not an opt-out from a
     * list, and it is typed as one of the options rather than left as a magic trailing index.
     *
     * Paying is not a cast and grants nobody priority (CR 605.3b permits the mana abilities, CR 605.3a
     * resolves them without the stack), so the counter's controller cannot respond to the answer.
     *
     * @property card the printed identity of the spell that would be countered, for display.
     * @property cost the mana [seat] must pay in full to save it (CR 118.3a).
     * @property options the legal answers: index 0 declines, the rest pay by a distinct plan.
     */
    data class ChooseCounterPayment(
        override val id: DecisionRequestId,
        val card: CardRef,
        val cost: ManaCost,
        val options: List<Option>,
    ) : SingleOptionSelection {
        override val optionCount: Int get() = options.size

        init {
            require(options.firstOrNull() == Option.Decline) {
                "CR 118.3a: declining is always legal and is index 0, got ${options.firstOrNull()}"
            }
            require(options.drop(1).all { it is Option.Pay }) {
                "CR 118.3a: every option after the decline pays a plan, got $options"
            }
        }

        /** One legal answer to "unless its controller pays". */
        sealed interface Option {
            /**
             * Do not pay (CR 118.3a): the targeted spell is countered (CR 701.5a). Always legal, always
             * index 0 — a player may decline even when they could pay.
             */
            data object Decline : Option

            /**
             * Pay the full cost by [plan] (CR 118.3a): the targeted spell is saved and the counter
             * resolves having done nothing.
             *
             * @property plan the payment plan, in the deterministic order of docs/design/mana-payment.md.
             */
            data class Pay(
                val plan: PaymentPlan,
            ) : Option
        }
    }
}
