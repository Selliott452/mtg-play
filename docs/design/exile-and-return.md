# Design note — exile-and-return, linked exile, and non-controller decisions

The reference for four frameworks that landed together: `FW-BLINK` (exile-and-return plus rebound),
`FW-TRIGLTB` (the general leaves-the-battlefield trigger), `FW-LINKEDEXILE` (CR 607 linked abilities),
and the pair `FW-HIDDENCHOICE` / `FW-NONCTRLDEC` (decisions over a hand that is not the decider's own,
and decisions whose decider is not the controller). Written to PLAN.md §5's rule that a framework packet
gets a design note, in the style of `docs/design/targeted-abilities.md` and
`docs/design/resolution-clause-hook.md`.

They landed together because three of the six cards need two or three of them at once, and splitting
would have meant encoding a card that half-works — Mesmeric Fiend alone needs the general LTB trigger,
the CR 607 link, *and* the reveal-and-choose clause.

---

## 0. The oracle text, and where the brief and the triage were wrong

Everything below is derived from Scryfall oracle text fetched at implementation time, not from the
upstream brief or `gauntlet-card-triage.md`. Three disagreements, and the oracle wins all three.

| Card | Oracle text |
|---|---|
| **Ephemerate** `{W}` Instant | "Exile target creature you control, then return it to the battlefield under its owner's control." + Rebound |
| **Ghostly Flicker** `{2}{U}` Instant | "Exile **two** target artifacts, creatures, and/or lands you control, then return those cards to the battlefield under your control." |
| **Journey to Nowhere** `{1}{W}` Enchantment | "When this enchantment enters, exile target creature." / "When this enchantment leaves the battlefield, return the exiled card to the battlefield under its owner's control." |
| **Mesmeric Fiend** `{1}{B}` Creature — Nightmare Horror 1/1 | "When this creature enters, target opponent reveals their hand and **you** choose a nonland card from it. Exile that card." / "When this creature leaves the battlefield, return the exiled card to its owner's hand." |
| **Duress** `{B}` Sorcery | "Target opponent reveals their hand. **You** choose a noncreature, nonland card from it. That player discards that card." |
| **Refurbished Familiar** `{3}{B}` Artifact Creature — Zombie Rat 2/1 | Affinity for artifacts. Flying. "When this creature enters, **each opponent** discards a card. For each opponent who can't, you draw a card." |

**1. Duress and Mesmeric Fiend are not non-controller decisions.** The brief groups them with
Refurbished Familiar under `FW-NONCTRLDEC` and describes all three as making "an opponent reveal or
discard". Both cards print "**You** choose": the opponent reveals — which is not a decision, because a
player told to reveal their hand reveals all of it — and the resolving object's *controller* picks. There
is no non-controller decision anywhere in either card. What they need is the ADR-007 question of a
choice over a **hand that is not the chooser's own**, which is `FW-HIDDENCHOICE`. The triage has this
right (it files Duress under `FW-HIDDENCHOICE`, "an ADR-007 per-seat-filter question, not merely a
discard", and Mesmeric Fiend under `FW-HIDDENCHOICE` + `FW-LINKEDEXILE`); the brief does not.

The distinction is not pedantry — it inverts the ADR-007 answer. A `FW-HIDDENCHOICE` reveal makes a
hidden zone **public**; a `FW-NONCTRLDEC` discard keeps it hidden from the very player whose card caused
it. Implementing the first as the second would have redacted cards the printed card publishes; the
second as the first would have leaked an opponent's hand. §6 and §7 are deliberately written as a
matched pair for that reason.

**2. Only Refurbished Familiar is a real non-controller decision**, and it is the whole of
`FW-NONCTRLDEC` in this packet. Its affinity half was already built (`FW-COST`) and needed nothing — the
brief asked whether affinity was now sufficient, and it is: the card reuses `CostReductionCards.kt`'s
existing `affinityForArtifacts` declaration unchanged.

**3. The triage cites the wrong rule for linked abilities.** Its Journey to Nowhere row says
"CR 610.3 linked abilities"; linked abilities are **CR 607**. The brief's CR 607 is correct.

**4. Journey to Nowhere is not "exile until this leaves the battlefield".** The brief describes the
linked-exile shape with that wording, which is the *old* templating. Current oracle text is two separate
triggered abilities, and that matters: two abilities means two stack objects, two windows in which a
player may respond, and a genuine CR 607 link between them — rather than one continuous effect. The
implementation follows the printed two-ability form.

### 0.1 Dropped

**Ghostly Flicker** — dropped, and only for its cardinality. It needs `FW-MULTITGT` (CR 601.2c): two
targets across a union of three permanent types. `TargetSpec` is single-target by construction and
`DecisionRequest.ChooseTargets` is a `SingleOptionSelection` whose answer is one index;
`graveyard-targeting.md` §6 and `targeted-abilities.md` §1.1 both record the same gap for their own
families. The *blink* half is fully built and Ghostly Flicker would be a two-line card the day
multi-target lands: `flickerPermanent` applied to each target. Encoding it with one target would be a
different card (PLAN.md §7). It is also outside this packet's ownership — the brief explicitly reserves
`Target`/`TargetSpec`/`Targets.kt` structural changes.

Nothing else was dropped. Five of the six cards ship.

---

## 1. What CR actually requires

**CR 400.7** — an object that moves zones becomes a **new object**, with none of the old one's status.
The engine already models this everywhere (`exilePermanent`, `destroy`, `returnToOwnersHand` all mint a
fresh `ObjectId` and build a fresh `GameObject`), which the brief asked me to verify: it does, and this
packet needed to add nothing to it. Every observable property of a "blink" is a consequence — counters
cease to exist (CR 122.2), damage is gone, Auras fall off at the next CR 704.5m check, a token flickered
this way never comes back (CR 704.5d), and enters-the-battlefield abilities fire again.

**CR 603.6a** — the re-trigger. Handled entirely by routing the return through
`announceBattlefieldEntry`, the single announce-and-fire step the T18 fix introduced. §2.2.

**CR 603.6c** — "leaves the battlefield" is a *different* condition from CR 603.6b's "is put into a
graveyard from the battlefield". §3.

**CR 607.2** — linked abilities: when one ability of an object exiles cards and another refers to "the
exiled card", the second refers to *the cards the first exiled* and to nothing else. CR 607.3 adds that a
linked ability finding no recorded object simply does nothing. §4.

**CR 702.88a** — rebound, quoted in full: *"If this spell was cast from your hand, instead of putting it
into your graveyard as it resolves, exile it and, at the beginning of your next upkeep, you may cast this
card from exile without paying its mana cost."* §5.

**CR 701.7a** — a discard is chosen by the player discarding. **CR 701.16a** — a reveal makes the cards
public. §6 and §7.

---

## 2. Exile-and-return (`FW-BLINK`)

### 2.1 The primitives

Three published effect primitives (ADR-003), in `mtg-rules/effect/ExileReturn.kt`:

```kotlin
fun returnExiledToBattlefield(state: GameState, exileId: ObjectId): GameState
fun returnExiledToOwnersHand(state: GameState, exileId: ObjectId): GameState
fun flickerPermanent(state: GameState, objectId: ObjectId): GameState
```

plus `exileLinkedToSource` in `LinkedExile.kt` (§4). All three returns are **no-ops when the id is no
longer in exile** — CR 603.10 honest last-known information, the same rule
`returnFromGraveyardToBattlefieldTapped` already follows.

`exilePermanent` gained an `internal` sibling, `exilePermanentReturningId`, which reports the new exile
id. The published primitive still forgets, because its callers (Scour from Existence) should not have to
name a value they do not want; only the two engine-side compositions need the handle.

**"Under its owner's control" is written against `GameObject.owner`**, not against a controller. Both
printed cards say owner, and it is the line that gives an opponent's creature back to the opponent when a
Journey to Nowhere dies. Control is ownership in the current pool, so the two coincide today; writing it
against the owner is what keeps it right when they stop.

`TargetSpec` gained no member. "Target creature you control" is
`TargetPermanent(PermanentRestriction.CREATURE_YOU_CONTROL)` — a new *restriction*, not a new spec, and
the first **decider-relative** permanent restriction (CR 109.5: "you" means the object's controller). It
needed one new function, `satisfiesPermanentRestrictionFor(state, restriction, candidate, chooser)`, and
one changed line in `Targets.kt` — which already carried the chooser, because `TargetOpponent` and
`GraveyardScope.YOURS` are decider-relative already. The board-only predicate keeps its own entry point
and **fails loudly** on the decider-relative member, so a caller that lost the chooser is a crash rather
than a silently wrong option list.

### 2.2 The fifth entry path that isn't

The brief warned against adding a fifth entry path. `returnExiledToBattlefield` adds none: it finishes
through `announceBattlefieldEntry(state, id, PermanentEntered(...))`, the one home the T18 fix gave
CR 603.6a. That is the entire reason Ephemerate re-triggers correctly, and there is no code in this
packet that fires an ETB trigger — announcing the entry *is* firing them, and there is no other way to
narrate an entry.

`flickerPermanent` is therefore literally `exilePermanentReturningId` followed by
`returnExiledToBattlefield`, and every rules consequence is inherited rather than restated.

---

## 3. The general leaves-the-battlefield trigger (`FW-TRIGLTB`)

`TriggerCondition` had exactly one departure condition: `PutIntoGraveyardFromBattlefieldSelf`
(CR 603.6b), fired from four call sites that each remembered to call `detectPutIntoGraveyardTriggers`.
Journey to Nowhere and Mesmeric Fiend both print CR 603.6c's **"leaves the battlefield"**, which fires on
*every* departure — to a graveyard, to exile, to a hand, to a library.

Encoding them with the narrower condition would have been a silent approximation of exactly the kind
CONVENTIONS.md forbids: exile a Journey to Nowhere in response to its own trigger and the creature it
holds would stay exiled for the rest of the game, with a plausible-looking card doing the wrong thing.

**Both conditions are kept, not collapsed.** Rancor's CR 603.6b ability genuinely does *not* fire when
Rancor is exiled; making the narrow one a filter over the wide one would be wrong in the opposite
direction.

### 3.1 One home, before the second half goes wrong

The four graveyard call sites are replaced by one:

```kotlin
internal fun announceBattlefieldDeparture(
    state: GameState,
    leftObject: GameObject,
    graveyardId: ObjectId?,   // non-null only when the destination is a graveyard
): GameState
```

It fires `LeftBattlefieldSelf` always and `PutIntoGraveyardFromBattlefieldSelf` only when `graveyardId`
is non-null, and it is now called from **five** paths: destroy, sacrifice, creature death, Aura fall-off,
and — new — exile. This is deliberately the same move `announceBattlefieldEntry` made for CR 603.6a, and
it is made here *before* a departure path forgets rather than after: T18 is the record of what the
"be careful at every call site" alternative costs, and a lost trigger leaves no residue for any invariant
or test to notice.

A permanent leaving for a graveyard fires both conditions, in that order. No card in the pool prints
both.

---

## 4. Linked exile (`FW-LINKEDEXILE`) — how CR 607 is represented

**The link is a list of exile object ids held on the source permanent:**

```kotlin
// mtg-core/state/GameObject.kt
val linkedExiled: PersistentList<ObjectId> = persistentListOf()
```

CR 607.2's own phrasing is that the second ability *refers to the objects the first exiled*, so the
reference lives on the object whose abilities are linked. Written by exactly one function,
`recordLinkedExile`, reached from both the effect path (Journey to Nowhere, via `exileLinkedToSource`)
and the clause path (Mesmeric Fiend, whose exiled card is chosen mid-resolution and so cannot be an
effect at all).

**Why this is right, in one sentence: two Journeys to Nowhere on the battlefield each return their own
creature**, by construction rather than by a disambiguation rule — which is the property that a global
"last exiled card" or a scan of the exile zone would both get wrong.

### 4.1 The part that is not obvious: the link must be captured, not read

By the time the *second* ability resolves, the source permanent has left the battlefield — that is what
fired it. So the link cannot be read off the board at resolution time. It is captured as last-known
information (CR 603.10) at the moment of departure:

```
GameObject.linkedExiled  --(announceBattlefieldDeparture)-->  PendingTrigger.linkedExiled
                         --(resolveAbility)-->               ResolutionContext.linkedExiled
```

This is the plural sibling of `PendingTrigger.subject`, and the same idiom as `discardedForCost` and
`sacrificedForCost` on `ResolutionContext` — captured rather than looked up. It differs from those two in
carrying a live `ObjectId` rather than a printed `CardRef`, because the effect must move *that exact
exile object*, not merely name a card.

### 4.2 Scope, and CR 607.3

`linkedExiled` is battlefield-only, and the CR 400.7 rebirth drops it. That is CR 607.3's answer to "what
if the permanent leaves and comes back": the returning permanent is a new object whose new first ability
has exiled nothing yet. A Journey to Nowhere that dies and is later returned does not return the creature
its previous incarnation exiled. `Invariant.LINKED_EXILE_SCOPE` pins both that scope and the referential
integrity of every recorded id.

`exileLinkedToSource` with a source already gone still exiles — that is what the first ability was told to
do — and records nothing, so the linked partner finds nothing and does nothing (CR 607.3).

---

## 5. Rebound (`FW-BLINK`, CR 702.88)

Three halves, each narrow on purpose.

**The leave-stack replacement.** `reboundReplacesGraveyardMove(entry)` is true when the definition has
rebound *and* the spell was cast from a hand. It is consulted only in
`completeInstantSorceryResolution`, never in the counter or fizzle paths, because CR 702.88a says "as it
resolves". `putSpellOffStack` gained an `exileInstead` parameter for it rather than a new
`CastingPermission` flag: flashback's `exilesOnLeaveStack` covers *every* departure from the stack, and
putting rebound there would have silently made a countered Ephemerate rebound.

**The loop terminates by the rule.** A rebound cast comes from exile via `CastingPermission.Rebound`, so
"if this spell was cast from your hand" is false for it and it finishes in the graveyard. No guard, no
counter, no "already rebounded" flag.

**The delayed ability.** `GameObject.reboundTurn` records the turn the card was exiled on; at the
beginning of each player's upkeep, `fireReboundTriggers` synthesizes a `ReboundCast` trigger for every
exile card that player owns whose `reboundTurn` is **strictly earlier** than the current turn. The
strictness is "your *next* upkeep": a spell that resolved during its own controller's upkeep must not
rebound in that same upkeep. The free cast then reuses madness's reflexive-cast shape (CR 702.35b) —
`PendingRebound`, a `ChooseYesNo`, and `beginCastGathering` from exile for `{0}`.

The **one** rules difference from madness is why `PendingRebound` is a separate record rather than a flag
on `PendingMadness`: a declined madness card goes to its owner's graveyard (CR 702.35b), while a declined
rebound simply **stays in exile** — CR 702.88a says what happens if it is cast and nothing about what
happens if it is not. Two different CR paragraphs behind one branch would have been one branch too few.

The rebound mark is cleared whether the cast is taken, declined, or impossible, so the offer happens
exactly once.

### 5.1 What is deliberately *not* built

**CR 603.7 — delayed triggered abilities in general — remains absent.** This is a rebound-shaped marker
on an object, in the idiom `plottedTurn` and `awaitingMadness` already set, not a general "an effect
creates a trigger that fires on a future event" framework. A general one would need: a delayed-trigger
list on `GameState` with its own creation timestamps; a way to express the firing event as data (CR 603.7a
allows any event, not just an upkeep); the CR 603.7b rule that a delayed ability fires only once; and a
seat-view projection plus protocol shape for a pending delayed trigger. None of that is needed by any card
in the gauntlet other than through rebound, and inventing it here would have been a framework with one
speculative client.

---

## 6. Non-controller decisions (`FW-NONCTRLDEC`)

Refurbished Familiar's "each opponent discards a card" is the engine's first decision whose decider is not
the resolving object's controller **and** whose options are hidden from that controller.

`FW-COUNTER`'s unless-pay clause was the first of the first kind — but its options are payment plans over
the battlefield, which is public (CR 400.2), so it never posed the second question. A hand is not
(CR 402.1).

### 6.1 The ADR-007 ruling

> **The enumerated options of a decision belong to `id.seat` and to no other seat.**

ADR-005 requires the engine to enumerate legal options; ADR-007 requires a seat to be shown only what it
may know. They do not conflict, and the reason is structural rather than a new mechanism:

- **A `DecisionRequest` is addressed to exactly one seat.** `DecisionRequestId` is `(seat, ordinal)` and
  the request is delivered only to `id.seat`. So `ChooseOpponentDiscards` enumerating the discarding
  opponent's whole hand leaks nothing — the controller is never handed the object. `viewFor` already
  gives every non-deciding seat a bare `DecisionView.Elsewhere(seat, kind)`, which structurally carries
  no options.
- **The seat view carries a count, not cards.** `PendingOpponentDiscardView` is count-only *for every
  seat, the deciding one included*: decider, controller, count, how many opponents remain, and the source
  card. Its projection function deliberately **takes no `GameState`** — a signature that cannot reach a
  hand cannot leak one.
- **The deciding seat is not given a richer projection than the controller.** It already receives its own
  hand in `PlayerView.hand` and its own request; a second, seat-dependent copy of the same cards would be
  a second thing to keep in agreement, and would turn the invariant from "never populated" into
  "sometimes populated", which is much weaker.

This is the same asymmetry a private library look (CR 701.14a) already gets, arrived at from the other
side: there the *decider* holds the secret, here the decider is the one seat entitled to see it.

### 6.2 The queue, and the opponent who can't

"Each opponent" is one clause producing one decision *per opponent*, while `AdvanceResult` surfaces one
request at a time. So `PendingOpponentDiscard` carries a queue and the clause walks it in APNAP order
(CR 101.4). An opponent with an empty hand is **never asked** — no request is surfaced for that seat at
all, and the controller's draw accumulates instead. That is CR 701.7a ("for each opponent who can't")
and also ADR-005: an illegal option has no index, and an empty option list is not a decision.

The pool is two-player, so the queue is always empty in a real game. It is modelled anyway so that the
printed "each opponent" is not quietly a "target opponent".

### 6.3 How it is pinned

`ViewLeakPropertySpec` is **extended, never relaxed**, in three ways:

1. A new `checkHiddenOptionOwnership` runs at every pause: the count-only projection agrees with the real
   pause in every seat's view, and no card name from the deciding opponent's hand appears anywhere in the
   controller's serialized view — checked against the whole JSON, not against the discard projection
   alone, because a leak arriving through some *other* field would be exactly as bad.
2. `Invariant.HIDDEN_DECISION_OWNERSHIP` pins the state-side half: the decider is not the controller, and
   never has an empty hand.
3. The Madness-vs-Bogles corpus contains none of these cards, so a **second corpus** was added that
   actually casts Refurbished Familiar, Duress and Mesmeric Fiend and runs the identical checks. It
   asserts it reaches both new pause kinds, because a property no game reaches proves nothing.

---

## 7. Choosing from a revealed hand (`FW-HIDDENCHOICE`)

Duress and Mesmeric Fiend. The matched opposite of §6, and worth reading beside it.

**The reveal is not a decision.** CR 701.16a: a player told to reveal their hand reveals all of it. The
engine performs it, emits `GameEvent.CardsRevealed` for the *whole* hand, and pauses only for the
*choice*.

**The chooser is the controller** (the printed "**You** choose"), so `ChooseRevealedHandCard` is
addressed to the resolving object's controller — not to the revealer, who makes no choice at all.

**The ADR-007 answer runs the opposite way to §6's.** This is the one pending record in the engine that
makes a hidden zone temporarily *public*. While the reveal is open both seats see the revealed cards, the
card table describes them, and the enumerated options are public by rule. ADR-007 hides what is hidden; it
does not hide what a card publishes, and a Duress that revealed a hand privately would be a different
card.

That has one consequence worth naming loudly: `ViewLeakPropertySpec`'s forbidden-name oracle had to be
**taught** about an open reveal, in both the byte-scan oracle (`publicNames`) and the independent card-table
reconstruction. Those are extensions to the oracle's model of what the rules make public — the same clause
it already had for a library reveal — not reductions in what it checks. Nothing was removed from the
forbidden set that a printed card does not itself publish.

### 7.1 Shape

One `ResolutionClauses` member, `HandRevealChoice(restriction, outcome)`, carried by a spell (Duress) and
a triggered ability (Mesmeric Fiend) alike — which is `FW-CLAUSEHOOK` paying off exactly as its note
predicted: the clause is written once and the two cards differ by one word.

The revealing player is the clause's **target** (`TargetSpec.TargetOpponent`), not a field on the clause:
both cards target, so who reveals is already recorded where every other target is, and duplicating it
would let the two disagree. `StackEntry.resolutionTargets` was added for the orchestrator to reach it —
the fifth projection beside `resolutionController` and `resolutionSourceId`, and the only new one.

The `DISCARD` outcome routes through `discardApplyingReplacements` like every other discard, so an
opponent's madness card taken by Duress is exiled instead and its reflexive cast fires **for that
opponent**. Correct, and free from not special-casing the move.

A hand with no card satisfying the restriction is still revealed and yields **no request** — the clause
does nothing rather than enumerating an empty choice.

---

## 8. Protocol

**`PROTOCOL_VERSION` stays at `6.0.0`.** The call, and the reasoning, are the file's own established
standard rather than a new judgement: `6.0.0` is **unreleased** — the only tag in the repository is
`v0.1.0`, which shipped protocol `1.0.0` — and the `5.0.0` note already records that an unshipped major
absorbs further breaks from the same wave, because "inflating the major count for a version nobody could
have consumed would describe a break that never existed". A new subsection under the `6.0.0` heading
names this packet's breaks instead.

The breaks, all of them real:

- **`GameObjectDto` gains required `linkedExiled` and `reboundTurn`.** Every game object on the wire is a
  `GameObjectDto`, so a `5.0.0` peer's strict codec rejects *every* seat view — the same break shape
  `FW-COUNTERS` recorded for `counters`.
- **`SeatViewDto` gains required `pendingHandReveal`, `pendingOpponentDiscard`, `pendingRebound`**, and
  `CastingPermissionDto` gains the `rebound` discriminator.
- **`DecisionRequestDto` gains two members and `DecisionRequestKindDto` two names.** These fail at
  `valueOf` at **runtime** mid-match, the sharper of the two break modes, and are answerable in the
  client→server direction too.

---

## 9. Test strategy

`mtg-rules/ExileAndReturnSpec.kt` uses fixture definitions only (the `mtg-rules`-names-no-card rule
holds); `mtg-cards/ExileAndReturnCardsSpec.kt` pins the five cards' declared shapes against the oracle
text in §0. CR-cited names throughout, per CONVENTIONS.md.

The assertions that would fail if the design were wrong, rather than merely if the code were:

1. *CR 400.7 / CR 603.6a*: a flickered permanent is a new object **and its enters-trigger fires again**.
2. *CR 603.6c vs CR 603.6b*: exiling a permanent fires the general departure trigger and **not** the
   graveyard one — the discriminator that would catch encoding Journey to Nowhere with the narrow
   condition.
3. *CR 607.2*: two sources each record only their own exile — the "two Journeys" case.
4. *CR 603.10*: the link survives into the fired trigger, so it is readable after the source is gone.
5. *CR 702.88a*: rebound applies to a hand cast, **not** to a cast from exile and **not** to a fizzle.
6. *CR 701.7a*: `ChooseOpponentDiscards.id.seat` is the opponent, and an empty-handed opponent is skipped
   rather than asked.
7. *CR 701.16a*: `ChooseRevealedHandCard.id.seat` is the **controller**, and the whole hand is revealed
   even when no card is choosable.
8. *CR 109.5*: "target creature you control" enumerates differently for each seat on the same board.

Plus the three new invariants (§6.3) and the extended `ViewLeakPropertySpec`.

---

## 10. Flagged

1. **`Invariant` gained three members and the seat view gained three fields**; the invariant checker was
   extended in the same packet, per DoD item 4.
2. **`GauntletCoverageSpec`'s pins moved**, as the brief predicted: mainboard encoded 93 → 97, sideboard
   17 → 19, total backlog 112 → 107. Six per-deck rows changed. This is the burn-down doing its job, not
   a weakened test.
3. **`CostReductionCardsSpec` had two pins updated**: Refurbished Familiar now declares a cost reduction
   (so the "no other card declares a reduction" set grows from five to six) and has left the
   "stays unencoded" list. Both are pins on a card inventory, not on behaviour.
4. **`affinityForArtifacts` was widened from `private` to `internal`** so Refurbished Familiar reuses the
   existing declaration rather than restating CR 702.41a in a second place.
5. **A general CR 603.7 delayed-triggered-ability framework is still absent** (§5.1), and rebound is the
   marker-shaped special case. The next card that needs a delayed trigger on any other event should build
   it properly rather than adding a second marker.
6. **`satisfiesPermanentRestriction` now fails loudly on `CREATURE_YOU_CONTROL`.** That is a tightening: a
   caller that has no chooser cannot evaluate a decider-relative restriction, and crashing is better than
   an option list that is silently wrong for one seat. Named here because it is behaviour that changed.
7. **`FW-MULTITGT` is the single thing standing between this packet and Ghostly Flicker**, and between
   `FW-ZONETGT` and four of its own family. It is the highest-value unblocked framework left in the
   triage's ordering (nine cards) and every piece of it is a `Target`/`TargetSpec` change that no packet
   so far has been allowed to make.
