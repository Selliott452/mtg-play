package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId

/**
 * The object that deals a point of damage (CR 120.1: "An object that deals damage is the source of
 * that damage") — the missing half of the engine's damage primitive. Additive, flagged core
 * (`FW-PREVENT`, docs/design/protection.md §3 Part A).
 *
 * Damage used to be source-less here: [dev.mtgplay.core.event.GameEvent.DamageDealt] narrated *what
 * was hit* and never *what hit it*, and combat computed a source in
 * `CombatDamage.DamageAssignment` only to discard it at the call. Everything that asks "who dealt
 * this" therefore had nothing to read — most sharply CR 615 prevention and CR 702.16e protection,
 * both of which are predicates **on the source's characteristics** ("prevent all damage that
 * sources of the chosen colour would deal", "damage from sources with the stated quality is
 * prevented"). Neither is expressible without this type.
 *
 * **Both halves are carried deliberately** (docs/design/protection.md §3 Part A). CR 609.7a lets a
 * source be a permanent, a spell on the stack, or an object referred to by an object on the stack,
 * and in this pool it is always either a battlefield creature (combat) or the resolving spell or
 * ability itself. By the time the damage lands the source may already have left the zone it was in —
 * a Lightning Bolt is still on the stack as it resolves and a sacrificed Lava Dart is not — so
 * looking its characteristics up by [objectId] alone is unreliable. [card] resolves from
 * `GameState.definitions` whatever zone the source has since reached (CR 113.7c last-known
 * information), which is what a quality test actually needs.
 *
 * @property objectId the source's own id where the engine has one, or `null` where it genuinely has
 *   none. Diagnostics and future redirection (CR 614.9); the quality tests read [card].
 * @property card the source's printed identity — the half every rules predicate consults.
 */
data class DamageSource(
    val objectId: ObjectId?,
    val card: CardRef,
)
