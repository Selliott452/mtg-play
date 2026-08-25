package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaCost

/**
 * Ninjutsu (CR 702.49) — the declaration that a card in hand may be swapped onto the battlefield for an
 * unblocked attacker. Additive, flagged core (`FW-NINJUTSU`). Ninja of the Deep Hours' `Ninjutsu {1}{U}`.
 *
 * **It is an activated ability, not a special action, and that is the whole shape of this framework.**
 * CR 702.49a: *"Ninjutsu is an activated ability that functions only while the card with ninjutsu is in a
 * player's hand. 'Ninjutsu \[cost\]' means '\[cost\], Reveal this card from your hand, Return an unblocked
 * attacking creature you control to its owner's hand: Put this card onto the battlefield from your hand
 * tapped and attacking.'"* Three consequences follow, and each is a place the "special action" reading
 * would have made the engine silently wrong:
 *
 * 1. **It uses the stack** (CR 602.2, CR 113.3b), so it can be responded to. The card is *not* put onto
 *    the battlefield when the ability is activated; it goes there when the ability **resolves**. The
 *    Oracle rulings are explicit that a Ninja which leaves its owner's hand in that window never enters
 *    the battlefield at all — a state a stackless special action could never reach.
 * 2. **The cost is paid on activation** (CR 602.2b): the mana, the reveal, and the return of the
 *    unblocked attacker all happen as the ability goes on the stack, before anybody may respond. So the
 *    attacker is already in its owner's hand while the ability waits, and undoing that is not possible
 *    (CR 701.5a: costs stay paid even if the ability is countered).
 * 3. **It is never a cast** (CR 601 does not run): no spell exists, no cast trigger fires, no
 *    cost-modification effect applies, and the card's own printed mana cost is irrelevant. This is why
 *    ninjutsu is **not** a [CastingPermission] — that type's whole contract is "one alternative way a
 *    card may be *cast*", and every member of it runs the CR 601 pipeline from a source zone. Ninjutsu
 *    puts a card onto the battlefield without the card ever having been a spell, so a
 *    [CastingPermission] member for it would have had to opt out of the one thing the type is for.
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This declares only the [cost];
 * `mtg-rules` owns the whole rule — which attackers are unblocked (CR 509.1h, a combat-state read that
 * is meaningless until blockers have been declared), when the ability may be activated, synthesizing the
 * CR 702.49a activated ability onto the stack, and putting the card onto the battlefield tapped and
 * attacking as it resolves.
 *
 * Declared on [CardDefinition] rather than [SpellDefinition] for [CardDefinition.entersTapped]'s reason:
 * the ability functions from the hand and never consults the card's castability. That the card must be a
 * creature card to enter attacking is checked loudly in `mtg-rules`, not encoded in this type.
 *
 * @property cost the ninjutsu cost (CR 702.49a) — the mana half of the composite activation cost, paid
 *   alongside the reveal and the return of the unblocked attacker. It **replaces nothing**: the card's
 *   printed mana cost is simply not involved, because no cast happens.
 */
data class Ninjutsu(
    val cost: ManaCost,
)
