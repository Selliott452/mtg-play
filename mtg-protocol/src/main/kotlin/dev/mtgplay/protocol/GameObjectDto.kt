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
 */
@Serializable
data class GameObjectDto(
    val id: Long,
    val card: String,
    val owner: Int,
    val tapped: Boolean,
    val damageMarked: Int,
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
)

/** [GameObject] to its wire form. */
fun GameObject.toDto(): GameObjectDto =
    GameObjectDto(
        id = id.value,
        card = card.name,
        owner = owner.seat,
        tapped = tapped,
        damageMarked = damageMarked,
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
    )

/** [GameObjectDto] back to the engine value. */
fun GameObjectDto.toDomain(): GameObject =
    GameObject(
        id = ObjectId(id),
        card = CardRef(card),
        owner = PlayerId(owner),
        tapped = tapped,
        damageMarked = damageMarked,
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
    )
