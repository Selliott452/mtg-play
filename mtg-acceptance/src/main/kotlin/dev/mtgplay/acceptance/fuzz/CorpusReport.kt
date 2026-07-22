package dev.mtgplay.acceptance.fuzz

/**
 * The value a fuzz corpus run returns (deliverable 1 of P3.3): the per-seed [SeedOutcome]s plus the
 * aggregate tallies a suite asserts against.
 *
 * That a report exists at all is itself the core fuzz guarantee (PLAN.md §2.3): the harness only
 * returns one when every seed played to a conclusion or a cap with no exception, no invariant
 * violation, and no enumeration-completeness probe failure — any of those aborts the run loudly with
 * a persisted repro instead of producing a report. So a suite that receives a report already knows
 * "nothing crashed"; the aggregates below let it assert the *positive* properties too (both endings
 * occur, the intended death paths fire, both matchup win paths appear).
 *
 * @property name the corpus' human-readable name (for reports and repro files).
 * @property seedOutcomes one classified outcome per seed, in seed order.
 */
data class CorpusReport(
    val name: String,
    val seedOutcomes: List<SeedOutcome>,
) {
    /** How many seeds this corpus played. */
    val seedCount: Int get() = seedOutcomes.size

    /** How many games reached a game-over state within the caps (CR 104.1). */
    val decisive: Int get() = seedOutcomes.count { it.outcome != Outcome.INCONCLUSIVE }

    /** How many games hit a cap without ending — board stalls, not failures. */
    val inconclusive: Int get() = seedOutcomes.count { it.outcome == Outcome.INCONCLUSIVE }

    /** How many decisive games ended in a deck-out (CR 704.5c). */
    val deckOuts: Int get() = seedOutcomes.count { it.outcome == Outcome.DECISIVE_DECK_OUT }

    /** How many decisive games ended on a life total of 0 or less (CR 704.5a). */
    val lifeLosses: Int get() = seedOutcomes.count { it.outcome == Outcome.DECISIVE_LIFE_LOSS }

    /** Total creatures that died across the corpus (CR 704.5f/g). */
    val creatureDeaths: Int get() = seedOutcomes.sumOf { it.creatureDeaths }

    /** Total spells that fizzled across the corpus (CR 608.2b). */
    val fizzles: Int get() = seedOutcomes.sumOf { it.fizzles }

    /** Total decision windows probed for enumeration completeness across the corpus. */
    val probedWindows: Int get() = seedOutcomes.sumOf { it.probedWindows }

    /** Total individual options the completeness probe checked across the corpus. */
    val probedOptions: Int get() = seedOutcomes.sumOf { it.probedOptions }

    /**
     * A one-line human-readable digest of the aggregates, for a suite to print or a report to quote.
     */
    fun summary(): String =
        "corpus \"$name\": $seedCount seeds, $decisive decisive " +
            "($deckOuts deck-out, $lifeLosses life), $inconclusive inconclusive; " +
            "$creatureDeaths creature death(s), $fizzles fizzle(s); " +
            "probed $probedOptions option(s) across $probedWindows window(s)"
}
