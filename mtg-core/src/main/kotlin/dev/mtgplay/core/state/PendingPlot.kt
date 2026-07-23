package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * A plot special action the engine is gathering a payment for (CR 702.140, CR 116.2g): the caster has
 * chosen to plot a hand card and must now choose how to pay its plot cost, gathered as a
 * `ChoosePaymentPlan` decision (ADR-004). Additive, flagged core (P6.2a).
 *
 * While a [PendingPlot] is open, the card is **still in the caster's hand** and nothing about the game
 * has changed: the plot action runs atomically in the single transition that receives the payment plan,
 * exiling the card with a plotted-turn marker (see [GameObject.plottedTurn]). The caster holds priority
 * throughout, as with a cast's gathering, which is what lets the pending decision stay a pure function
 * of the state (ADR-004). Non-null only at that payment pause.
 *
 * @property caster the player plotting; they hold priority for the whole gathering.
 * @property cardObjectId the hand object being plotted; still in [caster]'s hand.
 */
data class PendingPlot(
    val caster: PlayerId,
    val cardObjectId: ObjectId,
)
