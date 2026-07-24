package dev.mtgplay.server

import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.GameEngine
import dev.mtgplay.rules.MatchConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The in-memory registry of hosted matches (ADR-008): create a match from a [MatchConfig] and look
 * one up by [MatchId]. A [ConcurrentHashMap] backs it so concurrent connections resolve their match
 * without a global lock; each [Match] carries its own mutex for state (see [Match]).
 *
 * **Lifecycle / eviction.** A finished match is *retained* so a seat that dropped can still
 * reconnect and re-derive its final [dev.mtgplay.protocol.ServerMessage.GameOver] (the resync path
 * works after game over too). The reference server keeps a match until [evict] is called explicitly;
 * it deliberately ships **no** automatic TTL or reaper — retention/GC policy (how long "briefly"
 * lasts, memory bounds) is an operational concern a real deployment owns (ADR-008 amendment). A
 * caller evicts once both seats have acknowledged the result, or on a schedule of its choosing.
 *
 * @property engine the shared, stateless engine (ADR-004: implementations hold no per-game state, so
 *   one instance serves every match); the [DefaultGameEngine] by default.
 * @property tokenSource how per-seat tokens are minted; the deterministic [SeededTokenSource] by
 *   default (inject an unpredictable source for a real deployment).
 */
class MatchRegistry(
    private val engine: GameEngine = DefaultGameEngine(),
    private val tokenSource: TokenSource = SeededTokenSource,
) {
    private val matches = ConcurrentHashMap<MatchId, Match>()
    private val sequence = AtomicLong()

    /**
     * Starts a new match from [config] (drawing opening hands and advancing to the first decision via
     * the engine), mints its seat tokens, registers it, and returns its [MatchHandle]. Seats are
     * tokenized in ascending seat order so token assignment is independent of [config]'s map order.
     */
    fun create(config: MatchConfig): MatchHandle {
        val id = MatchId("m-${sequence.incrementAndGet()}")
        val seats = config.libraries.keys.sortedBy { it.seat }
        val tokens = tokenSource.mint(config.seed, seats)
        val first: AdvanceResult = engine.start(config)
        matches[id] = Match(id, engine, config, tokens, first)
        return MatchHandle(id, config.seed, tokens)
    }

    /** The live match for [id], or `null` if none (or it was evicted). */
    fun find(id: MatchId): Match? = matches[id]

    /** Removes [id] from the registry; returns whether a match was present. */
    fun evict(id: MatchId): Boolean = matches.remove(id) != null

    /** How many matches are currently held. */
    val size: Int get() = matches.size
}
