package dev.mtgplay.rules

/**
 * Build-scaffold placeholder proving `mtg-rules` compiles and is wired into the multi-module build.
 *
 * Introduced by packet P0.1; it carries no game logic and is replaced by real module types in
 * later packets. Kept `internal` so it never leaks into another module's public surface.
 */
internal object ModuleMarker {
    /** Stable module identifier, used only to give the scaffold smoke test something to assert. */
    const val MODULE_NAME: String = "mtg-rules"
}
