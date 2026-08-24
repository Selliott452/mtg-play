package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaType
import kotlinx.collections.immutable.PersistentList

/**
 * An intrinsic activated mana ability with cost `{T}` (CR 605.1a, CR 106.11a): "tap this
 * permanent: add [amount] mana of one of [options]".
 *
 * Activating it taps the source and adds mana of the activator's chosen option to their pool,
 * resolving immediately — no stack, no priority round (CR 605.3). A basic Mountain is
 * `ManaAbility([RED])`; an "add one mana of any color" source lists all five colors; a `{C}`
 * producer lists [ManaType.COLORLESS].
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
 * *different* types — is deliberately **not** expressible: an amount multiplies one chosen type.
 * That card needs another `ManaAmount`-sized addition here, and no change at all to
 * `SourceClassKey`, whose production descriptor is already a list of arbitrary multisets
 * (docs/design/mana-payment.md §8.1).
 *
 * The option list's order is the canonical enumeration order for payment plans (see
 * docs/design/mana-payment.md), so definitions should list options in WUBRG-then-colorless
 * order.
 *
 * The activation cost is `{T}` by default; [viaSacrifice] flips it to "Sacrifice this permanent"
 * instead (CR 605.1a) — Malevolent Rumble's Eldrazi Spawn token, "Sacrifice this token: Add {C}". A
 * sacrifice-cost mana ability is still a mana ability (no stack, CR 605.3) and is payable during
 * payment enumeration like tap-for-mana; the engine sacrifices the source rather than tapping it, and
 * such a source is usable whether or not it is tapped. Costs beyond those two — Saruli Caretaker's
 * "{T}, Tap an untapped creature you control", Conduit Pylons' "{1}, {T}" — are still absent, and
 * are a payment-*capacity* problem rather than a production one (docs/design/mana-payment.md §9).
 *
 * @property options the mana types the activator may choose between, exactly one per activation;
 *   never empty, no duplicates.
 * @property viaSacrifice whether activating this ability sacrifices the source instead of tapping it
 *   (CR 605.1a) — Eldrazi Spawn's `{C}`. Additive, flagged core (P6.2a). `false` for an ordinary `{T}`
 *   mana ability.
 * @property amount how many mana of the chosen option one activation adds (CR 605.1a, CR 605.2);
 *   [ManaAmount.Fixed] `1` for an ordinary source. Additive, flagged core (`FW-MANA`).
 */
data class ManaAbility(
    val options: PersistentList<ManaType>,
    val viaSacrifice: Boolean = false,
    val amount: ManaAmount = ManaAmount.Fixed(1),
) {
    init {
        require(options.isNotEmpty()) { "CR 605.1a: a mana ability adds mana; options cannot be empty" }
        require(options.distinct().size == options.size) { "mana options must be distinct, got $options" }
    }
}
