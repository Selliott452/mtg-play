package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Quality
import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PreventionEffect
import dev.mtgplay.core.state.Target

/**
 * The **damage-prevention application point** (CR 615) — the one function
 * [dev.mtgplay.rules.effect.dealDamage] consults before it does anything at all. `FW-PREVENT`,
 * docs/design/protection.md §3 Part B.
 *
 * ## Why this is one function taking (source, recipient, amount)
 *
 * The shape is deliberate and is the packet's main extension decision. Prevention is a *family* of
 * effects that all answer the same question at the same moment, and the family had three known
 * members with only one built. All three are built now:
 *
 * - **CR 702.16e protection** — "any damage that would be dealt by sources that have the stated
 *   quality to a permanent or player with protection is prevented". `FW-PREVENT`. It is a pure
 *   *static read* of the recipient's layered characteristics: no stored effect, no duration, no
 *   shield. That is the load-bearing sizing insight of docs/design/protection.md §3 — protection's
 *   prevention needs nothing but the source's identity threaded to this moment.
 * - **CR 615.1 shields** — Prismatic Strands' "prevent all damage that sources of the colour of your
 *   choice would deal this turn". `FW-PREVENT2`, built into the slot this KDoc reserved for it: the
 *   turn-duration store is [GameState.preventionEffects] and the clause is [colorShieldCatches].
 * - **CR 615.9 "damage can't be prevented"** — Flaring Pain. Same packet, and it is why the
 *   signature is a function rather than a sprinkling of booleans through `dealDamage`: it inverts the
 *   whole framework, and a single application point has exactly one place to invert. That inversion
 *   is the first branch of the body, ahead of every clause including protection's.
 *
 * ## Three CR corners this must not approximate
 *
 * - **Prevention is not damage reduction** (CR 615.6: "If damage that would be dealt is prevented,
 *   it never happens"). So the check happens *inside* `dealDamage`, upstream of the
 *   [dev.mtgplay.core.event.GameEvent.DamageDealt] event, upstream of marking and life loss, and
 *   therefore upstream of lifelink (CR 702.15 gains life as a result of damage *dealt*) and of every
 *   damage-dealt trigger. Subtracting from a combat assignment instead would get the life totals
 *   right and the lifelink, the triggers and the log all wrong.
 * - **Lethal assignment is computed before prevention** (CR 510.1c). A blocked attacker must assign
 *   at least lethal damage to each blocker, and that calculation reads toughness and marked damage
 *   and is entirely indifferent to whether the damage will then be prevented — as is the CR 702.19b
 *   trample excess. An attacker with trample facing a blocker with protection from it still
 *   "wastes" the lethal assignment on that blocker. Nothing in this file is reachable from
 *   `TrampleAssignment.kt`, and that is the point.
 * - **Prevention shrinks no enumeration.** It changes an outcome, not an option set
 *   (docs/design/protection.md §6). A Lightning Bolt must still be castable at a creature with
 *   protection from red — the cast is legal, the *targeting* is what CR 702.16b forbids, and a Bolt
 *   aimed at the player is legal regardless. The two store-backed members inherit this exactly: a
 *   Prismatic Strands shield on red does not remove a Bolt from anybody's action list, and a Flaring
 *   Pain does not add one.
 */
internal fun damageIsPrevented(
    state: GameState,
    source: DamageSource,
    recipient: Target,
): Boolean =
    when {
        // CR 615.9: "A prevention effect that can't be applied simply doesn't do anything." So Flaring
        // Pain deletes no shield, races none on a timestamp, and does not care which came first — while
        // it is in force **every** clause below fails to apply, protection's CR 702.16e prevention
        // included. Protection's other three letters (CR 702.16b targeting, CR 702.16c attachment,
        // CR 702.16f blocking) are untouched: none of them is prevention, and Flaring Pain says nothing
        // about them.
        preventionCannotApply(state) -> false
        // CR 615.1: a shield keyed on the *source's* colour, catching damage to every permanent and to
        // both players alike. It is checked before protection only because the two are independent —
        // damage caught by either is prevented — so no ordering between them is observable.
        colorShieldCatches(state, source) -> true
        else -> recipientHasProtectionFrom(state, recipient, source)
    }

/**
 * Whether a CR 615.9 "damage can't be prevented" effect is in force (Flaring Pain) — the one predicate
 * that turns the whole framework off.
 *
 * A presence test over the store rather than a count or a timestamp comparison, because CR 615.9 admits
 * no arithmetic: one such effect and two behave identically, and one created *after* a shield disables
 * that shield exactly as one created before it does. A Prismatic Strands cast in response to a Flaring
 * Pain still resolves and still creates its shield; the shield simply never applies this turn.
 */
private fun preventionCannotApply(state: GameState): Boolean =
    state.preventionEffects.any { it.effect is PreventionEffect.DamageCantBePrevented }

/**
 * Whether any CR 615.1 colour shield in force catches damage from [source] (Prismatic Strands).
 *
 * The colour test is [sourceHasQuality] with a [Quality.OfColor], which is the *same* predicate
 * CR 702.16e protection uses — deliberately, because the two rules ask an identical question of an
 * identical object, and a second colour read is a second thing that could drift. It inherits that
 * predicate's recorded blind spot too: colours come from
 * [dev.mtgplay.core.card.PrintedCharacteristics.colors], so a source with
 * [dev.mtgplay.core.card.Keyword.DEVOID] is colourless everywhere (CR 702.114a) and no shield ever
 * catches it — which is correct — while a layer-5 colour-*changing* effect would need this read to move
 * to layered colour. Layer 5 is kept empty behind a loud gate, so that day cannot pass silently.
 *
 * **Colourless damage is never caught, and that is the printed card rather than a gap.** [Quality.OfColor]
 * ranges over [dev.mtgplay.core.mana.Color], which has five members: CR 105.4 makes colourless the
 * absence of colour rather than a sixth one, so "the colour of your choice" cannot name it.
 */
private fun colorShieldCatches(
    state: GameState,
    source: DamageSource,
): Boolean =
    state.preventionEffects.any { timed ->
        val effect = timed.effect
        effect is PreventionEffect.PreventDamageFromColor &&
            sourceHasQuality(state, source.card, Quality.OfColor(effect.color))
    }

/**
 * The CR 702.16e clause: whether [recipient] has protection from a quality the object printed as
 * [source]'s card has. Unchanged by `FW-PREVENT2` — it is split out of [damageIsPrevented] only so the
 * three clauses read as three clauses.
 */
private fun recipientHasProtectionFrom(
    state: GameState,
    recipient: Target,
    source: DamageSource,
): Boolean =
    when (recipient) {
        // CR 702.16e: a player with protection from the source's quality takes no damage from it.
        // No card in the pool grants a *player* protection (CR 613.10 puts that outside the layer
        // system entirely), so there is nothing to read and this is honestly `false` rather than a
        // lookup into a store that does not exist.
        is Target.Player -> false
        // CR 702.16e on a permanent: a pure read of the recipient's layered protections against the
        // source's characteristics, re-derived at the moment damage would be dealt. A recipient that
        // has left the battlefield since the assignment was computed has no characteristics to read
        // (CR 400.7) and is not protected.
        is Target.Permanent ->
            state.sharedZones.battlefield.any { it.id == recipient.id } &&
                hasProtectionFrom(state, recipient.id, source.card)
        // CR 120.3 admits neither recipient; `dealDamage`'s own exhaustive `when` refuses both with
        // the citation. Answering "not prevented" here keeps that refusal the single loud one.
        is Target.SpellOnStack, is Target.CardInGraveyard -> false
    }
