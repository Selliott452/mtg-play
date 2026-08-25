package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
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
 * @property manaAbilitiesActivatedThisTurn the indices of this object's printed mana abilities already
 *   activated this turn (CR 602.5b), ascending; empty for every object with no "Activate only once
 *   each turn" mana ability, which is almost all of them. Public information (ADR-007) — that a Wall
 *   of Roots has already been used this turn is as visible across the table as its tapped status.
 *   Added by `FW-MANACOST`.
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
    val manaAbilitiesActivatedThisTurn: List<Int>,
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
        // CR 602.5b: publicly observable — every player sees that a Wall of Roots has already been
        // activated this turn, exactly as they see that it is tapped (ADR-007).
        manaAbilitiesActivatedThisTurn = manaAbilitiesActivatedThisTurn.sorted(),
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
        manaAbilitiesActivatedThisTurn = manaAbilitiesActivatedThisTurn.toPersistentSet(),
    )
