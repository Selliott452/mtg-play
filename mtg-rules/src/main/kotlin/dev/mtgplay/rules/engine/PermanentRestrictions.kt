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
 * Whether the battlefield object [candidate] satisfies [restriction] (CR 115.1b). Exhaustive over
 * [PermanentRestriction] so a new restriction breaks compilation rather than being silently ignored.
 *
 * An object with no definition is inert — it satisfies nothing, not even
 * [PermanentRestriction.ANY_PERMANENT], because the engine cannot know what it is (the same answer
 * [isCreature] and [satisfiesEnchantRestriction] give).
 */
internal fun satisfiesPermanentRestriction(
    state: GameState,
    restriction: PermanentRestriction,
    candidate: GameObject,
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
        // CR 109.5: "you" is the choosing player, so this arm is decider-relative and is answered by
        // [satisfiesPermanentRestrictionFor] instead; reaching it here means a caller lost the chooser.
        PermanentRestriction.CREATURE_YOU_CONTROL ->
            error(
                "CR 109.5: ${PermanentRestriction.CREATURE_YOU_CONTROL} is decider-relative and cannot be " +
                    "judged without the choosing player; call satisfiesPermanentRestrictionFor",
            )
    }
}

/**
 * Whether [candidate] satisfies [restriction] **for the player [chooser]** (CR 115.1b, CR 109.5) — the
 * decider-relative form, and the one every targeting caller should use.
 *
 * Only [PermanentRestriction.CREATURE_YOU_CONTROL] reads [chooser] today; every other restriction is a
 * property of the board alone and delegates unchanged. Splitting it this way rather than threading
 * `chooser` through [satisfiesPermanentRestriction] itself keeps the board-only predicate callable from
 * the places that genuinely have no chooser, while making the one restriction that *needs* a chooser
 * impossible to evaluate without one — the `error` arm above is what turns "a caller forgot" from a
 * silently wrong option list into a loud failure.
 *
 * Control is ownership in the current pool (no control-changing effect exists), so "you control" reads
 * [GameObject.owner]; [PermanentRestriction.CREATURE_YOU_CONTROL]'s KDoc records that this is one of the
 * sites that must start reading a real controller the day that stops being true.
 */
internal fun satisfiesPermanentRestrictionFor(
    state: GameState,
    restriction: PermanentRestriction,
    candidate: GameObject,
    chooser: PlayerId,
): Boolean =
    when (restriction) {
        PermanentRestriction.CREATURE_YOU_CONTROL -> {
            val characteristics = state.definitions[candidate.card]?.characteristics
            characteristics != null &&
                CardType.CREATURE in characteristics.cardTypes &&
                candidate.owner == chooser
        }
        PermanentRestriction.ANY_PERMANENT,
        PermanentRestriction.CREATURE,
        PermanentRestriction.NONLEGENDARY_CREATURE,
        PermanentRestriction.CREATURE_POWER_2_OR_LESS,
        PermanentRestriction.ARTIFACT,
        -> satisfiesPermanentRestriction(state, restriction, candidate)
    }
