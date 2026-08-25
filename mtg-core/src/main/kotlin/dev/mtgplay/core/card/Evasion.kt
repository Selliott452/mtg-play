package dev.mtgplay.core.card

/**
 * A printed evasion ability that restricts how a creature can be blocked (CR 509.1b) — the
 * modelled complement to [Keyword.FLYING], for evasion abilities that are ability *text* rather
 * than a named keyword (CR 702) and so do not belong in [Keyword].
 *
 * Exactly the evasions the gauntlet pool needs (docs/decklists.md). Carried on
 * [PrintedCharacteristics] as a printed characteristic — and, since the keyword-tail packet, **also
 * grantable**: that packet took the future this KDoc reserved ("a future granting effect would route
 * it through the layer system exactly as keyword grants are"), because Gingerbrute's `{1}` ability
 * grants [BLOCKABLE_ONLY_BY_HASTE] to itself until end of turn. Combat therefore no longer reads
 * printed evasions directly; it reads the CR 613 layer-6 union through `effectiveEvasions` in
 * `mtg-rules`, exactly as it reads keywords and protections.
 *
 * A "can't be blocked except by …" line **is** a static ability, so CR 613.1f layer 6 is its home and
 * the grant is additive like every other: two evasions on one attacker restrict cumulatively
 * (CR 509.1b), which the set gives for free.
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

    /**
     * "This creature can't be blocked except by creatures with haste" (Gingerbrute) — a block-legality
     * restriction (CR 509.1b) keyed on the *blocker* having [Keyword.HASTE]. Added by the keyword-tail
     * packet.
     *
     * The pool's first **granted** evasion, and the reason evasions gained a layer-6 seam. Gingerbrute
     * prints no evasion at all: its `{1}` ability *creates* one for the turn, so the restriction has no
     * printed value to read and only exists as a resolution-generated continuous effect
     * (CR 611.2 — `dev.mtgplay.core.state.ContinuousModification.grantedEvasions`).
     *
     * It reads the blocker's haste through the same effective-keyword seam the attack bar uses, so a
     * blocker whose haste is itself granted (Goblin Tomb Raider while you control an artifact) may
     * block, and one whose haste has worn off may not. Note what it does **not** ask: haste has nothing
     * to do with the blocker being untapped or summoning sick — CR 302.6 never restricted blocking — so
     * this is a pure characteristic test on the blocker and nothing more.
     */
    BLOCKABLE_ONLY_BY_HASTE,

    /**
     * "This creature can't be blocked except by three or more creatures" (Troll of Khazad-dûm) — a
     * block-legality restriction (CR 509.1b) on the **number** of blockers rather than on any blocker's
     * characteristics. Added by `W8-E`. The three-blocker sibling of menace (CR 702.110a), which is the
     * same sentence with "two".
     *
     * **The first evasion that is not a property of a (blocker, attacker) pair**, and that is its whole
     * significance to the engine. Both members above are answered by looking at one blocker: a flyer
     * either has flying or it does not, and either pairing is legal on its own. This one is a property
     * of the **whole declaration** — one creature blocking the Troll is illegal, three are legal, and no
     * amount of inspecting the first creature reveals which. `mtg-rules` therefore publishes it as a
     * per-attacker minimum on the declare-blockers request, so the deciding seat can see the constraint
     * it must satisfy (ADR-005), and enforces it across the chosen set beside the CR 509.1a "a creature
     * blocks at most one attacker" rule that was already a cross-option check.
     *
     * **Zero blockers is always legal**: CR 509.1b restricts *how* a creature may be blocked, never
     * *whether* it must be. The minimum is a floor on a non-empty block, not a requirement to block —
     * conflating the two would force a defending player to throw three creatures under a 6/5 whenever
     * they had them, which is a different card and an enumerated-but-illegal line either way (ADR-005).
     *
     * **Blockers that leave combat afterwards do not un-block it** (CR 506.4, CR 509.1h): the
     * restriction is checked once, as blockers are declared. Kill two of the three in response and the
     * Troll stays blocked by the survivor — which is exactly why the card is played over a plain
     * unblockable body, and why this is a declaration-time check rather than a standing condition.
     */
    BLOCKABLE_ONLY_BY_THREE_OR_MORE,
}
