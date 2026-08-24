# Design note — looking at, and arranging, the top of a library (`FW-LIBLOOK`)

The reference for the framework that lets a card **look at** cards privately and **arrange** them between
the top of a library, the bottom of a library, and a hand. Written to PLAN.md §5's rule that a framework
packet gets a design note reviewed **before** implementation starts, in the style of
`docs/design/layer-system.md` and `docs/design/targeted-abilities.md`.

Four rules anchor the design downward: **CR 701.14a** ("look at" — the player sees the cards and *no one
else does*; a look is not a reveal), **CR 701.16a** (to reveal is to show a card to *all* players),
**CR 701.17a** (scry N: look at the top N, put any number of them on the bottom in any order and the rest
on top in any order), and **CR 400.7** (an object that changes zones becomes a new object — and, by the
same rule read the other way, an object that is merely *reordered within its zone* does not).

Upward, the seams the engine already cut: `orchestrateLibraryReveal` as the mid-resolution
orchestration idiom (the resolving spell stays on top of the stack so the pause is a pure derivation of
the state, ADR-004); `PendingLibrarySearch` as the precedent for a pending record whose *fact* is public
but whose *cards* are not; `DecisionView.Elsewhere` as the mechanism that already withholds a request's
options from every non-deciding seat (ADR-007); and `shuffled(rng)` in `LibrarySearch.kt` as the one
sanctioned way to shuffle (ADR-006).

`gauntlet-card-triage.md` §5 measured this framework: **13 gauntlet cards name it, 9 of them unlocked
outright — the best ratio in the table — across 8 of the 13 mainboards**, with no layer work and no new
characteristics. §5 also records the trap this note exists to avoid (trap T14): *"The obvious encoding
picks a deterministic order and looks perfect — and silently decides the next several draws… a 'library
order is unobservable' argument is false, because the player who made the choice observes it and the
opponent's information state differs."* This note does not re-derive any of that; it takes it as the
premise.

---

## 1. The gap, precisely

The engine can **reveal** the top N cards of a library and keep up to M of them. `LibraryReveal` +
`PendingRevealSelection` + `RevealedCardFilter` + `orchestrateLibraryReveal` are that flow, and they are
correct for what they model. Five things they cannot do, each of which killed a real card:

1. **Look privately.** `orchestrateLibraryReveal` emits `GameEvent.CardsRevealed`, and `viewFor` resolves
   `pendingRevealSelection` into a `PendingRevealView` whose `revealed` list carries **both** the object
   ids *and* — through `visibleCardRefs` — the printed characteristics, to **every seat**. That is right
   for CR 701.16 and catastrophic for CR 701.14: using it for Ponder would hand the opponent the top three
   cards of the library.
2. **Order.** "Put them back in any order", "the rest on the bottom in any order", "on top of your library
   in any order". Ordering is a *permutation over objects in a hidden zone*, and no `DecisionRequest`
   expresses one over cards (`OrderTriggers` and `OrderBlockers` permute triggers and blockers).
3. **Scry** (CR 701.17) — a look plus a *partition* into an any-order bottom group and an any-order top
   group, which is strictly more than an ordering.
4. **Require a keep.** `LibraryReveal.toHandCount` is documented as a **maximum** — *"Keeping fewer
   (including none) is always legal, since every MVP reveal clause is a 'you may'/'up to'"* — and
   `pendingRevealRequest` always appends `ChooseFromRevealed.keepNoneIndex`. Impulse's "Put **one** of them
   into your hand" is mandatory, so today the engine would enumerate an illegal decline.
5. **Send the rest to the bottom.** `putRevealedIntoGraveyard` is hard-wired: everything not kept goes to
   the graveyard.

A prior packet dropped **Brainstorm, Ponder, Preordain, Impulse, Winding Way, and Lead the Stampede** for
exactly these reasons and said so, in `CardSelection.kt`'s file KDoc and in `PendingRevealSelection`'s. This
packet is the framework those six were waiting on, and the first four are its proof (§9).

### 1.1 Scope

In scope: a private look at the top N; an arrangement decision over a private pool of cards; the three
destinations top / bottom / hand; scry (CR 701.17); a mandatory keep; an optional PRNG shuffle; and the
enumeration, view, protocol, replay, and invariant consequences.

Out of scope, each with where it slots: a **filter** on which looked-at cards may be kept (Ancient
Stirrings' "reveal a colourless card", Augur of Bolas' instant-or-sorcery) — that is `RevealedCardFilter`
territory and belongs to whoever encodes those cards; **surveil** (CR 701.44, look at the top N and put any
number into the graveyard — the same shape with a graveyard destination, Conduit Pylons); **a
resolution-time card-type choice** (Winding Way, `FW-MODAL`-adjacent); **"reveal any number of matching
cards"** with a variable mandatory keep (Lead the Stampede); **looking at a library that is not yours**
(`FW-HIDDENCHOICE`, ADR-007); and **scry/surveil driven by a triggered or activated ability** rather than
a spell resolution (Faerie Seer, Giant's Boulder, Torch the Tower — see §12). Each is a documented
absence, never a silent approximation.

---

## 2. What CR actually requires, and the distinction that causes silent leaks

**CR 701.14a.** *"For a player to look at an object, that player sees the object … and no other player
does."* A look is a private observation. The public facts an opponent *does* get are that the look
happened, whose library it was, and how many cards were looked at — these are physically observable at the
table.

**CR 701.16a.** *"To reveal a card, show that card to all players for a brief time."* This is the flow the
engine already has, and it is the wrong one here.

**CR 701.17a.** *"To 'scry N' means to look at the top N cards of your library, then put any number of them
on the bottom of your library in any order and the rest on top of your library in any order."* Note what is
*not* said: there is no reveal, and the split is genuinely free — including all-bottom and all-top.

**CR 400.7.** An object that moves zones becomes a new object with a new id. The corollary the arrangement
depends on: a scry that leaves a card on top has moved nothing, so **that card keeps its object id**. Only
the cards that reach a hand (Impulse) or a library from a hand (Brainstorm) are reborn.

### 2.1 The distinction — "the decider sees it" is not "the state may name it"

This is the one place a plausible implementation leaks. Three different audiences want three different
things from the same pause:

| Audience | What it may have | Mechanism |
|---|---|---|
| The decider | the looked-at cards' identities **and characteristics** | `DecisionView.ToDecide(request)` — the full request, options included |
| The opponent | that a look is happening, whose, and over how many cards | a count-only view record |
| The replay fingerprint / invariant checker | enough to distinguish two positions | opaque ids, never resolved to names |

The failure mode is not the request — `DecisionView.Elsewhere` already withholds every request's options
from every non-deciding seat, and its KDoc already names library search as the intrinsically-secret case.
The failure mode is the **pending record on `SeatView`**, which is where `pendingReveal` publishes the
revealed cards to both seats today. §3 is the ruling.

---

## 3. The ADR-007 ruling for a private look

**Decision — the pending look is exposed on `SeatView` as a purpose-built count-only view type, and it
contributes nothing to `SeatView.cards`.**

```kotlin
// mtg-rules — the view type, deliberately not the core record.
data class PendingLibraryLookView(
    val decider: PlayerId,
    val source: LibraryLookSource,   // TOP_OF_LIBRARY or HAND — publicly observable at the table
    val count: Int,                  // how many cards are being arranged — publicly countable
    val awaitingShuffle: Boolean,     // which half of a Ponder-shaped clause is pending
)
```

Three parts to the ruling, each with its reason.

**(a) A view type, not the core `PendingLibraryLook`.** Every other pending record is passed through to
`SeatView` unchanged, and several carry object ids that the SeatView KDoc calls "opaque" (`pendingCast`'s
`cardObjectId`, `pendingActivation`'s chosen discards). This one is not, and the difference is real rather
than fastidious: those are *hand* object ids, and a hand's **size** is already public, so an opaque id adds
nothing an opponent could not already count. A **library** object id is different — library order is
precisely the hidden state a look manipulates, so an opponent who learns "the objects being arranged are
#312 and #313" can correlate them against a later draw or a later look and reconstruct order the CR never
gave them. `PendingLibrarySearch` avoids this by carrying nothing but its decider; this record cannot,
because the pool is what the pause is *about*. So the ids stop at the boundary. This is a **tightening**
relative to the `pendingCast` precedent, taken deliberately and flagged in §13.

**(b) `visibleCardRefs` does not see the pool.** `VisibleCards.kt`'s contract is *"exactly the refs this
view names, and no others"*, derived from the finished projection rather than the raw state precisely so
that a hidden card can only reach the card table by first reaching the view. With (a), the pool never
reaches the view, so the table cannot name it. The security argument survives unchanged and needs no new
special case.

**(c) The decider's own request options *do* enter `SeatView.cards` — and this is the ADR-007 revisit that
`targeted-abilities.md` predicted.** That note's SeatView KDoc says: *"The moment `Target` gains a member
naming a card in a hidden or semi-hidden zone … this ruling must be revisited together with `cards`."* The
`SeatView.cards` KDoc, in turn, records a deliberate exclusion: *"the `pendingDecision` request's own option
cards (a library search enumerates library cards, CR 701.18), which would make the key set depend on the
pending request rather than on the zones alone."*

That exclusion is now load-bearing in the wrong direction. A scry is decided **on characteristics** — is
this card a land, is it cheap, is it the removal spell I need — and under the current rule the decider
receives a `CardRef` (a name) with **no entry in `cards`**, while `SeatView` drops the definition registry
wholesale. A driver would have a name and nothing to reason with. That is not a leak; it is the engine
failing to give the deciding seat what CR 701.14a says it sees.

So `visibleCardRefs` gains exactly one clause:

```kotlin
// ADR-007: only the *deciding* seat's own request options, and only the arrangement request.
// DecisionView.Elsewhere carries no options at all, so a non-deciding seat can never reach this.
(view.pendingDecision as? DecisionView.ToDecide)?.request?.let { request ->
    if (request is DecisionRequest.ChooseLibraryArrangement) addAll(request.pool.map { it.card })
}
```

The `ToDecide` guard *is* the per-seat filter: the same `viewFor` call for the opponent produces
`DecisionView.Elsewhere`, which structurally holds no request, so the branch cannot fire. The key set does
now depend on the pending request for one request kind, and the KDoc says so and says why.

**Why not extend it to `ChooseFromLibrary` while we are here.** Tempting, and rejected. A search's options
are *pre-filtered by the engine* — every option already satisfies `LibrarySearchFilter`, so the decider's
choice among them turns on nothing the characteristics would add — and the found card becomes public
(`CardsRevealed`) and lands in a hand the same transition, so it enters `cards` a moment later anyway. An
arrangement's pool is **unfiltered**, and the characteristics *are* the decision. Changing the search case
would also change an existing view's output for no card in the pool, which is out of this packet's
mandate. Flagged in §13.

---

## 4. The arrangement decision, and its bound

### 4.1 One request, one index

**Decision — the whole arrangement is a single enumerated `Decision.SingleSelect` over a list of complete
outcomes**, rather than a sequence of small rounds.

An arrangement option is a total assignment of the pool to three ordered destination lists:

```kotlin
data class Option(
    val toHand: List<Int>,    // pool indices, in the order they enter the hand
    val toTop: List<Int>,     // pool indices, topmost first
    val toBottom: List<Int>,  // pool indices, in placement order — the first ends up above the last
)
```

Indices into the request's `pool`, not object ids: the wire form stays trivial, and `pool` is the one
place the identities live.

The alternative — the `PendingRevealSelection` idiom of "up to M rounds of pick-one-more", with a partial
arrangement accumulating in the pending record — was considered and rejected. Its argument (every legal
outcome is reachable, and no information is revealed between rounds, so the reachable outcomes are exactly
the CR's) holds here too. But: a scry is one CR choice made with complete information, so decomposing it
into four rounds makes an agent plan across pauses to express a decision it could state in one; the partial
arrangement is extra state that can drift; and the round decomposition makes the *reachability* argument
load-bearing, where the single-shot enumeration makes the option list itself the proof — which is exactly
what ADR-005 asks for ("legality is defined **by** the enumeration").

### 4.2 The counts, and the bound

The enumeration is closed-form per mode, over a pool of `n` (and, for the hand mode, a hand of `h` from
which `k` are taken):

| Mode | Card | Options | At the pool sizes we ship |
|---|---|---|---|
| `Scry(n)` | Preordain (n = 2) | `(n + 1)!` | 6 |
| `ReorderTop(n)` | Ponder (n = 3) | `n!` | 6 |
| `OneToHandRestToBottom(n)` | Impulse (n = 4) | `n!` | 24 |
| `HandToTop(k)` | Brainstorm (k = 2) | `h! / (h − k)!` | 72 at h = 9 |

The scry count is worth stating because it is not obvious: an outcome is a permutation of the `n` cards
**plus one divider**, so there are exactly `(n + 1)!` of them — a scry 2 has 6 outcomes, a scry 3 has 24.
`sum_k C(n,k)·k!·(n−k)! = (n+1)·n!` is the same number the long way.

**The bound is `MAX_LIBRARY_ARRANGEMENTS = 720`**, and exceeding it is a loud `error`, never a truncation
or a sample. 720 = 6!, which admits every look up to five cards in any mode (Ancient Stirrings, the deepest
look in the gauntlet, is a five-card look) and every Brainstorm from a hand of up to 27. A future card that
exceeds it — a scry 6, or a hand-to-top from a 28-card hand — fails the build rather than shipping a
truncated action space, and the fix at that point is the round decomposition §4.1 rejected, taken
deliberately for that card. That is the CONVENTIONS.md "fail loudly; never silently approximate" rule
applied to an enumeration budget.

### 4.3 Determinism, and its independence from the seed

ADR-005 requires stable indices; ADR-006 requires that a replay of the same seed and decision log
reproduce the game. Both demand that the option list be a pure function of the pool, with no PRNG, no hash
iteration, and no wall-clock or identity-hash tiebreak.

The enumeration is a deterministic nested walk over **pool order** (top-first for a look; hand order for
`HandToTop`) with a fixed outer/inner precedence per mode:

- `Scry(n)`: outer loop over the split point `s` in `0..n` ascending; inner loop over the permutations of
  the pool in lexicographic index order. For each `(perm, s)`, the first `s` entries are `toBottom` and the
  rest are `toTop`. Injective, because `toBottom + toTop == perm` and `|toBottom| == s` recover the pair.
- `ReorderTop(n)`: permutations in lexicographic index order, all to `toTop`.
- `OneToHandRestToBottom(n)`: outer loop over the kept index ascending; inner loop over the permutations of
  the remaining indices in lexicographic order, to `toBottom`.
- `HandToTop(k)`: ordered `k`-tuples of distinct hand indices in lexicographic order, to `toTop`; every
  other hand card stays in the hand, listed in `toHand` in **hand order** so the option is total and
  canonical (two options never differ only in an unobservable hand order).

The one place a seed *does* enter is Ponder's "You may shuffle", which is a separate `ChooseYesNo` and goes
through the match-owned `Rng` via the existing `shuffled(rng)` helper (ADR-006). A test that depends on the
post-shuffle order pins the seed.

---

## 5. The decision request — a new member, and why the `FW-ABILTGT` answer flips here

**Decision — add exactly one new `DecisionRequest` member, `ChooseLibraryArrangement`.**

`targeted-abilities.md` §4 declined a new member and saved ~25 compile-breaking sites across five modules
by reusing `ChooseTargets`. Its argument was that CR 601.2c is *the same decision* for a cast, an
activation, and a trigger, so a distinct request kind would teach a training agent an artefact of the
engine's internals. That argument does not transfer, for three reasons, and the survey of the cost is the
same ~19–22 sites (`DecisionView.kindOf` + its enum, `DecisionApplication`, `DecisionValidation`, the
protocol's `DecisionRequestDto`/`ToDto`/`ToDomain`/`DecisionRequestKindDto`/round-trip fixture, five CLI
`when`s, `RandomRemoteAgent`, `RandomLegalResponder`, `Responders`, `EnumerationProbe`, `EngineTestSupport`
and three scripted acceptance responders):

1. **No existing member expresses the answer.** The three candidate families are wrong in kind, not in
   degree. `ChoiceCountSelection` (`ChooseFromRevealed`, `ChooseFromLibrary`, `ChooseCostMode`) is
   "pick one thing or opt out" — it has no ordering and no partition. `PermutationSelection`
   (`OrderTriggers`, `OrderBlockers`) is an ordering but not a partition, and both leaves carry
   combat/trigger payloads. `SizedSelection` is an unordered fixed-size subset. Squeezing an arrangement
   into any of them would mean a driver reading the wrong contract.
2. **Reuse would be an information-hiding bug, not just an infelicity.** The nearest fit by shape,
   `ChooseFromRevealed`, is coupled to `PendingRevealSelection`, which `viewFor` resolves into
   `PendingRevealView` and publishes **to both seats**. Reusing it for a private look would make the leak
   §3 exists to prevent structural rather than accidental.
3. **It genuinely is a different decision to an agent.** A scry and a target choice are not the same act
   under a different name; the request kind is information the agent should have.

What the new member gets right by construction: it is answered by a plain `Decision.SingleSelect`, so
`validateDecision` is one `validateSingleSelect` line and `EnumerationProbe`'s arm is
`singleSelectPerOption` — every enumerated arrangement is probed the day it lands, which is precisely the
guard against the enumeration gap this framework could introduce.

### 5.1 The twenty-fifth leaf forced a family — `DecisionRequest.SingleOptionSelection`

Implementation surfaced something the survey above missed, and it is worth recording because it changes
the *shape* of the cost rather than its size. **Every driver's `when` over `DecisionRequest` was sitting
exactly one branch below detekt's complexity ceiling**, and a twenty-fifth leaf pushed nine of them over
at once: `kindOf`, `applyDecision`, `validateDecision`, both protocol mappers, four CLI `when`s,
`RandomLegalResponder`, `RandomRemoteAgent`, and `EnumerationProbe`.

That is not a lint accident; it is the codebase saying the hierarchy had outgrown flat dispatch. It
already answers this exact pressure three times — `SizedSelection`, `PermutationSelection`, and
`ChoiceCountSelection` all exist because *"grouping them under one sub-interface lets drivers and the
enumeration probe handle [the shape] uniformly rather than one branch per request kind"*. The missing
fourth shape is the plainest one: **a single-select over exactly its own options, with no opt-out index.**

So this packet adds `DecisionRequest.SingleOptionSelection` (mirrored as
`DecisionRequestDto.SingleOptionSelectionDto`) with a single `optionCount`, and moves six leaves into it:
`ChooseTargets`, `ChoosePaymentPlan`, `AssignTrampleDamage`, `ChooseColor`, `ChooseReplacement`, and the
new `ChooseLibraryArrangement`. Nine dispatch sites get **smaller**, not larger — six branches collapse
into one at each — and the next request kind of this shape costs nothing at all.

Three things it deliberately does not do. It does not absorb `ChooseAction`: a priority window is
special-cased by every driver (a pass is not merely "option 0"), and `AutoPassPolicy` depends on that. It
does not absorb `ChooseYesNo`: its two indices are a fixed decline/accept pair, not an enumerated list. And
it does not absorb `ChoiceCountSelection`, whose **last index is an opt-out and therefore not one of its
options** — conflating the two is exactly the bug that would let a driver decline a mandatory keep, which
is the bug this framework exists to make impossible. The family's KDoc records all three exclusions.

It is wire-invisible: `kotlinx.serialization` keys off each leaf's `@SerialName`, so no encoded payload
changes and the grouping costs the protocol nothing beyond the version bump §10 already takes.

**What is deliberately *not* a new member:** Ponder's optional shuffle reuses `ChooseYesNo` (CR 601.3b
"you may"), disambiguated by which pending record is open, exactly as `applyChosenYesNo` already
disambiguates madness from the optional discard-then-draw, and as `applyChosenPaymentPlan` disambiguates
plot / activation / cast. One new member, not two.

---

## 6. Mandatory vs optional, and bottom vs graveyard — flags or a new effect?

**Decision — a new clause type, `LibraryLook`, alongside `LibraryReveal`; not flags on it.**

The alternative was to widen `LibraryReveal` with `mandatory: Boolean`, `private: Boolean`, and
`restTo: GRAVEYARD | BOTTOM`, plus an ordering. Rejected, on the same grounds `targeted-abilities.md` §6
refused to route an ability through `putResolvedSpellOffStack`: the two clauses agree on a *surface*
(take some cards off the top of a library, distribute them) and disagree on everything the CR cares about.
A reveal is public and emits `CardsRevealed`; a look is private and emits nothing. A reveal's destination
set is {hand, graveyard} and its rest-disposition is unordered; a look's is {hand, top, bottom} and its
rest-disposition is *the whole decision*. A reveal's keep is filtered and optional; a look's is unfiltered
and may be mandatory. Three booleans and an enum on `LibraryReveal` would produce a type whose legal
combinations are a strict minority of its inhabitants, and whose `init` block would be a taxonomy.

So the shape is a small sealed mode hierarchy, one member per oracle pattern, interpreted exhaustively by
`mtg-rules`:

```kotlin
// mtg-core/definition/LibraryLook.kt
sealed interface LibraryLookMode {
    val count: Int
    val source: LibraryLookSource

    data class Scry(override val count: Int) : LibraryLookMode                       // CR 701.17
    data class ReorderTop(override val count: Int) : LibraryLookMode                 // Ponder
    data class OneToHandRestToBottom(override val count: Int) : LibraryLookMode      // Impulse, Sea Gate Oracle
    data class HandToTop(override val count: Int) : LibraryLookMode                  // Brainstorm
}

data class LibraryLook(
    val mode: LibraryLookMode,
    val optionalShuffle: Boolean = false,   // Ponder's "You may shuffle."
    val thenDraw: Int = 0,                  // Preordain's and Ponder's "Draw a card." — *after* the look
)
```

Three consequences worth stating:

- **The mandatory keep is expressed by the mode, not by a count.** `OneToHandRestToBottom` enumerates no
  arrangement whose `toHand` is empty, so the illegal decline `LibraryReveal.toHandCount` would have offered
  simply does not exist as an index. ADR-005's "legality is defined by the enumeration" does the work; no
  validation rule is needed. The one honest exception is a short library: looking at the top four of a
  three-card library looks at three, and looking at an *empty* library keeps nothing — CR 701's "do as much
  as possible" — which the enumerator produces naturally because the pool is `library.take(n)`.
- **`thenDraw` is on the clause, not in the card's `ResolutionEffect`.** Preordain is "Scry 2, **then** draw
  a card": the draw must happen after the arrangement, and a card's ordinary resolution runs *before* the
  orchestrated clause (the `LibraryReveal` precedent). Brainstorm, whose draw comes first, therefore needs
  no `thenDraw` at all — its draw *is* the ordinary resolution. The field records that ordering
  distinction rather than hiding it.
- **`source` is on the mode.** `HandToTop`'s pool is the decider's own hand; every other mode's is the top
  of their library. The distinction is public (an opponent sees which zone you took cards from) and it
  changes both the CR 400.7 rebirth rule (§7) and the invariant checker's residence test (§8), so it is a
  declared property rather than a `when` repeated at four sites.

---

## 7. Where the pause lives, and what moves when

**The look is a mid-resolution orchestration, exactly like the reveal.** The resolving spell stays on top
of the stack, so `pendingDecisionRequest` re-derives the request from the state alone (ADR-004);
`GameState` gains one nullable field; `midTransitionPauseRequest` gains one branch, placed after
`pendingLibrarySearch` and before `pendingMadness`.

```kotlin
data class PendingLibraryLook(
    val decider: PlayerId,
    val poolIds: PersistentList<ObjectId>,   // the pool, in pool order; still in its source zone
    val awaitingShuffle: Boolean = false,    // the arrangement is settled; the CR 601.3b yes/no is pending
)
```

`awaitingShuffle` is the same two-stage idiom `PendingOptionalDiscardDraw.awaitingDiscard` uses, and it is
why Ponder needs no second pending record. In the shuffle stage `poolIds` is empty — the cards have already
moved — which the invariant checker pins.

The sequence for one clause:

1. The spell's ordinary `ResolutionEffect` runs (Brainstorm's draw-three).
2. The pool is taken: `library.take(n)` for a library source, `hand` for `HandToTop`. **Nothing is revealed
   and no event is emitted** — this is the whole point (CR 701.14a).
3. If the pool is empty, or the mode admits exactly one arrangement, the engine still pauses. A single-option
   request is surfaced, never collapsed — the same rule that always surfaces a lone payment plan (ADR-004).
   An *empty* pool admits the one empty arrangement, so even a look at an empty library is a visible,
   replayable pause rather than a silent skip.
4. The chosen arrangement is applied (below), then the optional shuffle yes/no, then `thenDraw`, then the
   spell leaves the stack via the existing `completeInstantSorceryResolution`.

**Applying an arrangement, and CR 400.7.** The rule is mechanical once stated: a card that changes zone is
reborn with a fresh id; a card that does not, is not.

- `toTop` from a **library** pool: the card never left the library, so it keeps its object id and is simply
  re-seated at the front. This is why a scry that keeps everything on top is observably a no-op in the
  fingerprint's zone census while still being a real decision.
- `toBottom` from a library pool: same zone, same id, moved to the back.
- `toHand` from a library pool: a zone change — new id, `CardReturnedToHand` (reused as the generic
  move-to-hand event, as the reveal flow does).
- `toTop` from a **hand** pool (Brainstorm): a zone change — new id.
- `toHand` from a hand pool: the residue, which never moved; same id, and the relative hand order is
  preserved.

---

## 8. Blast radius — by file and type

**`mtg-core` (all additive):**

- `definition/LibraryLook.kt` (**new**) — the clause, the four-member `LibraryLookMode`, and
  `LibraryLookSource`, with `require`s on the counts.
- `definition/SpellDefinition.kt` — `val libraryLook: LibraryLook? get() = null`, beside `libraryReveal`.
- `state/PendingLibraryLook.kt` (**new**) + the `pendingLibraryLook` field on `state/GameState.kt`, with an
  `init` validation (seated decider; the pool distinct and still resident in a hidden zone of the decider;
  empty exactly while `awaitingShuffle`).
- `event/GameEvent.kt` — two new members. **`CardsLookedAt(player, count)`** is the deliberate counterpart
  of `CardsRevealed`: it records that a look happened and over how many cards, and *never* the identities
  (CR 701.14a), so the two clause types are distinguishable in a transcript without the look leaking into
  one. **`CardPutOnLibrary(player, objectId, card, onTop)`** narrates a hand-to-library placement, which is
  a real zone change; a card merely reordered *within* the library is deliberately silent.

**`mtg-rules`:**

- `engine/LibraryLook.kt` (**new**) — the orchestrator, the two pending requests, and the shuffle stage.
- `engine/LibraryArrangements.kt` (**new**) — the per-mode enumerator, the closed-form counts, the
  `MAX_LIBRARY_ARRANGEMENTS` budget, and the display prompt. Split from the orchestrator because the
  enumeration is the part with the mathematical contract and it deserves to be readable on its own — the
  same reason `VisibleCards.kt` sits beside `viewFor`.
- `engine/LibraryArrangementApply.kt` (**new**) — the zone mechanics of applying a chosen arrangement,
  whose one governing rule is CR 400.7 read both ways (§7).
- `decision/DecisionRequest.kt` — the `ChooseLibraryArrangement` member and the `SingleOptionSelection`
  family (§5, §5.1).
- `DecisionView.kt` — `CHOOSE_LIBRARY_ARRANGEMENT`, and `kindOf` regrouped onto the new family.
- `engine/PendingDecision.kt` — two branches in `midTransitionPauseRequest`, one per look stage.
- `engine/DecisionApplication.kt` — regrouped onto the new family, plus one branch in `applyChosenYesNo`
  for the optional shuffle.
- `engine/SingleOptionApplication.kt` (**new**) — the family's applier, split out because
  `DecisionApplication.kt` would otherwise cross detekt's eleven-function budget.
- `engine/DecisionValidation.kt` — one `validateSingleSelect` arm for the whole family.
- `engine/StackResolution.kt` — one arm in the resolution post-effect `when`.
- `SeatView.kt` / `ViewFor.kt` — `pendingLibraryLook: PendingLibraryLookView?` and its projection (§3a).
- `PendingLibraryLookView.kt` (**new**).
- `VisibleCards.kt` — the one `ToDecide`-guarded clause of §3c.
- **No** change to `LibraryReveal.kt`, `PendingRevealSelection`, or `RevealedCardFilter`. They are correct
  for CR 701.16 and this framework does not touch them; the only edit in that neighbourhood is the stale
  paragraph in `CardSelection.kt`'s KDoc that says these cards cannot be encoded.

**`mtg-protocol`:** `DecisionRequestDto.kt` (+ one member, + the mirrored family),
`DecisionRequestOptionDtos.kt` (+ `LibraryArrangementDto`), `DecisionRequestToDto.kt`,
`DecisionRequestToDomain.kt`, `ViewDecisionDtos.kt` (+ one `DecisionRequestKindDto`),
`PendingResolutionDtos.kt` (+ `PendingLibraryLookViewDto`), `SeatViewDto.kt` (+ the field),
`DefinitionEnumDtos.kt` (+ `LibraryLookSourceDto`), and the round-trip fixture. §10 makes the version call.

**`mtg-cli`:** `MenuRenderer`, `DecisionInput`, `DefaultDecision`, and `RandomLegalChooser` all *shrink*
onto the new family; the six "pick one option" menus move into a new `SingleOptionMenus.kt` alongside the
new arrangement menu, which renders each option as its three destination groups in card names.

**`mtg-server`:** `RandomRemoteAgent`'s dispatch, likewise regrouped.

**`mtg-acceptance`:**

- `driver/RandomLegalResponder.kt` — regrouped onto the new family.
- `driver/Responders.kt` — `PASS_AND_DISCARD_LOWEST` gains a loud-failure arm: that policy never casts, so
  a look can only reach it through an engine bug. (Unlike the trigger-target case `FW-ABILTGT` had to
  soften, a look clause is spell-scoped in this packet — §12 — so no passive game can reach one.)
- `fuzz/EnumerationProbe.kt` — every arrangement is probed, through the family's `singleSelectPerOption`.
- `replay/Fingerprint.kt` — a `pendingLibraryLook` token beside the existing `pending*` tokens. No golden
  fingerprint strings are stored anywhere and every replay test compares a run against its own re-run, so
  this rebaselines nothing.
- `invariant/PendingResolutionInvariant.kt` — the look pause joins the four existing mid-resolution pauses
  (decider seated, stack non-empty) and adds two properties of its own (DoD item 4): every pool id is still
  resident in the mode's source zone, and the pool is empty exactly while `awaitingShuffle`.

**`mtg-cards`:** `LibraryLookCards.kt` (**new**, the four demonstration cards), one paragraph of
`CardSelection.kt`'s KDoc, four lines in `MvpCards.kt`.
**`mtg-pauper`:** the `GauntletCoverageSpec` burn-down pin moves by four (§11).

---

## 9. The demonstration cards

The brief names four cards as the proof, because they are the four this framework's absence killed. All
four land, and between them they exercise every mode, both sources, the mandatory keep, the PRNG shuffle,
and the after-the-look draw.

| Card | Clause | What it proves |
|---|---|---|
| **Preordain** `{U}` | `Scry(2)`, `thenDraw = 1` | CR 701.17 proper: the partition *and* the ordering, 6 enumerated outcomes, and a draw sequenced after the look. The triage calls it "the minimal scry card, and the right one to build the framework against". |
| **Ponder** `{U}` | `ReorderTop(3)`, `optionalShuffle`, `thenDraw = 1` | A pure 3! ordering over a hidden zone, plus the ADR-006 half: the optional shuffle draws from the match-owned `Rng`, and a seeded test pins the resulting order. |
| **Impulse** `{1}{U}` | `OneToHandRestToBottom(4)` | The **mandatory** keep — no arrangement with an empty `toHand` is enumerated, so the illegal decline `toHandCount` would have offered does not exist — and the bottom disposition, over a 24-outcome space. |
| **Brainstorm** `{U}` | ordinary resolution draws 3; `HandToTop(2)` | The hand source: an *ordered placement into* a hidden zone, the CR 400.7 rebirth on the way in, and the draw-first ordering that makes `thenDraw` unnecessary. |

**Not attempted, and why.** *Winding Way* ("Reveal the top four cards of your library. Choose creature or
land. Put all cards of the chosen type revealed this way into your hand and the rest into your graveyard")
needs a resolution-time **card-type choice** before the partition is even defined — a mode choice
(`FW-MODAL`, CR 700.2), which this framework does not deliver and must not fake. *Lead the Stampede*
("Reveal the top five cards of your library. Put all creature cards revealed this way into your hand and
the rest on the bottom of your library in any order") is a **reveal**, not a look, with a *variable
mandatory* keep-all-matching — it wants `RevealedCardFilter` on the public reveal path plus this
framework's ordered bottom disposition, i.e. a genuine merge of the two clause types rather than a card.
Both are named in §1.1 as out of scope with where they slot, per the brief.

---

## 10. Protocol versioning

`ProtocolVersion.kt` is at **`3.0.0`** and its KDoc records the standard twice over: *"Bump this whenever
the wire shape changes in a way a peer must know about"*, and P8.3's precedent that **a required field
added to a strictly-decoded payload is a major bump**, because the strict codec rejects unknown fields.
`3.0.0` itself was taken for exactly that, and it explicitly noted that it added **no** `DecisionRequest`
kind, so `DecisionRequestKindDto` — whose mapping fails at *runtime* rather than at compile time — was left
alone.

This packet does both halves. Server→client, `SeatViewDto` gains a required `pendingLibraryLook`. And,
unlike `FW-ABILTGT`, it **does** add a `DecisionRequest` kind, so `DecisionRequestDto` gains a member and
`DecisionRequestKindDto` gains a value that a `3.0.0` peer's `valueOf` would fail on at runtime — the
sharper of the two break modes, since it surfaces as a decode exception in a live match rather than as a
compile error.

**Decision — bump to `4.0.0`**, with a KDoc paragraph in the shape of the `2.0.0` and `3.0.0` ones. It is a
strictly larger break than `3.0.0`'s under the recorded standard, and the standard is to say so rather than
to argue that nobody is listening.

---

## 11. Test strategy

**`mtg-rules` unit tests**, all CR-cited (CONVENTIONS.md), split between `LibraryLookSpec.kt` (the flow,
the views, and the zone mechanics, on fixture cards only — `mtg-rules` names no card) and
`LibraryArrangementSpec.kt` (the enumeration's own contract: the closed-form counts, completeness,
determinism, and the budget):

1. *CR 701.14a: a look at the top of a library is private — the opponent's `SeatView` names no looked-at
   card* — the leak test, asserted against `SeatView.cards` and the pending record, from both seats.
2. *CR 701.14a: the deciding seat's `SeatView` carries the looked-at cards' printed characteristics* — §3c
   from the other side, so the two halves of the ADR-007 ruling are pinned together and neither can be
   "fixed" without breaking the other.
3. *CR 701.16 vs 701.14: a look emits no `CardsRevealed`* — the event-log discriminator between the two
   clause types.
4. *CR 701.17a: scry 2 enumerates all six splits-with-order, and no more* — the `(n+1)!` bound as a
   behaviour.
5. *CR 701.17a: a scry that puts every card on the bottom reverses nothing it was not told to* — the
   ordering convention, asserted on the resulting library.
6. *CR 400.7: a card left on top of the library by a scry keeps its object id; a card put into a hand does
   not* — the rebirth rule from both sides in one test.
7. *ADR-005: the mandatory keep enumerates no empty-hand arrangement* — Impulse's asymmetry against
   `LibraryReveal`'s optional keep, asserted directly.
8. *ADR-005/ADR-006: the arrangement enumeration is a pure function of the pool* — two calls on the same
   state are equal, and a state differing only in `rng` enumerates identically.
9. *ADR-006: Ponder's optional shuffle draws from the match `Rng`* — the seed is pinned and the post-shuffle
   library order asserted; declining leaves the chosen order intact.
10. *CR 701.14a: looking at more cards than the library holds looks at as many as possible* — the short- and
    empty-library corners, including that the empty pool still surfaces its one arrangement.
11. *ADR-004: the arrangement pause re-derives its own request* — resumability, and that the request's
    option list is index-stable across the re-derivation.
12. *CR 400.7: Brainstorm's hand-to-top placement reborns the placed cards and preserves the residue's hand
    order.*

**`mtg-acceptance`** — `LibraryLookAcceptanceSpec.kt`: one scripted game per demonstration card through the
real engine, with the invariant checker running on every transition (including the two new library-look
properties — pool residence, and empty-pool-iff-`awaitingShuffle`). Preordain's scry asserts the resulting
library *and* that no `CardsRevealed` was emitted; a second Preordain case asserts the ADR-007 ruling from
both seats at once; Ponder is run twice from the same pinned seed to pin the shuffle against the decline;
Impulse asserts that no enumerated arrangement leaves the hand empty; Brainstorm asserts the CR 400.7
rebirth and the surviving hand order. Plus the existing corpora, unchanged and green — no MVP mainboard card
has a look clause, so no existing fingerprint or coverage number moves except the pinned burn-down.

**`mtg-cards`** — the four declared shapes.

**`mtg-protocol`** — the new request in the round-trip fixture, and the new seat-view field.

---

## 12. Non-goals (explicit)

Out of scope, with where each slots: **surveil** (CR 701.44 — the same shape with a graveyard destination;
Conduit Pylons, Torch the Tower — *not* Lembas or Giant's Boulder, whose printed triggers are scry 1 and
scry 2 respectively; verified against Scryfall oracle text by `FW-CLAUSEHOOK`); **a filter on the keep** (`RevealedCardFilter` on the look path —
Ancient Stirrings' colourless card, Augur of Bolas' instant or sorcery); **"reveal any number of matching
cards"** (Lead the Stampede); **a resolution-time card-type choice** (Winding Way, `FW-MODAL`); **scry or
surveil from a triggered or activated ability** rather than a spell resolution (Faerie Seer, Giant's
Boulder — this framework hangs its orchestration off `StackEntry.Spell`, exactly as `LibraryReveal` does,
and generalising the post-resolution clause hook to abilities is its own packet); **looking at a library
you do not own** (`FW-HIDDENCHOICE`, ADR-007); **shuffling a graveyard back in** (`FW-SHUFFLEIN`, Lembas);
and **"put a card from your hand on the bottom"** (no gauntlet card needs it). Each is a documented
absence, never a silent approximation.

---

## 13. Open questions for the architect

1. **The `SeatView.cards` widening (§3c).** Recommendation: take it, scoped to the arrangement request and
   guarded by `DecisionView.ToDecide`. It is the ADR-007 revisit `targeted-abilities.md` predicted, and the
   alternative — a deciding seat that receives card *names* it has no characteristics for — is the engine
   failing to deliver what CR 701.14a says the player sees. The cost is honest: `cards`' key set now depends
   on the pending request for one kind, and its KDoc's "deliberately excluded" paragraph becomes a
   "deliberately excluded, with one exception" paragraph.
2. **Should `ChooseFromLibrary` get the same widening?** Recommendation: not in this packet. Its options are
   engine-filtered and become public a moment later, so the gap is cosmetic there; and changing it would move
   an existing view's output for no card in the pool. Flagged rather than bundled.
3. **The count-only pending view (§3a) is stricter than the `pendingCast` precedent.** Recommendation: keep
   the tightening, because a library object id is a correlatable handle on the exact state a look
   manipulates and a hand object id is not. If the architect prefers uniformity, the alternative is to pass
   the core record through and accept the id channel — but then the `pendingCast` KDoc's "opaque id"
   argument should be re-examined for all fourteen records at once, not silently extended to this one.
4. **The `MAX_LIBRARY_ARRANGEMENTS = 720` budget (§4.2).** Recommendation: ship it as a loud gate. The open
   question is whether the eventual escape hatch is the round decomposition or a cap on look depth; this
   note deliberately does not pre-commit, because the first card to exceed it will say which.
5. **`DecisionRequest.SingleOptionSelection` (§5.1) is API this packet did not set out to own.** It was
   forced by nine simultaneous complexity failures, and it is additive and behaviour-preserving — every
   existing per-leaf `when` still compiles, because Kotlin exhaustiveness is over concrete subtypes.
   Recommendation: keep it; the alternative was six privately-`else`-guarded dispatchers, which is the
   pattern CONVENTIONS.md forbids for exactly this hierarchy. Flagged as the packet's one uninvited
   change to shared API, and as a guaranteed textual conflict with any packet touching the same `when`s.
6. **Should the look clause be liftable onto abilities now?** Four gauntlet cards scry from an
   enters-the-battlefield trigger (Faerie Seer, Conduit Pylons, Giant's Boulder, Lembas), and
   `FW-ABILTGT` has just made abilities first-class. Recommendation: no — the post-resolution clause hook is
   spell-shaped for `libraryReveal`, `optionalCostThenDraw`, and `drawThenDiscard` alike, so generalising it
   is one packet that fixes all four clauses rather than a rider on this one. Flagged.

   **Resolved.** Taken as its own packet, `FW-CLAUSEHOOK` (docs/design/resolution-clause-hook.md): all four
   clauses moved onto a `ResolutionClauses` carrier that `TriggeredAbility` and `ActivatedAbility` implement
   alongside `SpellDefinition`, with one orchestration and no new state, decision, or wire shape. Faerie Seer
   and Sea Gate Oracle landed on it. Of the other three cards named above, only Lembas' *clause* was the
   blocker — it also needs `FW-SHUFFLEIN` — while Conduit Pylons needs surveil and `FW-MANA`, and Giant's
   Boulder needs `FW-MANA` and a targeted destroy.
