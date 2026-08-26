package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.Serializable

/**
 * Wire form of one [GameObject] (CR 109): identity as stable primitives (object id as a `Long`,
 * printed card as its name `String`, owner as a seat index), plus the public status a permanent
 * carries. Only public game objects reach the wire — a filtered view never puts a hidden-zone card
 * here except the viewer's own hand and publicly revealed cards.
 *
 * @property id the object's current-zone id (CR 400.7).
 * @property card the printed card name.
 * @property owner the owner's seat index (CR 108.3).
 * @property tapped whether the object is tapped (CR 110.5b).
 * @property damageMarked marked damage (CR 120.3).
 * @property dealtDeathtouchDamage whether damage from a source with deathtouch has been marked on this
 *   object (CR 702.2, CR 704.5h); `false` for every object off the battlefield. Public information
 *   (ADR-007) — which creature is about to die to a deathtoucher is as visible across the table as the
 *   damage itself, and a consumer that could see the damage but not its source could not explain the
 *   death that follows. Added by the keyword-tail packet.
 * @property summoningSick the summoning-sickness fact (CR 302.6).
 * @property attachedTo the object this Aura is attached to (CR 303.4), or `null`.
 * @property awaitingMadness the exile madness marker (CR 702.35a), or `false`.
 * @property plottedTurn the turn this card was plotted (CR 702.140), or `null`.
 * @property chosenColor the colour chosen as this permanent entered (CR 614.12), or `null`.
 * @property counters the counters on this permanent (CR 122.1), as one entry per kind; empty for a
 *   permanent with none and for every object off the battlefield. Public information (ADR-007),
 *   unredacted. Added by `FW-COUNTERS`.
 * @property linkedExiled the exile objects this permanent's own ability exiled, in the order exiled
 *   (CR 607.2 linked abilities), as bare object ids; empty for a permanent that has exiled nothing
 *   and for every object off the battlefield. Public (ADR-007) — exile is a face-up zone (CR 406.3)
 *   and [SeatViewDto.exile] already carries the objects these ids name. Added by `FW-LINKEDEXILE`.
 * @property reboundTurn the turn rebound exiled this card on (CR 702.88a), or `null` when it was not
 *   exiled by rebound; an exile-only marker in the shape [plottedTurn] already set. Public, for the
 *   same CR 406.3 reason. Added by `FW-BLINK`.
 * @property manaAbilitiesActivatedThisTurn the indices of this object's printed mana abilities already
 *   activated this turn (CR 602.5b), ascending; empty for every object with no "Activate only once
 *   each turn" mana ability, which is almost all of them. Public information (ADR-007) — that a Wall
 *   of Roots has already been used this turn is as visible across the table as its tapped status.
 *   Added by `FW-MANACOST`.
 * @property activatedAbilitiesActivatedThisTurn the indices of this object's printed **non-mana**
 *   activated abilities already activated this turn (CR 602.5b), ascending; empty for every object with
 *   no "Activate only once each turn" activated ability. The sibling of
 *   [manaAbilitiesActivatedThisTurn] and a separate field for the reason [GameObject] keeps two: the
 *   two index different ability lists, so one merged array could not say which ability index 0 named.
 *   Public information (ADR-007) — that a Quirion Ranger has already untapped this turn is as visible
 *   across the table as its tapped status. Added by `FW-TAPUNTAP`.
 * @property skipsNextUntapStep whether this permanent will not untap during its controller's next
 *   untap step (CR 502.2) — Sleep of the Dead's rider; `false` for almost every object. Public
 *   information (ADR-007): a "doesn't untap" effect resolves face-up on the stack and both players
 *   must reason about it. Added by `FW-TAPUNTAP`.
 * @property evokedWhenCast whether this permanent entered from a spell cast for its **evoke** cost
 *   (CR 702.74a); `false` for almost every object. The exact sibling of [kickedWhenCast], and public for
 *   the same reason (ADR-007): everyone at the table saw the evoke cost paid, and the fact is what makes
 *   the permanent sacrifice itself a moment later. Added by `W8-D`.
 * @property playGrantedTurn the turn an effect granted this **exile** card's owner permission to play it
 *   (CR 118.5) — Reckless Impulse's; `null` for every other object. An exile-only marker in the shape
 *   [plottedTurn] and [reboundTurn] already set, and public for the same CR 406.3 reason: the cards are
 *   exiled face up. It records when the permission *began*, not when it ends — see
 *   [dev.mtgplay.core.state.GameObject.playGrantedTurn] for why that is the honest encoding. Added by
 *   `W8-D`.
 * @property enteredTurn the turn this permanent entered the battlefield (CR 603.6a); `null` for every
 *   object that is not on the battlefield. Public information (ADR-007) — every seat watched it arrive,
 *   and Moon-Circuit Hacker's *"unless this creature entered this turn"* is a question both players must
 *   be able to answer. Deliberately **not** `summoningSick`, which is a different fact about control
 *   (CR 302.6) and already rides separately. Added by `W9-A`.
 * @property prototyped whether this permanent entered from a spell cast **prototyped** (CR 702.160a,
 *   CR 718.3b) — Boulderbranch Golem cast for its `Prototype {3}{G} — 3/3`; `false` for almost every
 *   object. Public for [kickedWhenCast]'s reason and then some (ADR-007): the alternative mana cost was
 *   paid in front of everyone, and unlike the other two markers this one decides what the permanent's
 *   power, toughness and colours *are*, so a peer that dropped it would render the wrong creature.
 *   Added by `W9-G`.
 * @property onAnAdventure whether this **exile** card was put there by an Adventure spell of its own
 *   resolving (CR 715.3d) — Fang Dragon exiled by *Forktail Sweep*; `false` for every other object.
 *   The fifth exile marker, beside [awaitingMadness], [plottedTurn], [reboundTurn] and
 *   [playGrantedTurn], and public for the same CR 406.3 reason: the card is exiled face up, and every
 *   seat needs to know that a 6/3 flier is waiting to be played out of it. A boolean rather than a turn
 *   number because CR 715.3d's grant lasts *"for as long as that card remains exiled"* and so has no
 *   expiry to record. Added by `W10-B`.
 * @property goadedBy the seat that goaded this creature (CR 701.38a), or `null` when it is not goaded —
 *   the Undercity's Arena. Added by `W11`.
 *
 *   **Public, and the one marker whose whole visible consequence is on a *future* decision** (ADR-007).
 *   Goad changes no characteristic, so a peer that dropped it would render an identical creature and
 *   then be unable to explain why its own declare-attackers request refuses a declaration that leaves
 *   the creature at home. Both halves of CR 701.38a are public information — goad is announced on
 *   resolution and every seat watched it.
 * @property goadedOnTurn the turn [goadedBy] goaded it on (CR 701.38a), or `null` when it is not
 *   goaded. The *beginning* of the "until your next turn" window rather than its end, for the reason
 *   [playGrantedTurn] records a grant turn: the end cannot be named without predicting the turn order.
 *   Added by `W11`.
 */
@Serializable
data class GameObjectDto(
    val id: Long,
    val card: String,
    val owner: Int,
    val tapped: Boolean,
    val damageMarked: Int,
    val dealtDeathtouchDamage: Boolean,
    val summoningSick: Boolean,
    val attachedTo: Long?,
    val awaitingMadness: Boolean,
    val plottedTurn: Int?,
    val chosenColor: ColorDto?,
    val counters: List<CounterDto>,
    val linkedExiled: List<Long>,
    val reboundTurn: Int?,
    val manaAbilitiesActivatedThisTurn: List<Int>,
    val activatedAbilitiesActivatedThisTurn: List<Int> = emptyList(),
    val skipsNextUntapStep: Boolean = false,
    val kickedWhenCast: Boolean,
    val evokedWhenCast: Boolean = false,
    val playGrantedTurn: Int? = null,
    val optionalCostPaidWhenCast: Boolean = false,
    val enteredTurn: Int? = null,
    val prototyped: Boolean = false,
    val onAnAdventure: Boolean = false,
    val goadedBy: Int? = null,
    val goadedOnTurn: Int? = null,
)

/** [GameObject] to its wire form. */
fun GameObject.toDto(): GameObjectDto =
    GameObjectDto(
        id = id.value,
        card = card.name,
        owner = owner.seat,
        tapped = tapped,
        damageMarked = damageMarked,
        dealtDeathtouchDamage = dealtDeathtouchDamage,
        summoningSick = summoningSick,
        attachedTo = attachedTo?.value,
        awaitingMadness = awaitingMadness,
        plottedTurn = plottedTurn,
        chosenColor = chosenColor?.toDto(),
        counters = counters.toDto(),
        linkedExiled = linkedExiled.map { it.value },
        reboundTurn = reboundTurn,
        // CR 602.5b: publicly observable — every player sees that a Wall of Roots has already been
        // activated this turn, exactly as they see that it is tapped (ADR-007).
        manaAbilitiesActivatedThisTurn = manaAbilitiesActivatedThisTurn.sorted(),
        activatedAbilitiesActivatedThisTurn = activatedAbilitiesActivatedThisTurn.sorted(),
        skipsNextUntapStep = skipsNextUntapStep,
        // CR 702.33f: publicly observable — everyone at the table saw the kicker paid, and the fact
        // changes what the permanent's own abilities do, so it rides unredacted (ADR-007).
        kickedWhenCast = kickedWhenCast,
        // CR 702.74a / CR 118.5: both are public — an evoked permanent sacrifices itself in front of
        // everyone, and a playable exiled card sits face up (CR 406.3).
        evokedWhenCast = evokedWhenCast,
        playGrantedTurn = playGrantedTurn,
        optionalCostPaidWhenCast = optionalCostPaidWhenCast,
        // CR 603.6a: every seat watched the permanent arrive, so when it arrived is public (ADR-007).
        enteredTurn = enteredTurn,
        // CR 718.3b: public, and load-bearing rather than decorative — the permanent's size and colours
        // are read off this flag, so a seat view that omitted it would describe a different creature.
        prototyped = prototyped,
        // CR 715.3d / CR 406.3: an exiled card on an adventure sits face up and may be played from
        // there, so the marker that says so is public exactly as `playGrantedTurn` is.
        onAnAdventure = onAnAdventure,
        // CR 701.38a: goad resolves on the public stack and constrains a public declaration, so both
        // halves of the marker ride unredacted.
        goadedBy = goadedBy?.seat,
        goadedOnTurn = goadedOnTurn,
    )

/** [GameObjectDto] back to the engine value. */
fun GameObjectDto.toDomain(): GameObject =
    GameObject(
        id = ObjectId(id),
        card = CardRef(card),
        owner = PlayerId(owner),
        tapped = tapped,
        damageMarked = damageMarked,
        dealtDeathtouchDamage = dealtDeathtouchDamage,
        summoningSick = summoningSick,
        attachedTo = attachedTo?.let(::ObjectId),
        awaitingMadness = awaitingMadness,
        plottedTurn = plottedTurn,
        chosenColor = chosenColor?.toDomain(),
        counters = counters.toDomain(),
        linkedExiled = linkedExiled.map(::ObjectId).toPersistentList(),
        reboundTurn = reboundTurn,
        manaAbilitiesActivatedThisTurn = manaAbilitiesActivatedThisTurn.toPersistentSet(),
        activatedAbilitiesActivatedThisTurn = activatedAbilitiesActivatedThisTurn.toPersistentSet(),
        skipsNextUntapStep = skipsNextUntapStep,
        kickedWhenCast = kickedWhenCast,
        evokedWhenCast = evokedWhenCast,
        playGrantedTurn = playGrantedTurn,
        optionalCostPaidWhenCast = optionalCostPaidWhenCast,
        enteredTurn = enteredTurn,
        prototyped = prototyped,
        onAnAdventure = onAnAdventure,
        goadedBy = goadedBy?.let(::PlayerId),
        goadedOnTurn = goadedOnTurn,
    )
