package dev.mtgplay.core.card

/**
 * A printed keyword ability (CR 702) a card carries in its [PrintedCharacteristics].
 *
 * The MVP-minimal set: exactly the keywords the pinned pool prints or the combat engine
 * consults (docs/decklists.md). Modelled as **printed** characteristics — what the physical
 * card says (CR 702). In-game keywords are *computed* from these by the continuous-effect layer
 * system (CR 613, layer 6) in Phase 4, which grows the set as aura keyword grants arrive
 * (Ethereal Armor, Rancor, Armadillo Cloak). Nouns only: which keyword changes combat, and how,
 * is rules-engine territory (P3.1 onward).
 *
 * Combat consults these only through the documented effective-keyword accessor in `mtg-rules`
 * (never by reading a definition directly), so Phase 4 can reroute the read without touching the
 * combat rules.
 *
 * In P3.1 only [FLYING], [FIRST_STRIKE], and [VIGILANCE] change engine behaviour; [TRAMPLE],
 * [HEXPROOF], and [LIFELINK] are printed vocabulary whose combat/targeting effects land in later
 * packets (trample P5, hexproof P5.2, lifelink P5.2) — present now so the enum never has to
 * shift underneath cards that already print them.
 */
enum class Keyword {
    /** Flying (CR 702.9): can be blocked only by creatures with flying or reach. */
    FLYING,

    /** First strike (CR 702.7): deals combat damage before creatures without it (CR 510.5). */
    FIRST_STRIKE,

    /** Vigilance (CR 702.21): attacking does not cause the creature to tap (CR 508.1f). */
    VIGILANCE,

    /**
     * Trample (CR 702.19): excess combat damage may be assigned to the defending player. Inert
     * in P3.1 (the deterministic minimum wastes excess on the last blocker); the assignment
     * decision arrives in P5.
     */
    TRAMPLE,

    /**
     * Hexproof (CR 702.11): can't be the target of spells or abilities an opponent controls.
     * Inert in P3.1 (a targeting restriction on enumeration, P5.2).
     */
    HEXPROOF,

    /**
     * Lifelink (CR 702.15): damage this creature deals also causes its controller to gain that
     * much life. Inert in P3.1 (a damage-result modification, P5.2).
     */
    LIFELINK,
}
