package dev.mtgplay.protocol

/**
 * The match-protocol schema version (ADR-008), carried in every [ServerMessage]/[ClientMessage]
 * envelope so a peer can reject a mismatch loudly rather than silently misreading the wire format.
 *
 * The schema is same-repo with the engine (ADR-008 amendment): a new `DecisionRequest` kind is a
 * compile-time break of the exhaustive DTO mapping, so schema versions track engine versions. Bump
 * this whenever the wire shape changes in a way a peer must know about.
 *
 * **Held at 1.0.0 through P7.3.** The [ServerMessage.Error] variant added in P7.3 is a purely additive
 * schema change and the protocol is still pre-release with no external consumers, so the version is
 * deliberately not bumped: there is no deployed peer that could observe the difference. The first bump
 * is reserved for the first change that a *released* consumer must be told about.
 */
const val PROTOCOL_VERSION: String = "1.0.0"
