package dev.mtgplay.protocol

import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.RevealedCardOutcome
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.PendingRebound
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.PendingHandRevealView
import dev.mtgplay.rules.PendingOpponentDiscardView
import dev.mtgplay.rules.SeatView
import dev.mtgplay.rules.viewFor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The exile-and-return wave on the wire (ADR-007/ADR-008): the CR 607.2 linked-exile record, the
 * CR 702.88a rebound marker and its CR 702.88b free cast, the CR 701.16a revealed hand, and the
 * CR 701.7a opponent discard all survive the strict [ProtocolJson] codec, and the two opposite
 * filtering rulings the wave encodes are pinned here so a later change cannot quietly reverse them.
 * `FW-BLINK`, `FW-LINKEDEXILE`, `FW-HIDDENCHOICE`, `FW-NONCTRLDEC`.
 */
class ExileAndReturnDtoSpec :
    StringSpec({

        "CR 607.2: a permanent's linked-exile record round-trips through the strict codec" {
            val journey =
                GameObject(
                    id = ObjectId(7),
                    card = CardRef("Journey to Nowhere"),
                    owner = PlayerId(0),
                    linkedExiled = persistentListOf(ObjectId(11), ObjectId(12)),
                )
            val json = ProtocolJson.encodeToString(journey.toDto())
            ProtocolJson.decodeFromString<GameObjectDto>(json).toDomain() shouldBe journey
        }

        "CR 702.88a: a rebounding exile card's turn marker round-trips through the strict codec" {
            val exiled =
                GameObject(
                    id = ObjectId(9),
                    card = CardRef("Ephemerate"),
                    owner = PlayerId(0),
                    reboundTurn = 4,
                )
            val json = ProtocolJson.encodeToString(exiled.toDto())
            ProtocolJson.decodeFromString<GameObjectDto>(json).toDomain() shouldBe exiled
        }

        "CR 122.2: an object that has exiled nothing serialises an empty list and a null rebound turn" {
            val bears = GameObject(ObjectId(1), CardRef("Grizzly Bears"), PlayerId(0))
            val dto = bears.toDto()
            dto.linkedExiled shouldContainExactly emptyList()
            dto.reboundTurn shouldBe null
        }

        "CR 702.88b: the rebound casting permission carries a payload-free discriminator and round-trips" {
            val json = ProtocolJson.encodeToString<CastingPermissionDto>(CastingPermission.Rebound.toDto())
            json shouldContain "\"type\":\"rebound\""
            ProtocolJson.decodeFromString<CastingPermissionDto>(json).toDomain() shouldBe CastingPermission.Rebound
        }

        "CR 701.16a/701.7a/702.88b: the three new pending records round-trip inside a seat view" {
            val view =
                seatView().copy(
                    pendingHandReveal = handReveal,
                    pendingOpponentDiscard = opponentDiscard,
                    pendingRebound = PendingRebound(PlayerId(0), ObjectId(21)),
                )
            val json = ProtocolJson.encodeToString(view.toDto())
            ProtocolJson.decodeFromString<SeatViewDto>(json).toDomain() shouldBe view
        }

        "CR 701.16a: a revealed hand crosses the wire in full, because the reveal is public" {
            val json = ProtocolJson.encodeToString(seatView().copy(pendingHandReveal = handReveal).toDto())
            json shouldContain "\"Lightning Bolt\""
            json shouldContain "\"outcome\":\"EXILE_LINKED\""
        }

        "CR 402.1: the opponent-discard projection names no card in the decider's hand" {
            // ADR-007: the options exist only in the deciding seat's own ChooseOpponentDiscards request,
            // so this record is count-only for every seat. Pinned as the whole encoded payload, because
            // `encodeDefaults = true` makes that payload total: a field added here breaks this string.
            ProtocolJson.encodeToString(opponentDiscard.toDto()) shouldBe
                """{"decider":1,"controller":0,"count":1,"remainingCount":0,"sourceCard":"Refurbished Familiar"}"""
        }
    })

/** The CR 701.16a pause of a Mesmeric Fiend: seat 1's hand revealed, seat 0 choosing, exile-and-link. */
private val handReveal =
    PendingHandRevealView(
        decider = PlayerId(0),
        revealer = PlayerId(1),
        revealed =
            listOf(
                GameObject(ObjectId(31), CardRef("Lightning Bolt"), PlayerId(1)),
                GameObject(ObjectId(32), CardRef("Mountain"), PlayerId(1)),
            ),
        outcome = RevealedCardOutcome.EXILE_LINKED,
        sourceCard = CardRef("Mesmeric Fiend"),
    )

/** The CR 701.7a pause of a Refurbished Familiar: seat 1 discarding, seat 0 controlling. */
private val opponentDiscard =
    PendingOpponentDiscardView(
        decider = PlayerId(1),
        controller = PlayerId(0),
        count = 1,
        remainingCount = 0,
        sourceCard = CardRef("Refurbished Familiar"),
    )

/** Seat 0's filtered view at the first pause of a deterministic real-card game. */
private fun seatView(): SeatView {
    val config =
        MatchConfig(
            seed = 1,
            libraries =
                mapOf(
                    PlayerId(0) to List(60) { CardRef("Mountain") },
                    PlayerId(1) to List(60) { CardRef("Mountain") },
                ),
            definitions = MvpCards.definitions,
            mulligansEnabled = false,
            startingPlayer = PlayerId(0),
        )
    val paused = DefaultGameEngine().start(config) as AdvanceResult.NeedsDecision
    return viewFor(paused.state, PlayerId(0))
}
