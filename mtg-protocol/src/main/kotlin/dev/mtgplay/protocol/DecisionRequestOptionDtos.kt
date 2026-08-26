package dev.mtgplay.protocol

import kotlinx.serialization.Serializable

/*
 * The option payloads shared across the decision-request DTOs. The engine models each request's
 * options as its own nested `Option` type; on the wire the interchangeable shapes are unified here,
 * and each request's `toDomain` reconstructs its specific nested type.
 */

/**
 * One card-in-a-zone option (an object id plus its printed name) — the shape shared by every "choose
 * cards from a zone" selection (discards, exiles, sacrifices, mulligan bottoming, reveal/library
 * finds, blocker ordering).
 *
 * @property objectId the object the option refers to.
 * @property card the printed card name, for display.
 */
@Serializable
data class CardObjectOptionDto(
    val objectId: Long,
    val card: String,
)

/**
 * One thing that may be named to pay a **non-consuming** additional cost (CR 601.2b), for
 * [DecisionRequestDto.ChooseCostPowerSource] — `W9-D`, Monstrous Emergence.
 *
 * Not a [CardObjectOptionDto], because the two halves of the pool are not the same kind of thing: a
 * battlefield creature is named by object id and read back through the CR 613 layer system, while a card
 * in hand is named by its printed identity and read back through CR 109.3. The [kind] word is what tells
 * a client which, and an unknown one fails loudly on decode rather than being read as the other.
 *
 * @property kind `CHOSEN_CREATURE` for a creature on the battlefield, `REVEALED_CARD` for a creature card
 *   in hand.
 * @property objectId the battlefield object id for `CHOSEN_CREATURE`, and `null` for `REVEALED_CARD`,
 *   which names no object — the card stays in hand and is identified by name alone.
 * @property card the printed card name, for display and, for `REVEALED_CARD`, as the identity itself.
 * @property power what this option would supply **right now** (CR 613 layered for a creature, CR 109.3
 *   printed for a card). Display only: the value that matters is recalculated when the spell resolves
 *   (CR 608.2h), so a creature pumped after this answer deals more damage than the number shown here.
 */
@Serializable
data class PowerSourceOptionDto(
    val kind: String,
    val objectId: Long?,
    val card: String,
    val power: Int,
)

/**
 * One weighted option of a summed-weight selection (CR 601.2b, CR 701.60a), for
 * [DecisionRequestDto.ChooseEvidence]: a graveyard card and the mana value it contributes.
 *
 * The weight is on the **wire** rather than derived client-side, and that is the point of the family: a
 * client cannot tell a paying subset from a failing one without it, and re-deriving mana values from
 * card names would need a card database the protocol deliberately does not assume (ADR-008).
 *
 * @property objectId the object the option refers to.
 * @property card the printed card name, for display.
 * @property weight the option's contribution to the required total — its mana value (CR 202.3).
 */
@Serializable
data class WeightedCardOptionDto(
    val objectId: Long,
    val card: String,
    val weight: Int,
)

/**
 * One choosable mode of a modal spell (CR 700.2), for [DecisionRequestDto.ChooseModes].
 *
 * [modeIndex] is the mode's **printed** index on the card, which is not in general its index in the
 * option list — an unavailable mode is omitted from the list but does not renumber the ones that
 * remain. A client answers by option index, as always; [modeIndex] is what that answer *means*, and it
 * is what the server records on the cast record and the replay log.
 *
 * @property modeIndex the mode's printed index on the card.
 * @property text the printed bullet, for display.
 */
@Serializable
data class ModeOptionDto(
    val modeIndex: Int,
    val text: String,
)

/**
 * One eligible attacker option (CR 508.1).
 *
 * @property attacker the eligible creature.
 * @property card its printed name.
 * @property defendingPlayer the player it would attack.
 */
@Serializable
data class AttackerOptionDto(
    val attacker: Long,
    val card: String,
    val defendingPlayer: Int,
)

/**
 * One legal (blocker, attacker) pairing option (CR 509.1).
 *
 * @property blocker the defending creature that would block.
 * @property blockerCard the blocker's printed name.
 * @property attacker the declared attacker it would block.
 * @property attackerCard the attacker's printed name.
 */
@Serializable
data class BlockerOptionDto(
    val blocker: Long,
    val blockerCard: String,
    val attacker: Long,
    val attackerCard: String,
)

/**
 * One attacker's CR 509.1b blocker-count floor: it may be blocked by no creature at all, or by
 * [minimum] or more, and by nothing in between. Troll of Khazad-dûm's "can't be blocked except by three
 * or more creatures". Added by `W8-E`.
 *
 * Carried alongside the pairing options rather than folded into them because the restriction is a
 * property of the whole declaration and not of any one pairing — see
 * [dev.mtgplay.core.card.Evasion.BLOCKABLE_ONLY_BY_THREE_OR_MORE].
 *
 * @property attacker the declared attacker the floor applies to.
 * @property attackerCard the attacker's printed name.
 * @property minimum the smallest legal non-zero number of blockers; two or more.
 */
@Serializable
data class BlockerMinimumDto(
    val attacker: Long,
    val attackerCard: String,
    val minimum: Int,
)

/**
 * One simultaneous-trigger option to be ordered (CR 603.3b).
 *
 * @property sourceCard the printed name of the trigger's source.
 * @property description a short human description of the trigger.
 */
@Serializable
data class TriggerOptionDto(
    val sourceCard: String,
    val description: String,
)

/**
 * One applicable replacement-effect option (CR 616.1).
 *
 * @property description a short human description of the replacement.
 */
@Serializable
data class ReplacementOptionDto(
    val description: String,
)

/**
 * One complete arrangement of a privately looked-at pool (CR 701.14a, CR 701.17a, CR 701.44a). Each list
 * holds **indices into the request's `pool`**, and the four together cover every pool index exactly once.
 *
 * @property toHand the cards put into the deciding seat's hand, in the order they enter it.
 * @property toTop the cards put on top of the library, topmost first.
 * @property toBottom the cards put on the bottom of the library, in placement order — the first ends up
 *   above the last.
 * @property toGraveyard the cards put into the deciding seat's graveyard, in placement order — surveil's
 *   "put any number of them into your graveyard" (CR 701.44a). Empty for every other look; added by
 *   `W8-A`.
 */
@Serializable
data class LibraryArrangementDto(
    val toHand: List<Int>,
    val toTop: List<Int>,
    val toBottom: List<Int>,
    val toGraveyard: List<Int>,
)
