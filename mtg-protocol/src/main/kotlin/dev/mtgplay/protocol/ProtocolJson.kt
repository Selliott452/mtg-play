package dev.mtgplay.protocol

import kotlinx.serialization.json.Json

/**
 * The single configured JSON codec for the match protocol (ADR-008): strict, so a malformed or
 * drifted payload fails loudly rather than being silently reinterpreted.
 *
 * - `ignoreUnknownKeys = false` — an unknown field is a hard error, not tolerated leniency. A peer
 *   sending a field this schema does not know about (a version skew, a hand-rolled message) is
 *   rejected, never half-parsed.
 * - `isLenient = false` — only strict JSON is accepted (quoted keys, no relaxed literals).
 * - `encodeDefaults = true` — every field is written explicitly, so the wire form of a value is
 *   total and does not depend on a decoder sharing the same defaults.
 * - `classDiscriminator = "type"` — the sealed-hierarchy tag (a stable `@SerialName` per leaf), so
 *   polymorphic messages and options round-trip unambiguously.
 *
 * All serialization goes through this instance; there is no second, laxer codec.
 */
val ProtocolJson: Json =
    Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        classDiscriminator = "type"
        prettyPrint = false
    }
