package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Interpreting a "target <permanent>" restriction (CR 115.1b): whether a battlefield object is a
 * legal choice for a spell or ability whose spec carries a [PermanentRestriction].
 *
 * The sibling of [satisfiesEnchantRestriction], and split from it for the same reason the two specs
 * are separate: an Aura's restriction describes what it may be *attached* to (CR 303.4a), this one
 * describes what a spell may *point at*. Both are consulted by the one enumeration in `Targets.kt`,
 * so cast-time legality (CR 601.2c), the CR 608.2b resolution re-check, and the option list an agent
 * sees (ADR-005) are the same predicate by construction.
 *
 * **Card types are read layered** ([effectiveCardTypes]) since `FW-TYPECHANGE`: a CR 613 layer-4
 * effect makes a noncreature artifact a creature, and every arm of this predicate that names a card
 * type must see that or a legal target goes unenumerated (ADR-005). Subtypes likewise, through the one
 * changeling-aware [hasSubtype] seam. **Power** is read through [effectivePower], the CR 613
 * sublayer-7c accessor, so a creature pumped in response to a "power 2 or less" spell stops being a
 * legal target and the spell fizzles (CR 608.2b).
 *
 * **Supertypes and colour stay printed**, which is a statement about the rules rather than about work
 * not done: a supertype change would be layer 4 and a colour change layer 5, and no effect in the pool
 * writes to either — `ActiveEffect` has no supertype or colour field at all, so a printed read here
 * cannot disagree with a layered read that does not exist.
 */

/** The greatest in-game power a [PermanentRestriction.CREATURE_POWER_2_OR_LESS] target may have. */
private const val POWER_TWO_OR_LESS_LIMIT: Int = 2

/** The artifact subtype [PermanentRestriction.CREATURE_OR_VEHICLE]'s second arm names (CR 301.7). */
private val VEHICLE: Subtype = Subtype("Vehicle")

/**
 * Whether the battlefield object [candidate] satisfies [restriction] (CR 115.1b) for the deciding
 * player [you]. Exhaustive over [PermanentRestriction] so a new restriction breaks compilation rather
 * than being silently ignored.
 *
 * An object with no definition is inert — it satisfies nothing, not even
 * [PermanentRestriction.ANY_PERMANENT], because the engine cannot know what it is (the same answer
 * [isCreature] and [satisfiesEnchantRestriction] give).
 *
 * [you] is the caster, activator, or ability controller — the player CR 601.2c/602.2b/603.3d hands the
 * choice to, and the same player again at the CR 608.2b re-check, which is what stops a "permanent you
 * control" spell from being cast against one seat's board and re-checked against the other's. Most
 * restrictions ignore it; [PermanentRestriction.PERMANENT_YOU_CONTROL] and
 * [PermanentRestriction.CREATURE_AN_OPPONENT_CONTROLS] are the two that read it, and they are the
 * reason it is a parameter at all. It is the same parameter [satisfiesEnchantRestriction] already
 * takes, for the same reason.
 */
internal fun satisfiesPermanentRestriction(
    state: GameState,
    restriction: PermanentRestriction,
    candidate: GameObject,
    you: PlayerId,
): Boolean {
    val characteristics = state.definitions[candidate.card]?.characteristics ?: return false
    // CR 613 layer 4: the *in-game* type line, so a type-changed permanent is offered where its printed
    // types would not have been. Read once and threaded into the split arms below, so the helpers
    // cannot answer from three different reads.
    val cardTypes = effectiveCardTypes(state, candidate.id)
    val isCreature = CardType.CREATURE in cardTypes
    return when (restriction) {
        PermanentRestriction.ANY_PERMANENT -> true
        PermanentRestriction.CREATURE -> isCreature
        // CR 205.4: "nonlegendary" excludes exactly the legendary supertype.
        PermanentRestriction.NONLEGENDARY_CREATURE ->
            isCreature && Supertype.LEGENDARY !in characteristics.supertypes
        // CR 613 sublayer 7c: the *in-game* power, so a pump in response makes the target illegal.
        // Guarded on creature-hood first — [effectivePower] fails loudly on an object with no P/T box.
        PermanentRestriction.CREATURE_POWER_2_OR_LESS ->
            isCreature && effectivePower(state, candidate.id) <= POWER_TWO_OR_LESS_LIMIT
        PermanentRestriction.ARTIFACT,
        PermanentRestriction.NONCREATURE_ARTIFACT,
        PermanentRestriction.ENCHANTMENT,
        PermanentRestriction.LAND,
        PermanentRestriction.CREATURE_OR_VEHICLE,
        -> satisfiesTypeRestriction(state, restriction, candidate, cardTypes, isCreature)
        PermanentRestriction.RED_PERMANENT,
        PermanentRestriction.BLUE_PERMANENT,
        -> satisfiesColourRestriction(restriction, characteristics)
        PermanentRestriction.PERMANENT_YOU_CONTROL,
        PermanentRestriction.CREATURE_AN_OPPONENT_CONTROLS,
        PermanentRestriction.ARTIFACT_OR_ENCHANTMENT_AN_OPPONENT_CONTROLS,
        PermanentRestriction.CREATURE_YOU_CONTROL,
        PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL,
        -> satisfiesControlRestriction(restriction, cardTypes, candidate, you, isCreature)
    }
}

/**
 * The **card-type** arms of [satisfiesPermanentRestriction] (CR 205.2), split out beside
 * [satisfiesColourRestriction] and [satisfiesControlRestriction] to keep that `when` inside detekt's
 * complexity budget. None of them reads the deciding player: what card types a permanent has is a
 * property of the object alone.
 *
 * [PermanentRestriction.CREATURE_OR_VEHICLE] is the one arm that is not a bare card-type test, and it is
 * here because it is *half* of one: a creature qualifies by card type (CR 302) and a Vehicle by the
 * artifact subtype (CR 301.7), which is the only disjunction in the family that crosses those two axes.
 * [cardTypes] and [isCreature] are passed in rather than recomputed so the caller's single layered read
 * is the one answer.
 */
private fun satisfiesTypeRestriction(
    state: GameState,
    restriction: PermanentRestriction,
    candidate: GameObject,
    cardTypes: Set<CardType>,
    isCreature: Boolean,
): Boolean =
    when (restriction) {
        PermanentRestriction.ARTIFACT -> CardType.ARTIFACT in cardTypes
        // CR 205.1a: an artifact that is not *also* a creature. Both halves read the layered type line,
        // so an artifact a layer-4 effect has already animated is correctly excluded.
        PermanentRestriction.NONCREATURE_ARTIFACT -> CardType.ARTIFACT in cardTypes && !isCreature
        // CR 303: an Aura is an enchantment, so every Aura in the pool qualifies.
        PermanentRestriction.ENCHANTMENT -> CardType.ENCHANTMENT in cardTypes
        // CR 305: any land. An artifact land satisfies this *and* [ARTIFACT] — a permanent has every
        // card type printed on it (CR 205.1a) — which is why this is a card-type test, not an exclusion.
        PermanentRestriction.LAND -> CardType.LAND in cardTypes
        // CR 302 / CR 301.7: a creature by card type, or a Vehicle by subtype. A crewed Vehicle
        // qualifies both ways; crew (CR 702.122) is the layer-4 effect that would grant the type, and
        // now that layer 4 exists the subtype is read through the one battlefield seam that sees it.
        PermanentRestriction.CREATURE_OR_VEHICLE -> isCreature || hasSubtype(state, candidate.id, VEHICLE)
        else -> error("CR 205.2: $restriction is not a card-type restriction")
    }

/** The card types Troublemaker Ouphe's "artifact or enchantment" admits (CR 205.2b). */
private val ARTIFACT_OR_ENCHANTMENT: Set<CardType> = setOf(CardType.ARTIFACT, CardType.ENCHANTMENT)

/** The card types Ghostly Flicker's "artifacts, creatures, and/or lands" admits (CR 205.2b). */
private val BLINKABLE_TYPES: Set<CardType> = setOf(CardType.ARTIFACT, CardType.CREATURE, CardType.LAND)

/**
 * The colour arms of [satisfiesPermanentRestriction] (CR 202.2), split out to keep that `when` inside
 * detekt's complexity budget. Colour is derived from the printed mana cost — the same derivation
 * [satisfiesSpellRestriction] makes for a spell on the stack, and with the same CR 204 limit. A land
 * has no mana cost and is therefore colourless (CR 105.2), so no land ever qualifies.
 */
private fun satisfiesColourRestriction(
    restriction: PermanentRestriction,
    characteristics: PrintedCharacteristics,
): Boolean =
    when (restriction) {
        PermanentRestriction.RED_PERMANENT -> Color.RED in characteristics.colors
        PermanentRestriction.BLUE_PERMANENT -> Color.BLUE in characteristics.colors
        else -> error("CR 202.2: $restriction is not a colour restriction")
    }

/**
 * The decider-relative arms of [satisfiesPermanentRestriction] (CR 109.5, CR 102.1), split out beside
 * [satisfiesColourRestriction] to keep that `when` inside detekt's complexity budget. Control is
 * ownership in the current pool — nothing in the gauntlet changes control of a permanent
 * (docs/design/layer-system.md §4) — and these are the arms that must start reading a real controller
 * the day one does.
 *
 * Takes the whole layered [cardTypes] set rather than a pre-computed creature flag because
 * [PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL] is a **union** over three card types
 * (CR 205.1a) rather than a question about one.
 */
private fun satisfiesControlRestriction(
    restriction: PermanentRestriction,
    cardTypes: Set<CardType>,
    candidate: GameObject,
    you: PlayerId,
    isCreature: Boolean,
): Boolean {
    val yours = candidate.owner == you
    return when (restriction) {
        PermanentRestriction.PERMANENT_YOU_CONTROL -> yours
        PermanentRestriction.CREATURE_YOU_CONTROL -> isCreature && yours
        PermanentRestriction.CREATURE_AN_OPPONENT_CONTROLS -> isCreature && candidate.owner != you
        // CR 205.1a/205.2b: a disjunction over two card types, then the control test.
        PermanentRestriction.ARTIFACT_OR_ENCHANTMENT_AN_OPPONENT_CONTROLS ->
            candidate.owner != you && cardTypes.any { it in ARTIFACT_OR_ENCHANTMENT }
        // CR 205.2b: a permanent may have several card types, so "and/or" is a disjunction over them —
        // one match is enough, and an artifact land satisfies it twice over.
        PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL ->
            yours && cardTypes.any { it in BLINKABLE_TYPES }
        else -> error("CR 109.5: $restriction is not a control restriction")
    }
}
