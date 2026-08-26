package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.EnchantRestriction
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
 * **Arity.** [dev.mtgplay.core.definition.ModeChoice] carries it, and every accessor below is written
 * over a *list* of chosen modes because "Choose up to two" is a different shape of decision and not
 * merely a wider bound (`W9-B`). Two consequences are worth stating out loud:
 *
 * - **Each chosen mode is its own targeting line.** CR 115.3 is explicit that "if the spell or ability
 *   uses the word 'target' in multiple places, the same object or player can be chosen once for each
 *   instance of the word 'target'", so two chosen modes are two independent target choices — and
 *   naming one graveyard card for two different bullets is *legal*. That is what keeps the mode decision
 *   a plain subset choice: no combination of modes can be jointly unsatisfiable, so none has to be
 *   filtered out, and [castableModes] asking each mode about itself is exactly the right gate.
 * - **Choosing zero modes is legal for an "up to N" card**, and the spell still resolves, doing nothing
 *   (CR 700.2). So a modal card whose every mode is dead is still castable when its minimum is zero —
 *   see [someModeIsCastable], which is the one place that asymmetry lives.
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
    castVia: CastingPermission? = null,
): Boolean =
    when {
        // CR 702.103b (`W10-C`): the spec asked about is the one the *cast* puts in force, not the one
        // printed on the card — a card cast for its bestow cost is an Aura spell and needs a creature to
        // enchant, so a board with none makes that cast unavailable while the ordinary cast stays legal.
        definition.modes.isEmpty() -> targetsAvailable(state, specInForce(definition, castVia), seat, chooser)
        // CR 700.2: an "up to N" card may choose *no* modes, so it is castable whatever the board looks
        // like — Call Damage Control with an empty graveyard is a legal cast that resolves doing
        // nothing, and binning it to bait a counter is a real line the engine must not delete (ADR-005).
        definition.modeChoice.minimum == 0 -> true
        // CR 700.2b: a card that must choose N modes needs N modes it could legally choose.
        else -> castableModes(state, definition, seat, chooser).size >= definition.modeChoice.minimum
    }

/**
 * The **targeting lines** in force for [definition] given the settled [chosenModes] (CR 115, CR 601.2c):
 * one spec per chosen mode for a modal card, in chosen order; the card's own single spec for an ordinary
 * one. Every enumeration, every CR 601.2c re-validation and every CR 608.2b re-check reads specs through
 * here, so the set a caster picked from and the set the engine later re-checks against are the same set
 * by construction.
 *
 * A **list** because each bullet is its own instance of the word "target" (CR 115.3): "choose up to two"
 * with both modes chosen asks two questions with two option lists, and collapsing them to one spec would
 * be a different card. An ordinary card is the one-element case, which is why the two are not separate
 * functions.
 *
 * Fails loudly on a printed index that names no mode — an engine defect, never a rules case (ADR-005).
 */
internal fun effectiveTargetSpecs(
    definition: SpellDefinition,
    chosenModes: List<Int>,
    castVia: CastingPermission? = null,
): List<TargetSpec> =
    if (definition.modes.isEmpty()) {
        listOf(specInForce(definition, castVia))
    } else {
        chosenSpellModes(definition, chosenModes).map { it.targetSpec }
    }

/**
 * The single printed targeting line of a **non-modal** card, as the way it is being cast puts it in force
 * (CR 115, CR 702.103b) — the card's own [SpellDefinition.targetSpec], except for a card being cast for
 * its **bestow** cost, which is an Aura spell with enchant creature whatever its type line says.
 *
 * **One card, two target specs, decided by the cost paid** (`W10-C`). This is the first time in the
 * engine that *how* a spell was cast changes *what it targets*, and it is why the permission is threaded
 * through the whole targeting seam rather than read once: CR 601.2c's enumeration, the re-validation, the
 * CR 608.2b re-check and the CR 303.4f attachment must all ask the same question, and a Nyxborn Hydra
 * cast normally targets nothing while the same card cast for bestow must name a creature.
 *
 * A modal card never reaches here: no card prints both modes and bestow, and [effectiveTargetSpecs]
 * keeps the two shapes apart so that combination would need an answer rather than getting a silent one.
 *
 * `null` [castVia] is an ordinary cast from hand, and every other permission leaves the printed spec
 * alone — flashback, madness, escape and the rest change where the card is cast from and what it costs,
 * never what kind of spell it is.
 */
private fun specInForce(
    definition: SpellDefinition,
    castVia: CastingPermission?,
): TargetSpec =
    if (castVia is CastingPermission.Bestow) {
        // CR 702.103b: "an Aura spell with enchant creature" — the restriction is written into the
        // keyword, so it is the same for every bestow card ever printed.
        TargetSpec.Enchantable(EnchantRestriction.CREATURE)
    } else {
        definition.targetSpec
    }

/**
 * The single targeting line in force for [definition] (CR 115), for the call sites that structurally
 * cannot have more than one: the Aura attachment read (CR 303.4f), which only a permanent spell reaches.
 *
 * Fails loudly on anything but exactly one line. That is deliberate rather than defensive: no modal
 * *permanent* spell exists in the pool, and one arriving with two chosen modes would need an answer to
 * "which of the two targets does the Aura attach to?" that this engine does not have. Failing is the
 * honest response; answering "the first" would be a silent guess.
 */
internal fun effectiveTargetSpec(
    definition: SpellDefinition,
    chosenModes: List<Int>,
    castVia: CastingPermission? = null,
): TargetSpec {
    val specs = effectiveTargetSpecs(definition, chosenModes, castVia)
    return specs.singleOrNull()
        ?: error(
            "CR 115: ${definition.characteristics.name} has ${specs.size} targeting lines, but this " +
                "call site can only read one",
        )
}

/**
 * The resolutions in force for [definition] given the settled [chosenModes] (CR 608.2c): the chosen
 * modes' instructions in chosen order for a modal card, the card's own single effect for an ordinary
 * one. The sibling of [effectiveTargetSpecs], and read at exactly one site — a resolving spell — for
 * the same reason.
 *
 * **The list may be empty**, and that is a rules case rather than a defect: an "up to N" card whose
 * controller chose no modes resolves and does nothing (CR 700.2).
 */
internal fun effectiveResolutions(
    definition: SpellDefinition,
    chosenModes: List<Int>,
): List<ResolutionEffect> =
    if (definition.modes.isEmpty()) {
        listOf(definition.resolution)
    } else {
        chosenSpellModes(definition, chosenModes).map { it.resolution }
    }

/**
 * The chosen [SpellMode]s of the modal card [definition], in chosen order (CR 700.2). Fails loudly
 * unless every printed index names a real mode, no mode is chosen twice, and the count lies within the
 * card's own [dev.mtgplay.core.definition.ModeChoice] — all three are enumeration invariants (ADR-005),
 * so a violation reaching here means a gathering stage settled the wrong shape.
 */
internal fun chosenSpellModes(
    definition: SpellDefinition,
    chosenModes: List<Int>,
): List<SpellMode> {
    val choice = definition.modeChoice
    require(chosenModes.size in choice.minimum..choice.maximum) {
        "CR 700.2: ${definition.characteristics.name} chooses ${choice.minimum}..${choice.maximum} " +
            "mode(s), got ${chosenModes.size}: $chosenModes"
    }
    require(chosenModes.distinct().size == chosenModes.size) {
        "CR 700.2: ${definition.characteristics.name} cannot choose the same mode twice, got $chosenModes"
    }
    return chosenModes.map { index ->
        require(index in definition.modes.indices) {
            "CR 700.2: mode $index does not exist on ${definition.characteristics.name}, " +
                "which prints ${definition.modes.size}"
        }
        definition.modes[index]
    }
}
