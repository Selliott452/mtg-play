package dev.mtgplay.acceptance.fuzz

import dev.mtgplay.rules.MatchResult

/**
 * How one fuzz game ended (deliverable 1 of P3.3): the classification the harness assigns each
 * seed, combining decisiveness with the ending taxonomy.
 *
 * A game is **decisive** when it reached a game-over state within the corpus' turn and decision
 * caps, and **inconclusive** when it hit a cap first — a board stall or an unresolved grind. The CR
 * imposes no turn limit, so an inconclusive game is not a failure; it was still invariant-checked at
 * every transition (PLAN.md §2.3). A decisive game is further split by *why* the loser lost.
 */
enum class Outcome {
    /** The loser decked out: it attempted to draw from an empty library (CR 104.3c, CR 704.5c). */
    DECISIVE_DECK_OUT,

    /** The loser's life total reached 0 or less (CR 704.5a) — a burn or combat kill. */
    DECISIVE_LIFE_LOSS,

    /** The game reached the turn or decision cap without ending (a board stall, not a failure). */
    INCONCLUSIVE,
}

/**
 * The classified result of one fuzz seed (deliverable 1 of P3.3): a lightweight, retained summary
 * the [CorpusReport] aggregates over. Card-agnostic by construction — the counts below come from the
 * generic event log and the match result, never from any specific card — so the harness stays
 * card-independent (the module's charter). A suite that needs card-specific analysis (which deck's
 * kill, combat versus burn) computes it in the harness' per-seed inspector, where the finished game
 * and its full event log are in hand.
 *
 * @property seed the seed that produced this game (ADR-006).
 * @property outcome the classification.
 * @property result the match result if [outcome] is decisive, or `null` if inconclusive.
 * @property finalTurnNumber the turn number the game was on when it ended or hit the cap.
 * @property decisionCount how many decisions were applied over the game.
 * @property creatureDeaths how many creatures died (CR 704.5f/g) — the count of
 *   [dev.mtgplay.core.event.GameEvent.CreatureDied] in the log.
 * @property fizzles how many spells fizzled (CR 608.2b) — the count of
 *   [dev.mtgplay.core.event.GameEvent.SpellFizzled] in the log.
 * @property probedWindows how many decision windows this game subjected to the completeness probe.
 * @property probedOptions how many individual options the probe checked across those windows.
 */
data class SeedOutcome(
    val seed: Long,
    val outcome: Outcome,
    val result: MatchResult?,
    val finalTurnNumber: Int,
    val decisionCount: Int,
    val creatureDeaths: Int,
    val fizzles: Int,
    val probedWindows: Int,
    val probedOptions: Int,
)
