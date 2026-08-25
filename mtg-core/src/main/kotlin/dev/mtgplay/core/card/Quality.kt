package dev.mtgplay.core.card

import dev.mtgplay.core.mana.Color

/**
 * The **quality** a protection ability is protection *from* (CR 702.16a) — "protection from black",
 * "protection from monocolored". Additive, flagged core (`FW-PROTECT`,
 * docs/design/protection.md §4).
 *
 * CR 702.16a: "Protection is a static ability, written 'Protection from [quality].' … The quality is
 * usually a color … but can be any characteristic value or information." **A `Color` field would
 * therefore be wrong**, and wrong in a way that looks right: it carries Mask of Law and Grace
 * ("protection from black and from red") and silently cannot express Guardian of the Guildpact,
 * whose quality is *monocolored* — a derived characteristic, not a member of [Color]. A sealed type
 * with exactly the two shapes the pool prints is the honest middle between that and an open
 * "any characteristic" predicate, which would over-generalise beyond every existing card and put
 * rules logic in `mtg-core` besides (ADR-009).
 *
 * **Core states which quality; `mtg-rules` owns the predicate.** Whether a given source *has* the
 * quality is a rules question — it reads the source's colours — and lives in `mtg-rules`
 * (`Protection.kt`). This type is vocabulary only, the same split
 * docs/design/layer-system.md §2 draws for continuous effects.
 *
 * The sealed hierarchy is the extension point, and its unbuilt members are named rather than faked
 * (docs/design/protection.md, Non-goals): protection from **everything** (CR 702.16j), from a
 * **player** (CR 702.16k), and from a creature type or other characteristic value each get a member
 * when a card in the pool needs one, and until then no code pretends they exist.
 *
 * CR 702.16g — "protection from black and from red" is shorthand for two separate protection
 * abilities, not one ability with a compound quality — is why the *characteristic* holds a set of
 * these rather than this type holding a set of colours. CR 702.16m's "multiple instances of
 * protection from the same quality are redundant" then falls out of that set for free.
 */
sealed interface Quality {
    /**
     * Protection from one colour (CR 702.16a), the usual case: Mask of Law and Grace grants
     * `OfColor(BLACK)` and `OfColor(RED)`.
     */
    data class OfColor(
        val color: Color,
    ) : Quality

    /**
     * Protection from **monocolored** (Guardian of the Guildpact) — a source that is exactly one
     * colour. Not a member of [Color] and not expressible as one, which is the whole reason this
     * type is sealed rather than a colour field.
     *
     * Colourless sources are *not* monocolored (CR 105.4: colorless is the absence of colour, not a
     * sixth colour), and neither are multicolored ones — which is exactly the printed card's
     * famous blind spot.
     */
    data object Monocolored : Quality
}
