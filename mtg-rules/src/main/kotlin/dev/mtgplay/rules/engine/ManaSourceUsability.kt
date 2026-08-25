package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Which mana abilities of a battlefield object can be activated right now (CR 605.1a, CR 602.2a,
 * CR 602.5a–b) — the one concept both halves of mana payment share.
 *
 * It lives in its own file because it has exactly one *derivation* and two consumers that must never
 * diverge: [manaSourceClasses] uses it (through [productionProfile]) to decide a source class's
 * membership and its alternatives (the planner), and [resolveTapForMana] re-derives the same key to
 * pick the member it activates (the executor). A plan enumerated against one membership and executed
 * against another is the worst defect the payment model can have (docs/design/mana-payment.md §10),
 * and two filter expressions in two files is exactly how that happens.
 *
 * **`FW-MANACOST` moved the question from the object to the ability.** Before it, "usable" was a
 * property of the source: every mana ability of a permanent shared one cost shape, so one predicate
 * over the object answered for all of them. Conduit Pylons prints a free `{T}` ability beside a
 * `{1}, {T}` one, and Wall of Roots' ability has no `{T}` at all and works while tapped, so the
 * answer is now per **ability** and folds into the production profile: an unavailable ability simply
 * contributes no alternative, and an object with no available ability is no mana source. That makes
 * the source class key carry availability as well as production — the same correspondence
 * certificate, one more thing it certifies (docs/design/mana-payment.md §11.4).
 */

/**
 * Whether [ability], the [index]th layered mana ability of the battlefield object [obj], can be
 * activated right now — everything except its mana component, which only a whole payment plan can
 * answer (docs/design/mana-payment.md §11.1).
 *
 * - **CR 602.5b** — "Activate only once each turn": an ability already activated by *this object*
 *   this turn is unavailable for the rest of the turn. The restriction is recorded on the object
 *   ([GameObject.manaAbilitiesActivatedThisTurn]) because CR 602.5b makes it a property of the
 *   object rather than of its controller.
 * - **CR 602.2a, CR 106.11a** — a `{T}` component needs an untapped source.
 * - **CR 602.5a** (the rule docs/design/mana-payment.md §2.1 cites as CR 302.6) — a creature's
 *   activated ability with `{T}` in its cost can't be activated unless the creature has been under
 *   its controller's control continuously since their most recent turn began, so a summoning-sick
 *   Elvish Mystic taps for nothing. **CR 702.10c**: haste lifts the restriction, read through
 *   [hasHaste] so a granted haste or a CR 122.1b haste counter counts and not only a printed one.
 * - **Sacrificing** the source has no `{T}`, so an Eldrazi Spawn is available whether or not it is
 *   tapped, and CR 602.5a does not reach it either.
 * - **Tapping another creature** needs another untapped creature to exist. This is only the
 *   existence precondition; how many the *whole plan* can afford is a capacity question and belongs
 *   to [enumerateActivationSets], because one activation's tap consumes another's.
 * - **Putting a counter** on the source is always payable, so Wall of Roots taps for mana while
 *   tapped, while blocking, and on any player's turn.
 */
internal fun manaAbilityAvailable(
    state: GameState,
    obj: GameObject,
    index: Int,
    ability: ManaAbility,
): Boolean {
    if (ability.oncePerTurn && index in obj.manaAbilitiesActivatedThisTurn) return false
    return ability.cost.all { component ->
        when (component) {
            ManaAbilityCost.TapSelf ->
                !obj.tapped && !(isCreature(state, obj) && obj.summoningSick && !hasHaste(state, obj.id))
            ManaAbilityCost.TapAnotherCreature -> untappedCreatures(state, obj.owner).any { it.id != obj.id }
            ManaAbilityCost.SacrificeSelf,
            is ManaAbilityCost.PutCounterOnSelf,
            is ManaAbilityCost.Mana,
            -> true
        }
    }
}

/**
 * Whether the battlefield object [obj] has **any** mana ability that can be activated right now —
 * the membership test [manaSourceClasses] and [resolveTapForMana] share.
 *
 * Since `FW-MANACOST` this is exactly "[productionProfile] found an alternative": availability is a
 * per-ability question folded into the profile, so asking it separately would be a second expression
 * of the same predicate, which is the divergence this file exists to prevent.
 */
internal fun manaSourceUsable(
    state: GameState,
    obj: GameObject,
): Boolean = productionProfile(state, obj) != null

/**
 * The untapped creatures [seat] controls, in battlefield order — the budget an
 * [ManaAbilityCost.TapAnotherCreature] component draws on (CR 602.1). Controller is owner in the MVP
 * pool. Summoning sickness is deliberately **not** filtered: the tap symbol appears on the ability's
 * source, not on the creature being tapped as a cost, so CR 602.5a does not reach it and a
 * freshly-played creature is a legal choice — exactly as it is for Springleaf Drum.
 */
internal fun untappedCreatures(
    state: GameState,
    seat: PlayerId,
): List<GameObject> = state.sharedZones.battlefield.filter { it.owner == seat && !it.tapped && isCreature(state, it) }

/**
 * Whether the battlefield source [id] produces mana **only** by being sacrificed (CR 605.1a) — an
 * Eldrazi Spawn's "Sacrifice this token: Add {C}". True when its layered mana abilities are non-empty
 * and every one sacrifices the source; the gauntlet pool never mixes a sacrifice mana ability with
 * another kind on one source, so this all-or-nothing test is exact (docs/design/mana-payment.md §9).
 *
 * Read by [manaSourcesReservedBy] and [sacrificeSourcesAmong] to decide which permanents a sibling
 * cost component must reserve, so it deliberately ignores availability: a source that *would* be
 * consumed by producing mana has to be reserved whether or not it could be activated right now.
 */
internal fun isSacrificeSource(
    state: GameState,
    id: ObjectId,
): Boolean {
    val abilities = layeredCharacteristics(state, id).manaAbilities
    return abilities.isNotEmpty() && abilities.all { ManaAbilityCost.SacrificeSelf in it.cost }
}
