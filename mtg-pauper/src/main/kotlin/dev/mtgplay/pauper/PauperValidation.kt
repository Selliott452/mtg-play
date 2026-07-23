package dev.mtgplay.pauper

/**
 * Validates a [LoadedDeck] against Pauper deck-construction rules (P6.1): per-card legality and the
 * CR 100.2a construction constraints.
 *
 * The report lists **every** violation, never just the first (deliverable spec): a deck with three
 * problems yields three [DeckViolation]s, so a decklist is corrected in one pass. Legality is read
 * from each card's authoritative snapshot value ([CardMetadata.pauperLegality]); the construction
 * rules are the Pauper-specific reading of CR 100.2a — a mainboard of at least 60, a sideboard of at
 * most 15, and at most four copies of a card across both boards except basic lands (CR 205.4).
 */
object PauperValidator {
    /** The Pauper minimum mainboard size (CR 100.2a). */
    const val MIN_MAIN_DECK_SIZE: Int = 60

    /** The Pauper maximum sideboard size. */
    const val MAX_SIDEBOARD_SIZE: Int = 15

    /** The copy limit for a non-basic card across both boards (CR 100.2a). */
    const val MAX_COPIES: Int = 4

    /** Validates [deck], returning a report of every construction and legality violation. */
    fun validate(deck: LoadedDeck): DeckValidationReport {
        val violations =
            buildList {
                addAll(constructionViolations(deck))
                addAll(illegalCardViolations(deck))
                addAll(copyLimitViolations(deck))
            }
        return DeckValidationReport(deckName = deck.name, violations = violations)
    }

    private fun constructionViolations(deck: LoadedDeck): List<DeckViolation> =
        buildList {
            if (deck.mainCount < MIN_MAIN_DECK_SIZE) {
                add(DeckViolation.MainDeckTooSmall(deck.mainCount, MIN_MAIN_DECK_SIZE))
            }
            if (deck.sideboardCount > MAX_SIDEBOARD_SIZE) {
                add(DeckViolation.SideboardTooLarge(deck.sideboardCount, MAX_SIDEBOARD_SIZE))
            }
        }

    // One violation per distinct non-legal card, in first-appearance order across both boards.
    private fun illegalCardViolations(deck: LoadedDeck): List<DeckViolation> =
        (deck.main + deck.sideboard)
            .distinctBy { it.ref.name }
            .filter { it.metadata.pauperLegality != Legality.LEGAL }
            .map { DeckViolation.IllegalCard(it.ref.name, it.metadata.pauperLegality) }

    // Total copies per card across both boards; a non-basic card over the limit is one violation,
    // in first-appearance order. Basic lands (CR 205.4) are exempt (CR 100.2a).
    private fun copyLimitViolations(deck: LoadedDeck): List<DeckViolation> {
        val all = deck.main + deck.sideboard
        return all
            .distinctBy { it.ref.name }
            .mapNotNull { first ->
                if (first.metadata.isBasic) return@mapNotNull null
                val total = all.filter { it.ref.name == first.ref.name }.sumOf { it.count }
                if (total > MAX_COPIES) DeckViolation.TooManyCopies(first.ref.name, total, MAX_COPIES) else null
            }
    }
}

/**
 * The outcome of validating a deck (P6.1): the deck name and every violation found.
 *
 * @property deckName the validated deck's name.
 * @property violations every construction and legality violation, in a deterministic order
 *   (construction, then illegal cards, then copy-limit, each in first-appearance order); empty for
 *   a legal deck.
 */
data class DeckValidationReport(
    val deckName: String,
    val violations: List<DeckViolation>,
) {
    /** Whether the deck is legal for Pauper construction (no violations). */
    val isLegal: Boolean get() = violations.isEmpty()
}

/**
 * One Pauper deck-construction or legality violation (P6.1). A sealed hierarchy so a report reader
 * `when`s over the kinds exhaustively.
 */
sealed interface DeckViolation {
    /** A human-readable description of the violation. */
    val description: String

    /**
     * A card not legal in Pauper (CR 100.2 legality): [name] carries the non-legal [legality] the
     * snapshot records.
     */
    data class IllegalCard(
        val name: String,
        val legality: Legality,
    ) : DeckViolation {
        override val description: String get() = "\"$name\" is not Pauper-legal (${legality.scryfall})"
    }

    /** The mainboard has fewer than [minimum] cards (CR 100.2a): it holds [size]. */
    data class MainDeckTooSmall(
        val size: Int,
        val minimum: Int,
    ) : DeckViolation {
        override val description: String get() = "mainboard has $size cards; CR 100.2a requires at least $minimum"
    }

    /** The sideboard exceeds [maximum] cards: it holds [size]. */
    data class SideboardTooLarge(
        val size: Int,
        val maximum: Int,
    ) : DeckViolation {
        override val description: String get() = "sideboard has $size cards; the maximum is $maximum"
    }

    /**
     * A non-basic card exceeds the [maximum] copy limit across both boards (CR 100.2a): [name]
     * appears [count] times.
     */
    data class TooManyCopies(
        val name: String,
        val count: Int,
        val maximum: Int,
    ) : DeckViolation {
        override val description: String get() = "\"$name\" appears $count times; CR 100.2a allows at most $maximum"
    }
}
