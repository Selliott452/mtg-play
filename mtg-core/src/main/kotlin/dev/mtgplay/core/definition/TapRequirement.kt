package dev.mtgplay.core.definition

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.mana.Color

/**
 * A non-mana cost component: **tap [count] untapped permanents the caster controls** matching [color]
 * and [cardType] (CR 601.2h, CR 702.34c) — Prismatic Strands' "Flashback—Tap an untapped white
 * creature you control". Additive, flagged core (`FW-PREVENT2`). Card-definition *declaration*;
 * `mtg-rules` owns whether it can be paid, surfaces the enumerated selection, and taps during payment.
 *
 * **The fourth cost component with a chosen object**, after
 * [AdditionalCost.DiscardCards], [AdditionalCost.Sacrifice] and
 * [AbilityCost.ReturnPermanentYouControl], and deliberately their shape: the engine enumerates the
 * candidates and the caster picks by index (ADR-005).
 *
 * **Why a fourth filter type rather than a reuse.** The predicate this cost needs is *a colour*, and
 * neither existing filter can say one:
 * - [PermanentFilter] carries subtype, controller, card type and keyword, and deliberately no colour.
 *   It also takes part in `SourceClassKey` structural equality, so widening it would reshape
 *   mana-payment class collapsing (docs/design/mana-payment.md §2) for a reason unrelated to mana —
 *   the objection [SacrificeFilter] already recorded when it declined the same reuse.
 * - [SacrificeFilter] carries a set of card types and nothing else.
 *
 * So this carries exactly the two axes the one printing needs, conjoined, and nothing else. A count
 * restriction beyond one, a subtype axis, and a controller axis are the extension points; "you
 * control" is *not* one of them, because it is not optional here — CR 601.2h lets a player tap only
 * permanents they control to pay a cost, so it is a property of the cost rather than of the filter.
 *
 * **Untapped is likewise not a field.** A tap cost can only ever be paid by an untapped permanent
 * (CR 118.4: a player can't pay a cost they can't pay, and tapping a tapped permanent does nothing),
 * so the word "untapped" in the printed text is reminder text and the restriction is intrinsic. Making
 * it a flag would invite a `false` that means nothing.
 *
 * **Summoning sickness does not apply, and the omission is the ruling.** CR 302.6 restricts abilities
 * *of that permanent* whose cost includes the `{T}` symbol. This is a cost of a **spell**, and the
 * tapped creature is the source of nothing — so a creature that entered the battlefield this turn can
 * pay it. There is deliberately nowhere here to express a summoning-sickness gate, because adding one
 * would delete a real and frequently-correct line of play (ADR-005).
 *
 * @property count how many permanents must be tapped (at least 1).
 * @property color the colour every tapped permanent must be (CR 105.1) — Prismatic Strands' white.
 *   Read from [dev.mtgplay.core.card.PrintedCharacteristics.colors], so a
 *   [dev.mtgplay.core.card.Keyword.DEVOID] permanent is colourless and never matches (CR 702.114a).
 * @property cardType the card type every tapped permanent must have (CR 205.2) — the "creature".
 */
data class TapRequirement(
    val count: Int,
    val color: Color,
    val cardType: CardType,
) {
    init {
        require(count >= 1) { "CR 601.2h: a tap cost taps at least one permanent, was $count" }
    }
}
