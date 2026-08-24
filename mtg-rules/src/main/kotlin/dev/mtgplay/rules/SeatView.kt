package dev.mtgplay.rules

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.PendingActivation
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.core.state.PendingColorChoice
import dev.mtgplay.core.state.PendingCounterPayment
import dev.mtgplay.core.state.PendingLibrarySearch
import dev.mtgplay.core.state.PendingMadness
import dev.mtgplay.core.state.PendingMulligan
import dev.mtgplay.core.state.PendingOptionalCostDraw
import dev.mtgplay.core.state.PendingOptionalDiscardDraw
import dev.mtgplay.core.state.PendingPlot
import dev.mtgplay.core.state.PendingReplacement
import dev.mtgplay.core.state.PendingResolutionDiscard
import dev.mtgplay.core.state.PendingTriggerTargets
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.Turn

/**
 * Exactly what one seat may legally see of a paused game (ADR-007): the pure, per-seat filtered
 * projection of a [dev.mtgplay.core.state.GameState] produced by [viewFor].
 *
 * ADR-007 makes hidden-information hiding the **engine's** responsibility, applied at the library
 * boundary — a wrong filter is a silent information leak to a training agent, so the derivation
 * lives here in `mtg-rules` (never in a consumer) beside [pendingRequestOf], and the protocol
 * layer (ADR-008) transports these views rather than the raw state. This type is a **read-only
 * derivation**: it never changes engine behaviour, and two calls on the same state and seat are
 * equal.
 *
 * **What is dropped entirely** (present on [dev.mtgplay.core.state.GameState], absent here):
 * - the PRNG (`rng`) — a seat that could read the PRNG state would predict every future shuffle
 *   and draw, the exact cheat ADR-006 + ADR-007 forbid; it is neither public nor per-seat.
 * - the event log (`events`) — derived observability for replay/debugging (ADR-006), not part of
 *   what a player sees at the table, and it records draw specifics that are not a seat's to read.
 * - the card-definition registry (`definitions`) — **as a whole**. What a card *does* (resolution
 *   effects, abilities, casting permissions) is not something a player reads off the table, and the
 *   registry's whole key set is every card the match was configured with, which would tell a seat
 *   the names of cards sitting unseen in an opponent's library. What a seat *may* see — the printed
 *   characteristics of the cards this view already names — is projected into [cards] instead
 *   (docs/design/seat-view-definitions.md).
 * - the object-id allocation counter (`nextObjectId`) — engine bookkeeping; agents choose
 *   enumerated options by index (ADR-005) and never mint ids, so it is not player-visible state.
 *
 * @property viewer the seat this projection is filtered for; only [viewer]'s own hidden zones are
 *   ever revealed in full.
 * @property cards the printed characteristics of the cards this view names, keyed by printed
 *   identity ([dev.mtgplay.core.identity.CardRef]) — the public half of the match's definition
 *   registry (see [PrintedCardView]). **Scope:** an entry for exactly those refs this same view
 *   already names — every zone below, plus [viewer]'s own hand — intersected with the registry, and
 *   no others; a ref with no definition is an inert card (P2.1) and simply has no entry, never a
 *   fabricated one. Every key is therefore a card whose identity the view has already disclosed
 *   under a rule documented here, so the table adds no disclosure of its own: naming an object and
 *   describing it are the same disclosure. This exists for **tokens** (CR 111): a token is not a
 *   card, its definition is registered only when an effect creates it, and its characteristics are
 *   public to every seat — so without this a consumer could see a token and never learn what it is.
 *   Deliberately excluded: the [pendingDecision] request's own option cards (a library search
 *   enumerates library cards, CR 701.18), which would make the key set depend on the pending
 *   request rather than on the zones alone. **With one exception**, added by `FW-LIBLOOK`
 *   (docs/design/library-look.md §3): the cards of a
 *   [dev.mtgplay.rules.decision.DecisionRequest.ChooseLibraryArrangement] the **viewer itself** must
 *   answer. A scry is decided *on characteristics* — is this a land, is it cheap — so a deciding seat
 *   holding names with no entry here would not be receiving what CR 701.14a says it sees, and
 *   [SeatView] drops the definition registry so there is nowhere else to look them up. The exception is
 *   safe because it is gated on [DecisionView.ToDecide], which is itself the per-seat filter: every
 *   other seat receives [DecisionView.Elsewhere], which structurally carries no request and therefore no
 *   options. The library-search case is left excluded on purpose — its options are pre-filtered by the
 *   engine, so characteristics add nothing to that choice, and the found card becomes public and
 *   hand-resident in the same transition.
 * @property players every seat's public standing plus the hidden-zone filtering, in turn order
 *   (CR 101.4) — [viewer]'s own hand in full, an opponent's hand as a count only (see [PlayerView]).
 * @property battlefield the shared battlefield (CR 403); fully public — every permanent, with its
 *   tapped/summoning-sick/damage/attachment/chosen-colour status, is visible to all (ADR-007).
 * @property stack the shared stack (CR 405), top last; fully public — the [StackEntryView] of each
 *   entry carries its controller and its targets, which are chosen openly whether the entry is a spell
 *   (CR 601.2c), an activated ability (CR 602.2b), or a triggered one (CR 603.3d).
 * @property exile the shared exile zone (CR 406); fully public — every exile path in the MVP pool
 *   (madness, plot, flashback-exile, escape) puts its cards face-up (CR 406.3), so identities and
 *   the plotted-turn marker are visible to all.
 * @property turn where the game stands (CR 500) — active player, turn number, phase/step, land
 *   drops, and combat state; all public.
 * @property pendingDecision the decision the game is paused at, filtered for [viewer] (see
 *   [DecisionView]); `null` when [state] is not a pause point. The deciding seat sees its full
 *   request (enumerated options, ADR-005); every other seat sees only who decides and the broad
 *   kind — never another seat's private option contents.
 * @property pendingCast the cast gathering decisions (CR 601.2), or `null`; public — a cast is an
 *   open action. Its `cardObjectId` is exposed as an opaque id, not resolved to a name: a
 *   non-deciding seat holds no priority during the caster's gathering and sees the completed spell
 *   as a public [StackEntryView] the moment it next receives priority.
 * @property pendingTriggers the fired-but-unplaced triggered abilities (CR 603.3b), filtered to
 *   their public last-known information (see [PendingTriggerView]); public — LKI of public events.
 * @property pendingMadness the madness reflexive yes/no (CR 702.35b), or `null`; public — it
 *   concerns an exiled (face-up) card.
 * @property pendingReplacement the CR 616.1 replacement-ordering pause, or `null`; public — it
 *   carries only the affected seat and an opaque hand-object id, no card identity.
 * @property pendingMulligan the pre-game mulligan progress (CR 103.4/103.5), or `null`; public —
 *   the opponent may see mulligan counts and stage, and the record carries neither hand contents
 *   nor bottoming selections (those are the deciding seat's private request options).
 * @property pendingPlot a plot special action gathering payment (CR 702.140), or `null`; the fact
 *   and the plotting seat are public, but the card stays in hand until the action executes, so its
 *   identity is not resolved here — it becomes public (face-up in exile) only on execution.
 * @property pendingColorChoice the "choose a colour as this enters" pause (CR 614.12), or `null`;
 *   public — it concerns a resolving permanent on the stack.
 * @property pendingActivation an activated ability gathering its choices (CR 602.2), or `null`; public
 *   — it concerns a public ability; its chosen-discard field carries only opaque hand-object ids, no
 *   card identities, and its chosen targets are public by CR 601.2c.
 * @property pendingReveal a "reveal top N, keep one" selection (CR 701.16), or `null`; the revealed
 *   cards are public to **both** seats (CR: they are revealed), so their identities are resolved
 *   here (see [PendingRevealView]) even though library contents are otherwise secret.
 * @property pendingOptionalDiscardDraw an optional discard-then-draw pause (CR 601.3b), or `null`;
 *   public — it carries only the deciding seat, a draw count, and a stage flag.
 * @property pendingOptionalCostDraw an optional cost-then-draw pause (CR 601.3b), or `null`; public
 *   — it carries only the deciding seat and the chosen mode.
 * @property pendingResolutionDiscard a mandatory "draw N, discard M" pause (CR 601.2c), or `null`;
 *   public — it carries only the deciding seat and a count, never hand contents.
 * @property pendingLibrarySearch a library search in progress (CR 701.18), or `null`; the fact and
 *   the searching seat are public, but the matching cards are **not** — library contents stay
 *   secret mid-search, exposed only to the searching seat as its private request options and
 *   revealed to all only when the found card is chosen.
 * @property pendingLibraryLook a private "look at these cards, then arrange them" in progress
 *   (CR 701.14a, CR 701.17a), or `null`; **count-only on purpose**. That a look is happening, whose cards
 *   they are, and how many, are all publicly observable at the table — but a look is seen by its
 *   controller and by *no other player*, so neither the identities nor even the object ids cross this
 *   boundary (see [PendingLibraryLookView] for why an id is a real channel here and not in the
 *   hand-scoped records). The looked-at cards reach the deciding seat only as its own
 *   [pendingDecision] options, which every other seat is already denied.
 * @property pendingCounterPayment a resolving counter's "unless its controller pays" pause (CR 118.3a),
 *   or `null`; public, and carried in full. Everything in it is already open at the table: the deciding
 *   seat, the amount printed on the counter, and the id of a spell sitting face-up on the public stack
 *   (CR 405). Every seat may see that the question was asked and of whom — a player who could not see it
 *   would not know why their opponent's lands became tapped — so ADR-007 adds no filtering rule here.
 * @property pendingTriggerTargets a triggered ability choosing its targets as it is put on the stack
 *   (CR 603.3d), or `null`; public — it names only the ability's controller and the source's
 *   last-known id and printed identity, all of which [pendingTriggers] already discloses. The choice
 *   itself is public (CR 601.2c), and its options are battlefield objects and players, so no hidden
 *   information crosses the boundary. **The moment `Target` gains a member naming a card in a hidden
 *   or semi-hidden zone (`FW-ZONETGT`, a graveyard or library card), this ruling must be revisited
 *   together with [cards].**
 */
data class SeatView(
    val viewer: PlayerId,
    val cards: Map<CardRef, PrintedCardView>,
    val players: List<PlayerView>,
    val battlefield: List<GameObject>,
    val stack: List<StackEntryView>,
    val exile: List<GameObject>,
    val turn: Turn,
    val pendingDecision: DecisionView?,
    val pendingCast: PendingCast? = null,
    val pendingTriggers: List<PendingTriggerView> = emptyList(),
    val pendingMadness: PendingMadness? = null,
    val pendingReplacement: PendingReplacement? = null,
    val pendingMulligan: PendingMulligan? = null,
    val pendingPlot: PendingPlot? = null,
    val pendingColorChoice: PendingColorChoice? = null,
    val pendingActivation: PendingActivation? = null,
    val pendingReveal: PendingRevealView? = null,
    val pendingOptionalDiscardDraw: PendingOptionalDiscardDraw? = null,
    val pendingOptionalCostDraw: PendingOptionalCostDraw? = null,
    val pendingResolutionDiscard: PendingResolutionDiscard? = null,
    val pendingLibrarySearch: PendingLibrarySearch? = null,
    val pendingLibraryLook: PendingLibraryLookView? = null,
    val pendingTriggerTargets: PendingTriggerTargets? = null,
    val pendingCounterPayment: PendingCounterPayment? = null,
)

/**
 * One seat's standing as another seat may see it (ADR-007): everything public about the player,
 * with the two hidden zones filtered — the hand to [HandView] (own contents vs an opponent's count)
 * and the library to a [libraryCount] only.
 *
 * **Libraries are a count on both sides**, including the viewer's own: a player does not know their
 * own library's order or the identity of the next card either (CR 401.1 — a library is shuffled and
 * face-down), so no seat ever receives a library's contents through a view. Only a resolving effect
 * that reveals or searches (a private request option to the acting seat, CR 701.16/701.18) exposes
 * specific library cards.
 *
 * Every other field is publicly observable at the table and carried unfiltered: [life] (CR 119),
 * [manaPool] (CR 106.4), [priorityStatus] (CR 117 — who holds priority is open), [graveyard]
 * (CR 404 — a face-up, ordered public zone), and the engine-maintained public facts
 * [attemptedDrawFromEmptyLibrary] (CR 704.5c — visible as it forces a loss), [decisionsAnswered]
 * (countable by watching the game), and [drawsThisTurn] (CR 121.1).
 *
 * @property seat which player this view describes.
 * @property life the player's life total (CR 119.1); public.
 * @property hand the hand, filtered: [HandView.Revealed] for the viewer's own seat, else
 *   [HandView.Concealed] with a count only.
 * @property libraryCount the number of cards in the library (CR 401); a count only, both sides.
 * @property graveyard the graveyard contents (CR 404), in order; fully public.
 * @property manaPool the mana currently in the pool (CR 106.4); public.
 * @property priorityStatus where the player stands in the priority round (CR 117); public.
 * @property attemptedDrawFromEmptyLibrary the CR 704.5c draw-from-empty fact; public.
 * @property decisionsAnswered how many decisions the seat has answered (ADR-004); public.
 * @property drawsThisTurn how many cards the player has drawn this turn (CR 121.1); public.
 */
data class PlayerView(
    val seat: PlayerId,
    val life: Int,
    val hand: HandView,
    val libraryCount: Int,
    val graveyard: List<GameObject>,
    val manaPool: List<ManaType>,
    val priorityStatus: PriorityStatus,
    val attemptedDrawFromEmptyLibrary: Boolean,
    val decisionsAnswered: Int,
    val drawsThisTurn: Int,
)

/**
 * How a hand appears to a seat (CR 402 — the hand is a hidden zone, ADR-007): its full contents to
 * its owner, only a count to everyone else. Sealed so a consumer handles both cases exhaustively
 * and can never accidentally read contents that are not there.
 */
sealed interface HandView {
    /**
     * The viewer's own hand: full contents (CR 402 — a player always sees their own hand).
     *
     * @property cards the hand's objects, in hand order.
     */
    data class Revealed(
        val cards: List<GameObject>,
    ) : HandView

    /**
     * An opponent's hand: a count only (CR 402 — the contents are hidden, ADR-007). The count
     * itself is public — players openly track how many cards an opponent holds.
     *
     * @property count how many cards the hand holds.
     */
    data class Concealed(
        val count: Int,
    ) : HandView
}
