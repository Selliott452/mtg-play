package dev.mtgplay.server

/**
 * A per-seat bearer credential for one match (ADR-008): a connecting client presents its token and
 * the server maps it to exactly one seat. Tokens are match-scoped and validated only against the
 * match that minted them — there is no global token space.
 *
 * This is the reference server's *only* authentication: it is a seat handle, not a user identity.
 * Real authentication (accounts, TLS, revocation) is a consumer concern (ADR-008 amendment).
 *
 * @property value the opaque token string; compared for exact equality.
 */
@JvmInline
value class SeatToken(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "a seat token cannot be blank" }
    }
}
