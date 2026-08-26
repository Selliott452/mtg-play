package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.SpellMode
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState

/*
 * Modality (CR 700.2, CR 601.2b): the single seam through which the engine asks a spell what it targets
 * and what it does, for modal and ordinary cards alike (`FW-MODAL`, docs/design/countering-spells.md §8).
 *
 * **Why the accessors exist at all.** An ordinary card answers "what do you target?" from its own
 * [SpellDefinition.targetSpec]; a modal card cannot, because the answer belongs to the mode chosen at
 * CR 601.2b and no mode is chosen at definition time. [dev.mtgplay.core.definition.ModalSpell] makes that
 * unanswerable question **throw** rather than return a default, so every site that asks it must come
 * through here with the chosen modes in hand. The result is that the CR 601.2b-before-CR 601.2c ordering
 * is enforced by the type system rather than by review: a call site that has no mode yet has nothing to
 * pass, and one that forgets to thread the mode fails loudly on the first modal card it meets.
 *
 * **Arity.** The pool prints only "Choose one —", so exactly one mode is chosen and every accessor here
 * demands exactly one. "Choose up to two" and "Choose two" need a count on the declaration, a
 * multi-select mode decision, and a target per chosen mode — the multi-target framework this packet does
 * not own — and until they arrive an arity other than one is an engine defect, not a rules case.
 */

/**
 * The modes of [definition] that [seat] could legally choose right now (CR 601.2b), by **printed** index:
 * a mode is choosable exactly when every target it demands has at least one legal choice (CR 601.2c), so
 * a mode that would dead-end at the target stage is never offered (ADR-005). Empty for a non-modal card,
 * which has no mode decision at all.
 *
 * **This is where the two Blast templates part company**, and the split is entirely a property of the
 * modes' target specs rather than of any code here:
 *
 * - Blue Elemental Blast's modes restrict the **target** — `SpellOnStack(OfColor(RED))` and
 *   `TargetPermanent(RED_PERMANENT)` — so with no red object anywhere, both modes have no legal target,
 *   this returns empty, and [someModeIsCastable] refuses the cast entirely. The card is absent from the
 *   priority window, which is correct: there is no legal way to cast it.
 * - Pyroblast's modes restrict the **effect** — `SpellOnStack(Any)` and `TargetPermanent(ANY_PERMANENT)`
 *   with the colour test inside the resolution — so against a white spell and a Forest both modes are
 *   choosable, this returns both, and the cast is offered. Casting it is legal; it simply may do nothing
 *   (CR 608.2c). Filtering *that* by the condition would be the enumeration gap
 *   docs/design/countering-spells.md §1.2 warns about, and it is precisely the mistake that copying Blue
 *   Elemental Blast's shape onto Pyroblast would make.
 *
 * [chooser] is the spell that would be cast ([Chooser.Spell]), excluded from its own target enumeration
 * (CR 601.2a) and — since a spell is its own source (CR 113.7c) — the object CR 702.16b tests a
 * protected permanent against.
 */
internal fun castableModes(
    state: GameState,
    definition: SpellDefinition,
    seat: PlayerId,
    chooser: Chooser,
): List<Int> =
    definition.modes.indices.filter { index ->
        targetsAvailable(state, definition.modes[index].targetSpec, seat, chooser)
    }

/**
 * Whether [definition] can be cast at all as far as targeting is concerned (CR 601.2b–c). A non-modal
 * card asks the ordinary question of its own spec; a modal card is castable exactly when **at least
 * one** mode is choosable, because CR 601.2b requires a legal mode and CR 601.2c requires that mode's
 * targets to exist — Blue Elemental Blast with a red permanent but no red spell is castable, with
 * exactly one mode on offer.
 *
 * The one place the engine may ask "can this be cast?" without already knowing the mode, and the reason
 * it can: the answer is a disjunction over modes, not a property of any single one.
 */
internal fun someModeIsCastable(
    state: GameState,
    definition: SpellDefinition,
    seat: PlayerId,
    chooser: Chooser,
): Boolean =
    if (definition.modes.isEmpty()) {
        // CR 601.2c (`W9-C`): every printed instance of the word "target" must have a legal choice, and
        // for a card whose later line depends on an earlier one that is a search rather than a
        // conjunction — see `TargetLines.kt`. A one-line card takes the same single test it always did.
        targetLinesSatisfiable(state, targetLinesOf(definition, emptyList()), seat, chooser)
    } else {
        castableModes(state, definition, seat, chooser).isNotEmpty()
    }

/**
 * The target spec in force for [definition] given the settled [chosenModes] (CR 115, CR 601.2c): the
 * card's own spec for an ordinary card, the chosen mode's for a modal one. Every enumeration, every
 * CR 601.2c re-validation, and every CR 608.2b re-check reads its spec through here, so the set a caster
 * picked from and the set the engine later re-checks against are the same set by construction.
 *
 * Fails loudly on a mode arity other than one, and on a printed index that names no mode: both are
 * engine defects (ADR-005), never rules cases.
 */
internal fun effectiveTargetSpec(
    definition: SpellDefinition,
    chosenModes: List<Int>,
): TargetSpec =
    if (definition.modes.isEmpty()) definition.targetSpec else chosenMode(definition, chosenModes).targetSpec

/**
 * The resolution in force for [definition] given the settled [chosenModes] (CR 608.2c): the card's own
 * effect for an ordinary card, the chosen mode's for a modal one. The sibling of [effectiveTargetSpec],
 * and read at exactly one site — a resolving spell — for the same reason.
 */
internal fun effectiveResolution(
    definition: SpellDefinition,
    chosenModes: List<Int>,
): ResolutionEffect =
    if (definition.modes.isEmpty()) definition.resolution else chosenMode(definition, chosenModes).resolution

/**
 * The single chosen [SpellMode] of the modal card [definition] (CR 700.2). Fails loudly unless exactly
 * one mode was chosen and its printed index names a real mode — the pool prints only "Choose one —", and
 * anything else reaching here means a gathering stage settled the wrong shape.
 */
internal fun chosenMode(
    definition: SpellDefinition,
    chosenModes: List<Int>,
): SpellMode {
    require(chosenModes.size == 1) {
        "CR 700.2: ${definition.characteristics.name} prints \"choose one\", " +
            "so exactly one mode must be chosen, got $chosenModes"
    }
    val index = chosenModes.single()
    require(index in definition.modes.indices) {
        "CR 700.2: mode $index does not exist on ${definition.characteristics.name}, " +
            "which prints ${definition.modes.size}"
    }
    return definition.modes[index]
}
