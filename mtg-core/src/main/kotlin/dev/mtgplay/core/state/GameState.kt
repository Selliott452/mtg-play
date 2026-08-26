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
 * @property pendingNinjutsu a ninjutsu ability gathering its mana payment (CR 702.49a, CR 602.2b), or
 *   `null`. Additive, flagged core (`FW-NINJUTSU`). Non-null only at that payment pause, where the
 *   activating player holds priority and the ninja card is still in their hand — see [PendingNinjutsu].
 * @property pendingOptionalDraw a bare optional "you may draw N" clause the engine is resolving
 *   (CR 601.3b), or `null`. Additive, flagged core (`FW-OPTDRAW`). Non-null only at that yes/no pause,
 *   where the clause's ability has already ceased to exist — see [PendingOptionalDraw].
 * @property pendingOptionalTrigger a resolving triggered ability whose whole effect is inside a printed
 *   "you may" (CR 603.2), or `null`. Additive, flagged core (`W8-A`). Non-null only at that yes/no
 *   pause, where the ability is still on top of the stack — see [PendingOptionalTrigger].
 * @property pendingTapOrUntap a "you may tap or untap [target]" clause the engine is resolving
 *   (CR 701.20a, CR 701.21a), or `null`. Additive, flagged core (`W8-G`). Non-null only at that
 *   three-way pause, where the resolving object is still on top of the stack — see [PendingTapOrUntap].
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
 * @property pendingCounterPayment a "counter target spell unless its controller pays" clause awaiting that
 *   payment (CR 701.5, CR 118.3a), or `null`. Additive, flagged core (`FW-COUNTER`,
 *   docs/design/countering-spells.md). Non-null only at that mid-resolution pause, where the resolving
 *   counter is still on top of the stack and the spell it targets is still below it. The one pending
 *   record whose decider is normally **not** the resolving object's controller — see
 *   [PendingCounterPayment].
 * @property pendingTriggerTargets a fired triggered ability awaiting its CR 603.3d target choice as it is
 *   put on the stack, or `null`. Additive, flagged core (`FW-ABILTGT`,
 *   docs/design/targeted-abilities.md). Non-null only at that placement pause, where no priority round is
 *   open and the ability is not yet on the stack — see [PendingTriggerTargets].
 * @property pendingHandReveal a "target opponent reveals their hand and you choose a card from it" clause
 *   awaiting the resolving object's controller's choice (CR 701.16a), or `null`. Additive, flagged core
 *   (`FW-HIDDENCHOICE`, docs/design/exile-and-return.md §7). Non-null only at that mid-resolution pause.
 *   While it is open the revealing player's hand is **public** — the one pending record that *widens*
 *   what a seat may see rather than narrowing it (ADR-007) — see [PendingHandReveal].
 * @property pendingOpponentDiscard an "each opponent discards a card" clause awaiting one opponent's
 *   choice from their own hand (CR 701.7a), or `null`. Additive, flagged core (`FW-NONCTRLDEC`,
 *   docs/design/exile-and-return.md §6). Non-null only at that mid-resolution pause. The one pending
 *   record whose decider is not the resolving object's controller **and** whose options are hidden from
 *   that controller — see [PendingOpponentDiscard].
 * @property pendingRebound a resolved rebound delayed ability awaiting its controller's yes/no free cast
 *   (CR 702.88b), or `null`. Additive, flagged core (`FW-BLINK`, docs/design/exile-and-return.md §5).
 *   Non-null only at that pause, where the delayed ability has already left the stack — see
 *   [PendingRebound].
 * @property pendingPermanentSelection an untargeted "choose up to N permanents, then untap them or
 *   return them to their owners' hands" clause the engine is resolving (CR 609.4), or `null`. Additive,
 *   flagged core (`FW-TAPUNTAP`) — Snap and Azorius Chancery. Non-null only at that mid-resolution
 *   pause, where the resolving object is on top of the stack. The one pending record whose option list
 *   is drawn entirely from the **battlefield**, and therefore the one that hides nothing from anybody
 *   (CR 400.2) — see [PendingPermanentSelection].
 * @property pendingOptionalManaPayment an optional "you may pay {cost}; if you do, draw N" clause
 *   awaiting that payment (CR 601.3b), or `null`. Additive, flagged core (`W8-D`) — Nihil Spellbomb.
 *   Non-null only at that mid-resolution pause, where the resolving object is on top of the stack. The
 *   sibling of [pendingCounterPayment] with the *controller* deciding rather than a targeted spell's
 *   controller — see [PendingOptionalManaPayment].
 * @property pendingGraveyardExile a "target player exiles a card from their graveyard" clause awaiting
 *   that player's choice (CR 701.3a), or `null`. Additive, flagged core (`W8-D`) — Relic of Progenitus.
 *   Non-null only at that mid-resolution pause. The second pending record whose decider is not the
 *   resolving object's controller, and the first whose decider is named by a *target* and may therefore
 *   be the controller — see [PendingGraveyardExile].
 * @property pendingTypeChoice a "choose a card type, then reveal the top N and partition them" clause
 *   awaiting the type (CR 609.4, CR 701.16), or `null`. Additive, flagged core (`W8-D`) — Winding Way.
 *   Non-null only at that mid-resolution pause, where the resolving spell is on top of the stack and
 *   **nothing has been revealed yet** — see [PendingTypeChoice].
 * @property timedEffects the continuous effects generated by the resolution of spells and abilities that
 *   are still running (CR 611.2), in creation order — which is timestamp order, because the list is
 *   append-only. Additive, flagged core (`FW-DURATION`, docs/design/duration.md §5.1). The first
 *   rules-relevant content of a [GameState] that hangs off no object: an "until end of turn" effect
 *   outlives the ability that made it and may outlive the permanent it modifies. Empty outside the turn
 *   an effect was created on, because the CR 514.2 cleanup turn-based action ends every one of them.
 * @property pendingChosenColor a "choose a colour, then do something with it" clause gathered
 *   mid-resolution (CR 609.4), or `null`. Additive, flagged core (`FW-PREVENT2`) — Prismatic Strands.
 *   Non-null only at that pause, where the resolving object is still on top of the stack. The sibling of
 *   [pendingColorChoice] and not a mode of it: that one is CR 614.12's as-enters choice on a permanent
 *   and resumes into a battlefield entry — see [PendingChosenColor].
 * @property preventionEffects the **global**, turn-scoped effects read at the CR 615 damage-prevention
 *   application point, in creation order. Additive, flagged core (`FW-PREVENT2`) — Prismatic Strands'
 *   colour shield and Flaring Pain's CR 615.9 off-switch. The second piece of rules-relevant content
 *   that hangs off no object, and a separate store from [timedEffects] rather than a widening of it
 *   because neither member names an affected object or classifies into a CR 613 layer (see
 *   [PreventionEffect]). Empty outside the turn an effect was created on, for [timedEffects]' reason:
 *   the same CR 514.2 turn-based action ends both.
 * @property deathReplacements the **delayed** replacement effects watching named permanents for a
 *   battlefield-to-graveyard move (CR 614.1a, CR 700.4), in creation order. Additive, flagged core
 *   (`W9-D`) — Torch the Tower's "if a permanent dealt damage by this would die this turn, exile it
 *   instead". The third piece of rules-relevant content that hangs off no object, and a separate store
 *   from both [timedEffects] and [preventionEffects] because it is read at a third place: the CR 614
 *   interception point of a death, rather than at characteristic computation or at CR 615 (see
 *   [TimedDeathReplacement]). Empty outside the turn a replacement was created on — the same CR 514.2
 *   turn-based action ends all three.
 * @property lastKnownPower the CR 613 layered power each permanent had as it **left** the battlefield
 *   (CR 608.2h, CR 113.7a), keyed by the battlefield object id it had there. Additive, flagged core
 *   (`W9-D`) — Monstrous Emergence's "damage equal to the power of the creature you chose", whose chosen
 *   creature may be killed in response.
 *
 *   The engine's first *pull*-shaped last-known information, and the reason it is a state field rather
 *   than a capture on the thing that reads it: the reader is a spell already on the stack, and the moment
 *   of capture — the departure — belongs to whatever removed the permanent, which has no idea anybody is
 *   watching. See `LastKnownPower.kt` for why it records power alone and why it is turn-scoped, pruned by
 *   the same CR 514.2 cleanup as the three effect stores.
 *
 *   Not carried on any [dev.mtgplay.core.definition.CardDefinition]-visible view (ADR-007): it is derived
 *   from public history every seat already watched, it changes no option list, and no decision reads it.
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
    val pendingCounterPayment: PendingCounterPayment? = null,
    val pendingHandReveal: PendingHandReveal? = null,
    val pendingOpponentDiscard: PendingOpponentDiscard? = null,
    val pendingRebound: PendingRebound? = null,
    val pendingNinjutsu: PendingNinjutsu? = null,
    val pendingOptionalDraw: PendingOptionalDraw? = null,
    val pendingOptionalTrigger: PendingOptionalTrigger? = null,
    val pendingPermanentSelection: PendingPermanentSelection? = null,
    val pendingTapOrUntap: PendingTapOrUntap? = null,
    val pendingOptionalManaPayment: PendingOptionalManaPayment? = null,
    val pendingGraveyardExile: PendingGraveyardExile? = null,
    val pendingTypeChoice: PendingTypeChoice? = null,
    val timedEffects: PersistentList<TimedContinuousEffect> = persistentListOf(),
    val preventionEffects: PersistentList<TimedPreventionEffect> = persistentListOf(),
    val pendingChosenColor: PendingChosenColor? = null,
    val deathReplacements: PersistentList<TimedDeathReplacement> = persistentListOf(),
    val lastKnownPower: PersistentMap<ObjectId, Int> = persistentMapOf(),
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
        val colorChoice = pendingColorChoice
        val playedLand = colorChoice?.playedLand
        if (colorChoice != null && playedLand != null) {
            val decider = players[colorChoice.decider]
            requireNotNull(decider) { "pending colour choice names unseated decider ${colorChoice.decider}" }
            // CR 614.12, CR 305.1: the choice is made *as* the land enters, so the card is still in
            // hand — the land joins the battlefield only once the colour arrives.
            require(decider.hand.any { it.id == playedLand }) {
                "CR 614.12: a played land choosing a colour must still be in its controller's hand until " +
                    "the choice is answered; $playedLand is not there for ${colorChoice.decider}"
            }
        }
        val ninjutsu = pendingNinjutsu
        if (ninjutsu != null) {
            val activator = players[ninjutsu.activator]
            requireNotNull(activator) { "pending ninjutsu names unseated activator ${ninjutsu.activator}" }
            // CR 702.49a: the ability functions from the hand, and the card is put onto the battlefield
            // only when it resolves — so it must still be in hand while the payment is gathered.
            require(activator.hand.any { it.id == ninjutsu.ninjaObjectId }) {
                "CR 702.49a: a pending ninjutsu's card must still be in the activator's hand until it " +
                    "executes; ${ninjutsu.ninjaObjectId} is not there for ${ninjutsu.activator}"
            }
            // CR 702.49a: the returned attacker is a cost, so it is still on the battlefield attacking
            // until the activation executes.
            require(sharedZones.battlefield.any { it.id == ninjutsu.returnedAttacker }) {
                "CR 702.49a: a pending ninjutsu's returned attacker must still be on the battlefield " +
                    "until the cost is paid; ${ninjutsu.returnedAttacker} is not"
            }
            require(turn.combat?.attackers?.any { it.attacker == ninjutsu.returnedAttacker } == true) {
                "CR 702.49a: a pending ninjutsu returns an *attacking* creature; " +
                    "${ninjutsu.returnedAttacker} is not a declared attacker"
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
        val counterPayment = pendingCounterPayment
        if (counterPayment != null) {
            require(counterPayment.decider in players) {
                "CR 118.3a: the unless-pay decider ${counterPayment.decider} is not seated"
            }
            require(sharedZones.stack.any { (it as? StackEntry.Spell)?.obj?.id == counterPayment.counteredObjectId }) {
                "CR 701.5a: the spell ${counterPayment.counteredObjectId} an unless-pay clause would counter " +
                    "must still be on the stack while the payment is pending"
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
        val handReveal = pendingHandReveal
        if (handReveal != null) {
            require(handReveal.decider in players) {
                "CR 701.16a: the hand-reveal chooser ${handReveal.decider} is not seated"
            }
            require(handReveal.revealer in players) {
                "CR 701.16a: the revealing player ${handReveal.revealer} is not seated"
            }
        }
        val opponentDiscard = pendingOpponentDiscard
        if (opponentDiscard != null) {
            require(opponentDiscard.decider in players) {
                "CR 701.7a: the discarding opponent ${opponentDiscard.decider} is not seated"
            }
            require(opponentDiscard.controller in players) {
                "CR 701.7a: the clause's controller ${opponentDiscard.controller} is not seated"
            }
            require(opponentDiscard.remaining.all { it in players }) {
                "CR 701.7a: every queued opponent is seated, got ${opponentDiscard.remaining}"
            }
            // CR 701.7a: an opponent who cannot discard is never asked, so an open request means a real hand.
            require(players.getValue(opponentDiscard.decider).hand.isNotEmpty()) {
                "CR 701.7a: ${opponentDiscard.decider} was asked to discard with an empty hand; an opponent " +
                    "who cannot discard is skipped, not asked"
            }
        }
        val rebound = pendingRebound
        if (rebound != null) {
            require(rebound.controller in players) {
                "CR 702.88b: the rebounding spell's controller ${rebound.controller} is not seated"
            }
            require(sharedZones.exile.any { it.id == rebound.exiledObjectId }) {
                "CR 702.88b: the rebounding card ${rebound.exiledObjectId} must still be in exile while its " +
                    "free cast is pending"
            }
        }
        val chosenColor = pendingChosenColor
        if (chosenColor != null) {
            require(chosenColor.decider in players) {
                "CR 609.4: the colour-choice decider ${chosenColor.decider} is not seated"
            }
            require(sharedZones.stack.isNotEmpty()) {
                "CR 609.4: a mid-resolution colour choice pauses inside a resolution, so the " +
                    "resolving object must still be on the stack"
            }
        }
        val permanentSelection = pendingPermanentSelection
        if (permanentSelection != null) {
            require(permanentSelection.decider in players) {
                "CR 609.4: the permanent-selection decider ${permanentSelection.decider} is not seated"
            }
            require(sharedZones.stack.isNotEmpty()) {
                "CR 609.4: a mid-resolution permanent selection pauses inside a resolution, so the " +
                    "resolving object must still be on the stack"
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
