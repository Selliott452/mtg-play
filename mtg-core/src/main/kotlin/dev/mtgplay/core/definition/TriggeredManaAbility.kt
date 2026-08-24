package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaType

/**
 * A triggered **mana** ability (CR 605.1b): a triggered ability that could add mana and triggers off an
 * activation of a mana ability. Additive, flagged core (P6.2a; [AddFixedMana] added in P6.3). Unlike an
 * ordinary triggered ability it does **not** use the stack and does not use priority (CR 605.3): it
 * resolves immediately, during the mana-ability resolution that set it off, and its mana joins the pool
 * in the same payment step.
 *
 * The MVP pool needs two shapes, both carried by an Aura watching the permanent it is attached to being
 * tapped for mana:
 * - [AddChosenColor] — Utopia Sprawl's "Whenever enchanted Forest is tapped for mana, its controller
 *   adds an additional one mana of the chosen color", reading the Aura's own
 *   [dev.mtgplay.core.state.GameObject.chosenColor], fixed as the Aura entered;
 * - [AddFixedMana] — Wild Growth's "Whenever enchanted land is tapped for mana, its controller adds an
 *   additional {G}", whose mana type is printed on the card and needs no choice.
 *
 * `mtg-rules` fires both inside `resolveTapForMana` (docs/design/mana-payment.md, §"Triggered mana
 * abilities mid-payment").
 *
 * Sealed so the mana engine handles every triggered-mana shape exhaustively; other shapes (a choice made
 * on resolution, a conditional bonus) are the extension point.
 */
sealed interface TriggeredManaAbility {
    /**
     * "Whenever enchanted permanent is tapped for mana, add an additional [amount] mana of the chosen
     * colour" (CR 605.1b) — Utopia Sprawl. The colour is the source Aura's
     * [dev.mtgplay.core.state.GameObject.chosenColor], chosen as it entered.
     *
     * @property amount how much extra mana of the chosen colour to add (Utopia Sprawl's is 1).
     */
    data class AddChosenColor(
        val amount: Int = 1,
    ) : TriggeredManaAbility {
        init {
            require(amount >= 1) { "CR 605.1b: a triggered mana ability adds at least one mana, was $amount" }
        }
    }

    /**
     * "Whenever enchanted permanent is tapped for mana, add an additional [amount] mana of [manaType]"
     * (CR 605.1b) — Wild Growth's additional `{G}`. The type is printed, so — unlike [AddChosenColor] —
     * the Aura makes no as-it-enters choice and needs no
     * [dev.mtgplay.core.state.GameObject.chosenColor].
     *
     * @property manaType the mana this ability adds (Wild Growth's [ManaType.GREEN]).
     * @property amount how much of [manaType] to add (Wild Growth's is 1).
     */
    data class AddFixedMana(
        val manaType: ManaType,
        val amount: Int = 1,
    ) : TriggeredManaAbility {
        init {
            require(amount >= 1) { "CR 605.1b: a triggered mana ability adds at least one mana, was $amount" }
        }
    }
}
