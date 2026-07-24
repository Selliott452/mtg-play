package dev.mtgplay.server

/*
 * The reference server's application-specific WebSocket close codes (ADR-008). The 4000–4999 range
 * is reserved by RFC 6455 for application use. A fatal handshake or takeover closes the socket with
 * one of these, mirroring the equivalent HTTP status so a client can classify the close without
 * parsing the reason text. Recoverable, in-game rejections do NOT close — they use [ServerError].
 */

/** Close code for a connection that presented a token matching no seat (mirrors HTTP 401). */
internal const val CLOSE_BAD_TOKEN: Short = 4401

/** Close code for a connection whose route named an unknown match (mirrors HTTP 404). */
internal const val CLOSE_MATCH_NOT_FOUND: Short = 4404

/** Close code for a connection superseded by a newer one for the same seat (mirrors HTTP 409). */
internal const val CLOSE_SUPERSEDED: Short = 4409
