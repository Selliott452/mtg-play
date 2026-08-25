package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaType
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
     * A constant **mixed** multiset: exactly the mana of [types], of two or more different kinds in one
     * activation (CR 605.1a) — Azorius Chancery's "{T}: Add {W}{U}", Burning-Tree Emissary's "Add
     * {R}{G}". Additive, flagged core (`FW-TAPUNTAP`), and the addition [ManaAbility]'s KDoc and
     * docs/design/mana-payment.md §9 both promised: a mixed production was recorded as expressible in
     * `SourceClassKey` (whose profile is already a list of arbitrary multisets) but **not declarable**
     * on a [ManaAbility], and this is the declaration.
     *
     * **The one member that supplies its own types, and that is why it is a [ManaAmount] rather than a
     * widening of [ManaAbility.options].** Every other member is a *number* that the ability's option
     * list is multiplied by: `options × amount` is a choice of type crossed with a count, which can
     * only ever produce a uniform multiset. A mixed production is not a point in that cross product at
     * all — there is no chosen type — so it enters as a shape that replaces the product outright, and
     * [ManaAbility]'s `init` requires such an ability's [ManaAbility.options] to be exactly the distinct
     * types listed here so the two halves of the declaration cannot disagree.
     *
     * **No choice, therefore one production alternative.** A Chancery's activation adds `{W}` *and*
     * `{U}`; it does not offer a pick between them. `mtg-rules` builds one [ProductionAlternative] from
     * this member rather than one per option, which is precisely the difference between "add {W}{U}"
     * and "add one mana of white or blue" — two genuinely different cards that a widened option list
     * could not tell apart.
     *
     * **A single-type multiset is not expressible**, and deliberately: `FixedMultiset([RED, RED])` and
     * [Fixed]`(2)` on a one-option ability would be two spellings of one card, which is how a rules
     * read drifts (the collapse [TargetSpec.TargetPermanent]'s KDoc records). At least two *distinct*
     * types are required.
     *
     * @property types the mana one activation adds, in printed order — Azorius Chancery's is
     *   `[WHITE, BLUE]`. At least two entries, and at least two distinct kinds among them.
     */
    data class FixedMultiset(
        val types: PersistentList<ManaType>,
    ) : ManaAmount {
        init {
            require(types.size >= MIXED_PRODUCTION_MINIMUM) {
                "CR 605.1a: a mixed production adds at least two mana, got $types"
            }
            require(types.distinct().size >= MIXED_PRODUCTION_MINIMUM) {
                "a uniform multiset is Fixed(${types.size}) on a one-option ability, not a mixed " +
                    "production; two spellings of one card would have to be kept in agreement forever, " +
                    "got $types"
            }
        }

        private companion object {
            /** A mixed production names at least this many mana, and at least this many distinct kinds. */
            const val MIXED_PRODUCTION_MINIMUM: Int = 2
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
