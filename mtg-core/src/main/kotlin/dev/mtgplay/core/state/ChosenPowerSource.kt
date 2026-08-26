package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId

/**
 * What was named to pay a **non-consuming** additional cost, and where a later "the power of" read must
 * therefore look (CR 601.2b, CR 608.2h) — the linked information Monstrous Emergence's
 * *"the power of the creature you chose or the card you revealed"* reads back. Additive, flagged core
 * (`W9-D`).
 *
 * **Two members because the two halves of that sentence read power from genuinely different places**, and
 * collapsing them would be a rules error rather than a modelling shortcut:
 *
 * - A creature on the battlefield has the CR 613 **layered** power the layer system computes. It is live
 *   until the spell resolves (CR 608.2h), so pumping it in response grows the damage and Cryoshatter's
 *   `-5/-0` shrinks it — both real plays, both correct only if the value is read at resolution.
 * - A card in a hand has its **printed** power and nothing else. CR 109.3 is explicit that a card outside
 *   the battlefield has only the characteristics its printed text and copy effects give it, and no
 *   continuous effect in this pool reaches a hand at all. So a revealed card's power is a constant, and
 *   the engine must not route it through the layer system, which would fail looking for a battlefield
 *   object that is not there.
 *
 * **Neither member consumes what it names**, which is what makes the cost that produces them a new shape:
 * the chosen creature stays on the battlefield and the revealed card stays in hand. That is why one is an
 * [ObjectId] and the other a [CardRef] rather than both being object ids — the creature must be
 * *re-identified* at resolution to read its live power, while the card need only be *remembered*, and
 * remembering a hand object id would invite a reader to look up an object the caster may since have
 * discarded, played, or lost.
 *
 * Sealed so `mtg-rules` reads the power exhaustively: a third way to name a power source must break the
 * read at compile time rather than defaulting into one of these two.
 */
sealed interface ChosenPowerSource {
    /**
     * "…the creature you chose" (CR 601.2b) — a creature the caster controls, named as the cost was paid
     * and **not** sacrificed, tapped, or otherwise touched.
     *
     * The id is the creature's battlefield object id, and the read at resolution is deliberately a *live*
     * one: CR 608.2h calculates the value as the spell resolves. If the creature has left the battlefield
     * by then, CR 113.7a's last known information applies, which is what
     * `dev.mtgplay.rules.engine.powerOnBattlefieldOrLastKnown` exists for — the engine cannot recompute a
     * layered power for an object that is gone, so the departure recorded it.
     *
     * @property objectId the chosen creature's battlefield object id (CR 400.7).
     */
    data class ChosenCreature(
        val objectId: ObjectId,
    ) : ChosenPowerSource

    /**
     * "…the card you revealed" (CR 601.2b, CR 701.15a) — a creature card revealed from the caster's hand
     * as the cost was paid, and left there.
     *
     * The **printed** identity rather than the hand object, for the reason [ChosenPowerSource]'s KDoc
     * gives: CR 109.3 makes a hand card's power its printed one and nothing can change it, so the value
     * is already fixed when the reveal happens and the object it came from need never be found again.
     * The reveal itself is public (CR 701.15a) — both seats saw the card, which is what makes recording
     * its name rather than an anonymous number honest.
     *
     * @property card the revealed creature card's printed identity (CR 201.2).
     */
    data class RevealedCard(
        val card: CardRef,
    ) : ChosenPowerSource
}
