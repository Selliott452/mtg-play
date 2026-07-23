package dev.mtgplay.pauper

import dev.mtgplay.core.identity.CardRef

/*
 * Shared builders for the format-layer specs: real-catalog resolution plus fabricated metadata for
 * the cases the all-legal snapshot cannot express (a non-legal card).
 */

/** The ingested MVP catalog, shared by the specs. */
internal val testCatalog: CardCatalog = MvpCardPool.catalog

/** The metadata of [name] from the snapshot, failing loudly if absent. */
internal fun snapshotMeta(name: String): CardMetadata =
    testCatalog.metadataFor(name) ?: error("\"$name\" is not in the test snapshot")

/** A fabricated card metadata, for cases the all-legal snapshot cannot supply (e.g. a banned card). */
internal fun fabricatedMeta(
    name: String,
    legality: Legality = Legality.LEGAL,
    basic: Boolean = false,
): CardMetadata =
    CardMetadata(
        name = name,
        manaCost = "{1}",
        typeLine = if (basic) "Basic Land — Test" else "Instant",
        oracleText = "",
        power = null,
        toughness = null,
        colors = emptySet(),
        pauperLegality = legality,
        oracleId = "fabricated-$name",
    )

/** A resolved card of [count] copies of the snapshot card [name]. */
internal fun snapshotCard(
    name: String,
    count: Int,
): ResolvedCard = ResolvedCard(count = count, ref = CardRef(name), metadata = snapshotMeta(name))

/** A resolved card of [count] copies of a fabricated card. */
internal fun fabricatedCard(
    name: String,
    count: Int,
    legality: Legality = Legality.LEGAL,
    basic: Boolean = false,
): ResolvedCard = ResolvedCard(count = count, ref = CardRef(name), metadata = fabricatedMeta(name, legality, basic))

/** A [LoadedDeck] straight from resolved cards, for the construction-rule specs. */
internal fun loadedDeck(
    name: String = "test deck",
    main: List<ResolvedCard>,
    sideboard: List<ResolvedCard> = emptyList(),
): LoadedDeck = LoadedDeck(name = name, main = main, sideboard = sideboard)
