package dev.mtgplay.core.definition

/**
 * A non-mana cost component: sacrifice [count] permanents matching [filter] that the caster controls
 * (CR 601.2h, CR 701.17). Additive, flagged core (P6.2a; [filter] widened by `W8-D`). Card-definition
 * *declaration*; `mtg-rules` owns whether it can be paid (enough matching permanents), surfaces the
 * selection decision, and performs the sacrifice during payment.
 *
 * The pool prints three: Fireblast's alternative cost "sacrifice two Mountains rather than pay this
 * spell's mana cost" ([count]`= 2`), Lava Dart's flashback cost "Sacrifice a Mountain" ([count]`= 1`),
 * and Dread Return's flashback cost "Sacrifice three creatures" ([count]`= 3`). Control is ownership in
 * the MVP pool.
 *
 * **[filter] used to be a bare [dev.mtgplay.core.card.Subtype], and Dread Return is why it is not.**
 * The two Mountain costs made a single printed subtype look like the whole shape of a permission-side
 * sacrifice, and its KDoc said so; "Sacrifice three creatures" names a **card type**, which a subtype
 * cannot express and which no amount of Mountains would have revealed. Rather than adding a second,
 * parallel axis here, the requirement now carries the [SacrificeFilter] the cast-side and
 * activation-side sacrifice costs were already written against — so there is one answer to "which
 * permanents may pay a sacrifice cost?" and it cannot drift between the three places a cost attaches.
 *
 * @property count how many permanents must be sacrificed (at least 1).
 * @property filter which permanents may be chosen to pay it (CR 601.2h).
 */
data class SacrificeRequirement(
    val count: Int,
    val filter: SacrificeFilter,
) {
    init {
        require(count >= 1) { "CR 601.2h: a sacrifice cost sacrifices at least one permanent, was $count" }
    }
}
