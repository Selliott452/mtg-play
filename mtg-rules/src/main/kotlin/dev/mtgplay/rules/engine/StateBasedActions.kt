package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.cardObject
import dev.mtgplay.rules.MatchResult
import dev.mtgplay.rules.effect.unattachPermanent

/**
 * One applicable state-based action (CR 704.5): something the game itself does the moment its
 * condition holds, checked whenever a player would receive priority (CR 704.3).
 *
 * Sealed so the performer handles every kind exhaustively; later phases add members next to the
 * checks that detect them, without reshaping the check-and-repeat loop. CR 704.5n — the Equipment
 * unattach analogue, which this KDoc recorded as absent for six packets — arrived with `FW-EQUIP` as
 * [EquipmentUnattaches], and is the sharpest illustration of why the hierarchy is a sum rather than a
 * flag: it fires on the *same* condition as [AuraFallsOff] and does the opposite thing.
 */
internal sealed interface StateBasedAction {
    /**
     * [player] loses the game for [reason]: life 0 or less (CR 704.5a) or an attempted draw
     * from an empty library since the last check (CR 704.5c).
     */
    data class PlayerLoses(
        val player: PlayerId,
        val reason: LossReason,
    ) : StateBasedAction

    /**
     * The creature [objectId] dies (CR 704.5f/g/h): it is put from the battlefield into its owner's
     * graveyard. [cause] distinguishes destruction by damage — whether lethal marked damage
     * (CR 704.5g) or any damage from a deathtouch source (CR 704.5h) — from zero-or-less toughness
     * (CR 704.5f), which is not destruction; see [CreatureDeathCause]. Added in P3.2, widened by the
     * keyword-tail packet.
     */
    data class CreatureDies(
        val objectId: ObjectId,
        val cause: CreatureDeathCause,
    ) : StateBasedAction

    /**
     * The Aura [objectId] is put into its owner's graveyard because it is attached to an illegal
     * object or to nothing (CR 704.5m). Added in P4.1. It composes with the fresh-id rebirth rule:
     * when an enchanted creature dies it is reborn in the graveyard with a new id (CR 400.7), the
     * Aura's [dev.mtgplay.core.state.GameObject.attachedTo] then names no battlefield object, and
     * this fires on the following check.
     */
    data class AuraFallsOff(
        val objectId: ObjectId,
    ) : StateBasedAction

    /**
     * The permanent with bestow [objectId] becomes **unattached** because it is attached to an illegal
     * object (CR 702.103c). Added by `W10-C`.
     *
     * **The third outcome on the one condition**, and the only one that changes what the permanent *is*.
     * A dangling Aura goes to a graveyard ([AuraFallsOff], CR 704.5m); a dangling Equipment lets go and
     * waits to be equipped again ([EquipmentUnattaches], CR 704.5n); a dangling bestowed permanent lets
     * go **and becomes a creature** — not here, but in the same instant, because the type-changing
     * static ability that made it an Aura is conditioned on being attached to a creature and stops
     * applying the moment this action lands (CR 604.3).
     *
     * That is why it is its own member rather than a reuse of [EquipmentUnattaches]. The two perform the
     * same *mechanical* step and answer to different rules with different consequences, and merging them
     * would make the engine's own log unable to say which rule acted. Reachable by three routes, all of
     * them ordinary play: the enchanted creature dies, it stops being a creature, or it gains protection
     * from the bestowed permanent (CR 702.16c).
     */
    data class BestowedPermanentUnattaches(
        val objectId: ObjectId,
    ) : StateBasedAction

    /**
     * The Equipment [objectId] becomes **unattached** because it is attached to an illegal permanent or
     * to nothing (CR 704.5n). Added by `FW-EQUIP`.
     *
     * **The opposite outcome to [AuraFallsOff], and deliberately its own member rather than a widening
     * of it.** Both fire on the same condition — a dangling attachment — and they do opposite things: a
     * dangling Aura is put into its owner's graveyard (CR 704.5m) and a dangling Equipment lets go and
     * *stays on the battlefield*, ready to be equipped onto something else. That is the whole reason a
     * deck plays an Equipment rather than an Aura, so collapsing the two would delete the difference
     * between the two card types while still passing every dangling-attachment test.
     *
     * Reachable the same way its sibling is: the equipped creature dies, is reborn in the graveyard with
     * a new id (CR 400.7), the Equipment's [dev.mtgplay.core.state.GameObject.attachedTo] then names no
     * battlefield object, and this fires on the following check.
     */
    data class EquipmentUnattaches(
        val objectId: ObjectId,
    ) : StateBasedAction

    /**
     * The token [objectId] ceases to exist because it is in a zone other than the battlefield
     * (CR 704.5d). Added in P5.1. A token creature put into a graveyard by a death (CR 704.5f/g) is
     * there for only the moment between two checks: this fires on the following check, removing it
     * from the graveyard entirely. "This object is a token" is `definitions[card] is TokenDefinition`
     * — stable across the CR 400.7 rebirth into the graveyard, so the death and this cessation land in
     * separate batches and never contend for the same object.
     */
    data class TokenCeasesToExist(
        val objectId: ObjectId,
    ) : StateBasedAction

    /**
     * [amount] `+1/+1` counters and [amount] `-1/-1` counters are removed from the permanent
     * [objectId] because it has both (CR 704.5q), where [amount] is the smaller of the two counts.
     * Added by `FW-COUNTERS`.
     *
     * Applies to any **permanent**, not only a creature — CR 704.5q says "permanent" — though only a
     * creature can carry both kinds in the gauntlet pool today.
     */
    data class CountersAnnihilate(
        val objectId: ObjectId,
        val amount: Int,
    ) : StateBasedAction
}

/**
 * The CR 704.5q counter-annihilation actions applicable to [state], in battlefield order: for each
 * permanent carrying both `+1/+1` and `-1/-1` counters, one action removing N of each, N being the
 * smaller count.
 *
 * **Only that exact pair annihilates.** A `-0/-1` counter (Wall of Roots' cost) is a different kind
 * under CR 122.1a and CR 704.5q does not name it, so a creature with `+1/+1` and `-0/-1` counters
 * keeps both forever — which is the printed rule, not an omission.
 */
private fun counterAnnihilationActions(state: GameState): List<StateBasedAction> =
    state.sharedZones.battlefield.mapNotNull { obj ->
        val plus = obj.counterCount(Counter.PLUS_ONE_PLUS_ONE)
        val minus = obj.counterCount(Counter.MINUS_ONE_MINUS_ONE)
        val pairs = minOf(plus, minus)
        if (pairs > 0) StateBasedAction.CountersAnnihilate(obj.id, pairs) else null
    }

/**
 * The creature-death state-based actions applicable to [state] (CR 704.5f, CR 704.5g, CR 704.5h), in
 * battlefield order — split out of [applicableStateBasedActions] so each check reads as one rule.
 *
 * The three are ordered by the CR's own precedence: CR 704.5f (toughness 0 or less) is not destruction
 * and applies first; the two destruction actions apply only to a creature with toughness greater than
 * 0, and each produces the same [CreatureDeathCause.LETHAL_DAMAGE] outcome because each is a
 * destruction by damage — a distinction between them would be a distinction nothing in the rules reads.
 */
private fun creatureDeathActions(state: GameState): List<StateBasedAction> =
    state.sharedZones.battlefield.mapNotNull { obj ->
        if (!isCreature(state, obj)) {
            null
        } else {
            val toughness = effectiveToughness(state, obj.id)
            // CR 702.12b: an indestructible permanent is not destroyed, which exempts it from both
            // destruction actions below. The exemption is deliberately confined to them: CR 704.5f is
            // not destruction (see [CreatureDeathCause]), and indestructible never stops it.
            val destructible = !isIndestructible(state, obj.id)
            when {
                // CR 704.5f: toughness 0 or less — graveyard, and it takes precedence over
                // CR 704.5g/h, which only ever apply to a creature with toughness greater than 0.
                toughness <= 0 ->
                    StateBasedAction.CreatureDies(obj.id, CreatureDeathCause.ZERO_OR_LESS_TOUGHNESS)
                // CR 704.5g: toughness greater than 0 and marked damage at least equal to it —
                // lethal damage, destroyed. (Marked damage is then necessarily positive.)
                obj.damageMarked >= toughness && destructible ->
                    StateBasedAction.CreatureDies(obj.id, CreatureDeathCause.LETHAL_DAMAGE)
                // CR 704.5h: toughness greater than 0 and damage dealt by a source with deathtouch —
                // destroyed, *whatever* the amount. This is a genuinely separate action from
                // CR 704.5g, not a lower threshold for it: one damage from a deathtoucher destroys a
                // 5/5 that is four short of its lethal total, so no arithmetic on the marked-damage
                // total above could ever express it. The condition is the fact recorded when the
                // damage was marked (CR 702.2b), because the source may be gone by now (CR 113.7a).
                obj.dealtDeathtouchDamage && destructible ->
                    StateBasedAction.CreatureDies(obj.id, CreatureDeathCause.LETHAL_DAMAGE)
                else -> null
            }
        }
    }

/**
 * All state-based actions applicable to [state] right now (CR 704.5), in deterministic order:
 * player losses first, in seat order, then creature deaths in battlefield order, then Aura
 * fall-offs in battlefield order, then token cessations, then CR 704.5q counter annihilations.
 * Later phases append checks here.
 */
internal fun applicableStateBasedActions(state: GameState): List<StateBasedAction> =
    buildList {
        for ((seat, playerState) in state.players) {
            if (playerState.life <= 0) {
                // CR 704.5a: a player with 0 or less life loses the game.
                add(StateBasedAction.PlayerLoses(seat, LossReason.LIFE_TOTAL_ZERO_OR_LESS))
            }
            if (playerState.attemptedDrawFromEmptyLibrary) {
                // CR 704.5c: a player who attempted to draw from an empty library since the
                // last check loses the game. Acts on the recorded attempt, not on emptiness.
                add(StateBasedAction.PlayerLoses(seat, LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY))
            }
        }
        addAll(creatureDeathActions(state))
        addAll(danglingAttachmentActions(state))
        for (obj in tokensOffBattlefield(state)) {
            // CR 704.5d: a token in any zone other than the battlefield ceases to exist.
            add(StateBasedAction.TokenCeasesToExist(obj.id))
        }
        addAll(counterAnnihilationActions(state))
    }

/**
 * Whether the game is already over on [state] (CR 104.2a): the engine is at a would-receive-priority
 * checkpoint (no player holds priority) where a player-loss state-based action (CR 704.5a/c) applies —
 * the exact point [performStateBasedActions] ends the game, since [priorityTo] runs the loop *before*
 * marking any seat [PriorityStatus.HOLDS_PRIORITY] (so a terminal state never has a holder).
 *
 * The player-loss part is read straight from [applicableStateBasedActions], so terminality and the SBA
 * loop can never diverge. The no-holder guard is what keeps this from firing on a still-live pause: a
 * seat that holds priority with a zero-or-less life total is not yet lost — CR 704.3 actions that loss
 * only when priority next passes (the tampered `StateBasedActionSpec` states), so such a state is a
 * genuine pause the engine must still advance, not a terminal one.
 *
 * Used by [pendingDecisionRequest] to short-circuit a finished game before deriving a pause request: a
 * terminal state is not a pause point, yet it can still carry a moot fired-but-unplaced trigger the
 * loss left dangling, which would otherwise mis-derive an ordering request and throw (CR 603.3b).
 */
internal fun isTerminalState(state: GameState): Boolean =
    state.players.values.none { it.priorityStatus == PriorityStatus.HOLDS_PRIORITY } &&
        applicableStateBasedActions(state).any { it is StateBasedAction.PlayerLoses }

/**
 * Every token currently in a zone other than the battlefield (CR 704.5d), in a deterministic order:
 * each seat's library, hand, and graveyard in seat order, then the shared stack and exile. "Token" is
 * `definitions[card] is TokenDefinition` — stable across the CR 400.7 rebirth. In the MVP pool the
 * only reachable case is a token creature that has just died into a graveyard.
 */
private fun tokensOffBattlefield(state: GameState): List<GameObject> =
    buildList {
        for (playerState in state.players.values) {
            addAll(playerState.library.filter { isToken(state, it) })
            addAll(playerState.hand.filter { isToken(state, it) })
            addAll(playerState.graveyard.filter { isToken(state, it) })
        }
        addAll(
            state.sharedZones.stack
                .mapNotNull { it.cardObject }
                .filter { isToken(state, it) },
        )
        addAll(state.sharedZones.exile.filter { isToken(state, it) })
    }

/** The result of one full state-based-action check (the CR 704.3 repeat-until-quiet loop). */
internal sealed interface SbaOutcome {
    /**
     * No further state-based actions apply; play proceeds. [performedWork] records whether any
     * check in the loop performed anything — the fact CR 514.3a's cleanup rule branches on.
     */
    data class Continued(
        val state: GameState,
        val performedWork: Boolean,
    ) : SbaOutcome

    /** A player lost; the game is over (CR 104.2a). [state] already carries the closing events. */
    data class Loss(
        val state: GameState,
        val result: MatchResult,
    ) : SbaOutcome
}

/**
 * Performs state-based actions until none apply (CR 704.3): each iteration collects every
 * applicable action, performs the batch simultaneously (CR 704.3), and checks again. A player loss
 * ends the loop — and the game — immediately (CR 104.2a); a creature-death batch feeds the loop's
 * next iteration, so a death that itself creates a new applicable action (none does in the P3.2
 * pool, but a chain is legal) is caught on the following pass.
 */
internal fun performStateBasedActions(state: GameState): SbaOutcome {
    var current = SbaOutcome.Continued(state, performedWork = false)
    while (true) {
        val actions = applicableStateBasedActions(current.state)
        if (actions.isEmpty()) return current
        when (val batch = performBatch(current.state, actions)) {
            is SbaOutcome.Loss -> return batch
            is SbaOutcome.Continued -> current = SbaOutcome.Continued(batch.state, performedWork = true)
        }
    }
}

/**
 * Performs one batch of applicable state-based actions simultaneously (CR 704.3). Player losses
 * are resolved first: a loss ends the game (CR 104.2a), so any simultaneous creature deaths or Aura
 * fall-offs are moot and left unperformed. With no loss this batch, creature deaths (CR 704.5f/g)
 * and Aura fall-offs (CR 704.5m) are performed together — an Aura and its enchanted creature never
 * fall in the *same* batch (while the creature is still on the battlefield the Aura is legal), so
 * these two moves never contend for the same object.
 *
 * **CR 704.5q annihilation and a creature death *can* name the same object in one batch**, and that
 * is why it is performed last and skips an object that is no longer on the battlefield: a creature
 * whose counters make its toughness 0 or less dies (CR 704.5f) in the same check that annihilates its
 * opposing counters. Order is nonetheless unobservable, which is the point worth recording — removing
 * N `+1/+1` and N `-1/-1` counters changes power by `-N + N` and toughness by `-N + N`, so
 * annihilation is exactly P/T-neutral and cannot make a creature die that would have lived, or the
 * reverse. It is sequenced after the deaths only because a departed object has no counters to remove
 * (CR 122.2), not to resolve a rules conflict.
 */
private fun performBatch(
    state: GameState,
    actions: List<StateBasedAction>,
): SbaOutcome {
    val losses = mutableListOf<StateBasedAction.PlayerLoses>()
    val deaths = mutableListOf<StateBasedAction.CreatureDies>()
    val fallOffs = mutableListOf<StateBasedAction.AuraFallsOff>()
    val unattachments = mutableListOf<StateBasedAction.EquipmentUnattaches>()
    val bestowUnattachments = mutableListOf<StateBasedAction.BestowedPermanentUnattaches>()
    val tokenCeases = mutableListOf<StateBasedAction.TokenCeasesToExist>()
    val annihilations = mutableListOf<StateBasedAction.CountersAnnihilate>()
    for (action in actions) {
        // Exhaustive over the sealed hierarchy: a new state-based-action kind must be sorted here.
        when (action) {
            is StateBasedAction.PlayerLoses -> losses += action
            is StateBasedAction.CreatureDies -> deaths += action
            is StateBasedAction.AuraFallsOff -> fallOffs += action
            is StateBasedAction.EquipmentUnattaches -> unattachments += action
            is StateBasedAction.BestowedPermanentUnattaches -> bestowUnattachments += action
            is StateBasedAction.TokenCeasesToExist -> tokenCeases += action
            is StateBasedAction.CountersAnnihilate -> annihilations += action
        }
    }
    if (losses.isNotEmpty()) return performPlayerLoss(state, losses)
    val afterDeaths = performCreatureDeaths(state, deaths.map(StateBasedAction.CreatureDies::objectId))
    val afterFallOffs = performAuraFallOffs(afterDeaths, fallOffs.map(StateBasedAction.AuraFallsOff::objectId))
    // CR 704.5n, in the same batch as the Aura fall-offs and after them for definiteness only: the two
    // act on disjoint objects (an Equipment is never an Aura), so the order is unobservable.
    val afterUnattach =
        unattachments
            .map(StateBasedAction.EquipmentUnattaches::objectId)
            .fold(afterFallOffs, ::unattachPermanent)
    // CR 702.103c, in the same batch and after the CR 704.5n unattachments for definiteness only: the
    // two act on disjoint objects (a permanent with bestow is a creature card, never an Equipment).
    val afterBestow =
        bestowUnattachments
            .map(StateBasedAction.BestowedPermanentUnattaches::objectId)
            .fold(afterUnattach, ::unattachPermanent)
    val afterTokens =
        performTokenCeasesToExist(afterBestow, tokenCeases.map(StateBasedAction.TokenCeasesToExist::objectId))
    return SbaOutcome.Continued(performCounterAnnihilations(afterTokens, annihilations), performedWork = true)
}

/**
 * Resolves the player-loss actions of a batch. Exactly one loser ends the game (CR 104.2a).
 *
 * **CR 104.4a draw verdict (P3.2).** Two players losing at the same check would be a draw — which
 * [MatchResult] deliberately cannot represent — so this fails loudly rather than guess a winner. It
 * stays a loud, unreachable corner in the P3.2 pool: no effect damages or drains *both* players at
 * once (combat damages only the defending player; a Bolt hits one target; there is no life
 * payment anywhere in the pool, so no cost drives a second player to 0 alongside the first), and
 * draws happen one player at a time (only the active player draws). A draw first becomes
 * constructible when a symmetric life-loss effect or a life-paying cost joins the pool; the
 * representation lands with it, tested, rather than speculatively now.
 */
private fun performPlayerLoss(
    state: GameState,
    losses: List<StateBasedAction.PlayerLoses>,
): SbaOutcome {
    val losers = losses.distinctBy(StateBasedAction.PlayerLoses::player)
    if (losers.size > 1) {
        error(
            "CR 104.4a: players ${losers.map(StateBasedAction.PlayerLoses::player)} would lose " +
                "simultaneously (a draw); the P3.2 pool cannot construct this and draws are unrepresentable",
        )
    }
    val loss =
        losers.firstOrNull()
            ?: error("performPlayerLoss requires at least one loss, got $losses")
    return loseGame(state, loss.player, loss.reason)
}

/**
 * Ends the game with [loser] losing for [reason]: in a two-player game the losing player's only
 * opponent wins (CR 104.2a). Emits [GameEvent.PlayerLost] and [GameEvent.GameEnded].
 */
internal fun loseGame(
    state: GameState,
    loser: PlayerId,
    reason: LossReason,
): SbaOutcome.Loss {
    val winner =
        state.players.keys.singleOrNull { it != loser }
            ?: error("CR 104.2a: exactly one opponent must remain; games with more seats are unsupported in P1.2")
    val final =
        state
            .emit(GameEvent.PlayerLost(loser, reason))
            .emit(GameEvent.GameEnded(winner, loser))
    return SbaOutcome.Loss(final, MatchResult(winner, loser, reason))
}
