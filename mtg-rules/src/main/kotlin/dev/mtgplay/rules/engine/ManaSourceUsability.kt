package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Which battlefield objects can have a mana ability of theirs activated right now (CR 605.1a,
 * CR 602.2a, CR 302.6) — the one concept both halves of mana payment share.
 *
 * It lives in its own file because it has exactly two callers and they must never diverge:
 * [manaSourceClasses] uses it to decide a source class's *membership* (the planner), and
 * [resolveTapForMana] uses it to pick the member it activates (the executor). A plan enumerated
 * against one membership and executed against another is the worst defect the payment model can
 * have (docs/design/mana-payment.md §10), and two filter expressions in two files is exactly how
 * that happens.
 */

/**
 * Whether a mana ability of the battlefield object [obj] can be activated right now.
 *
 * - A **sacrifice**-cost mana ability (CR 605.1a — an Eldrazi Spawn's "Sacrifice this token: Add
 *   {C}") has no `{T}` in its cost, so its source is usable whether or not it is tapped, and the
 *   CR 302.6 restriction below does not reach it either.
 * - A `{T}` mana ability needs an untapped source (CR 602.2a, CR 106.11a).
 * - **CR 302.6**: a creature's activated ability whose cost includes the `{T}` symbol can't be
 *   activated unless the creature has been under its controller's control continuously since
 *   their most recent turn began. A mana ability *is* an activated ability (CR 605.1a), so a
 *   summoning-sick Elvish Mystic taps for no mana. This is the same clause the
 *   [dev.mtgplay.core.definition.AbilityCost.TapSelf] component enforces for non-mana abilities
 *   in Activation.kt. Until the pool's first creature mana source was encoded no object could
 *   reach it from the payment path, and its absence there was silent: mana offered in the
 *   enumerated action space (ADR-005) that the rules do not permit.
 * - **CR 702.10c**: haste lifts that restriction — "its controller can activate its activated
 *   abilities whose cost includes the tap symbol … even if that creature hasn't been controlled by
 *   that player continuously since their most recent turn began". A hasty creature mana source taps
 *   for mana the turn it arrives. It is read through [hasHaste], the effective-keyword seam, so a
 *   granted haste or a CR 122.1b haste counter counts and not only a printed one.
 *
 * Because both halves of payment call this one predicate, honouring haste **here** honours it in the
 * planner ([manaSourceClasses]) and the executor ([resolveTapForMana]) at once — which is the whole
 * reason the file exists, and the reason haste could not become a silent half-gap the way CR 302.6
 * itself once was.
 *
 * An object that is no mana source at all is filtered by [productionProfile] rather than here, so
 * this predicate answers only "may it be activated", never "has it anything to activate".
 */
internal fun manaSourceUsable(
    state: GameState,
    obj: GameObject,
): Boolean {
    if (isSacrificeSource(state, obj.id)) return true
    return !obj.tapped && !(isCreature(state, obj) && obj.summoningSick && !hasHaste(state, obj.id))
}

/**
 * Whether the battlefield source [id] produces mana by being **sacrificed** rather than tapped
 * (CR 605.1a) — an Eldrazi Spawn's "Sacrifice this token: Add {C}". True when its layered mana
 * abilities are non-empty and every one is a sacrifice ability; the MVP pool never mixes tap and
 * sacrifice mana abilities on one source, so this all-or-nothing test is exact
 * (docs/design/mana-payment.md §9).
 */
internal fun isSacrificeSource(
    state: GameState,
    id: ObjectId,
): Boolean {
    val abilities = layeredCharacteristics(state, id).manaAbilities
    return abilities.isNotEmpty() && abilities.all { it.viaSacrifice }
}
