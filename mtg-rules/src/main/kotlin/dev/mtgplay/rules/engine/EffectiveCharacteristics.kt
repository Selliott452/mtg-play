package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.PersistentSet

/*
 * The single read-through combat uses for a permanent's in-game characteristics.
 *
 * Combat asks these accessors — never a card definition directly — for the keywords, power, and
 * toughness it consults (CR 508–510). Each now delegates to the CR 613 continuous-effect layer
 * system ([layeredCharacteristics]): keyword grants in layer 6, P/T modifiers in sublayer 7c
 * (docs/design/layer-system.md §6). Because combat only ever reads through this one seam, no combat
 * rule changed when the layer engine landed — the P3.1 contract kept. A card without a definition is
 * inert: no keywords, and it is not a creature — so it can never be a combatant.
 */

/** The battlefield object with [id]; fails loudly if it is not on the battlefield (CR 110.1). */
internal fun GameState.battlefieldObject(id: ObjectId): GameObject =
    sharedZones.battlefield.firstOrNull { it.id == id }
        ?: error("object $id is not on the battlefield")

/**
 * Whether the battlefield object [obj] is a creature right now (CR 302.1) — the P3.1 answer is
 * "its printed types include creature." An object with no definition is inert and not a creature.
 * Phase 4's layer system (type-changing effects, layer 4) reroutes through here.
 */
internal fun isCreature(
    state: GameState,
    obj: GameObject,
): Boolean {
    val characteristics = state.definitions[obj.card]?.characteristics ?: return false
    return CardType.CREATURE in characteristics.cardTypes
}

/**
 * The in-game keyword abilities of the battlefield object [id] (CR 702, CR 613 layer 6): printed
 * keywords unioned with active aura/effect grants, via [layeredCharacteristics]. An object with no
 * definition has none.
 */
internal fun effectiveKeywords(
    state: GameState,
    id: ObjectId,
): PersistentSet<Keyword> = layeredCharacteristics(state, id).keywords

/**
 * Whether the battlefield object [id] can't be destroyed right now (CR 702.12b) — the single seam the
 * destruction rules consult, so a grant of indestructible (CR 613 layer 6) is honoured automatically.
 * The engine's only destruction today is the CR 704.5g lethal-damage state-based action; every
 * destruction effect added later must read this rather than re-deriving it.
 */
internal fun isIndestructible(
    state: GameState,
    id: ObjectId,
): Boolean = Keyword.INDESTRUCTIBLE in effectiveKeywords(state, id)

/**
 * Whether the battlefield object [id] has haste right now (CR 702.10) — the single seam every
 * CR 302.6 summoning-sickness gate consults, so a granted or counter-given haste (CR 613 layer 6,
 * CR 122.1b) is honoured automatically at all of them.
 *
 * There are exactly three such gates, and each calls this rather than testing
 * [dev.mtgplay.core.state.GameObject.summoningSick] alone:
 * - [eligibleAttackers] — CR 702.10b, which creatures are *offered* as attackers (ADR-005);
 * - [abilityCostPayable]'s [dev.mtgplay.core.definition.AbilityCost.TapSelf] arm — CR 702.10c, the
 *   `{T}` component of a non-mana activated ability;
 * - [manaSourceUsable] — CR 702.10c again, for a mana ability (CR 605.1a is an activated ability
 *   too). That predicate is deliberately shared by the payment **planner**
 *   ([manaSourceClasses]) and the payment **executor** ([resolveTapForMana]), so honouring haste
 *   once there covers both and they cannot disagree about which sources a plan may use
 *   (docs/design/mana-payment.md §10).
 *
 * A missed gate would not merely mis-play the keyword: it would enumerate the wrong action set,
 * which under ADR-005 is either a silently illegal option or a silently missing one.
 */
internal fun hasHaste(
    state: GameState,
    id: ObjectId,
): Boolean = Keyword.HASTE in effectiveKeywords(state, id)

/**
 * Whether the battlefield object [id] has defender right now (CR 702.3) — "this creature can't
 * attack" (CR 702.3b). Read at attacker enumeration ([eligibleAttackers]) and, unusually for a
 * keyword, at a *mana* read: Overgrown Battlement's "for each creature you control with defender"
 * counts through this same seam.
 */
internal fun hasDefender(
    state: GameState,
    id: ObjectId,
): Boolean = Keyword.DEFENDER in effectiveKeywords(state, id)

/**
 * Whether the battlefield object [id] has reach right now (CR 702.17) — it may block a creature with
 * flying (CR 702.17b). Read only at [eligibleBlockPairings]'s legality check, and only against
 * flying: reach does **not** satisfy [dev.mtgplay.core.card.Evasion.BLOCKABLE_ONLY_BY_FLYING].
 */
internal fun hasReach(
    state: GameState,
    id: ObjectId,
): Boolean = Keyword.REACH in effectiveKeywords(state, id)

/**
 * The in-game power of the battlefield creature [id] (CR 208.1, CR 613 sublayer 7c), via
 * [layeredCharacteristics]. Fails loudly on a non-creature — a combatant is always a creature, so
 * reaching this without a P/T box is an engine defect.
 */
internal fun effectivePower(
    state: GameState,
    id: ObjectId,
): Int = layeredPower(state, id)

/**
 * The in-game toughness of the battlefield creature [id] (CR 208.1, CR 613 sublayer 7c), via
 * [layeredCharacteristics]. Fails loudly on a non-creature.
 */
internal fun effectiveToughness(
    state: GameState,
    id: ObjectId,
): Int = layeredToughness(state, id)
