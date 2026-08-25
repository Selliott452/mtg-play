package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target

/**
 * The **damage-prevention application point** (CR 615) — the one function
 * [dev.mtgplay.rules.effect.dealDamage] consults before it does anything at all. `FW-PREVENT`,
 * docs/design/protection.md §3 Part B.
 *
 * ## Why this is one function taking (source, recipient, amount)
 *
 * The shape is deliberate and is the packet's main extension decision. Prevention is a *family* of
 * effects that all answer the same question at the same moment, and the family already has three
 * known members with only one built:
 *
 * - **CR 702.16e protection** — "any damage that would be dealt by sources that have the stated
 *   quality to a permanent or player with protection is prevented". Built here. It is a pure
 *   *static read* of the recipient's layered characteristics: no stored effect, no duration, no
 *   shield. That is the load-bearing sizing insight of docs/design/protection.md §3 — protection's
 *   prevention needs nothing but the source's identity threaded to this moment.
 * - **CR 615.1 shields** — Prismatic Strands' "prevent all damage that sources of the colour of
 *   your choice would deal this turn". *Not built*: it needs a turn-duration prevention store on
 *   [GameState], which needs a card that produces one, and Prismatic Strands is blocked on a cost
 *   shape the engine does not have. Its slot is this function's second clause.
 * - **CR 615.9 "damage can't be prevented"** — Flaring Pain. *Not built*, and it is why the
 *   signature is a function rather than a sprinkling of booleans through `dealDamage`: it inverts
 *   the whole framework, and a single application point has exactly one place to invert.
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
 *   aimed at the player is legal regardless.
 */
internal fun damageIsPrevented(
    state: GameState,
    source: DamageSource,
    recipient: Target,
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
