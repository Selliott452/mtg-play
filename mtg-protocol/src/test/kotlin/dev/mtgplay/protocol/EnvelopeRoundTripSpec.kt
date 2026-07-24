package dev.mtgplay.protocol

import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.MatchResult
import dev.mtgplay.rules.SeatView
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.viewFor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The message-envelope round-trip and codec contract (ADR-008): both seats' filtered views, the
 * server messages, and the client message survive the strict [ProtocolJson] codec unchanged; every
 * envelope carries [PROTOCOL_VERSION]; and an unknown field is rejected loudly.
 */
class EnvelopeRoundTripSpec :
    StringSpec({
        "ADR-007/ADR-008: both seats' filtered views round-trip through the codec" {
            forEachSeatView { view ->
                val json = ProtocolJson.encodeToString(view.toDto())
                ProtocolJson.decodeFromString<SeatViewDto>(json).toDomain() shouldBe view
            }
        }

        "ADR-008: a seat-update envelope round-trips and carries the protocol version" {
            forEachSeatView { view ->
                val message = seatUpdateMessage(view)
                val json = ProtocolJson.encodeToString<ServerMessage>(message)
                json shouldContain "\"protocolVersion\":\"$PROTOCOL_VERSION\""
                val back = ProtocolJson.decodeFromString<ServerMessage>(json)
                back shouldBe message
                (back as ServerMessage.SeatUpdate).protocolVersion shouldBe PROTOCOL_VERSION
            }
        }

        "ADR-008: a game-over envelope round-trips and carries the protocol version" {
            val view = seatViews().first()
            val result = MatchResult(PlayerId(0), PlayerId(1), LossReason.LIFE_TOTAL_ZERO_OR_LESS)
            val message = gameOverMessage(result, view)
            val json = ProtocolJson.encodeToString<ServerMessage>(message)
            json shouldContain "\"protocolVersion\":\"$PROTOCOL_VERSION\""
            ProtocolJson.decodeFromString<ServerMessage>(json) shouldBe message
        }

        "ADR-008: a decision envelope round-trips and carries the protocol version" {
            val message = decisionMessage(Decision.SingleSelect(DecisionRequestId(PlayerId(0), 0), 0))
            val json = ProtocolJson.encodeToString<ClientMessage>(message)
            json shouldContain "\"protocolVersion\":\"$PROTOCOL_VERSION\""
            ProtocolJson.decodeFromString<ClientMessage>(json) shouldBe message
        }

        "ADR-008: the strict codec rejects an unknown field" {
            val valid =
                ProtocolJson.encodeToString<ClientMessage>(
                    decisionMessage(Decision.SingleSelect(DecisionRequestId(PlayerId(0), 0), 0)),
                )
            val withUnknown = valid.dropLast(1) + ",\"unknownField\":true}"
            shouldThrow<SerializationException> { ProtocolJson.decodeFromString<ClientMessage>(withUnknown) }
        }
    })

/** The two seats' filtered views at the first pause of a deterministic real-card game. */
private fun seatViews(): List<SeatView> {
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
    return listOf(viewFor(paused.state, PlayerId(0)), viewFor(paused.state, PlayerId(1)))
}

private fun forEachSeatView(assert: (SeatView) -> Unit) = seatViews().forEach(assert)
