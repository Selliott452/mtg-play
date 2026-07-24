package dev.mtgplay.server

/**
 * The categories of rejection the reference server emits (ADR-008). Each maps a rejected input to a
 * stable, machine-readable reason so a client can distinguish "you are not the deciding seat" from
 * "your request id is stale" without parsing prose.
 *
 * These are the reference server's own vocabulary. On the wire they travel as a
 * [dev.mtgplay.protocol.ServerMessage.Error] whose `code` is the enum constant's [name] — the schema
 * carries a free string there precisely so a different server can use a different set (ADR-008
 * amendment: the schema fixes the envelope, not the category list).
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
