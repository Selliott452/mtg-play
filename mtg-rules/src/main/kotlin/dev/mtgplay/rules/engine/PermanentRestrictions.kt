package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.TargetContext
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target

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
 * Card types and supertypes are read printed: no type-changing effect exists in the pool
 * (docs/design/layer-system.md §6). **Power is not** — it is read through [effectivePower], the
 * CR 613 sublayer-7c accessor, so a creature pumped in response to a "power 2 or less" spell stops
 * being a legal target and the spell fizzles (CR 608.2b). Reading printed power there would be
 * silently wrong on any board with an Aura.
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
 *
 * [context] is what the choosing object has already settled (`W9-C`): the value announced for its
 * variable cost (CR 601.2b) and the targets it chose for its **earlier** targeting lines (CR 601.2c). Two
 * restrictions read it and the rest ignore it, exactly as most ignore [you]. Its default
 * ([TargetContext.NONE]) fails closed in both directions — X reads as zero, an earlier target as absent —
 * so a caller that has nothing to say under-offers rather than offering something illegal.
 */
internal fun satisfiesPermanentRestriction(
    state: GameState,
    restriction: PermanentRestriction,
    candidate: GameObject,
    you: PlayerId,
    context: TargetContext = TargetContext.NONE,
): Boolean {
    val characteristics = state.definitions[candidate.card]?.characteristics ?: return false
    val isCreature = CardType.CREATURE in characteristics.cardTypes
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
        PermanentRestriction.ENCHANTMENT,
        PermanentRestriction.LAND,
        PermanentRestriction.NONLAND_PERMANENT,
        PermanentRestriction.CREATURE_OR_VEHICLE,
        -> satisfiesTypeRestriction(restriction, characteristics, isCreature)
        PermanentRestriction.RED_PERMANENT,
        PermanentRestriction.BLUE_PERMANENT,
        -> satisfiesColourRestriction(restriction, characteristics)
        PermanentRestriction.PERMANENT_YOU_CONTROL,
        PermanentRestriction.CREATURE_AN_OPPONENT_CONTROLS,
        PermanentRestriction.ARTIFACT_OR_ENCHANTMENT_AN_OPPONENT_CONTROLS,
        PermanentRestriction.CREATURE_YOU_CONTROL,
        PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL,
        -> satisfiesControlRestriction(restriction, characteristics, candidate, you, isCreature)
        PermanentRestriction.NONCREATURE_ARTIFACT_WITH_MANA_VALUE_X,
        PermanentRestriction.CREATURE_CONTROLLED_BY_TARGETED_PLAYER,
        -> satisfiesDependentRestriction(restriction, characteristics, candidate, isCreature, context)
    }
}

/**
 * The **dependent** arms of [satisfiesPermanentRestriction] (`W9-C`,
 * docs/design/dependent-targets.md §3): the two restrictions whose answer is not a function of the board
 * alone but of what the choosing object has already announced or already chosen ([context]).
 *
 * Split out beside [satisfiesTypeRestriction] and [satisfiesControlRestriction] to keep the main `when`
 * inside detekt's complexity budget, and grouped together because they share the property that makes them
 * unusual rather than because they share a rule — one reads a cost announcement (CR 601.2b), the other an
 * earlier targeting line (CR 601.2c).
 */
private fun satisfiesDependentRestriction(
    restriction: PermanentRestriction,
    characteristics: PrintedCharacteristics,
    candidate: GameObject,
    isCreature: Boolean,
    context: TargetContext,
): Boolean =
    when (restriction) {
        // CR 202.3b: the candidate's *printed* mana value, compared with the value announced for the
        // activating ability's own {X} (CR 107.3). Two different Xs, which is why one is read off the
        // permanent and the other off the context. "Noncreature" excludes an artifact creature outright
        // (CR 205.1a), whatever its mana value.
        PermanentRestriction.NONCREATURE_ARTIFACT_WITH_MANA_VALUE_X ->
            CardType.ARTIFACT in characteristics.cardTypes &&
                !isCreature &&
                characteristics.manaValue == context.chosenX
        // CR 115.1b: the creatures controlled by the player an earlier targeting line named. With no
        // earlier target the set is empty rather than universal — see the restriction's own KDoc.
        PermanentRestriction.CREATURE_CONTROLLED_BY_TARGETED_PLAYER ->
            isCreature && context.earlierTargets.any { it is Target.Player && it.id == candidate.owner }
        else -> error("CR 601.2b/c: $restriction does not depend on an earlier announcement or choice")
    }

/**
 * The **card-type** arms of [satisfiesPermanentRestriction] (CR 205.2), split out beside
 * [satisfiesColourRestriction] and [satisfiesControlRestriction] to keep that `when` inside detekt's
 * complexity budget. None of them reads the deciding player: what card types a permanent has is a
 * property of the object alone.
 *
 * [PermanentRestriction.CREATURE_OR_VEHICLE] is the one arm that is not a bare card-type test, and it is
 * here because it is *half* of one: a creature qualifies by card type (CR 302) and a Vehicle by the
 * printed artifact subtype (CR 301.7), which is the only disjunction in the family that crosses those two
 * axes. [isCreature] is passed in rather than recomputed so the caller's single read is the one answer.
 */
private fun satisfiesTypeRestriction(
    restriction: PermanentRestriction,
    characteristics: PrintedCharacteristics,
    isCreature: Boolean,
): Boolean =
    when (restriction) {
        PermanentRestriction.ARTIFACT -> CardType.ARTIFACT in characteristics.cardTypes
        // CR 303: an Aura is an enchantment, so every Aura in the pool qualifies.
        PermanentRestriction.ENCHANTMENT -> CardType.ENCHANTMENT in characteristics.cardTypes
        // CR 305: any land. An artifact land satisfies this *and* [ARTIFACT] — a permanent has every
        // card type printed on it (CR 205.1a) — which is why this is a card-type test, not an exclusion.
        PermanentRestriction.LAND -> CardType.LAND in characteristics.cardTypes
        // CR 205.1a/205.2b: a permanent has *every* card type printed on it, so "nonland" excludes an
        // artifact land as well as a basic — the exclusion reads the whole type line, not the first type.
        PermanentRestriction.NONLAND_PERMANENT -> CardType.LAND !in characteristics.cardTypes
        // CR 302 / CR 301.7: a creature by card type, or a Vehicle by printed subtype. A crewed Vehicle
        // qualifies both ways; crew is a layer-4 effect the engine does not have, so the subtype is
        // read printed like every other subtype test.
        PermanentRestriction.CREATURE_OR_VEHICLE -> isCreature || characteristics.hasSubtype(VEHICLE)
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
 * Takes the whole [characteristics] rather than a pre-computed creature flag because
 * [PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL] is a **union** over three card types
 * (CR 205.1a) rather than a question about one.
 */
private fun satisfiesControlRestriction(
    restriction: PermanentRestriction,
    characteristics: PrintedCharacteristics,
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
            candidate.owner != you && characteristics.cardTypes.any { it in ARTIFACT_OR_ENCHANTMENT }
        // CR 205.2b: a permanent may have several card types, so "and/or" is a disjunction over them —
        // one match is enough, and an artifact land satisfies it twice over.
        PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL ->
            yours && characteristics.cardTypes.any { it in BLINKABLE_TYPES }
        else -> error("CR 109.5: $restriction is not a control restriction")
    }
}
