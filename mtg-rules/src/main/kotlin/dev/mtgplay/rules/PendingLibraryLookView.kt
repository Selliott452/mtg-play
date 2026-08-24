package dev.mtgplay.rules

import dev.mtgplay.core.definition.LibraryLookSource
import dev.mtgplay.core.identity.PlayerId

/**
 * A private library look as any seat may see it (ADR-007, CR 701.14a): **what happened, never what was
 * seen**. The per-seat projection of [dev.mtgplay.core.state.PendingLibraryLook], and the one pending
 * record that is deliberately *not* passed through to [SeatView] unchanged
 * (docs/design/library-look.md §3).
 *
 * CR 701.14a says a look is seen by its controller and by no other player. Three facts about it are
 * nonetheless public, because they are physically observable at the table: that a look is happening, whose
 * cards are being looked at, and how many. Those are exactly the three fields here.
 *
 * **Why the object ids stop here.** Several other pending records reach a view carrying object ids the
 * [SeatView] KDoc calls opaque — a pending cast's hand card, a pending activation's chosen discards. Those
 * are *hand* ids, and a hand's size is already public, so the id discloses nothing countable. A **library**
 * id is different in kind: library order is precisely the hidden state a look manipulates, so an opponent
 * who learned which objects were arranged could correlate them against a later draw or a later look and
 * reconstruct an order the CR never granted them. The identities — and the ids — reach the deciding seat
 * only through its own [dev.mtgplay.rules.decision.DecisionRequest.ChooseLibraryArrangement], which
 * [DecisionView.Elsewhere] already withholds from everyone else.
 *
 * @property decider the seat looking and arranging (CR 701.14a); public.
 * @property source which zone the cards were taken from — the top of a library, or a hand; public, since
 *   an opponent watches which zone was touched.
 * @property count how many cards are being arranged; public, since an opponent counts them.
 * @property awaitingShuffle whether the arrangement is settled and the clause's "you may shuffle"
 *   (CR 601.3b — Ponder's) is what remains; public, as the yes/no itself will be.
 */
data class PendingLibraryLookView(
    val decider: PlayerId,
    val source: LibraryLookSource,
    val count: Int,
    val awaitingShuffle: Boolean,
)
