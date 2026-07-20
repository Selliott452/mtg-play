package dev.mtgplay.core.identity

/**
 * Identifies one game object for as long as it exists in one zone.
 *
 * Per CR 400.7, an object that moves from one zone to another becomes a **new** object with no
 * memory of its former self, so an [ObjectId] is never reused across a zone change: whatever
 * moves the object allocates a fresh id from the game-owned counter
 * ([dev.mtgplay.core.state.GameState.allocateObjectId]). This type is only the identity; the
 * zone-move logic that mints new ids is rules-engine territory (Phase 1.2+).
 *
 * @property value the monotonically allocated id; never reused within a game.
 */
@JvmInline
value class ObjectId(
    val value: Long,
) {
    init {
        require(value >= 0) { "object id must be non-negative, was $value" }
    }
}
