package dev.mtgplay.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/*
 * The public codec surface (ADR-008): message <-> JSON string, through the strict [ProtocolJson].
 * Consumers (the P7.2 reference server, the P7.3 client) serialize with these and never touch
 * `kotlinx.serialization` directly, so the web/transport layer inherits no serialization API — only
 * the schema module knows the codec.
 */

/** Serializes this server message to a JSON string (ADR-008). */
fun ServerMessage.encode(): String = ProtocolJson.encodeToString(this)

/** Parses a server message from a JSON string; throws on a malformed or unknown-field payload. */
fun decodeServerMessage(json: String): ServerMessage = ProtocolJson.decodeFromString<ServerMessage>(json)

/** Serializes this client message to a JSON string (ADR-008). */
fun ClientMessage.encode(): String = ProtocolJson.encodeToString(this)

/** Parses a client message from a JSON string; throws on a malformed or unknown-field payload. */
fun decodeClientMessage(json: String): ClientMessage = ProtocolJson.decodeFromString<ClientMessage>(json)
