package dev.mtgplay.pauper

/*
 * Classpath-resource loading for the format layer's bundled data (the Scryfall snapshot and the
 * decklist fixtures). One place so the loud "resource missing" failure is uniform.
 */

/** A marker for locating the module's bundled resources on the classpath. */
private object ResourceAnchor

/** The UTF-8 byte-order mark; some tools (the staged snapshot among them) prefix a text file with it. */
private const val BYTE_ORDER_MARK = "﻿"

/**
 * Reads the bundled resource at [path] (an absolute classpath path such as `/scryfall-mvp.json`)
 * as UTF-8 text, stripping a leading byte-order mark if present. Fails loudly if the resource is
 * absent — a packaging error must never be read as empty data (CONVENTIONS.md: fail loudly). The
 * BOM is stripped rather than the staged snapshot edited: the snapshot is architect-owned data.
 */
internal fun readResourceText(path: String): String =
    (
        ResourceAnchor::class.java.getResource(path)?.readText(Charsets.UTF_8)
            ?: error("bundled resource \"$path\" is missing from the classpath")
    ).removePrefix(BYTE_ORDER_MARK)
