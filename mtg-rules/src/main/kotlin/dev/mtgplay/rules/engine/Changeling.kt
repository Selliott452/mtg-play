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
 * It reads the object's **printed** subtypes — no layer-4 type-changing effect exists, so there is
 * nothing layered to add there — and unions on the changeling answer from the **layered** keyword set,
 * which is the half that has to be layered: changeling is an ability, abilities are granted in layer 6
 * (CR 613.1f), and reading it printed would be wrong the day a card grants one.
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
 * The any-zone answer is [dev.mtgplay.core.card.PrintedCharacteristics.hasSubtype], which this
 * delegates to for the printed half; CR 702.73a makes changeling work everywhere, so a card in a
 * library or a graveyard gets the same answer from that accessor without a battlefield object.
 */
fun hasSubtype(
    state: GameState,
    id: ObjectId,
    subtype: Subtype,
): Boolean {
    val characteristics = state.definitions[state.battlefieldObject(id).card]?.characteristics ?: return false
    // The printed answer — printed subtypes, plus a printed changeling — comes from the one core
    // accessor rather than being restated here; only the layer-6 *grant* is this function's own.
    return characteristics.hasSubtype(subtype) ||
        (Keyword.CHANGELING in effectiveKeywords(state, id) && subtype.isCreatureType())
}
