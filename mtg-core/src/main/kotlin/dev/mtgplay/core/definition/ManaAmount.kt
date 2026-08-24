package dev.mtgplay.core.definition

import kotlinx.collections.immutable.PersistentList

/**
 * How much mana one activation of a [ManaAbility] adds (CR 605.1a) — a constant, or a number read
 * off the board when the ability **resolves** (CR 605.2).
 *
 * **CR 605.2, not CR 601.2f.** This is the distinction the whole framework turns on. A cost
 * *reduction* is locked in at CR 601.2f: the total cost is determined once, early in casting, and
 * nothing that happens during payment can change it. A mana ability's amount is not determined
 * early at all — the ability resolves during CR 601.2g, in the middle of paying, and CR 605.2's own
 * worked example is a counted one ("{T}: Add {G} for each creature you control"). So the count is
 * read live, per activation, and is never snapshotted. The practical consequence for this engine is
 * in docs/design/mana-payment.md §8.1: the enumerator must plan against a number it does not own,
 * and the executor must be *unable* to quietly produce a different one.
 *
 * A closed hierarchy, exhaustively matched in `mtg-rules`, so a card printing a shape that is not
 * here breaks compilation rather than being approximated. Members exist only where a card in the
 * pool prints them.
 */
sealed interface ManaAmount {
    /**
     * A constant amount: `[count]` mana of the chosen type. Every ordinary source is
     * `Fixed(1)` — a Mountain's "{T}: Add {R}" — and it is the default a [ManaAbility] carries, so
     * the overwhelming majority of definitions never mention this type at all.
     *
     * @property count how many mana one activation adds; at least 1. Zero is not expressible: an
     *   ability that adds nothing is not a mana ability worth enumerating, and the rules-side
     *   profile drops a source down to zero mana rather than offering an empty activation.
     */
    data class Fixed(
        val count: Int,
    ) : ManaAmount {
        init {
            require(count >= 1) { "CR 605.1a: a mana ability adds at least one mana, got $count" }
        }
    }

    /**
     * One mana per battlefield permanent matching [each] (CR 605.2) — Priest of Titania's "Add {G}
     * for each Elf on the battlefield", Overgrown Battlement's "for each creature you control with
     * defender".
     *
     * The count is read when the ability resolves, so it tracks the board between two activations
     * in the same payment. It can legitimately be **zero** (no matching permanent), in which case
     * the source produces nothing and `mtg-rules` treats it as no mana source at all for that
     * state — a source that adds nothing can never appear in a payment plan, because the plan's
     * no-idle rule (docs/design/mana-payment.md §4) would reject every plan containing it.
     *
     * @property each the permanents counted.
     */
    data class PerPermanent(
        val each: PermanentFilter,
    ) : ManaAmount

    /**
     * A two-valued amount chosen by a board condition (CR 605.2): [ifMet] mana when the player
     * controls at least one permanent matching **every** filter in [requires], and [otherwise]
     * mana when they do not.
     *
     * This is the Urza-land shape, and the amounts differ per card: Urza's Mine and Urza's Power
     * Plant read "add {C}{C} instead" ([ifMet] = 2) while Urza's Tower reads "add **{C}{C}{C}**
     * instead" ([ifMet] = 3). All three are [otherwise] = 1.
     *
     * Like [PerPermanent] the condition is evaluated at resolution and never locked in. Unlike a
     * CR 601.2f reduction, assembling or losing a Tron piece *between* two activations of the same
     * payment would change the second activation's yield — see docs/design/mana-payment.md §8.3 for
     * why that is unreachable in this pool and what the engine does if it ever is not.
     *
     * @property requires the filters that must each match at least one permanent; never empty.
     * @property ifMet the amount when every filter is satisfied; at least 1.
     * @property otherwise the amount when any filter is not; at least 1.
     */
    data class Conditional(
        val requires: PersistentList<PermanentFilter>,
        val ifMet: Int,
        val otherwise: Int,
    ) : ManaAmount {
        init {
            require(requires.isNotEmpty()) { "a conditional mana amount needs at least one condition" }
            require(ifMet >= 1 && otherwise >= 1) {
                "CR 605.1a: a mana ability adds at least one mana, got ifMet=$ifMet otherwise=$otherwise"
            }
        }
    }
}
