package dev.mtgplay.core.definition

/**
 * A triggered **mana** ability (CR 605.1b): a triggered ability that could add mana and triggers off an
 * activation of a mana ability. Additive, flagged core (P6.2a). Unlike an ordinary triggered ability it
 * does **not** use the stack and does not use priority (CR 605.3): it resolves immediately, during the
 * mana-ability resolution that set it off, and its mana joins the pool in the same payment step.
 *
 * The MVP pool needs exactly Utopia Sprawl's shape: "Whenever enchanted Forest is tapped for mana, its
 * controller adds an additional one mana of the chosen color." That is [AddChosenColor] — the ability
 * is carried by an Aura, watches the permanent the Aura is attached to being tapped for mana, and adds
 * mana of the Aura's own chosen colour ([dev.mtgplay.core.state.GameObject.chosenColor], fixed as the
 * Aura entered). `mtg-rules` fires it inside `resolveTapForMana` (docs/design/mana-payment.md,
 * §"Triggered mana abilities mid-payment").
 *
 * Sealed so the mana engine handles every triggered-mana shape exhaustively; other shapes (a fixed
 * colour, colourless, more than one mana) are the extension point.
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
}
