package dev.mtgplay.core.state

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * The complete, immutable state of a game in progress (ADR-002).
 *
 * Every engine transition returns a **new** state; nothing mutates in place, and unchanged
 * substructure is shared between successive states through the persistent collections.
 * Construction validates the basic invariants; game *logic* — advancing turns, moving objects
 * between zones — lives in `mtg-rules`, never here.
 *
 * **Deterministic iteration (architect rule, P1.1).** Every collection reachable from a
 * [GameState] uses the insertion-ordered persistent implementations from
 * `kotlinx.collections.immutable` — `persistentListOf`, `persistentMapOf`, `persistentSetOf` —
 * and never `persistentHashMapOf`/`persistentHashSetOf`, whose iteration order is hash-driven.
 * Enumerated-option indices (ADR-005) and replay (ADR-006) depend on deterministic,
 * insertion-stable iteration order.
 *
 * The [events] log is derived observability (ADR-006): transitions append what happened, for
 * replay display and debugging; rules logic never reads it.
 *
 * @property players each seated player's state, keyed by seat; map insertion order is turn
 *   order, from which APNAP order derives (CR 101.4).
 * @property turn whose turn it is and where within it the game stands (CR 500).
 * @property sharedZones the battlefield, stack, and exile (CR 400.2).
 * @property nextObjectId the [ObjectId] allocation counter; every id in the state is strictly
 *   below it, and ids are never reused (CR 400.7).
 * @property rng the deterministic PRNG state all in-game randomness draws from (ADR-006).
 * @property events the append-only event log; derived, never load-bearing.
 * @property definitions the match's card-definition registry (P2.1): what each printed card
 *   *does*, keyed by [CardRef] in deterministic (name-sorted) insertion order. Static data
 *   carried in the state because the engine is a pure function of the state alone (ADR-004);
 *   a card without an entry is inert and uncastable (architect decision, P2.1).
 * @property pendingCast the cast currently gathering decisions (CR 601.2), or `null` when no
 *   cast is in progress; see [PendingCast] for the atomicity contract.
 * @property pendingTriggers the triggered abilities that have fired since a player last received
 *   priority and are waiting to be put on the stack (CR 603.3b), in the order they fired. Additive,
 *   flagged core (P5.1). Empty whenever no trigger is waiting; a player would receive priority only
 *   after these are placed on the stack in APNAP order (`mtg-rules`). Carried in the state so the
 *   pending decision — which player orders their simultaneous triggers — is a pure function of it
 *   (ADR-004 no-hidden-position).
 * @property pendingMadness a resolved madness reflexive trigger awaiting its owner's yes/no cast
 *   decision (CR 702.35b), or `null`. Additive, flagged core (P5.2). Non-null only at that yes/no
 *   pause; the reflexive trigger is already off the stack and this is what remains of it.
 * @property pendingReplacement a discard event with two or more applicable replacements awaiting the
 *   affected player's CR 616.1 ordering choice, or `null`. Additive, flagged core (P5.2). Non-null only
 *   at that choice pause; the card is still in the player's hand.
 * @property pendingMulligan the pre-game London-mulligan phase's progress (CR 103.4/103.5), or `null`
 *   once turn 1 has begun. Additive, flagged core (P6.1). Non-null only during the mulligan phase,
 *   where no player holds priority and the stack is empty — see [PendingMulligan].
 * @property pendingPlot a plot special action gathering its payment (CR 702.140), or `null`. Additive,
 *   flagged core (P6.2a). Non-null only at that payment pause, where the plotting player holds priority
 *   and the card is still in their hand — see [PendingPlot].
 * @property pendingColorChoice an "as this permanent enters, choose a colour" choice gathered
 *   mid-resolution (CR 614.12), or `null`. Additive, flagged core (P6.2a). Non-null only at that pause,
 *   where the resolving permanent spell is still on top of the stack — see [PendingColorChoice].
 * @property pendingActivation an activated ability gathering its cost selections (CR 602.2), or `null`.
 *   Additive, flagged core (P6.2a). Non-null only while gathering, where the activating player holds
 *   priority — see [PendingActivation].
 * @property pendingRevealSelection a "reveal top N, keep one" selection gathered mid-resolution
 *   (CR 701.16), or `null`. Additive, flagged core (P6.2a). Non-null only at that pause, where the
 *   resolving spell is on top of the stack — see [PendingRevealSelection].
 * @property pendingOptionalDiscardDraw an optional "you may discard a card; if you do, draw N" clause
 *   the engine is resolving (CR 601.3b), or `null`. Additive, flagged core (P6.2a). Non-null only at the
 *   yes/no or the following discard-selection pause — see [PendingOptionalDiscardDraw].
 * @property pendingOptionalCostDraw an optional "you may [discard a card | sacrifice a land]; if you do,
 *   draw N" clause the engine is resolving as part of a spell (CR 601.3b), or `null`. Additive, flagged
 *   core (P6.2c). Non-null only at the mode-choice or the following cost-object-selection pause, where the
 *   resolving spell is on top of the stack — see [PendingOptionalCostDraw].
 * @property pendingResolutionDiscard a mandatory "draw N, then discard M" resolution discard the engine is
 *   gathering (CR 601.2c), or `null`. Additive, flagged core (P6.2c). Non-null only at that mid-resolution
 *   pause, after the draw, where the resolving spell is on top of the stack — see [PendingResolutionDiscard].
 * @property pendingLibrarySearch a "search your library, put one into hand, then shuffle" the engine is
 *   resolving as part of an activated ability (CR 701.18), or `null`. Additive, flagged core (P6.2c).
 *   Non-null only at the find-one pause, where the resolving ability is on top of the stack — see
 *   [PendingLibrarySearch].
 * @property pendingLibraryLook a private "look at these cards, then arrange them" the engine is resolving
 *   as part of a spell (CR 701.14, CR 701.17), or `null`. Additive, flagged core (`FW-LIBLOOK`,
 *   docs/design/library-look.md). Non-null only at the arrangement pause or the optional-shuffle pause
 *   that may follow it, where the resolving spell is on top of the stack — see [PendingLibraryLook].
 * @property pendingTriggerTargets a fired triggered ability awaiting its CR 603.3d target choice as it is
 *   put on the stack, or `null`. Additive, flagged core (`FW-ABILTGT`,
 *   docs/design/targeted-abilities.md). Non-null only at that placement pause, where no priority round is
 *   open and the ability is not yet on the stack — see [PendingTriggerTargets].
 */
data class GameState(
    val players: PersistentMap<PlayerId, PlayerState>,
    val turn: Turn,
    val sharedZones: SharedZones,
    val nextObjectId: Long,
    val rng: Rng,
    val events: PersistentList<GameEvent>,
    val definitions: PersistentMap<CardRef, CardDefinition> = persistentMapOf(),
    val pendingCast: PendingCast? = null,
    val pendingTriggers: PersistentList<PendingTrigger> = persistentListOf(),
    val pendingMadness: PendingMadness? = null,
    val pendingReplacement: PendingReplacement? = null,
    val pendingMulligan: PendingMulligan? = null,
    val pendingPlot: PendingPlot? = null,
    val pendingColorChoice: PendingColorChoice? = null,
    val pendingActivation: PendingActivation? = null,
    val pendingRevealSelection: PendingRevealSelection? = null,
    val pendingOptionalDiscardDraw: PendingOptionalDiscardDraw? = null,
    val pendingOptionalCostDraw: PendingOptionalCostDraw? = null,
    val pendingResolutionDiscard: PendingResolutionDiscard? = null,
    val pendingLibrarySearch: PendingLibrarySearch? = null,
    val pendingLibraryLook: PendingLibraryLook? = null,
    val pendingTriggerTargets: PendingTriggerTargets? = null,
) {
    init {
        require(players.isNotEmpty()) { "a game has at least one seated player" }
        require(turn.activePlayer in players) { "active player ${turn.activePlayer} is not seated" }
        require(nextObjectId >= 0) { "object-id counter must be non-negative, was $nextObjectId" }
        val ids = allObjects().map(GameObject::id).toList()
        require(ids.size == ids.distinct().size) { "object ids must be unique across all zones" }
        val highest = ids.maxOfOrNull(ObjectId::value)
        require(highest == null || highest < nextObjectId) {
            "CR 400.7: object id $highest is not below the allocation counter $nextObjectId"
        }
        definitions.forEach { (ref, definition) ->
            require(definition.characteristics.name == ref.name) {
                "definition registered under ${ref.name} describes \"${definition.characteristics.name}\""
            }
        }
        val mulligan = pendingMulligan
        require(mulligan == null || mulligan.deciding in players) {
            "CR 103.5: the mulligan-phase decider ${mulligan?.deciding} is not seated"
        }
        val cast = pendingCast
        if (cast != null) {
            val caster = players[cast.caster]
            requireNotNull(caster) { "pending cast names unseated caster ${cast.caster}" }
            val sourceZone =
                when (cast.source) {
                    CastSource.HAND -> caster.hand
                    CastSource.GRAVEYARD -> caster.graveyard
                    CastSource.EXILE -> sharedZones.exile.filter { it.owner == cast.caster }
                }
            require(sourceZone.any { it.id == cast.cardObjectId }) {
                "CR 601.2: a pending cast's card must still be in its source zone ${cast.source} until the " +
                    "pipeline executes; ${cast.cardObjectId} is not there for ${cast.caster}"
            }
        }
        val plot = pendingPlot
        if (plot != null) {
            val caster = players[plot.caster]
            requireNotNull(caster) { "pending plot names unseated caster ${plot.caster}" }
            require(caster.hand.any { it.id == plot.cardObjectId }) {
                "CR 702.140: a pending plot's card must still be in the caster's hand until it executes; " +
                    "${plot.cardObjectId} is not there for ${plot.caster}"
            }
        }
        val activation = pendingActivation
        if (activation != null) {
            val activator = players[activation.activator]
            requireNotNull(activator) { "pending activation names unseated activator ${activation.activator}" }
            require(activation.abilityIndex >= 0) {
                "CR 602: a pending activation's ability index is non-negative, was ${activation.abilityIndex}"
            }
        }
        val look = pendingLibraryLook
        if (look != null) {
            val decider = players[look.decider]
            requireNotNull(decider) { "CR 701.14a: a pending look names unseated decider ${look.decider}" }
            val resident = decider.library.asSequence() + decider.hand.asSequence()
            val residentIds = resident.map(GameObject::id).toSet()
            require(look.poolIds.all { it in residentIds }) {
                "CR 701.14a: a looked-at card stays in its source zone until the arrangement is applied; " +
                    "${look.poolIds.filterNot { it in residentIds }} is not in ${look.decider}'s library or hand"
            }
        }
        val triggerTargets = pendingTriggerTargets
        if (triggerTargets != null) {
            require(triggerTargets.controller in players) {
                "CR 603.3d: the targeting trigger's controller ${triggerTargets.controller} is not seated"
            }
            require(pendingTriggers.any { it.controller == triggerTargets.controller }) {
                "CR 603.3d: targets are chosen as an ability is put on the stack, so the trigger being " +
                    "placed must still be pending for ${triggerTargets.controller}"
            }
        }
    }

    /**
     * Allocates a fresh [ObjectId]: returns the id and the successor state with the counter
     * advanced. Pure — this state is unchanged. Per CR 400.7 an object moving zones becomes a
     * new object, so whatever moves it mints a fresh id here; the zone-move logic itself is
     * rules territory (P1.2+).
     */
    fun allocateObjectId(): Pair<ObjectId, GameState> = ObjectId(nextObjectId) to copy(nextObjectId = nextObjectId + 1)

    private fun allObjects(): Sequence<GameObject> {
        val perPlayer =
            players.values.asSequence().flatMap {
                it.library.asSequence() + it.hand.asSequence() + it.graveyard.asSequence()
            }
        val shared =
            sharedZones.battlefield.asSequence() +
                sharedZones.stack.asSequence().mapNotNull(StackEntry::cardObject) +
                sharedZones.exile.asSequence()
        return perPlayer + shared
    }
}
