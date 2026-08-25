package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.identity.PlayerId
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
 * Card types and supertypes are read printed: no type-changing effect exists in the pool
 * (docs/design/layer-system.md §6). **Power is not** — it is read through [effectivePower], the
 * CR 613 sublayer-7c accessor, so a creature pumped in response to a "power 2 or less" spell stops
 * being a legal target and the spell fizzles (CR 608.2b). Reading printed power there would be
 * silently wrong on any board with an Aura.
 */

/** The greatest in-game power a [PermanentRestriction.CREATURE_POWER_2_OR_LESS] target may have. */
private const val POWER_TWO_OR_LESS_LIMIT: Int = 2

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
        PermanentRestriction.ARTIFACT -> CardType.ARTIFACT in characteristics.cardTypes
        // CR 108.4: control is ownership in the MVP pool — nothing in the gauntlet changes control of a
        // permanent (docs/design/layer-system.md §4).
        PermanentRestriction.PERMANENT_YOU_CONTROL -> candidate.owner == you
        // CR 102.1: "an opponent" is any player who is not the one choosing.
        PermanentRestriction.CREATURE_AN_OPPONENT_CONTROLS -> isCreature && candidate.owner != you
    }
}
