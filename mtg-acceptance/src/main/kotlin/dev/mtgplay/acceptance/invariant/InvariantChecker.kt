package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.zone.ZoneId
import dev.mtgplay.rules.engine.layeredCharacteristics
import dev.mtgplay.rules.engine.layeredToughness

/**
 * The correctness rig's first line of defence (PLAN.md §2.3): a pure function that inspects a
 * [GameState] and returns every [Invariant] it violates. The scripted-game driver runs it after
 * every transition and fails loudly on any non-empty result, so engine wrongness is caught the
 * instant it appears rather than surviving as silently-wrong-but-plausible state.
 *
 * **Structure for growth.** Each invariant is checked by its own small function of the minimal
 * data it needs — a residence list, or the state itself — so every check is independently
 * testable, including against corruption a real [GameState] cannot express (see below). Later
 * phases add invariants (mana-pool emptiness in Phase 2, battlefield statuses in Phase 3) by
 * adding a member to [Invariant] and a check here; existing checks are untouched.
 *
 * **Relationship to core construction invariants.** [Invariant.ZONE_CONSERVATION] and
 * [Invariant.ID_SANITY] overlap with guarantees `GameState`/`PlayerState` already enforce at
 * construction (id uniqueness, ids below the counter, non-negative counts) — a `GameState`
 * violating them cannot be built through the public constructor today. The checker re-derives
 * them independently anyway: it is the durable, phase-spanning guard for when the state model
 * grows (the stack gains spell objects in P2.1, the battlefield gains statuses in P3) and the
 * constructor's coverage no longer spans everything. Their check functions take the extracted
 * intermediate data precisely so their detection logic stays testable without a corrupt state.
 */
object InvariantChecker {
    /**
     * Checks the single-state invariants of [state]: zone conservation, id sanity, priority
     * uniqueness, and draw-failure honesty. Card conservation is *not* checked here — it is a
     * cross-state property with no meaning for a lone state; use the [expectedCards] overload.
     *
     * Returns every violation found (possibly none), in a deterministic order.
     */
    fun check(state: GameState): List<Violation> = check(state, expectedCards = null)

    /**
     * Checks every invariant of [state], including [Invariant.CARD_CONSERVATION] when
     * [expectedCards] is supplied: the baseline census the state's card multiset must still equal.
     * Pass the census of the game's first state to detect any card created or destroyed since.
     * A `null` [expectedCards] skips only the card-conservation check.
     *
     * Returns every violation found (possibly none), in a deterministic order.
     */
    fun check(
        state: GameState,
        expectedCards: CardCensus?,
    ): List<Violation> {
        val residences = ZoneResidence.of(state)
        return buildList {
            addAll(checkZoneConservation(residences))
            addAll(checkIdSanity(residences, state.nextObjectId, decisionCountsOf(state)))
            addAll(checkPriorityUniqueness(state))
            addAll(checkDrawFailureHonesty(state))
            addAll(checkManaPoolEmptiness(state))
            addAll(checkTapStatusScope(residences))
            addAll(checkLandDropBound(state))
            addAll(checkMarkedDamageScope(residences))
            addAll(checkCombatReferences(state))
            addAll(checkCreatureLethalityResolved(state))
            addAll(checkAttachmentIntegrity(state))
            addAll(checkTokenZoneScope(state))
            addAll(checkPendingTriggerSanity(state))
            addAll(checkMadnessMarkerSanity(state))
            addAll(checkMulliganPhaseSanity(state))
            addAll(checkP62aMarkerScopes(residences))
            addAll(checkEnteredTurnScope(residences, state.turn.number))
            addAll(checkAdventureMarkerScope(residences))
            addAll(checkPendingResolutionSanity(state))
            addAll(checkAbilityTargetSanity(state))
            addAll(checkTimedEffectSanity(state))
            addAll(checkCounterScope(residences))
            addAll(checkManaAbilityActivationScope(residences, state.definitions))
            // CR 602.5b / CR 502.2 (`FW-TAPUNTAP`): the sibling per-turn record and the doesn't-untap
            // marker, both battlefield-only state whose leak is silently unobservable.
            addAll(checkActivatedAbilityActivationScope(residences, state.definitions))
            addAll(checkSkipsNextUntapScope(residences))
            addAll(checkEntryTriggerDetection(state))
            addAll(checkExileAndReturnState(state, residences))
            addAll(checkNinjutsuCost(state))
            if (expectedCards != null) addAll(checkCardConservation(state, expectedCards))
        }
    }

    /**
     * [Invariant.LAND_DROP_BOUND]: the turn's land-drop count is 0 or 1 (CR 305.2 — nothing in
     * the MVP pool grants additional land plays). The lower bound is core-enforced at
     * construction; the checker re-derives both ends anyway, per its phase-spanning charter.
     */
    internal fun checkLandDropBound(state: GameState): List<Violation> {
        val count = state.turn.landsPlayedThisTurn
        return if (count in 0..1) {
            emptyList()
        } else {
            listOf(
                Violation(
                    Invariant.LAND_DROP_BOUND,
                    "CR 305.2: $count lands played this turn; the P2.x bound is one",
                ),
            )
        }
    }

    /**
     * [Invariant.MANA_POOL_EMPTY_AT_PAUSE]: every seat's mana pool is empty at an observed pause
     * (CR 500.4), **except** the declared triggered-mana-ability exception the invariant's KDoc promised:
     * a seat that controls a permanent *enchanted by* an Aura with a triggered mana ability (CR 605.1b —
     * Utopia Sprawl) may hold the extra mana that ability floats between the cast and the step's end. A seat
     * with floating mana and no such source is engine wrongness (the P2.x rule still bites for every other
     * deck).
     *
     * The exemption is keyed on the controller of the **enchanted permanent**, not of the Aura. A triggered
     * mana ability adds its mana when the enchanted permanent is tapped, and that mana goes to whoever
     * tapped it; `PaymentEnumeration.triggeredManaBonus` credits it exactly that way, gathering the Auras
     * attached to the tapped source. The two controllers differ whenever a Utopia Sprawl enchants an
     * opponent's Forest, which the card permits — it enchants *any* Forest.
     *
     * Keying on the Aura's controller therefore excused the wrong seat and reported the right one: 7,920
     * spurious violations over 2,000 GW Bogles mirror games. It never surfaced against Mono-Red Madness,
     * which plays no Forests, so no existing suite caught it.
     *
     * An Aura attached to nothing adds no mana and so grants no exemption.
     *
     * P8.3 (docs/design/mana-payment.md) **narrows** what actually floats without narrowing the
     * exemption: a payment plan may now spend a CR 605.1b bonus inside the cast that produced it, so
     * the bonus reaches a pause only when the plan genuinely had no use for it. The exemption stays
     * keyed on the same seats, because the same Auras are still the only things that can float mana.
     *
     * **`W8-B` adds a second, structurally different floater** and therefore a second exemption:
     * a triggered ability that adds mana without being a CR 605.1b mana ability at all
     * ([dev.mtgplay.core.definition.TriggeredAbility.addsMana] — Burning-Tree Emissary's "When this
     * creature enters, add {R}{G}"). Its mana arrives on the **stack**, in the priority window the
     * ability's resolution hands back, so floating it across a pause is not a side effect of the card,
     * it *is* the card. Nothing narrows it the way P8.3 narrowed the Aura bonus: no payment is in
     * progress to spend it.
     *
     * That exemption is keyed on **ownership in any zone**, not on the battlefield, which is a
     * deliberate widening rather than laziness. The Emissary can be killed in response to its own
     * trigger: the trigger still resolves (CR 603.3, it is independent of its source), the mana still
     * arrives, and the source is in a graveyard by the time the checker sees the pause. A
     * battlefield-keyed exemption would report that entirely correct game as engine wrongness — the
     * same shape of false positive the Aura fix above records, found before it could cost 7,920
     * violations rather than after.
     */
    internal fun checkManaPoolEmptiness(state: GameState): List<Violation> {
        val permanentsById = state.sharedZones.battlefield.associateBy { it.id }
        val seatsThatMayFloat =
            state.sharedZones.battlefield
                .filter { state.definitions[it.card]?.triggeredManaAbilities?.isNotEmpty() == true }
                .mapNotNull { aura -> aura.attachedTo?.let { permanentsById[it]?.owner } }
                .toSet() + seatsOwningAManaAddingTrigger(state)
        return state.players.entries
            .sortedBy { it.key.seat }
            .filter { it.value.manaPool.isNotEmpty() && it.key !in seatsThatMayFloat }
            .map { (seat, player) ->
                Violation(
                    Invariant.MANA_POOL_EMPTY_AT_PAUSE,
                    "CR 500.4: seat ${seat.seat}'s mana pool holds ${player.manaPool} at an observed pause",
                )
            }
    }

    /**
     * [Invariant.TAP_STATUS_SCOPE]: only battlefield objects may be tapped (CR 110.5). Operates
     * on the residence list so corrupt placements are directly testable.
     */
    internal fun checkTapStatusScope(residences: List<ZoneResidence>): List<Violation> =
        residences
            .filter { it.zone != ZoneId.Battlefield && it.obj.tapped }
            .map { residence ->
                Violation(
                    Invariant.TAP_STATUS_SCOPE,
                    "CR 110.5: object ${residence.obj.id.value} is tapped in ${residence.zone}, " +
                        "but tapped is a battlefield-only status",
                )
            }

    /**
     * [Invariant.ZONE_CONSERVATION]: no object id occupies more than one zone. Operates on a
     * residence list so it can be tested with duplicate residences a real [GameState] would reject
     * at construction.
     */
    internal fun checkZoneConservation(residences: List<ZoneResidence>): List<Violation> =
        residences
            .groupBy { it.obj.id }
            .filter { (_, occurrences) -> occurrences.size > 1 }
            .map { (id, occurrences) ->
                Violation(
                    Invariant.ZONE_CONSERVATION,
                    "CR 400.7: object ${id.value} occupies ${occurrences.size} zones at once: " +
                        occurrences.map { it.zone },
                )
            }

    /**
     * [Invariant.ID_SANITY]: every object id is strictly below [nextObjectId] (CR 400.7) and every
     * answered-decision count in [decisionCounts] is non-negative. Takes the extracted values so
     * both bounds can be tested with inputs a real [GameState] cannot hold.
     */
    internal fun checkIdSanity(
        residences: List<ZoneResidence>,
        nextObjectId: Long,
        decisionCounts: List<SeatDecisionCount>,
    ): List<Violation> =
        buildList {
            residences
                .filter { it.obj.id.value >= nextObjectId }
                .forEach { residence ->
                    add(
                        Violation(
                            Invariant.ID_SANITY,
                            "CR 400.7: object ${residence.obj.id.value} is not below " +
                                "the allocation counter $nextObjectId",
                        ),
                    )
                }
            decisionCounts
                .filter { it.count < 0 }
                .forEach { seatCount ->
                    add(
                        Violation(
                            Invariant.ID_SANITY,
                            "seat ${seatCount.seat} has a negative answered-decision count ${seatCount.count}",
                        ),
                    )
                }
        }

    /** [Invariant.PRIORITY]: at most one seat is [PriorityStatus.HOLDS_PRIORITY] (CR 117.1a). */
    internal fun checkPriorityUniqueness(state: GameState): List<Violation> {
        val holders =
            state.players.entries
                .filter { it.value.priorityStatus == PriorityStatus.HOLDS_PRIORITY }
                .map { it.key }
                .sortedBy { it.seat }
        return if (holders.size <= 1) {
            emptyList()
        } else {
            listOf(
                Violation(
                    Invariant.PRIORITY,
                    "CR 117.1a: ${holders.size} seats hold priority simultaneously: $holders",
                ),
            )
        }
    }

    /**
     * [Invariant.DRAW_FAILURE_HONESTY]: a set empty-library-draw flag implies an empty library
     * (CR 704.5c).
     */
    internal fun checkDrawFailureHonesty(state: GameState): List<Violation> =
        state.players.entries
            .sortedBy { it.key.seat }
            .filter { it.value.attemptedDrawFromEmptyLibrary && it.value.library.isNotEmpty() }
            .map { (seat, player) ->
                Violation(
                    Invariant.DRAW_FAILURE_HONESTY,
                    "CR 704.5c: seat ${seat.seat} recorded an empty-library draw but its " +
                        "library holds ${player.library.size} card(s)",
                )
            }

    /**
     * [Invariant.CARD_CONSERVATION]: [state]'s card multiset still equals the [expected] baseline.
     */
    internal fun checkCardConservation(
        state: GameState,
        expected: CardCensus,
    ): List<Violation> {
        val actual = CardCensus.of(state)
        return if (actual == expected) {
            emptyList()
        } else {
            listOf(
                Violation(
                    Invariant.CARD_CONSERVATION,
                    "card multiset changed from ${expected.counts} to ${actual.counts}",
                ),
            )
        }
    }
}

private fun decisionCountsOf(state: GameState): List<SeatDecisionCount> =
    state.players.entries
        .sortedBy { it.key.seat }
        .map { (seat, player) -> SeatDecisionCount(seat.seat, player.decisionsAnswered) }

/**
 * [Invariant.MARKED_DAMAGE_SCOPE]: marked damage is non-negative everywhere and zero off the
 * battlefield (CR 120.3d). Operates on the residence list so corrupt placements are directly
 * testable. The non-negativity re-derives a core construction guarantee, per the checker's
 * phase-spanning charter. Top-level (like [decisionCountsOf]) so the checker object stays small.
 */
internal fun checkMarkedDamageScope(residences: List<ZoneResidence>): List<Violation> =
    buildList {
        residences
            .filter { it.obj.damageMarked < 0 }
            .forEach {
                add(
                    Violation(
                        Invariant.MARKED_DAMAGE_SCOPE,
                        "CR 120.3: object ${it.obj.id.value} has negative marked damage ${it.obj.damageMarked}",
                    ),
                )
            }
        residences
            .filter { it.zone != ZoneId.Battlefield && it.obj.damageMarked != 0 }
            .forEach {
                add(
                    Violation(
                        Invariant.MARKED_DAMAGE_SCOPE,
                        "CR 120.3d: object ${it.obj.id.value} has ${it.obj.damageMarked} marked damage in " +
                            "${it.zone}, but marked damage is a battlefield-only status",
                    ),
                )
            }
        residences
            .filter { it.zone != ZoneId.Battlefield && it.obj.dealtDeathtouchDamage }
            .forEach {
                add(
                    Violation(
                        Invariant.MARKED_DAMAGE_SCOPE,
                        "CR 704.5h: object ${it.obj.id.value} records deathtouch damage in ${it.zone}, " +
                            "but that record describes marked damage and is battlefield-only",
                    ),
                )
            }
    }

/**
 * [Invariant.COMBAT_REFERENCES_VALID]: the combat state, if present, references only battlefield
 * objects and its blocker orders are permutations of the corresponding blocks (CR 508–509). A
 * no-op when no combat is in progress. Top-level so the checker object stays small.
 */
internal fun checkCombatReferences(state: GameState): List<Violation> {
    val combat = state.turn.combat ?: return emptyList()
    val battlefieldIds =
        state.sharedZones.battlefield
            .map { it.id }
            .toSet()
    val declaredAttackers = combat.attackers.map { it.attacker }.toSet()
    return buildList {
        combat.attackers
            .map { it.attacker }
            .filter { it !in battlefieldIds }
            .forEach {
                add(Violation(Invariant.COMBAT_REFERENCES_VALID, "CR 508.1: attacker $it is not on the battlefield"))
            }
        combat.blocks.orEmpty().forEach { block ->
            if (block.blocker !in battlefieldIds) {
                add(
                    Violation(
                        Invariant.COMBAT_REFERENCES_VALID,
                        "CR 509.1: blocker ${block.blocker} is not on the battlefield",
                    ),
                )
            }
            if (block.attacker !in declaredAttackers) {
                add(
                    Violation(
                        Invariant.COMBAT_REFERENCES_VALID,
                        "CR 509.1: block by ${block.blocker} names undeclared attacker ${block.attacker}",
                    ),
                )
            }
        }
        combat.blockerOrder.forEach { (attacker, order) ->
            val actual =
                combat.blocks
                    .orEmpty()
                    .filter { it.attacker == attacker }
                    .map { it.blocker }
            if (order.size != actual.size || order.toSet() != actual.toSet()) {
                val message = "CR 509.2: $attacker's order $order is not a permutation of its blockers $actual"
                add(Violation(Invariant.COMBAT_REFERENCES_VALID, message))
            }
        }
        // CR 509.1h / 702.19: a blocked attacker (not battlefield-scoped — a blocked attacker whose
        // blockers all died still sits in it) and any trample assignment name a declared attacker.
        combat.blockedAttackers.filter { it !in declaredAttackers }.forEach {
            add(Violation(Invariant.COMBAT_REFERENCES_VALID, "CR 509.1h: blocked attacker $it was not declared"))
        }
        combat.trampleAssignments.keys.filter { it !in combat.blockedAttackers }.forEach {
            add(
                Violation(
                    Invariant.COMBAT_REFERENCES_VALID,
                    "CR 702.19: trample assignment $it is not a blocked attacker",
                ),
            )
        }
    }
}

/**
 * Whether a player-loss state-based action (CR 704.5a: life 0 or less; CR 704.5c: an attempted draw
 * from an empty library) is applicable in [state] — the signal that the game is ending. A player
 * loss ends the game immediately (CR 104.2a), and the same batch's creature-death (CR 704.5f/g) and
 * Aura-fall-off (CR 704.5m) actions are then left unperformed (the loss is resolved first — see
 * `StateBasedActions.performBatch`). The two SBA-quiescence invariants below are therefore no-ops
 * while a loss is pending: their premise ("SBAs ran before the pause") does not hold for a game-over
 * state. The checker only ever observes paused or final states, and a pending loss ends the game
 * rather than yielding a decision point, so in practice this holds exactly of the final state.
 */
private fun aPlayerLossIsPending(state: GameState): Boolean =
    state.players.values.any { it.life <= 0 || it.attemptedDrawFromEmptyLibrary }

/**
 * [Invariant.CREATURE_LETHALITY_RESOLVED]: no battlefield creature has a met death condition at a
 * non-final pause (CR 704.5f/g/h) — toughness stays above 0, marked damage stays below it, and no
 * destructible creature carries a deathtouch record, because state-based actions run before any pause
 * (CR 704.3). Reads the **layered** toughness ([layeredToughness]) so an Aura-buffed creature is
 * measured at its in-game toughness (CR 613 sublayer 7c) — the single source of truth combat and the
 * death SBA also read (docs/design/layer-system.md §5). A no-op once a player loss is pending
 * ([aPlayerLossIsPending]): a lethal creature in the final game-over state is correct, not a failed
 * SBA. Top-level so the checker object stays small.
 *
 * **CR 702.12b is an exemption on the two *destruction* conditions and not on CR 704.5f**, and the
 * keyword-tail packet had to state it out loud because both destruction conditions became reachable on
 * an indestructible creature at once: Tamiyo's Safekeeping grants indestructible, and Toxin Analysis
 * grants deathtouch, so a creature that is flagged and correctly alive is now an ordinary board rather
 * than a corruption. Toughness 0 or less is not destruction (CR 704.5f) and indestructible never stops
 * it, so that branch keeps no exemption.
 *
 * The keyword re-read goes through the public [layeredCharacteristics] rather than the engine's own
 * `isIndestructible`, which keeps the checker's independence rule: it consults the layer engine's
 * published output, exactly as it already does for toughness, and shares none of the SBA's logic.
 */
internal fun checkCreatureLethalityResolved(state: GameState): List<Violation> {
    if (aPlayerLossIsPending(state)) return emptyList()
    return state.sharedZones.battlefield.mapNotNull { obj ->
        val characteristics = state.definitions[obj.card]?.characteristics ?: return@mapNotNull null
        if (CardType.CREATURE !in characteristics.cardTypes) return@mapNotNull null
        if (characteristics.powerToughness == null) return@mapNotNull null
        val toughness = layeredToughness(state, obj.id)
        // CR 702.12b: an indestructible permanent is not destroyed, so neither destruction condition
        // below applies to it.
        val destructible = Keyword.INDESTRUCTIBLE !in layeredCharacteristics(state, obj.id).keywords
        val condition =
            when {
                toughness <= 0 -> "CR 704.5f: toughness $toughness is 0 or less"
                obj.damageMarked >= toughness && destructible ->
                    "CR 704.5g: marked damage ${obj.damageMarked} is lethal to toughness $toughness"
                obj.dealtDeathtouchDamage && destructible ->
                    "CR 704.5h: it was dealt damage by a source with deathtouch"
                else -> return@mapNotNull null
            }
        Violation(
            Invariant.CREATURE_LETHALITY_RESOLVED,
            "object ${obj.id.value} (${obj.card.name}) should already have died — $condition",
        )
    }
}

/**
 * [Invariant.ATTACHMENT_INTEGRITY]: an Aura's [GameObject.attachedTo] is well-formed at an observed
 * pause. Precise tolerance (docs/design/layer-system.md §5): the checker only ever sees paused
 * states (decision points and final states), where state-based actions have run to quiescence
 * (CR 704.3), so it tolerates **no** dangling attachment — every non-null attachment names a
 * current battlefield object, and a stale reference would mean the CR 704.5m fall-off failed to
 * fire, exactly as a lingering lethal creature would mean CR 704.5g failed. Three properties:
 * attachment is a battlefield-only status (null off the battlefield, CR 400.7, like tapped); a
 * battlefield attachment names a battlefield object; and only an Aura (a permanent whose enchant
 * ability is a [TargetSpec.Enchantable]) carries one (CR 303.4). The transient mid-transition state
 * in which an attachment dangles between an enchanted creature's death and the next SBA check is
 * never observed. A no-op once a player loss is pending ([aPlayerLossIsPending]): a dangling Aura in
 * the final game-over state is correct — its fall-off was left moot when the game ended (CR 104.2a).
 * Top-level so the checker object stays small.
 */
internal fun checkAttachmentIntegrity(state: GameState): List<Violation> {
    if (aPlayerLossIsPending(state)) return emptyList()
    val residences = ZoneResidence.of(state)
    val battlefieldIds =
        state.sharedZones.battlefield
            .map { it.id }
            .toSet()
    return buildList {
        residences
            .filter { it.zone != ZoneId.Battlefield && it.obj.attachedTo != null }
            .forEach {
                add(
                    Violation(
                        Invariant.ATTACHMENT_INTEGRITY,
                        "CR 303.4: object ${it.obj.id.value} is attached in ${it.zone}, but attachment is a " +
                            "battlefield-only status",
                    ),
                )
            }
        state.sharedZones.battlefield.forEach { obj ->
            val attachedTo = obj.attachedTo ?: return@forEach
            if (attachedTo !in battlefieldIds) {
                add(
                    Violation(
                        Invariant.ATTACHMENT_INTEGRITY,
                        "CR 704.5m: battlefield object ${obj.id.value} is attached to ${attachedTo.value}, which is " +
                            "not on the battlefield; a dangling attachment should have fallen off before this pause",
                    ),
                )
            }
            if (!isAura(state, obj) && !isEquipmentPermanent(state, obj)) {
                add(
                    Violation(
                        Invariant.ATTACHMENT_INTEGRITY,
                        "CR 303.4 / CR 301.5: battlefield object ${obj.id.value} carries an attachment but is " +
                            "neither an Aura nor an Equipment",
                    ),
                )
            }
            // CR 301.5b: an Equipment is attached to a *creature* or to nothing. The Aura half of this
            // check is the enchant restriction, which an Equipment has no equivalent of — CR 301.5b gives
            // every Equipment the same host requirement, so it is asserted here directly.
            val equipmentOnNonCreature =
                isEquipmentPermanent(state, obj) &&
                    attachedTo in battlefieldIds &&
                    !isPrintedCreature(state, attachedTo)
            if (equipmentOnNonCreature) {
                add(
                    Violation(
                        Invariant.ATTACHMENT_INTEGRITY,
                        "CR 704.5n: Equipment ${obj.id.value} is attached to ${attachedTo.value}, which is not a " +
                            "creature; it should have become unattached before this pause",
                    ),
                )
            }
        }
    }
}

/**
 * [Invariant.TOKEN_ZONE_SCOPE]: no token sits in a zone other than the battlefield at an observed
 * pause (CR 704.5d). A token is `definitions[card] is TokenDefinition`. Because state-based actions
 * run to quiescence before any pause (CR 704.3), an off-battlefield token would mean the cessation
 * failed to fire — exactly as a lingering dangling Aura would mean CR 704.5m failed. A no-op once a
 * player loss is pending ([aPlayerLossIsPending]): the game-over batch leaves the cessation unperformed
 * alongside the deaths. Top-level so the checker object stays small.
 */
internal fun checkTokenZoneScope(state: GameState): List<Violation> {
    if (aPlayerLossIsPending(state)) return emptyList()
    val residences = ZoneResidence.of(state)
    return residences
        .filter { it.zone != ZoneId.Battlefield && state.definitions[it.obj.card] is TokenDefinition }
        .map {
            Violation(
                Invariant.TOKEN_ZONE_SCOPE,
                "CR 704.5d: token ${it.obj.id.value} (${it.obj.card.name}) is in ${it.zone}, but a token off " +
                    "the battlefield should have ceased to exist before this pause",
            )
        }
}

/**
 * [Invariant.PENDING_TRIGGER_SANITY]: every fired-but-unplaced trigger is self-contained (CR 603.3,
 * CR 603.10). Each pending trigger's controller must be a seated player; the source need not still
 * exist, since the trigger carries its source as last-known information (id, card, controller) by
 * value. Top-level so the checker object stays small.
 */
internal fun checkPendingTriggerSanity(state: GameState): List<Violation> =
    state.pendingTriggers
        .filter { it.controller !in state.players }
        .map {
            Violation(
                Invariant.PENDING_TRIGGER_SANITY,
                "CR 603.3d: pending trigger from ${it.sourceCard.name} names unseated controller ${it.controller}",
            )
        }

/**
 * [Invariant.MADNESS_MARKER_SANITY]: the madness marker is exile-only, a marked object always has its
 * reflexive machinery, and a pending-madness record always names a marked exile object (CR 702.35a–b).
 * Top-level so the checker object stays small.
 */
internal fun checkMadnessMarkerSanity(state: GameState): List<Violation> {
    val residences = ZoneResidence.of(state)
    val exileIds =
        state.sharedZones.exile
            .map { it.id }
            .toSet()

    fun hasReflexiveMachinery(exiledId: ObjectId): Boolean {
        val inQueue =
            state.pendingTriggers.any { it.subject == exiledId && it.ability.condition == TriggerCondition.MadnessCast }
        val onStack =
            state.sharedZones.stack.any { entry ->
                entry is StackEntry.Ability &&
                    entry.trigger.subject == exiledId &&
                    entry.trigger.ability.condition == TriggerCondition.MadnessCast
            }
        return inQueue || onStack || state.pendingMadness?.exiledObjectId == exiledId
    }

    return buildList {
        // The marker is an exile-only status.
        residences
            .filter { it.obj.awaitingMadness && it.zone != ZoneId.Exile }
            .forEach {
                add(
                    Violation(
                        Invariant.MADNESS_MARKER_SANITY,
                        "CR 702.35a: object ${it.obj.id.value} is madness-marked in ${it.zone}, but the marker is " +
                            "an exile-only status",
                    ),
                )
            }
        // A marked exile object has a matching reflexive trigger (pending or on-stack) or a yes/no.
        state.sharedZones.exile
            .filter { it.awaitingMadness && !hasReflexiveMachinery(it.id) }
            .forEach {
                add(
                    Violation(
                        Invariant.MADNESS_MARKER_SANITY,
                        "CR 702.35b: madness-marked exile object ${it.id.value} (${it.card.name}) has no pending or " +
                            "on-stack reflexive trigger and no pending yes/no — an orphaned marker",
                    ),
                )
            }
        // A pending-madness record names a marked exile object.
        val pending = state.pendingMadness
        if (pending != null && pending.exiledObjectId !in exileIds) {
            add(
                Violation(
                    Invariant.MADNESS_MARKER_SANITY,
                    "CR 702.35b: pending madness cast names ${pending.exiledObjectId.value}, which is not in exile",
                ),
            )
        }
    }
}
