package dev.mtgplay.rules

import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.TokenDefinition

/**
 * The **public** half of one card's definition, as a seat may see it (ADR-007): its printed
 * characteristics plus the one derived public fact that it is a token. The value type of
 * [SeatView.cards]; see docs/design/seat-view-definitions.md for the full argument.
 *
 * Deliberately *not* a [CardDefinition]. A definition also carries what the card **does** —
 * resolution effects, triggered/activated/static abilities, casting permissions — which are
 * function-valued, carry reference equality only (ADR-009), are not serializable, and are no part of
 * what a player reads off the table. What a player reads off the table is the printed card, which is
 * exactly [PrintedCharacteristics].
 *
 * @property characteristics the card's printed characteristics (CR 109.3): name, mana cost, type
 *   line, printed power/toughness, printed keywords and evasions. Printed values only — in-game
 *   characteristics are computed by the layer system (CR 613), which a consumer must apply itself
 *   from the public battlefield state.
 * @property isToken whether this printed reference is a **token** rather than a card (CR 111): the
 *   engine's own `definitions[card] is `[TokenDefinition] test, surfaced because it is not
 *   reconstructible from [characteristics] (a token's type line is an ordinary type line) and
 *   because it is what the CR 704.5d "a token in a non-battlefield zone ceases to exist"
 *   state-based action keys on. Public — a token on the battlefield is visibly a token (CR 111).
 */
data class PrintedCardView(
    val characteristics: PrintedCharacteristics,
    val isToken: Boolean,
)

/** The public [PrintedCardView] projection of [definition] (CR 111 for the token fact). */
internal fun printedCardViewOf(definition: CardDefinition): PrintedCardView =
    PrintedCardView(
        characteristics = definition.characteristics,
        isToken = definition is TokenDefinition,
    )
