package dev.mtgplay.core.state

import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList

/**
 * A "choose a card type, then reveal the top N and partition them" clause paused for that choice
 * (CR 609.4, CR 701.16) — Winding Way's. Additive, flagged core (`W8-D`). Non-null only at that
 * mid-resolution pause, with the resolving spell still on top of the stack and **nothing yet revealed**.
 *
 * **The library is untouched while this record is open**, which is the ordering the clause exists to
 * enforce: Winding Way prints the choice before the reveal, so the caster names a type without seeing
 * the four cards. Recording the *offered* types rather than the revealed cards is what makes that
 * literal — there are no revealed cards yet to record.
 *
 * @property decider the resolving spell's controller, who names the type (CR 608.2a).
 * @property choices the types offered, in printed order; the answer is an index into this list
 *   (ADR-005).
 * @property revealCount how many cards the reveal will show once the type is named.
 * @property sourceCard the printed identity of the resolving object, for display.
 */
data class PendingTypeChoice(
    val decider: PlayerId,
    val choices: PersistentList<RevealedCardFilter>,
    val revealCount: Int,
    val sourceCard: CardRef,
)
