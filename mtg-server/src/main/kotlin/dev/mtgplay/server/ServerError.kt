package dev.mtgplay.server

import dev.mtgplay.protocol.PROTOCOL_VERSION

/**
 * The reference server's structured error reply (ADR-008): sent to a seat whose message the server
 * could not accept, so a bad decision is answered loudly and the client can recover, never crashing
 * the connection or corrupting the match.
 *
 * **Why a server-local frame rather than a protocol message:** the `mtg-protocol` schema has no
 * error `ServerMessage` variant (its `ServerMessage` is `SeatUpdate` | `GameOver`). The reference
 * server therefore defines this minimal, deliberately schema-adjacent frame here. A first-class
 * error message in the schema is an architect decision (a schema gap under the STOP protocol,
 * ADR-008 amendment), flagged in the P7.2 report — until then this stand-in keeps the transport
 * honest. It is hand-encoded (mtg-server applies no `kotlinx.serialization` plugin) so it carries no
 * dependency on the schema module's private codec.
 *
 * The wire form is a flat JSON object carrying [PROTOCOL_VERSION] (like every envelope), the error
 * [code], and a human [detail]:
 * `{"protocolVersion":"1.0.0","error":"WRONG_SEAT","detail":"..."}`.
 *
 * @property code the machine-readable category (see [ServerErrorCode]).
 * @property detail a human-readable explanation; safe to log or surface.
 */
data class ServerError(
    val code: ServerErrorCode,
    val detail: String,
) {
    /** The frame's JSON wire form (see the class KDoc). [detail] is JSON-string-escaped. */
    fun encode(): String =
        buildString {
            append("{\"protocolVersion\":\"")
            append(PROTOCOL_VERSION)
            append("\",\"error\":\"")
            append(code.name)
            append("\",\"detail\":\"")
            append(escapeJsonString(detail))
            append("\"}")
        }

    companion object {
        // "error" precedes "detail" in the encoded object, so the first match is always the code
        // field even if an (escaped) detail string happens to contain the same token.
        private val CODE_PATTERN = Regex("\"error\"\\s*:\\s*\"([A-Z_]+)\"")
        private const val UNICODE_ESCAPE_CEILING: Int = 0x20
        private const val HEX_RADIX: Int = 16
        private const val UNICODE_HEX_WIDTH: Int = 4

        /**
         * Reads the [ServerErrorCode] out of an encoded frame, or `null` if [json] is not a server
         * error frame. Used by clients (and the test wire clients) to classify a received frame that
         * is not a [dev.mtgplay.protocol.ServerMessage].
         */
        fun errorCodeOf(json: String): ServerErrorCode? =
            CODE_PATTERN
                .find(json)
                ?.groupValues
                ?.get(1)
                ?.let { name -> ServerErrorCode.entries.firstOrNull { it.name == name } }

        private fun escapeJsonString(raw: String): String =
            buildString {
                for (character in raw) {
                    when (character) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else ->
                            if (character.code < UNICODE_ESCAPE_CEILING) {
                                append("\\u")
                                append(character.code.toString(HEX_RADIX).padStart(UNICODE_HEX_WIDTH, '0'))
                            } else {
                                append(character)
                            }
                    }
                }
            }
    }
}

/**
 * The categories of [ServerError] the reference server emits (ADR-008). Each maps a rejected input
 * to a stable, machine-readable reason so a client can distinguish "you are not the deciding seat"
 * from "your request id is stale" without parsing prose.
 */
enum class ServerErrorCode {
    /** The connect handshake presented a token that matches no seat of the addressed match. */
    BAD_TOKEN,

    /** The connect route named a match id the registry does not hold. */
    MATCH_NOT_FOUND,

    /** A decision arrived from a seat that is not the one the engine is waiting on. */
    WRONG_SEAT,

    /** A decision named a request id other than the one currently pending (a stale/echoed id). */
    STALE_REQUEST,

    /** A decision was for the pending request but the engine rejected it (e.g. out-of-range index). */
    INVALID_DECISION,

    /** A frame could not be decoded as a client message (malformed JSON, unknown field/kind). */
    MALFORMED_MESSAGE,

    /** A well-formed client message carried a protocol version this server does not speak. */
    UNSUPPORTED_VERSION,

    /** A decision arrived while the match was over (or otherwise not paused on a request). */
    NO_PENDING_DECISION,
}
