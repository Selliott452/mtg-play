package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger

/*
 * `W8-C`: the two trigger conditions that watch a permanent's **status** rather than a zone change —
 * becoming tapped (CR 701.20a) and being dealt damage (CR 120.3d). Cryoshatter is their only client.
 *
 * Split from `TriggerDetection.kt` for file size, but the split names a real distinction. Every detector
 * there watches an object *moving* — entering the battlefield, leaving it, a spell being cast, a card
 * being drawn — and each of those has exactly one place in the engine where it happens. A status change
 * does not: a permanent becomes tapped in four unrelated places, and damage lands from combat and from
 * resolutions alike. Giving each condition **one announcement** that those places call is what keeps a
 * site from being added later that quietly fires nothing (the argument [announceBattlefieldEntry] makes
 * for CR 603.6a, and the failure triage T18 records).
 */

/**
 * Announces that [tappedId] has **become tapped** and fires the triggers that status change fires
 * (CR 603.2, CR 701.20a) — **the one home every tap shares**, the tap-side sibling of
 * [announceBattlefieldEntry] and [announceBattlefieldDeparture]. Additive (`W8-C`); Cryoshatter's
 * [TriggerCondition.EnchantedPermanentBecomesTapped] is the pool's only client.
 *
 * **Called only where the status actually flips from untapped to tapped.** CR 701.20a says a permanent
 * that is already tapped can't be tapped again, so "becomes tapped" is a *change* and not a request: each
 * of the four call sites checks or guarantees the pre-state and calls this exactly once per real flip.
 * The four are the ways a permanent in this engine can become tapped — a `{T}` ability cost (CR 602.2a),
 * a mana ability's cost (CR 605.1a, including Saruli Caretaker's "tap another untapped creature"), being
 * declared as an attacker without vigilance (CR 508.1f), and a resolving tap effect (CR 701.20a).
 *
 * **Entering the battlefield tapped is deliberately not one of them.** A permanent that arrives tapped was
 * never untapped, so no status changed and nothing fires — a Bridge land, a ninjutsu'd Ninja, and a
 * Landscape's searched-up basic are all silent here. That is the same line
 * [TriggerCondition.EnteredBattlefieldUntappedSelf] draws from the other side.
 *
 * **Why this is a shared announcement rather than four careful call sites**, which is the argument
 * [announceBattlefieldEntry] already makes and the reason triage T18 exists: a tap site that forgets to
 * fire leaves *no* trace — the permanent is tapped, the board looks right, and the trigger is simply gone.
 * Attacking is the site that matters most and the one furthest from the others in the code, so the whole
 * point of a Cryoshatter would be quietly missing if it were left to be remembered. The residual risk is a
 * fifth site that flips the status without calling this, which no invariant covers today.
 *
 * A no-op for an object that is not on the battlefield, and for one carrying no matching Aura.
 */
internal fun announceBecameTapped(
    state: GameState,
    tappedId: ObjectId,
): GameState = fireEnchantedTriggers(state, tappedId, TriggerCondition.EnchantedPermanentBecomesTapped)

/**
 * Announces that [damagedId] has **been dealt damage** and fires the triggers that fires (CR 603.2,
 * CR 120.3d) — Cryoshatter's [TriggerCondition.EnchantedPermanentIsDealtDamage]. Additive (`W8-C`).
 *
 * The recipient-side mirror of [fireEnchantedDamageTriggers], which watches the enchanted creature as the
 * damage's *source*. Its one call site is [markDamage], where damage dealt to a permanent is actually
 * marked — which is past both of `dealDamage`'s exits, so CR 120.8 zero damage and CR 615.6 prevented
 * damage each fire nothing without this function knowing either rule.
 *
 * The amount is deliberately **not** carried onto the trigger: the printed line is "is dealt damage" with
 * no "that much", so nothing reads it, and a captured number nothing consumes is a field that can silently
 * go stale. [fireEnchantedDamageTriggers] carries one because Armadillo Cloak's line does say "that much".
 */
internal fun fireEnchantedDamageReceivedTriggers(
    state: GameState,
    damagedId: ObjectId,
): GameState = fireEnchantedTriggers(state, damagedId, TriggerCondition.EnchantedPermanentIsDealtDamage)

/**
 * Fires, for every Aura attached to [hostId], its battlefield-scoped abilities whose condition is
 * satisfied by [event] (CR 603.2, CR 611.2c) — the shared body of the two announcements above.
 *
 * Each fired trigger carries the **Aura** as its source and controller (CR 603.3a: the ability's
 * controller is the source's) and the enchanted permanent as its [PendingTrigger.subject] — the object
 * "destroy **it**" acts on, captured now as CR 603.10 last-known information, because the Aura may have
 * fallen off (CR 704.5m) by the time the ability resolves.
 */
private fun fireEnchantedTriggers(
    state: GameState,
    hostId: ObjectId,
    event: TriggerCondition,
): GameState =
    state.sharedZones.battlefield
        .filter { it.attachedTo == hostId }
        .fold(state) { current, aura ->
            battlefieldTriggersOf(current, aura.card, event).fold(current) { inner, ability ->
                enqueuePendingTrigger(
                    inner,
                    PendingTrigger(aura.id, aura.card, aura.owner, ability, subject = hostId),
                )
            }
        }
