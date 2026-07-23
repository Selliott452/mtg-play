package dev.mtgplay.core.card

/**
 * A printed evasion ability that restricts how a creature can be blocked (CR 509.1b) — the
 * modelled complement to [Keyword.FLYING], for evasion abilities that are ability *text* rather
 * than a named keyword (CR 702) and so do not belong in [Keyword].
 *
 * The MVP-minimal set: exactly the evasion the pinned pool prints (docs/decklists.md). Modelled as
 * a **printed** characteristic — what the physical card says — carried on
 * [PrintedCharacteristics]. Nothing in the MVP pool grants or removes an evasion, so combat reads
 * it straight from the printed characteristics (the same "no effect modifies it, read printed
 * directly" pattern the type read uses); a future granting effect would route it through the layer
 * system exactly as keyword grants are (CR 613 layer 6).
 *
 * Nouns only: *which* evasion a creature has is core vocabulary; *how* it changes block legality is
 * rules-engine territory (`mtg-rules`, the block-legality seam beside the flying check).
 */
enum class Evasion {
    /**
     * "This creature can't be blocked except by creatures with flying" (Silhana Ledgewalker) — a
     * block-legality restriction (CR 509.1b): the same requirement flying imposes (only a flyer may
     * block), but keyed on this attacker ability rather than on the attacker having flying.
     */
    BLOCKABLE_ONLY_BY_FLYING,
}
