package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry

/*
 * The **gate** half of a cost increase (CR 601.2f) — the half `FW-COST`, `W8-C` and `W9-C` each recorded
 * as the reason Kaervek's Torch could not ship, and the half this file exists to make correct.
 *
 * ## The defect, stated exactly
 *
 * `cheapestTargetsFor` prices a cast's legality at **no targets at all** for every card without a
 * target-conditional reduction. For a *reduction* that is the safe direction — pricing without the
 * discount can only over-charge, so the gate is conservative and a cast it admits is one the filter can
 * still serve. For an *increase* it is the unsafe one. With a Torch the only spell on the stack,
 * `castIsLegal` would price a Counterspell at `{U}{U}`, admit it, and `affordableTargetOptions` would then
 * remove its only option — leaving `targetRequest` with an empty list, which its `init` refuses. A crash,
 * not a missing line.
 *
 * ## The fix, and why it is not the trade the notes assumed
 *
 * The correct gate is the **minimum over legal target choices**, which is literally
 * `affordableTargetOptions(...).isNotEmpty()` — consistent with the filter *by construction* rather than
 * by an argument, because it is the same call. The recorded objection was its price: a payment
 * enumeration per candidate target, on every cast in every priority window, "a change to the legality path
 * of every card in the pool for one card".
 *
 * **That objection assumed the expensive gate runs unconditionally, and it does not have to.** A cast can
 * only be taxed if two things are true at once, and both are cheap to ask:
 *
 * 1. **a taxing spell is on the stack** ([stackCarriesTargetTax]) — one scan of a list that is empty in
 *    the overwhelming majority of priority windows and never longer than a handful; and
 * 2. **this card can target a spell at all** ([couldTargetASpell]) — a *static* property of the
 *    definition, no state read at all.
 *
 * When either is false, no target choice can change what this cast costs, so pricing it at no targets is
 * **exact** and the existing path is not merely kept for speed — it is the right answer. Only a spell that
 * can point at a spell, in a game where something on the stack taxes that, ever reaches the enumeration.
 * The blast radius is a boolean-and per cast, and the behaviour change is unreachable in any position
 * without a Torch in it.
 *
 * ## The modal case is real, and it is where the first draft was wrong
 *
 * The obvious containment — refuse a modal card loudly, on the grounds that its cheapest cost depends on
 * a mode CR 601.2b has not settled — is **unavailable**, because the pool's counterspells are modal.
 * Pyroblast and Red Elemental Blast print "Counter target spell, **or** destroy target permanent", and a
 * Torch is exactly the red spell they are held for. A loud refusal there is a crash on a line two gauntlet
 * decks would actually play.
 *
 * The answer is that a modal card needs no whole-cast minimum at all: its gate is already a **disjunction
 * over modes** ([someModeIsCastable]), so narrowing [castableModes] by affordability narrows every
 * question downstream of it at once. A mode whose every legal target the caster could not pay for is not
 * offered; a card whose every mode is unaffordable is not castable; and the per-mode target request that
 * follows filters through the same [affordableTargetOptions] call, so its option list is non-empty by
 * construction. Pyroblast facing a Torch with one Mountain open therefore keeps its "destroy target
 * permanent" mode and loses its counter mode, which is the correct board.
 *
 * ## What is still refused rather than approximated
 *
 * A card printing **more than one targeting line** where one of them names a spell: the minimum would have
 * to be taken over line *assignments* rather than over one line's options. Nothing in the pool prints it,
 * `KaerveksTorchSpec` pins that, and [taxedPricingApplies] fails loudly rather than guessing.
 */

/**
 * Whether any spell on the stack levies [dev.mtgplay.core.definition.StackTargetTax] (CR 601.2f).
 *
 * The cheap half of the gate's pre-check, and the one that reads state. `false` in every game position
 * that contains no such spell, which is every position in every deck but one — so the expensive pricing
 * below is unreachable rather than merely unused.
 */
internal fun stackCarriesTargetTax(state: GameState): Boolean =
    state.sharedZones.stack.any { it is StackEntry.Spell && it.definition.stackTargetTax != null }

/**
 * Whether a cast of [definition] must be priced at the **minimum over its legal target choices** rather
 * than at no targets at all (CR 601.2c/f).
 *
 * True only when a tax is live *and* this card can name a spell. Both halves are necessary and the
 * conjunction is what keeps the correction contained; see this file's header for the argument that the
 * `false` answer is exact rather than an approximation.
 */
internal fun taxedPricingApplies(
    state: GameState,
    definition: SpellDefinition,
): Boolean = stackCarriesTargetTax(state) && couldTargetASpell(definition)

/**
 * Whether the **whole-cast** minimum-over-choices gate applies to [definition] (CR 601.2c/f) — the taxed
 * case narrowed to a card with exactly one targeting line, which is the only shape for which "the minimum
 * over legal target choices" is one enumeration.
 *
 * A modal card is deliberately excluded and is not thereby under-priced: its gate is
 * [someModeIsCastable], whose [castableModes] already drops a mode with no affordable target, so a modal
 * card whose only taxed mode is unaffordable is refused there instead — one mode later and with the same
 * answer. See this file's header.
 */
internal fun wholeCastTaxedPricingApplies(
    state: GameState,
    definition: SpellDefinition,
): Boolean = taxedPricingApplies(state, definition) && definition.modes.isEmpty()

/**
 * Whether some legal target of [spec] leaves a cast of [subject] payable (CR 601.2c/f) — the per-line
 * form of the minimum-over-choices gate, which [castableModes] asks of each mode and
 * [taxedCastIsPayable] asks of a non-modal card's single line.
 */
internal fun someTargetIsAffordable(
    state: GameState,
    seat: PlayerId,
    subject: CastSubject,
    spec: TargetSpec,
): Boolean {
    val self =
        subject.castObjectId
            ?: error("CR 601.2a: a taxed cast must name its card object; the gate cannot price a cast without one")
    val legal = announceableTargets(state, spec, seat, Chooser.Spell(self), TargetContext.NONE)
    return affordableTargetOptions(state, seat, subject, spec, legal).isNotEmpty()
}

/**
 * Whether some legal target choice leaves a cast of [subject] payable (CR 601.2c/f) — the
 * minimum-over-choices gate itself.
 *
 * **The same call the target request makes**, which is the whole point: `PendingCastRequest` offers
 * `affordableTargetOptions` over `announceableTargets`, so a cast admitted here has a non-empty option
 * list there *by construction*. The property the reduction path argues structurally — "the gate prices the
 * cheapest reachable target and the filter keeps the payable ones, so the filtered list contains it" —
 * becomes an identity here, which is the stronger form and the one a cost increase needs.
 *
 * Called only when [taxedPricingApplies], so the card is non-modal with exactly one targeting line and
 * the request's cursor is 0 with no earlier answers — which makes [TargetContext.NONE] the context the
 * request will use, not an approximation of it.
 */
internal fun taxedCastIsPayable(
    state: GameState,
    seat: PlayerId,
    subject: CastSubject,
): Boolean = someTargetIsAffordable(state, seat, subject, subject.definition.targetSpec)

/**
 * Whether [definition] can name a spell on the stack (CR 115.1) — a pure read of the declaration, with no
 * state.
 *
 * Refuses the shapes whose cheapest cost is not one enumeration (see this file's header): a modal card, or
 * one with more than one targeting line, either of which could name a spell. Nothing in the pool prints
 * the combination and a registry test pins that, so the failure is unreachable rather than latent.
 */
private fun couldTargetASpell(definition: SpellDefinition): Boolean {
    require(definition.additionalTargetSpecs.none { it is TargetSpec.SpellOnStack }) {
        "CR 601.2c/f: ${definition.characteristics.name} prints more than one targeting line and one of " +
            "them targets a spell; a cost increase would have to be minimised over line assignments"
    }
    // A modal card's spec belongs to its chosen mode (CR 601.2b) and `SpellDefinition.targetSpec` throws
    // for one, so the two halves are asked separately rather than folded.
    return if (definition.modes.isEmpty()) {
        definition.targetSpec is TargetSpec.SpellOnStack
    } else {
        definition.modes.any { it.targetSpec is TargetSpec.SpellOnStack }
    }
}
