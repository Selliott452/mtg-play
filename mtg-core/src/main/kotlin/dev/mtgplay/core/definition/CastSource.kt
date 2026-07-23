package dev.mtgplay.core.definition

/**
 * Which zone a cast draws its card from (CR 601.2a) — the "cast-from-elsewhere" seam
 * (docs/decklists.md). Additive, flagged core (P5.2).
 *
 * A spell is normally cast from the [HAND]; the four exile/graveyard mechanics of the MVP pool cast
 * from elsewhere — flashback and escape from the [GRAVEYARD] (CR 702.34, CR 702.139), madness and
 * plot from [EXILE] (CR 702.35, CR 702.140). The casting pipeline is generalized over this so nothing
 * downstream of the propose stage (CR 601.2a) knows where the card came from; only the propose stage
 * and enumeration read it. Named a distinct noun rather than reusing [dev.mtgplay.core.zone.ZoneId]
 * because a cast source is a caster-relative choice (my hand / my graveyard / exile), not a concrete
 * per-seat zone identity.
 */
enum class CastSource {
    /** The caster's hand (CR 402) — the default source of every normal cast (CR 601.2a). */
    HAND,

    /** The caster's graveyard (CR 404) — the source of a flashback or escape cast (CR 113.6). */
    GRAVEYARD,

    /** Exile (CR 406) — the source of a madness or plot cast (CR 113.6). */
    EXILE,
}
