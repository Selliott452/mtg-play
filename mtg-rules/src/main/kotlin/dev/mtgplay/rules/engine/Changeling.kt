package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.isCreatureType
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState

/**
 * Whether the battlefield object [id] has the subtype [subtype] right now (CR 205.3) — the single
 * battlefield seam every subtype predicate consults, so [Keyword.CHANGELING] (CR 702.73a) is honoured
 * at all of them.
 *
 * **Both halves are layered, and they are layered in different layers.** The subtype set comes from
 * [layeredCharacteristics] — printed subtypes unioned with CR 613 layer-4 additions (CR 613.1d), which
 * is how a Kenku-Artificer'd artifact is a Homunculus. The changeling answer comes from the layered
 * *keyword* set, layer 6 (CR 613.1f), because changeling is an ability and a granted one has to count.
 * This function reading printed subtypes was correct only while layer 4 was empty; `FW-TYPECHANGE`
 * filled it, and the seam followed.
 *
 * The creature-type gate is [Subtype.isCreatureType] and is the whole correctness of the feature: a
 * changeling is every creature *type*, so it counts as an Elf for Priest of Titania and Wellwisher and
 * as a Dragon for Breath Weapon, and is **not** a Forest for Gingerbread Cabin or a Mountain for
 * Fireblast. An object with no definition has no subtypes.
 *
 * **Public rules surface** (ADR-003 vocabulary discipline): a card whose text names a creature type —
 * Wellwisher's "for each Elf you control", Breath Weapon's "each non-Dragon creature" — composes this
 * rather than testing the printed set in `mtg-cards`, which is exactly how the two would drift. Both
 * cards held a private copy of the printed test before the keyword-tail packet, and both copies would
 * have silently ignored changeling.
 *
 * The any-zone answer is [dev.mtgplay.core.card.PrintedCharacteristics.hasSubtype]; CR 702.73a makes
 * changeling work everywhere, so a card in a library or a graveyard gets its answer from that accessor
 * without a battlefield object — and correctly gets no layer-4 addition there, because CR 613 does not
 * reach a hidden zone.
 */
fun hasSubtype(
    state: GameState,
    id: ObjectId,
    subtype: Subtype,
): Boolean {
    if (state.battlefieldObject(id).card !in state.definitions) return false
    // The layer-4 half through the narrow, static-free read (see [layeredCardTypes]'s cycle note): a
    // static ability's own activity condition may reach this seam, so the subtype half must not re-enter
    // the full walk. The layer-6 half must, because a *granted* changeling is exactly what it looks for.
    return subtype in layeredSubtypes(state, id) ||
        (Keyword.CHANGELING in effectiveKeywords(state, id) && subtype.isCreatureType())
}
