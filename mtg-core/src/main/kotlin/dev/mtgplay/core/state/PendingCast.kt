package dev.mtgplay.core.state

import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList

/**
 * A cast the engine is gathering decisions for: the caster has chosen to cast a card (CR 601.2), and
 * the choices the cast needs — targets (CR 601.2c), any additional-cost selection (CR 601.2b/f), then
 * a payment plan (CR 601.2g) — are being collected one `DecisionRequest` at a time (ADR-004).
 *
 * While a [PendingCast] is open, the card is **still in its [source] zone** and nothing about the game
 * has changed: the engine runs the whole CR 601 pipeline atomically in the single transition that
 * receives the final choice, so an abandoned or failed cast leaves exactly the pre-cast state — the
 * CR 601.3e/CR 728 rewind is the immutability of the paused state itself (see the casting pipeline in
 * `mtg-rules`). This record is only the gathered-so-far choices, which is what lets the pending
 * decision request stay a pure function of the state (ADR-004 resumability).
 *
 * A normal cast has [source] `HAND` and a `null` [castingPermission] (the printed mana cost applies).
 * A cast-from-elsewhere (madness, flashback, escape; docs/decklists.md) names the [castingPermission]
 * it uses, whose source zone the card is drawn from and whose alternative cost replaces the printed
 * one (CR 118.9); escape's additional "exile N others" cost fills [additionalExileCost] before payment.
 *
 * @property caster the player casting; they hold priority for the whole gathering.
 * @property cardObjectId the object being cast; still in [caster]'s [source] zone.
 * @property chosenModes the **printed** indices of the modes chosen for a modal spell (CR 601.2b,
 *   CR 700.2): `null` before the mode decision is answered when the card is modal, an (empty) list once
 *   settled or when the card has no modes. Additive, flagged core (`FW-MODAL`,
 *   docs/design/countering-spells.md §8).
 *
 *   Settled **before** [chosenTargets], and that ordering is the rules' own (CR 601.2b precedes
 *   CR 601.2c) rather than a convenience. It is load-bearing because a modal card's modes may target
 *   different *kinds* of object — Blue Elemental Blast counters a spell on the stack or destroys a
 *   battlefield permanent, Steel Sabotage counters a spell or bounces an artifact — so "what are the
 *   legal targets?" has no answer at all until the mode is known. The indices are the card's printed
 *   ones, not indices into whatever subset of modes happened to be legal on this board, so a replay log
 *   names the same mode whatever the board looked like.
 * @property chosenTargets the targets chosen so far: `null` before the targets decision (CR 601.2c) is
 *   answered, the chosen list after — empty exactly when the spell (or, for a modal spell, the chosen
 *   mode) targets nothing.
 * @property source the zone the card is being cast from (CR 601.2a).
 * @property castingPermission the alternative permission this cast uses (CR 601.2f), or `null` for a
 *   normal cast at the printed cost from the hand.
 * @property additionalExileCost the objects chosen to satisfy an additional "exile N other cards" cost
 *   (escape, CR 702.139a): `null` before the selection is answered when the permission demands one, an
 *   (empty) list once settled or when no such cost applies.
 * @property sacrificeCost the battlefield permanents chosen to satisfy a non-mana sacrifice cost
 *   (Fireblast's two Mountains, Lava Dart's Mountain — CR 601.2h): `null` before the selection is
 *   answered when the permission demands one, an (empty) list once settled or when no such cost
 *   applies. Additive, flagged core (P6.2a). The permanents are sacrificed only when the cast executes
 *   (CR 601.2h), atomically with everything else.
 * @property additionalDiscard the hand cards chosen to satisfy an additional discard cost (Grab the
 *   Prize's "discard a card" — CR 601.2b): `null` before the selection is answered when the definition
 *   demands one, an (empty) list once settled or when no such cost applies. Additive, flagged core
 *   (P6.2a). The cards are discarded only when the cast executes (CR 601.2h), through the CR 614/616
 *   framework so madness intercepts them.
 * @property additionalSacrifice the battlefield permanents chosen to satisfy an **intrinsic** sacrifice
 *   additional cost (Eviscerator's Insight's "sacrifice an artifact or creature" — CR 601.2b): `null`
 *   before the selection is answered when the definition demands one, an (empty) list once settled or
 *   when no such cost applies. Additive, flagged core (`FW-ADDSAC`). Distinct from [sacrificeCost],
 *   which is the *permission*-side cost (Fireblast's two Mountains) — a card may in principle carry
 *   both, and they are separate selections with separate filters.
 *
 *   Settled **before** the payment plan is enumerated, and that ordering is load-bearing: the chosen
 *   permanents are what the plan enumeration reserves against, so a permanent that produces mana by
 *   being sacrificed cannot be spent and then sacrificed again
 *   (docs/design/mana-payment.md §2.2). The permanents are sacrificed when the cast executes, *after*
 *   the mana payment — tapping a land for mana and then sacrificing it is legal (CR 601.2g precedes
 *   CR 601.2h), and that plan stays enumerable.
 */
data class PendingCast(
    val caster: PlayerId,
    val cardObjectId: ObjectId,
    val chosenModes: PersistentList<Int>?,
    val chosenTargets: PersistentList<Target>?,
    val source: CastSource,
    val castingPermission: CastingPermission?,
    val additionalExileCost: PersistentList<ObjectId>?,
    val sacrificeCost: PersistentList<ObjectId>? = null,
    val additionalDiscard: PersistentList<ObjectId>? = null,
    val additionalSacrifice: PersistentList<ObjectId>? = null,
)
