package dev.mtgplay.core.definition

import kotlinx.collections.immutable.PersistentList

/**
 * The [SpellDefinition] refinement for a **modal** card (CR 700.2): a card whose text is a "Choose one —"
 * list of [SpellMode]s, exactly one of which is chosen as the spell is put onto the stack (CR 601.2b).
 * Additive, flagged core (`FW-MODAL`, docs/design/countering-spells.md §8) — the Blasts and Steel
 * Sabotage.
 *
 * **The two inherited members are deliberately unimplementable, and that is the type's whole safety
 * argument.** [SpellDefinition.targetSpec] and [SpellDefinition.resolution] ask "what does this card
 * target?" and "what does this card do?" — questions a modal card cannot answer, because the answer is a
 * property of the chosen mode and no mode is chosen at definition time. Both therefore **throw** here
 * rather than returning a plausible default.
 *
 * The alternative — declaring [TargetSpec.None] and an identity resolution — was rejected on ADR-005
 * grounds. A modal card with those defaults would be enumerated as an untargeted spell that resolves and
 * does nothing: `targetsAvailable` would answer `true` unconditionally, `beginCastGathering` would settle
 * its targets empty, and Blue Elemental Blast would be offered with no red object anywhere on the table
 * and then quietly fizzle into the graveyard. That is precisely the *silent* enumeration defect this
 * packet exists to prevent, and it would be invisible in every test that did not specifically look for
 * it. Throwing converts it into a loud failure at the first call site that forgets the mode — which is
 * the standard `spellDefinitionOf` and the effect primitives already hold.
 *
 * `mtg-rules` reads a modal card only through its mode-aware accessors (`SpellModes.kt`), which take the
 * chosen mode from the [dev.mtgplay.core.state.PendingCast] while gathering and from the
 * [dev.mtgplay.core.state.StackEntry.Spell] cast record afterwards. Nothing in the engine may reach for
 * `definition.targetSpec` on a spell without first asking whether it is modal.
 */
interface ModalSpell : SpellDefinition {
    /**
     * This card's printed modes, in printed order (CR 700.2). The index into *this* list is the mode's
     * **printed** index — the value recorded on the cast record and carried into the replay log — and it
     * is stable regardless of which modes happen to be legal on a given board. Never fewer than two: a
     * one-mode "modal" card is not modal, and modelling one would give the engine a mode decision with a
     * single answer.
     */
    override val modes: PersistentList<SpellMode>

    /**
     * Unanswerable for a modal card (CR 601.2b): the targeting line belongs to the chosen mode, and no
     * mode is chosen until the spell is put onto the stack. Fails loudly rather than defaulting — see the
     * type KDoc for why a default here would be an ADR-005 enumeration defect.
     */
    override val targetSpec: TargetSpec
        get() =
            error(
                "CR 601.2b: ${characteristics.name} is modal, so it has no single target spec — " +
                    "read the chosen mode's (SpellModes.kt), never the card's",
            )

    /**
     * Unanswerable for a modal card (CR 700.2): the instructions belong to the chosen mode. Fails loudly
     * rather than defaulting to the identity — see the type KDoc.
     */
    override val resolution: ResolutionEffect
        get() =
            error(
                "CR 700.2: ${characteristics.name} is modal, so it has no single resolution — " +
                    "run the chosen mode's (SpellModes.kt), never the card's",
            )
}
