package dev.mtgplay.core.state

import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList

/**
 * An activated ability the engine is gathering choices for (CR 602.2): the activator has chosen to
 * activate an ability and the choices it needs — its targets (CR 602.2b via CR 601.2c), a card to
 * discard (CR 602.2b), then a payment plan (CR 602.2f–g) — are being collected one `DecisionRequest` at
 * a time (ADR-004). Additive, flagged core (P6.2a).
 *
 * While a [PendingActivation] is open, nothing about the game has changed: the whole activation runs
 * atomically in the transition that receives the final choice, so an aborted activation leaves exactly
 * the pre-activation state (CR 602.2 atomicity). This record is only the gathered-so-far choices, which
 * is what lets the pending decision stay a pure function of the state (ADR-004 resumability).
 *
 * @property activator the player activating; they hold priority for the whole gathering.
 * @property sourceObjectId the object whose ability is being activated; in the [source] zone.
 * @property source the zone the source is in (CR 113.6) — battlefield or hand.
 * @property abilityIndex which of the source definition's activated abilities is being activated.
 * @property chosenDiscard the hand cards chosen to pay a "discard a card" cost component: `null` before
 *   the selection is answered when the cost demands one, an (empty) list once settled or when no such
 *   component applies.
 * @property chosenTargets the targets chosen so far (CR 602.2b, following CR 601.2c): `null` before the
 *   targets decision is answered, the chosen list after — empty exactly when the ability targets nothing.
 *   Additive, flagged core (`FW-ABILTGT`, docs/design/targeted-abilities.md). Gathered **first**, before
 *   any cost selection, because CR 602.2b runs the CR 601.2b–i sequence in order. An ability with no
 *   legal target is never enumerated (CR 601.2c), so this list is never left short.
 * @property chosenSacrifice the battlefield permanent chosen to pay a
 *   [dev.mtgplay.core.definition.AbilityCost.Sacrifice] component (Krark-Clan Shaman's "Sacrifice an
 *   artifact", Makeshift Munitions' "Sacrifice an artifact or creature"): `null` before the selection is
 *   answered when the cost demands one, an (empty) list once settled or when no such component applies.
 *   Additive, flagged core (`FW-ADDSAC`).
 *
 *   Gathered **before** the payment plan, which is what lets the plan enumeration reserve exactly the
 *   chosen permanent when it is a sacrifice-cost mana source and nothing otherwise
 *   (docs/design/mana-payment.md §2.2).
 * @property chosenReturn the battlefield permanent chosen to pay a
 *   [dev.mtgplay.core.definition.AbilityCost.ReturnPermanentYouControl] component (Quirion Ranger's
 *   "Return a Forest you control to its owner's hand"): `null` before the selection is answered when
 *   the cost demands one, an (empty) list once settled or when no such component applies. Additive,
 *   flagged core (`FW-TAPUNTAP`).
 *
 *   Gathered **after** the sacrifice selection and **before** the payment plan, for the reason the
 *   sacrifice is: a permanent that has been chosen to leave the battlefield must be reservable by the
 *   plan enumeration, and a choice not yet made cannot be reserved. A returned permanent is reserved
 *   *unconditionally*, unlike a sacrificed one — see `manaSourcesReservedBy`.
 * @property chosenTap the battlefield permanent chosen to pay a
 *   [dev.mtgplay.core.definition.AbilityCost.TapPermanentYouControl] component (Pinnacle Kill-Ship's
 *   Station, "Tap another creature you control"): `null` before the selection is answered when the cost
 *   demands one, an (empty) list once settled or when no such component applies. Additive, flagged core
 *   (`W10-C`).
 *
 *   Gathered **after** the return selection and **before** the payment plan, for the reason both of
 *   those are, and with [chosenReturn]'s *unconditional* reservation rather than [chosenSacrifice]'s
 *   conditional one: a permanent tapped to pay this cost cannot also have been tapped for mana, because
 *   CR 601.2g's mana abilities are activated before CR 601.2h pays the costs and a tapped permanent has
 *   nothing left to give either way round. Reserving it is what stops that plan being enumerated.
 * @property chosenX the value announced for the ability's variable cost (CR 107.3, CR 601.2b via
 *   CR 602.2b): `null` before the announcement is made when the cost carries
 *   [dev.mtgplay.core.mana.ManaSymbol.X], `0` once settled or when it does not. Additive, flagged core
 *   (`W9-C`, docs/design/dependent-targets.md §3) — Gorilla Shaman's `{X}{X}{1}`.
 *
 *   **Settled *first*, before [chosenTargets], which is CR 601.2b's own printed order** and the exact
 *   opposite of where [dev.mtgplay.core.state.PendingCast.chosenX] sits. The two are not inconsistent;
 *   they are the same trade decided differently because the two paths have different things to protect:
 *
 *   - The **cast** path deviates from CR 601.2b and announces X *after* every cost selection, so the
 *     bound on X can use the exact mana reservation those selections produce. It pays for that with the
 *     rule that no card's targets may depend on its X — a rule the whole encoded pool obeys.
 *   - The **activation** path had no X stage at all before `W9-C`, so it has no such pool to protect, and
 *     Gorilla Shaman's "target noncreature artifact **with mana value X**" makes the deviation
 *     immediately observable: the legal targets are not knowable until X is. It therefore announces at
 *     CR 601.2b's printed position and takes the weaker bound — see `AbilityXCost.kt`, which gates that
 *     weakness loudly rather than assuming it away.
 *
 *   The choice is per *path* rather than global precisely so the cast side keeps its exact reservation;
 *   reordering both would have charged every cast in the game for one activated ability.
 */
data class PendingActivation(
    val activator: PlayerId,
    val sourceObjectId: ObjectId,
    val source: AbilityZoneScope,
    val abilityIndex: Int,
    val chosenDiscard: PersistentList<ObjectId>?,
    val chosenTargets: PersistentList<Target>? = null,
    val chosenSacrifice: PersistentList<ObjectId>? = null,
    val chosenReturn: PersistentList<ObjectId>? = null,
    val chosenTap: PersistentList<ObjectId>? = null,
    val chosenX: Int? = null,
) {
    init {
        require(chosenX == null || chosenX >= 0) {
            "CR 601.2b: an announced value of X is non-negative, was $chosenX"
        }
    }
}
