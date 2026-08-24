# Design note — the post-resolution clause hook (`FW-CLAUSEHOOK`)

The reference for the framework that lets a **resolving ability** carry the same post-resolution clauses a
resolving spell carries. Written to PLAN.md §5's rule that a framework packet gets a design note, in the
style of `docs/design/library-look.md` and `docs/design/targeted-abilities.md`.

This packet is `library-look.md` §13 item 6, taken up as its own packet exactly as that note recommended:

> **Should the look clause be liftable onto abilities now?** Four gauntlet cards scry from an
> enters-the-battlefield trigger … Recommendation: no — the post-resolution clause hook is spell-shaped for
> `libraryReveal`, `optionalCostThenDraw`, and `drawThenDiscard` alike, so generalising it is one packet
> that fixes all four clauses rather than a rider on this one.

All four are lifted. The note below is short on purpose: the framework is a *removal* of an accidental
restriction, not a new mechanism, and the honest measure of it is how little had to change.

---

## 1. The gap, precisely

Four clause types are parts of a resolution that `mtg-rules` **orchestrates** around a mid-resolution pause
rather than running as a pure `ResolutionEffect`, because each needs a decision the effect signature cannot
express:

| Clause | CR | The pause |
|---|---|---|
| `libraryReveal` | 701.16 | which revealed card to keep |
| `libraryLook` | 701.14a / 701.17a | the whole arrangement |
| `optionalCostThenDraw` | 601.3b | the mode, then the cost object |
| `drawThenDiscard` | 601.2c | the mandatory discard |

All four were declared on `SpellDefinition`, and every orchestrator took a `StackEntry.Spell` and finished
through `completeInstantSorceryResolution`. So the hook was **spell-shaped**: an ability that resolved could
carry none of them. That is not a rule — CR 701.14a does not care whether a spell's resolution (CR 601) or a
triggered ability's (CR 603) put the look on the stack — it was an artefact of where the properties
happened to be declared.

The cost of the artefact was measured in cards. Faerie Seer's "When this creature enters, scry 2" is
*byte-for-byte* the `LibraryLook` value Preordain declares. It was unencodable.

---

## 2. The shape

**Decision — a carrier interface in `mtg-core`, implemented by all three definition types.**

```kotlin
// mtg-core/definition/ResolutionClauses.kt
interface ResolutionClauses {
    val libraryReveal: LibraryReveal? get() = null
    val libraryLook: LibraryLook? get() = null
    val optionalCostThenDraw: OptionalCostThenDraw? get() = null
    val drawThenDiscard: DrawThenDiscard? get() = null
}
```

`SpellDefinition` gains it as a supertype and **deletes** its four declarations — every existing card
compiles untouched, because the properties it overrides are the same properties. `TriggeredAbility` and
`ActivatedAbility` gain four defaulted constructor parameters each.

Alternatives rejected:

- **A `ResolutionClauses` data-class field** (`val clauses: ResolutionClauses = ...`) on each of the three.
  This would have moved every existing card's `override val libraryLook = …` to `clauses = ResolutionClauses(libraryLook = …)`,
  a churn of ~10 card files for no expressive gain, and would have made `SpellDefinition`'s interface-with-defaults
  idiom inconsistent with itself.
- **Duplicating the orchestrators for abilities.** This is the option the brief exists to forbid, and it is
  worth naming why beyond "duplication is bad": the two copies would diverge on the *private* half. A look
  leaks or does not leak based on `ViewFor`'s projection and the invariant checker's residence test, and a
  second copy of those is a second place for CR 701.14a to be got wrong.

### 2.1 At most one clause, enforced loudly

No card in the pool prints two clauses, and the order between two is a rule this type does not state.
Running them in field-declaration order would be exactly the silent approximation CONVENTIONS.md forbids, so
`requireAtMostOneClause` rejects the ambiguity at construction. The spell path had this property
accidentally (its `when` picked the first non-null and dropped the rest, silently); the carrier makes it a
gate. This is a **tightening** — see §6.

---

## 3. Where the three paths differ, and only there

The projection that makes the orchestration uniform lives beside `StackEntry.cardObject`, which is the
existing precedent for "a property the three stack-entry kinds answer differently":

```kotlin
val StackEntry.resolutionClauses: ResolutionClauses   // definition | trigger.ability | ability
val StackEntry.resolutionController: PlayerId         // the decider of every pause
val StackEntry.resolutionSourceId: ObjectId           // obj.id | trigger.sourceId | sourceId
val StackEntry.resolutionSourceCard: CardRef
```

`resolutionSourceId` is the one that is *not* `cardObject`. An ability is not a card (CR 113.7a) so it has no
card object, but a decision request still has to point at something the deciding seat can identify — the
optional-shuffle `ChooseYesNo` does. For an ability that is its source as last known when it went on the
stack (CR 113.7c LKI).

Then two functions in `mtg-rules/engine/ResolutionClauseHook.kt` are the whole framework:

```kotlin
internal fun orchestrateResolutionClauses(state: GameState, entry: StackEntry): AdvanceResult
internal fun completeClauseResolution(state: GameState, entry: StackEntry): AdvanceResult
```

**`completeClauseResolution` is the only place the three paths differ.** CR 608.2m puts a spell's card into a
graveyard (or exile, for flashback — CR 702.34e) and narrates it; CR 113.7a makes an ability cease to exist,
with no card moving anywhere. Every orchestrator now takes a plain `StackEntry` and finishes through this
one call, which is what "one implementation rather than two" means concretely.

### 3.1 The resume path needed nothing

Worth recording, because it is the payoff of an earlier decision. Each clause's pause is keyed on a
`pending*` record on `GameState` and re-derives its request from the state alone (ADR-004). None of those
records ever named a spell. So the entire change on the resume path was replacing

```kotlin
state.sharedZones.stack.lastOrNull() as? StackEntry.Spell ?: error(...)
```

with `resolvingClauseEntry(state)`, at six sites. No new state, no new decision shape, no new pending
record, no protocol change. The mid-resolution-pause design was already ability-ready; only the *declaration*
of the clauses was not.

---

## 4. The demonstration cards

| Card | Clause | What it proves |
|---|---|---|
| **Faerie Seer** `{U}` | `TriggeredAbility(libraryLook = Scry(2))` | The minimal lift: the same `LibraryLook` Preordain declares, on CR 603 instead of CR 601, through the same six-option CR 701.17a enumeration and then a CR 113.7a cessation instead of a graveyard move. |
| **Sea Gate Oracle** `{2}{U}` | `TriggeredAbility(libraryLook = OneToHandRestToBottom(2))` | The **mandatory keep** on an ability — no arrangement with an empty hand is enumerated, so the illegal decline has no index (ADR-005). Impulse's asymmetry, reached from a trigger. |

Both are pure additions to `mtg-cards`: no new mode, no new decision, no engine line. That is the intended
measure of the framework.

**Dropped, and precisely why** (§5 of the packet report has the Scryfall oracle text for each):

- **Lembas** — the `LibraryLook(Scry(1), thenDraw = 1)` clause is fine and the `{2}, {T}, Sacrifice: gain 3
  life` ability is expressible. It is blocked on its third line, "When this artifact is put into a graveyard
  from the battlefield, its owner shuffles it into their library" — `FW-SHUFFLEIN`, a graveyard-to-library
  move plus a seeded shuffle, which no effect primitive provides. Encoding the first two lines and dropping
  the third would be a Food artifact that never comes back, which is a different card.
- **Conduit Pylons** — needs **surveil** (CR 701.44), a look with a *graveyard* destination that
  `LibraryLookMode` has no member for, and `FW-MANA`'s "add one mana of any color" with a mana cost.
- **Giant's Boulder** — needs `FW-MANA` and a `{7}, {T}, Sacrifice: Destroy target permanent` ability. Its
  enters trigger is **scry 2**, not the surveil it is sometimes filed under.

Surveil is the obvious next member of `LibraryLookMode` and it is deliberately not in this packet: it is a
*mode* question for `FW-LIBLOOK`, not a *carrier* question for this one, and bundling it would have made the
hook generalisation contingent on a mode debate.

---

## 5. Test strategy

`mtg-rules/ResolutionClauseHookSpec.kt`, fixture abilities only (the `mtg-rules`-names-no-card rule holds),
CR-cited per CONVENTIONS.md. All four clause types are exercised **on an ability**, and both ability kinds
are exercised:

1. *CR 701.17a via CR 603*: a triggered ability's scry enumerates the same six arrangements a spell's does.
2. *CR 113.7a*: an ability that finished a clause ceases to exist — stack empty, **graveyard empty**. This is
   the one assertion that would fail if `completeClauseResolution` had been written as "always the spell path".
3. *CR 701.14a*: an ability-driven look is private — a count event, no `CardsRevealed`.
4. *ADR-005*: the mandatory keep enumerates no empty-hand arrangement on the ability path either.
5. *CR 701.16*: a triggered ability's reveal clause reveals publicly (the discriminator against 3).
6. *CR 601.2c*: a triggered ability's draw-then-discard draws, then pauses for the mandatory discard.
7. *CR 601.3b*: an **activated** ability's optional cost-then-draw offers its performable modes.
8. *CR 113.7c*: the optional-shuffle yes/no names the ability's **source**, not a card on the stack — the
   `resolutionSourceId` contract, which is the one place an ability could not simply reuse the spell's answer.
9. *ADR-004*: the ability's clause pause re-derives its own request from the state alone.
10. *CR 608.2c*: a definition declaring two clauses fails loudly rather than sequencing them.

`mtg-cards` pins the two cards' declared shapes. No acceptance corpus moves: no MVP mainboard card carries
an ability clause, so no fingerprint and no coverage number changes except the pinned burn-down.

---

## 6. Flagged

1. **`requireAtMostOneClause` is a tightening.** The spell path previously accepted a definition with two
   clauses and silently ran the first. Nothing in the pool did so, and this gate is what CONVENTIONS.md's
   "fail loudly; never silently approximate" asks for — but it is behaviour that changed, so it is named
   here rather than left to be discovered.
2. **`TriggeredAbility.optionalDiscardDraw` is now redundant.** It predates the carrier and is the narrower
   trigger-only spelling of `optionalCostThenDraw` restricted to its discard mode (Melded Moxite). Retiring
   it would delete a pending record, a view, and protocol DTOs — wire-visible, so a version bump — which is
   more than this packet should carry. Recommendation: retire it in whichever packet next touches
   `PendingOptionalDiscardDraw`, and until then treat it as deprecated-in-fact.
3. **`ActivatedAbility.librarySearch` is deliberately *not* a fifth clause.** It is a CR 701.18 search of a
   whole library with a mandatory shuffle, orchestrated from `ActivationExecution` before the effect rather
   than after it. Folding it in would change when it runs. Left alone.
4. **No protocol change.** `SpellDefinition`, `TriggeredAbility`, and `ActivatedAbility` have no wire form —
   only *views* of stack entries do, and those are unchanged. `ProtocolVersion` stays at `4.0.0`. Recorded
   explicitly because a framework packet not bumping the version is the unusual case.
5. **Surveil (CR 701.44) is the next `LibraryLookMode` member**, and it unlocks Conduit Pylons, Torch the
   Tower, and the graveyard half of several others. It wants `FW-LIBLOOK`'s owner, not this one's.
