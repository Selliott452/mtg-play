package dev.mtgplay.rules.decision

import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId

/**
 * One enumerated legal option inside a priority window (ADR-005): something the player holding
 * priority may do (CR 117.1).
 *
 * The hierarchy is sealed so drivers handle every kind exhaustively. P1.2 shipped [Pass]; P2.1
 * added [CastSpell] (CR 117.1a via the CR 601 pipeline); P2.2 adds [PlayLand] (the CR 116.2a
 * special action); later phases add the remaining action options — activating an ability
 * (CR 117.1c) — as new members, which is why a priority window carries a list of typed options
 * rather than a yes/no.
 */
sealed interface PriorityOption {
    /**
     * Decline to take an action, passing priority (CR 117.3d). Always legal for the player
     * holding priority, so it is present in every priority window's options.
     */
    data object Pass : PriorityOption

    /**
     * Begin casting the card [objectId] from [source] (CR 601.2). Enumerated only when the cast is
     * fully legal from this window (ADR-005): the timing class permits it (CR 117.1a), at least one
     * payment plan exists for its cost, any additional cost is satisfiable, and every required target
     * has at least one legal choice — so choosing this option never dead-ends.
     *
     * A normal cast has [source] `HAND` and a `null` [permission] (the printed cost applies). A
     * cast-from-elsewhere (flashback, escape; docs/decklists.md) names the [permission] it uses and its
     * source zone; the same card castable both from hand and from the graveyard is two distinct options,
     * which is what keeps enumeration complete in both directions (ADR-005). Madness is not enumerated
     * here — it is offered only as its reflexive trigger resolves (CR 702.35b).
     *
     * @property objectId the castable object in [source].
     * @property card the printed identity, for display; the object is reborn on the stack with a fresh
     *   id when the cast's pipeline runs (CR 400.7).
     * @property source the zone the cast draws the card from (CR 601.2a).
     * @property permission the alternative permission this cast uses, or `null` for a normal cast at the
     *   printed cost from the hand.
     */
    data class CastSpell(
        val objectId: ObjectId,
        val card: CardRef,
        val source: CastSource = CastSource.HAND,
        val permission: CastingPermission? = null,
    ) : PriorityOption

    /**
     * Play the land [objectId] from the deciding player's hand (CR 115.2a) — the CR 116.2a
     * special action, not a spell: it uses no stack and the player retains priority afterward
     * (CR 116.4). Enumerated only when the play is fully legal (ADR-005): the player's own
     * turn, a main phase, the stack empty (CR 305.1 via CR 116.2a), and the turn's land drop
     * still available (CR 305.2 — one land per turn).
     *
     * @property objectId the land object in the deciding player's hand.
     * @property card the printed identity, for display; the object is reborn on the
     *   battlefield with a fresh id when the action executes (CR 400.7).
     */
    data class PlayLand(
        val objectId: ObjectId,
        val card: CardRef,
    ) : PriorityOption

    /**
     * Plot the card [objectId] from the deciding player's hand (CR 702.140) — the CR 116.2g special
     * action: pay the card's plot cost and exile it face-up, keeping priority afterward (CR 116.4).
     * Additive, flagged (P6.2a). Choosing it opens a payment gathering for the plot cost (a
     * [ChoosePaymentPlan]); once paid, the card is exiled with a plotted-turn marker and may be cast
     * for free on a later turn. Enumerated only when fully legal (ADR-005): the player's own main
     * phase with the stack empty (sorcery timing), and the plot cost affordable.
     *
     * @property objectId the plottable object in the deciding player's hand.
     * @property card the printed identity, for display; the object is reborn in exile with a fresh id
     *   when the action executes (CR 400.7).
     */
    data class PlotCard(
        val objectId: ObjectId,
        val card: CardRef,
    ) : PriorityOption

    /**
     * Activate the [abilityIndex]th activated ability of the source [objectId] (CR 602.1, CR 117.1c).
     * Additive, flagged (P6.2a). Choosing it begins gathering the ability's cost (any discard selection,
     * then a payment plan); once the cost is paid the ability goes on the stack. Enumerated only when the
     * cost is fully payable right now (ADR-005) — so choosing it never dead-ends. The source may be a
     * battlefield permanent or (landcycling) a card in hand.
     *
     * @property objectId the source object whose ability is activated (in its ability's zone).
     * @property card the source's printed identity, for display.
     * @property abilityIndex which of the source definition's activated abilities this option activates.
     * @property scope the zone the ability functions from (CR 113.6) — battlefield or hand.
     */
    data class ActivateAbility(
        val objectId: ObjectId,
        val card: CardRef,
        val abilityIndex: Int,
        val scope: AbilityZoneScope,
    ) : PriorityOption

    /**
     * Activate the ninjutsu ability of the hand card [objectId], returning the unblocked attacker
     * [returnedAttacker] to its owner's hand as part of the cost (CR 702.49a, CR 602.1). Additive, flagged
     * (`FW-NINJUTSU`). Choosing it opens a payment gathering for the ninjutsu cost (a
     * [ChoosePaymentPlan]); once paid, the attacker is returned and the ability goes on the stack, putting
     * the ninja onto the battlefield tapped and attacking **when it resolves**.
     *
     * **Its own member rather than an [ActivateAbility] index**, because ninjutsu is not one of the
     * source's [dev.mtgplay.core.definition.CardDefinition.activatedAbilities] — it is synthesized by the
     * engine from a [dev.mtgplay.core.definition.Ninjutsu] declaration (CR 702.49a's reminder text is the
     * ability), so there is no index to name it by. It also carries a chosen cost object that no other
     * activation option carries.
     *
     * **The returned attacker is part of the option, not a later decision.** One option per (ninja,
     * unblocked attacker) pair is enumerated, so an agent picks the whole action by a single stable index
     * (ADR-005) — see `ninjutsuOptions` for why this choice is worth an option rather than a follow-up
     * request. Enumerated only when fully legal: blockers have been declared (CR 509.1h — before that no
     * attacker is unblocked and the ability cannot be activated at all), [returnedAttacker] is an
     * unblocked attacker its activator controls, and the ninjutsu cost is payable.
     *
     * @property objectId the ninja card in the deciding player's hand.
     * @property card the ninja's printed identity; CR 702.49a's cost reveals it, so naming it here leaks
     *   nothing the opponent will not see.
     * @property returnedAttacker the unblocked attacker the cost returns to its owner's hand.
     * @property returnedAttackerCard the returned attacker's printed identity, for display.
     */
    data class ActivateNinjutsu(
        val objectId: ObjectId,
        val card: CardRef,
        val returnedAttacker: ObjectId,
        val returnedAttackerCard: CardRef,
    ) : PriorityOption
}
