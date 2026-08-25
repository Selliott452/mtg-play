package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.Ninjutsu
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.AttackerAssignment
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingNinjutsu
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.effect.returnPermanentToOwnersHand
import kotlinx.collections.immutable.persistentListOf

/*
 * Ninjutsu (CR 702.49) — the whole mechanic, in the four stages an activated ability has.
 *
 * CR 702.49a: "Ninjutsu is an activated ability that functions only while the card with ninjutsu is in a
 * player's hand. 'Ninjutsu [cost]' means '[cost], Reveal this card from your hand, Return an unblocked
 * attacking creature you control to its owner's hand: Put this card onto the battlefield from your hand
 * tapped and attacking.'"
 *
 * **It is an activated ability, not a special action**, and the engine models it as one. That correction
 * is load-bearing rather than pedantic: an activated ability *uses the stack* (CR 602.2, CR 113.3b), so
 * there is a window between activation and resolution in which the opponent may act. The Oracle rulings
 * name the consequence outright — "the Ninja isn't put onto the battlefield until the ability resolves;
 * if it leaves your hand before then, it won't enter the battlefield at all" — which is a state no
 * stackless special action (CR 116.2, a land play or a plot) can ever reach. Modelling ninjutsu on
 * [PlayLand]/[executePlot] would have deleted that window silently, which is exactly the approximation
 * CONVENTIONS.md forbids.
 *
 * What it borrows from the special actions is only the *gathering* shape: like the plot action it opens a
 * [PendingNinjutsu] and pauses for a payment plan, because the enumerated option already fixes every other
 * choice (ADR-005).
 *
 * The four stages:
 *  1. [ninjutsuOptions]        — enumeration (CR 602.5a timing + the cost's own feasibility);
 *  2. [beginNinjutsu]          — open the payment gathering;
 *  3. [executeNinjutsu]        — pay the cost (CR 602.2b) and put the ability on the stack;
 *  4. [ninjutsuResolution]     — the effect that runs when it resolves (CR 608.2).
 *
 * The ability itself is *synthesized* here rather than declared by the card, exactly as madness
 * synthesizes its CR 702.35b reflexive trigger: the card declares only its [Ninjutsu] cost, and the whole
 * CR 702.49a ability text is the engine's.
 */

/** The ninjutsu ability of [card]'s definition (CR 702.49), or `null` if it has none. */
internal fun ninjutsuOf(
    state: GameState,
    card: CardRef,
): Ninjutsu? = state.definitions[card]?.ninjutsu

/**
 * The attackers [seat] controls that are **unblocked** (CR 509.1h) — the creatures ninjutsu's cost may
 * return, and the whole reason the ability has a window rather than being available all combat.
 *
 * Empty until blockers have been declared, and that emptiness is the rule rather than an optimisation.
 * CR 509.1h assigns "blocked" or "unblocked" to each attacker as the declare-blockers turn-based action
 * completes; **before** that an attacker is neither, so there is nothing for the cost to name. The Oracle
 * ruling states it directly: "the ninjutsu ability can be activated only after blockers have been
 * declared. Before then, attacking creatures are neither blocked nor unblocked." [CombatState.blocks]
 * being non-null *is* that moment (its KDoc: a non-null empty list means blockers were declared and none
 * were chosen, distinct from `null`), so this reads the combat state rather than the turn step — an
 * attacker stays unblocked through the combat-damage and end-of-combat steps, and the ruling confirms
 * ninjutsu is activatable in all three.
 *
 * "You control" is ownership in the MVP pool (docs/design/layer-system.md §4), matching every other
 * control read in combat.
 */
internal fun unblockedAttackersOf(
    state: GameState,
    seat: PlayerId,
): List<AttackerAssignment> {
    val combat = state.turn.combat
    // CR 509.1h: blocked/unblocked does not exist until the declare-blockers action has completed, so a
    // combat that has not engaged, or has engaged but not yet declared blockers, offers nothing.
    if (combat?.blocks == null) return emptyList()
    return combat.attackers.filter { assignment ->
        assignment.attacker !in combat.blockedAttackers &&
            state.sharedZones.battlefield.any { it.id == assignment.attacker && it.owner == seat }
    }
}

/**
 * The ninjutsu options for [seat] (CR 702.49a, CR 602.5a): one [PriorityOption.ActivateNinjutsu] per
 * (ninja in hand, unblocked attacker) pair that is legal to activate right now (ADR-005).
 *
 * **The pair is enumerated rather than gathered**, which is the one design choice here worth stating.
 * "Return an unblocked attacking creature you control" is a cost with a *choice* in it, and the engine's
 * other chosen-object costs ([AbilityCost.DiscardACard], [AbilityCost.Sacrifice]) surface that choice as a
 * follow-up decision. Ninjutsu does not, because *which* attacker is returned changes the resulting board
 * far more than which land is sacrificed does — it decides which creature survives to be replayed and,
 * via CR 702.49d, which player the ninja ends up attacking — so making it a distinct enumerated option
 * puts the whole action in one index an agent can learn against (ADR-005) instead of behind a second
 * request. The pair count is bounded by hand ninjas times unblocked attackers, which is small.
 *
 * Every gate that must hold is checked here, so choosing an option never dead-ends:
 * - the card declares ninjutsu, and is a **creature** card (CR 702.49a puts it onto the battlefield
 *   attacking, and only a creature can attack — a non-creature declaring ninjutsu is a definition defect
 *   and fails loudly rather than being skipped);
 * - an unblocked attacker exists ([unblockedAttackersOf] — which also enforces the after-blockers window);
 * - the ninjutsu cost has at least one payment plan (CR 602.2g).
 *
 * Timing is CR 602.5a's default — an activated ability may be activated whenever its controller has
 * priority — so there is no step test beyond the one the cost itself imposes. That is why this needs no
 * `TurnStep` reasoning: the window falls out of CR 509.1h, which is where the CR puts it.
 */
internal fun ninjutsuOptions(
    state: GameState,
    seat: PlayerId,
): List<PriorityOption.ActivateNinjutsu> {
    val attackers = unblockedAttackersOf(state, seat)
    if (attackers.isEmpty()) return emptyList()
    return state.player(seat).hand.flatMap { ninja ->
        val ninjutsu = ninjutsuOf(state, ninja.card) ?: return@flatMap emptyList()
        require(isNinjutsuCreatureCard(state, ninja.card)) {
            "CR 702.49a: ${ninja.card.name} declares ninjutsu but is not a creature card; it could not " +
                "enter the battlefield attacking"
        }
        if (enumeratePaymentPlans(state, seat, ninjutsu.cost).isEmpty()) {
            return@flatMap emptyList()
        }
        attackers.map { assignment ->
            PriorityOption.ActivateNinjutsu(
                objectId = ninja.id,
                card = ninja.card,
                returnedAttacker = assignment.attacker,
                returnedAttackerCard = state.battlefieldObject(assignment.attacker).card,
            )
        }
    }
}

/** Whether [card]'s definition prints the creature type CR 702.49a's effect requires (CR 205.2). */
private fun isNinjutsuCreatureCard(
    state: GameState,
    card: CardRef,
): Boolean =
    CardType.CREATURE in
        state.definitions[card]
            ?.characteristics
            ?.cardTypes
            .orEmpty()

/**
 * Begins a ninjutsu activation for [seat]'s hand card [ninjaObjectId], returning [returnedAttacker]
 * (CR 702.49a): opens a [PendingNinjutsu] and suspends for the cost's payment plan. Legality was checked
 * at enumeration (ADR-005); nothing has moved and the card is still in hand.
 */
internal fun beginNinjutsu(
    state: GameState,
    seat: PlayerId,
    ninjaObjectId: ObjectId,
    returnedAttacker: ObjectId,
): AdvanceResult {
    val gathering = state.copy(pendingNinjutsu = PendingNinjutsu(seat, ninjaObjectId, returnedAttacker))
    return AdvanceResult.NeedsDecision(gathering, pendingNinjutsuRequest(gathering))
}

/**
 * The payment request the open [GameState.pendingNinjutsu] is waiting on (CR 702.49a, CR 602.2g): the
 * activating player chooses how to pay the ninjutsu cost. A pure function of the state (ADR-004).
 */
internal fun pendingNinjutsuRequest(state: GameState): DecisionRequest.ChoosePaymentPlan {
    val pending = state.pendingNinjutsu ?: error("no ninjutsu ability is gathering a payment")
    val ninja =
        state.player(pending.activator).hand.firstOrNull { it.id == pending.ninjaObjectId }
            ?: error("CR 702.49a: the ninja ${pending.ninjaObjectId} is not in ${pending.activator}'s hand")
    val ninjutsu =
        ninjutsuOf(state, ninja.card) ?: error("CR 702.49a: ${ninja.card.name} has no ninjutsu ability")
    return DecisionRequest.ChoosePaymentPlan(
        id = DecisionRequestId(pending.activator, state.player(pending.activator).decisionsAnswered),
        cardObjectId = pending.ninjaObjectId,
        card = ninja.card,
        // CR 702.49a: the ninjutsu cost is the ability's cost, not a spell's, so CR 601.2f never runs over
        // it and no spell cost reduction applies (docs/design/cost-modification.md §12).
        cost = ninjutsu.cost,
        options = enumeratePaymentPlans(state, pending.activator, ninjutsu.cost),
    )
}

/**
 * Executes the ninjutsu activation with the chosen [plan] (CR 702.49a, CR 602.2b): pays the whole
 * composite cost — the mana, the reveal, and the return of the unblocked attacker to its owner's hand —
 * and puts the CR 702.49a activated ability on the stack, leaving the activator holding priority
 * (CR 117.3c).
 *
 * **The card does not move here.** It stays in the activator's hand while the ability waits on the stack,
 * because CR 702.49a's *effect* — "put this card onto the battlefield" — happens on resolution
 * ([ninjutsuResolution]), not on activation. Everything in this function is cost payment.
 *
 * **The defending player is captured now, and must be.** CR 702.49d puts the ninja onto the battlefield
 * attacking *the same player the returned creature was attacking*; by the time the ability resolves that
 * creature has been in its owner's hand for a whole priority round and is no longer readable from combat
 * (CR 506.4 removed it). So the value is captured here, as the cost is paid — the same "cost-payment
 * results are linked information" rule that puts a sacrificed permanent's mana value on a cast record.
 */
internal fun executeNinjutsu(
    state: GameState,
    plan: PaymentPlan,
): AdvanceResult {
    val pending = state.pendingNinjutsu ?: error("no ninjutsu ability is gathering a payment")
    val cleared = state.copy(pendingNinjutsu = null)
    val ninja =
        cleared.player(pending.activator).hand.firstOrNull { it.id == pending.ninjaObjectId }
            ?: error("CR 702.49a: the ninja ${pending.ninjaObjectId} is not in ${pending.activator}'s hand")
    val ninjutsu =
        ninjutsuOf(cleared, ninja.card) ?: error("CR 702.49a: ${ninja.card.name} has no ninjutsu ability")
    val combat = cleared.turn.combat ?: error("CR 702.49a: ninjutsu is activated during combat")
    // CR 702.49d: read before the cost removes the attacker from combat.
    val defendingPlayer =
        combat.attackers
            .firstOrNull { it.attacker == pending.returnedAttacker }
            ?.defendingPlayer
            ?: error("CR 702.49a: ${pending.returnedAttacker} is not a declared attacker")
    val returnedCard = cleared.battlefieldObject(pending.returnedAttacker).card
    val paid = payManaPlan(cleared, pending.activator, ninjutsu.cost, plan)
    // CR 702.49a: "Return an unblocked attacking creature you control to its owner's hand" — a cost, so it
    // happens now and stays done even if the ability is later countered (CR 701.5a). CR 506.4's removal
    // from combat rides along inside the primitive.
    val returned = returnPermanentToOwnersHand(paid, pending.returnedAttacker)
    val entry =
        StackEntry.ActivatedAbilityOnStack(
            sourceId = pending.ninjaObjectId,
            sourceCard = ninja.card,
            controller = pending.activator,
            ability = ninjutsuAbility(ninjutsu, defendingPlayer),
        )
    val onStack =
        returned
            .updateStack { it.adding(entry) }
            // CR 702.49a's "Reveal this card from your hand" is why this names the ninja publicly: both
            // seats learn what is coming while the ability can still be responded to.
            .emit(
                GameEvent.NinjutsuActivated(
                    player = pending.activator,
                    ninjaObjectId = pending.ninjaObjectId,
                    card = ninja.card,
                    returnedAttacker = pending.returnedAttacker,
                    returnedAttackerCard = returnedCard,
                ),
            ).emit(GameEvent.AbilityActivated(pending.activator, pending.ninjaObjectId, ninja.card))
    // CR 602.2c / CR 117.3c: the activator keeps priority; pass-flags reset (an action was taken).
    return priorityTo(clearPriorityRound(onStack), pending.activator)
}

/**
 * The CR 702.49a activated ability itself, synthesized for a card that declares [ninjutsu] — cost, zone
 * scope, and effect, as the reminder text spells them out. Built here rather than declared on the card
 * for madness's reason (P5.2): the whole ability text is the *mechanic's*, identical on every ninja, so a
 * card that reprinted it could only get it wrong.
 *
 * [AbilityCost.Mana] is the only component listed, and that is deliberate rather than lossy: the reveal
 * and the return are performed by [executeNinjutsu] as it pays, and the cost list on a stack entry is a
 * record, never re-paid. Expressing the return as an [AbilityCost] member would require the generic
 * activation pipeline to know about combat, which is the coupling this separate path exists to avoid.
 *
 * @param defendingPlayer the player the returned attacker was attacking, captured at activation — the
 *   player the ninja will enter attacking (CR 702.49d).
 */
private fun ninjutsuAbility(
    ninjutsu: Ninjutsu,
    defendingPlayer: PlayerId,
): ActivatedAbility =
    ActivatedAbility(
        cost = persistentListOf(AbilityCost.Mana(ninjutsu.cost)),
        effect =
            ResolutionEffect { state, context ->
                ninjutsuResolution(state, context.source, context.controller, defendingPlayer)
            },
        // CR 702.49a: "functions only while the card with ninjutsu is in a player's hand".
        zoneScope = AbilityZoneScope.Hand,
    )

/**
 * Resolves the ninjutsu ability (CR 702.49a): puts the ninja onto the battlefield from its owner's hand
 * **tapped and attacking** [defendingPlayer].
 *
 * Three properties this has to get right, each of which the CR states and none of which follows from the
 * others:
 *
 * 1. **It may find nothing, and then it does nothing.** The card has been sitting in a hand through a
 *    priority round; anything that moved it (a discard, an opponent's hand attack) leaves the ability with
 *    no card to put anywhere. The Oracle ruling is explicit — "if it leaves your hand before then, it
 *    won't enter the battlefield at all" — so this returns the state untouched rather than failing. It is
 *    the one branch that only exists because ninjutsu uses the stack.
 * 2. **It enters attacking without having been declared** (CR 508.1). The new object is appended to
 *    [CombatState.attackers], which is what "attacking" *is* in this engine — but nothing else about the
 *    declare-attackers turn-based action runs. So no attack restriction or requirement was ever applied to
 *    it (a ninja with defender would still arrive attacking), it is not added to
 *    [CombatState.blockedAttackers] (it arrives unblocked, and blockers are long since declared, so it
 *    stays that way — which is why a second ninjutsu can return *it* next combat step), and **no
 *    "whenever this creature attacks" ability triggers**, because no creature was declared as an attacker.
 *    That last one is the trap the ruling calls out by name, and it is avoided structurally: those
 *    triggers fire from the declare-attackers transition, which this path never touches.
 * 3. **Its entry is an ordinary battlefield entry.** It goes through [announceBattlefieldEntry] — the one
 *    home every entry path shares — so its CR 603.6a enters-the-battlefield triggers fire like any other
 *    permanent's. Adding a private entry path here is precisely the T18 failure that funnel exists to
 *    prevent, and a ninja *with* an ETB trigger is a card the format prints.
 *
 * The creature enters tapped (CR 702.49a) and summoning sick (CR 302.6, the [GameObject] default) — the
 * sickness is real but inert, because it is already attacking and will not be asked to attack or tap.
 */
private fun ninjutsuResolution(
    state: GameState,
    ninjaObjectId: ObjectId?,
    controller: PlayerId,
    defendingPlayer: PlayerId,
): GameState {
    val handId = ninjaObjectId ?: error("CR 702.49a: a resolving ninjutsu ability names the ninja card")
    val hand = state.player(controller).hand
    val index = hand.indexOfFirst { it.id == handId }
    // CR 702.49a: the card is put onto the battlefield *from your hand*; gone from there, nothing enters.
    if (index < 0) return state
    val ninja = hand[index]
    val combat =
        state.turn.combat
            ?: error("CR 702.49a: a ninjutsu ability resolves during the combat phase it was activated in")
    val (battlefieldId, allocated) = state.allocateObjectId()
    // CR 702.49a: tapped. CR 400.7: a new object with no memory of its hand residence.
    val entering = GameObject(id = battlefieldId, card = ninja.card, owner = ninja.owner, tapped = true)
    val arrived =
        allocated
            .updatePlayer(controller) { it.copy(hand = it.hand.removingAt(index)) }
            .updateBattlefield { it.adding(entering) }
            // CR 702.49a/d: attacking, and attacking whoever the returned creature was attacking. Appended
            // to the declared attackers *without* any of CR 508.1's declaration machinery.
            .updateCombat {
                it.copy(attackers = it.attackers.adding(AttackerAssignment(battlefieldId, defendingPlayer)))
            }
    check(arrived.turn.combat != null) { "CR 702.49a: the ninja must join an engaged combat, had $combat" }
    // CR 603.6a: narrating the entry and firing the ninja's own enters-the-battlefield triggers are one
    // indivisible step — the single funnel every entry path shares.
    return announceBattlefieldEntry(
        arrived,
        battlefieldId,
        GameEvent.NinjaEnteredAttacking(controller, battlefieldId, ninja.card, defendingPlayer),
    )
}
