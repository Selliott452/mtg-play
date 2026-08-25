package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaType
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * An intrinsic activated mana ability (CR 605.1a, CR 106.11a): "[cost]: add [amount] mana of one of
 * [options]".
 *
 * Activating it pays [cost] and adds mana of the activator's chosen option to their pool, resolving
 * immediately — no stack, no priority round (CR 605.3). A basic Mountain is
 * `ManaAbility(persistentListOf(ManaType.RED))`; an "add one mana of any color" source lists all five
 * colors; a `{C}` producer lists [ManaType.COLORLESS].
 *
 * **How much** one activation adds is [amount], defaulting to exactly one mana — the shape of
 * every source in the MVP pool (docs/decklists.md), which is why almost no definition names it.
 * The `FW-MANA` framework added it as the new vocabulary this type's KDoc had promised rather than
 * a widening of [options]: Urza's Tower adds `{C}{C}{C}` when Tron is assembled and `{C}` when it
 * is not, and Priest of Titania adds one `{G}` per Elf. Both are amounts computed from the board
 * when the ability *resolves* (CR 605.2), so nothing about them can be folded into a static option
 * list; see [ManaAmount] for why that is a different lifecycle from a CR 601.2f cost reduction, and
 * docs/design/mana-payment.md §8 for what it costs the payment enumerator.
 *
 * [options] and [amount] are independent: the option list says *which type* the activator may
 * choose, the amount says *how many* of it. Every source in the pool that offers a choice adds one
 * mana, and every source that adds several has a single option, so the cross product is currently
 * unexercised — it is expressible anyway because keeping the two axes separate is what stops a
 * count from being smuggled into the option list.
 *
 * A **mixed** multiset — Azorius Chancery's "{T}: Add {W}{U}", one activation adding two mana of
 * *different* types — is [ManaAmount.FixedMultiset] since `FW-TAPUNTAP`, and it landed exactly where
 * this KDoc predicted: another `ManaAmount`-sized addition, with no change at all to `SourceClassKey`,
 * whose production descriptor was already a list of arbitrary multisets
 * (docs/design/mana-payment.md §8.1).
 *
 * It is the one amount that **supplies its own types**, so it does not multiply [options] — it
 * replaces the product. An ability declaring it adds every one of its types in a single activation
 * with no choice between them, and the `init` below requires [options] to be exactly the distinct
 * types the multiset names, in WUBRG-then-colorless order, so the two halves of the declaration cannot
 * drift: [options] keeps meaning "the types this source can produce" for every reader that asks, while
 * the amount says they arrive together rather than one at a time.
 *
 * The option list's order is the canonical enumeration order for payment plans (see
 * docs/design/mana-payment.md), so definitions should list options in WUBRG-then-colorless
 * order.
 *
 * **The cost (`FW-MANACOST`).** [cost] was a single `viaSacrifice` flag until the gauntlet printed
 * mana abilities whose cost is neither `{T}` nor "sacrifice this": Barrels of Blasting Jelly's plain
 * `{1}`, Giant's Boulder's `{1}, {T}`, Saruli Caretaker's "{T}, Tap an untapped creature you
 * control", Wall of Roots' "Put a -0/-1 counter on this creature". It is now a composite list of
 * [ManaAbilityCost] components in printed order, defaulting to the bare `{T}` every ordinary source
 * prints, so the Eldrazi Spawn's old `viaSacrifice = true` is now
 * `cost = persistentListOf(ManaAbilityCost.SacrificeSelf)` and nothing else moved.
 *
 * A mana component in that list is what makes a mana ability a *consumer* as well as a producer, and
 * that is a payment-**capacity** problem rather than a production one: two Conduit Pylons must not
 * fund each other's `{1}` out of nothing. What the enumerator does about it is
 * docs/design/mana-payment.md §11.
 *
 * **`{T}` and sacrifice together are now expressible**, which the old `viaSacrifice` flag made
 * impossible and the gauntlet triage records as trap **T2**: Lotus Petal's cost is "{T}, Sacrifice
 * this artifact", not "sacrifice instead of tapping". The flag forced a choice between the two, and
 * choosing sacrifice gave a *tapped* Lotus Petal a live mana ability. A composite list has no such
 * either/or — `[TapSelf, SacrificeSelf]` demands an untapped source (CR 602.2a) and then removes it.
 *
 * @property options the mana types the activator may choose between, exactly one per activation;
 *   never empty, no duplicates.
 * @property cost this ability's activation cost (CR 602.1, CR 605.1a), in printed order; never empty.
 *   Additive, flagged core (`FW-MANACOST`); `[ManaAbilityCost.TapSelf]` for an ordinary source.
 * @property amount how many mana of the chosen option one activation adds (CR 605.1a, CR 605.2);
 *   [ManaAmount.Fixed] `1` for an ordinary source. Additive, flagged core (`FW-MANA`).
 * @property includesChosenColor whether this ability offers, **in addition to** [options], one mana of
 *   the colour its source chose as it entered the battlefield (CR 614.12) — the second half of the Gate
 *   cycle's "{T}: Add {W} **or one mana of the chosen color**". Additive, flagged core (`W8-A`).
 *
 *   A flag beside [options] rather than a sixth [ManaType], because it names *where to read a type* and
 *   not a type: the answer lives on the entering object
 *   ([dev.mtgplay.core.state.GameObject.chosenColor]), so two Citadel Gates that chose differently are
 *   two different source classes and neither of them is a property of the card. `mtg-rules` appends the
 *   chosen colour to [options] when it builds the source's production profile, and appends nothing at
 *   all for a source that made no choice — an object whose colour is somehow absent taps for its printed
 *   options alone rather than for nothing.
 *
 *   It composes with [amount] as [options] does, and is refused alongside [ManaAmount.FixedMultiset]
 *   below for that member's own reason: a mixed production names its own types outright, so there is no
 *   option list for a chosen colour to join.
 * @property oncePerTurn whether the printed text restricts this ability to one activation each turn
 *   (CR 602.5b) — Barrels of Blasting Jelly's and Wall of Roots' "Activate only once each turn".
 *   Additive, flagged core (`FW-MANACOST`). A restriction, not a cost: `mtg-rules` tracks the
 *   activations per **object** (CR 602.5b: the restriction follows the object, not its controller)
 *   and drops the ability from that object's production profile once it is spent, so a spent source
 *   is simply not a source until the turn ends.
 * @property rider the **non-mana** effect this ability performs beside adding its mana (CR 605.1a),
 *   or `null` for the overwhelming majority that perform none. Additive, flagged core (`W8-B`) —
 *   Elves of Deep Shadow's "`{T}`: Add `{B}`. This creature deals 1 damage to you."
 *
 *   It is a field here rather than a reason to demote the card to an [ActivatedAbility] because
 *   CR 605.1a's test is about what the ability *requires* and *could do*, not about what else it does:
 *   an ability that does not target, could add mana, and is not a loyalty ability **is** a mana
 *   ability however much else it says. Demoting it would put the ability on the stack (CR 605.3a says
 *   it never goes there), take the Elf out of the payment planner, and delete the only line the card
 *   is played for. See [ManaAbilityRider].
 */
data class ManaAbility(
    val options: PersistentList<ManaType>,
    val cost: PersistentList<ManaAbilityCost> = persistentListOf(ManaAbilityCost.TapSelf),
    val amount: ManaAmount = ManaAmount.Fixed(1),
    val oncePerTurn: Boolean = false,
    val rider: ManaAbilityRider? = null,
    val includesChosenColor: Boolean = false,
) {
    init {
        require(options.isNotEmpty()) { "CR 605.1a: a mana ability adds mana; options cannot be empty" }
        require(options.distinct().size == options.size) { "mana options must be distinct, got $options" }
        require(cost.isNotEmpty()) { "CR 602.1: an activated ability has a cost, and a mana ability is one" }
        require(cost.distinct().size == cost.size) {
            "CR 602.1: a composite cost names each component once, got $cost"
        }
        // CR 605.1a: a mixed production names its own types, so the option list must say the same
        // thing the multiset does — otherwise a reader asking "what can this source add?" and the
        // production profile would answer differently for the same ability.
        val mixed = amount as? ManaAmount.FixedMultiset
        require(mixed == null || options == mixed.types.distinct().sortedBy(ManaType::ordinal)) {
            "CR 605.1a: a mixed production's options are the distinct types it adds, in " +
                "WUBRG-then-colorless order; ${mixed?.types} needs " +
                "${mixed?.types?.distinct()?.sortedBy(ManaType::ordinal)}, got $options"
        }
        // CR 605.1a: a mixed production supplies its own types, so it has no option list for a CR 614.12
        // chosen colour to be added to; the two declarations would contradict each other.
        require(!(includesChosenColor && amount is ManaAmount.FixedMultiset)) {
            "CR 605.1a: a mixed production names its own mana, so it cannot also add one mana of the " +
                "chosen colour; got $amount with includesChosenColor"
        }
        require(cost.count { it is ManaAbilityCost.Mana } <= 1) {
            "CR 601.2g: a mana ability has at most one mana component; the payment plan records one " +
                "assignment per activation, got $cost"
        }
        // The capacity model counts a source class's membership once per activation, which is only a
        // bound if activating a member *stops* it being available. A `{T}` or sacrifice cost does that
        // by itself; a cost that leaves the source untouched — Wall of Roots' counter, Barrels of
        // Blasting Jelly's bare `{1}` — is bounded only by CR 602.5b, and without either the ability
        // would be activatable without limit and no finite plan enumeration could exist.
        require(
            oncePerTurn ||
                ManaAbilityCost.TapSelf in cost ||
                ManaAbilityCost.SacrificeSelf in cost,
        ) {
            "CR 602.5b: a mana ability that neither taps nor sacrifices its source must be restricted " +
                "to one activation each turn, or it could be activated without limit, got $cost"
        }
    }
}
