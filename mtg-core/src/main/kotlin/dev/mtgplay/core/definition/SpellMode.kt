package dev.mtgplay.core.definition

/**
 * One mode of a modal spell (CR 700.2) — a single bullet of a "Choose one —" list, carrying its own
 * targeting line and its own resolution instructions. Additive, flagged core (`FW-MODAL`,
 * docs/design/countering-spells.md §8).
 *
 * **A mode is a whole spell's worth of declaration, and that is the framework's central claim.** A modal
 * card is not one spell with a switch inside its resolution; it is a list of alternative spells, exactly
 * one of which (in this pool) is selected at CR 601.2b. Blue Elemental Blast's two modes target a *spell
 * on the stack* and a *battlefield permanent* respectively — different kinds of object, so a single
 * [TargetSpec] on the card could not describe either honestly. That is why [targetSpec] lives here rather
 * than on the card, and it is the mechanical reason CR 601.2b must precede CR 601.2c: until the mode is
 * known, there is no question "what are the legal targets?" to ask.
 *
 * **Restriction versus condition lives here too.** A mode whose targeting line is restricted — Blue
 * Elemental Blast's "Counter target *red* spell" — carries that restriction in its [targetSpec], so an
 * ineligible object is never offered and the mode itself vanishes from enumeration when nothing
 * qualifies. A mode whose *effect* is conditional — Hydroblast's "Counter target spell *if it's red*" —
 * carries an unrestricted [targetSpec] and tests the condition inside its [resolution], so the mode stays
 * enumerable against a target it will do nothing to. Encoding the second shape as the first is a gap in
 * enumeration completeness, which is the single finding docs/design/countering-spells.md §1.2 most warns
 * about; see [ModalSpell] for how the engine keeps them apart.
 *
 * @property text the printed bullet, verbatim from the oracle text, for display (ADR-005 — an agent
 *   picks a mode by index, and this is what the index means).
 * @property targetSpec what this mode demands as targets (CR 115); [TargetSpec.None] for a mode that
 *   targets nothing.
 * @property resolution this mode's resolution instructions (CR 608.2c), run in place of the card's.
 */
data class SpellMode(
    val text: String,
    val targetSpec: TargetSpec,
    val resolution: ResolutionEffect,
)
