# Design note — dependent targets, storm, and X on activated abilities (`W9-C`)

The reference for three things that arrived together because they are one problem seen from three sides:
a targeting line whose legal set is **not a function of the board alone**. `TargetContext` is the shared
answer; the rest of this note is what each card needed on top of it, and what was deliberately not built.

Written to PLAN.md §5's rule that a framework packet gets a design note, in the style of
`docs/design/multi-target.md`, whose §8 named two of these gaps and declined them.

---

## 0. The oracle text, and the one correction

Read from the repo's own Scryfall snapshot (`ORACLE-29.md`), all four found:

| Card | Cost | Oracle text | Verdict |
|---|---|---|---|
| Searing Blaze | `{R}{R}` | Deals 1 damage to **target player** or planeswalker and 1 damage to **target creature that player** or that planeswalker's controller controls.<br>Landfall — If you had a land enter the battlefield under your control this turn, deals 3 damage to that player or planeswalker and 3 damage to that creature instead. | **encoded** |
| Weather the Storm | `{1}{G}` | You gain 3 life.<br>Storm | **encoded** |
| Gorilla Shaman | `{R}` | `{X}{X}{1}`: Destroy target noncreature artifact with mana value X. | **encoded** |
| Kaervek's Torch | `{X}{R}` | As long as Kaervek's Torch is on the stack, spells that target it cost `{2}` more to cast.<br>Deals X damage to any target. | **dropped**, §6 |

**One correction to the packet brief.** It gave Gorilla Shaman's ability as `{X}{X}`; the oracle text is
`{X}{X}{1}`. That is not cosmetic — at X = 2 the printed ability costs five mana, not four — and the
`{X}{X}` reading would hand an Affinity-hating deck its Mox-eating line a turn early. The encoding follows
the oracle text.

---

## 1. The shared answer: `TargetContext`

Before this packet, `legalTargets(state, spec, you, chooser)` was a pure function of the board, the
deciding player, and the choosing object. Every `TargetSpec` member and every `PermanentRestriction` could
be answered from those three, and that was not an accident — it is what makes the CR 601.2c choice and the
CR 608.2b re-check provably the same question (ADR-005, `Targets.kt`'s header).

Two printed lines break it, in opposite directions:

| Card | The line | What it depends on | CR |
|---|---|---|---|
| Gorilla Shaman | "…with mana value **X**" | a **cost announcement** the same object made at CR 601.2b | 107.3 |
| Searing Blaze | "target creature **that player** controls" | an answer to an **earlier targeting line** of the same object | 601.2c |

`TargetContext(chosenX, earlierTargets)` carries both. It is one type rather than two threaded parameters
because from the enumerator's side they are the same thing — *"here is what this object has settled so
far"* — and because a third dependence (a chosen mode, a chosen colour) becomes a third property rather
than a fourth parameter on eight functions.

Three properties are load-bearing:

- **It reaches exactly one place.** `legalTargets` passes it to `satisfiesPermanentRestriction` and nowhere
  else. Every other branch of the enumeration is unchanged, so nothing that worked before can behave
  differently now.
- **`NONE` fails closed.** `chosenX` reads as zero (CR 202.3b's value for an unannounced X) and
  `earlierTargets` is empty, which makes a dependent restriction name **nothing** rather than everything. A
  call site that forgets to thread the context therefore *under*-offers — a missing option, which a test
  catches — instead of over-offering an illegal one, which ADR-005 treats as the worse defect and which
  nothing downstream would notice.
- **It is not a second source of truth.** Once an object is on the stack its announcements live where they
  already lived (`StackEntry.Spell.chosenX`, `StackEntry.ActivatedAbilityOnStack.chosenX`, the entry's own
  `targets`), and the CR 608.2b re-check *rebuilds* a context from those. Nothing carries a context forward.

---

## 2. Searing Blaze: a list of targeting lines, gathered in order

### 2.1 What `FW-MULTITGT` deliberately left

`TargetCount`'s KDoc and `docs/design/multi-target.md` §8 both said it plainly: a count is a count on **one**
instance of the word "target", and a card printing two separate instances needs a *list* of lines, each with
its own noun, its own count, and its own CR 601.2c same-object scope. No card needed it then. Searing Blaze
needs it, and needs more.

### 2.2 The declaration

`SpellDefinition.additionalTargetSpecs: PersistentList<TargetSpec>` — the lines **after** the first, empty
for every other card. Additional rather than replacing, so:

- every existing definition, request, driver, replay log and wire message is untouched;
- `additionalTargetSpecs.isNotEmpty()` is the single test for multi-line-ness, with no second flag that
  could disagree with it — the same argument `SpellDefinition.modes` makes for emptiness being the
  non-modal case.

`mtg-rules` reads the whole list through `targetLinesOf(definition, chosenModes)`, which returns
`[effectiveTargetSpec] + additionalTargetSpecs` and never the field directly. A one-line card is the
one-element list, so there is no "does this card have lines?" branch anywhere downstream.

### 2.3 The record stays flat, and the gate that makes that safe

`PendingCast.chosenTargets` and `StackEntry.Spell.targets` are still one flat `List<Target>`. Lines are
recovered by **slicing** (`targetsByLine`) rather than by nesting, because the flat list is what four other
subsystems already read and re-shaping it would have rewritten all of them for a card that does not need
the nesting.

Slicing needs two things to be unambiguous, and the second is easy to miss:

1. **Where each line's slice begins.** Ambiguous for "up to two target creatures **and** target land": a
   three-element record could be split either way.
2. **Whether the choice is finished.** Ambiguous for "target player **and** up to two target creatures": a
   one-element record is either done or still owed a line.

Both hold exactly when **every** line of a multi-line card has a fixed count.
`requireSliceableTargetLines` refuses anything else, loudly, rather than guessing — the plausible-looking
approximation CONVENTIONS.md forbids. Searing Blaze's two lines are `Exactly(1)` each, so the gate costs the
gauntlet nothing. The fix, the day a card prints the harder shape, is per-line boundaries on the record, not
a cleverer slice.

### 2.4 Gathering, in printed order

`pendingCastRequest`'s target branch became `!targetLinesSettled(lines, chosenTargets)` instead of
`chosenTargets == null`, and `applyChosenTarget` **appends** rather than assigns. So a two-line spell
surfaces two `ChooseTargets` requests, and the second is enumerated against
`contextForLine(lines, chosen, 1)` — which is how "that player" gets an answer.

**No new `DecisionRequest` kind, and no new field on the existing one.** The second request is the same
shape as the first, for the same card, with a different option list. That is deliberate: an instance index
on the request would be a wire break for information the option list already carries, and the gathering
order is deterministic and documented. If a future card makes the ambiguity real — three lines over the
same noun — the index becomes a field then.

### 2.5 CR 601.2c and CR 608.2b, per line and whole-spell

- **CR 601.2c re-validation** (`establishTargets`) runs **per line**, each against its own context, so a
  card cannot pass validation by having its lines' answers swapped.
- **CR 608.2b** is a **whole-spell** verdict, not a fold of per-line ones. "If all its targets … are now
  illegal" — so a Searing Blaze whose creature died in response but whose player is still there **resolves**,
  and deals its damage to the player alone. `allTargetLinesIllegal` is `lines.all { that line is
  all-illegal }`, which is exactly that reading.

The re-check reads the recorded earlier target **even if that target has itself become illegal**. That is
correct and worth stating: "target creature that player controls" was chosen against the player named then,
and CR 608.2b asks whether the creature is still a legal target, not whether it would be chosen again.

### 2.6 The castability gate is a search

CR 601.2c: a spell whose targets cannot all be chosen cannot be cast. With independent lines that is a
conjunction; with a dependent line it is genuinely a **search**. Asking "is some player targetable?" and "is
some creature targetable?" separately says yes on a board where the only creature belongs to a player the
caster cannot name — an enumerated-but-unplayable cast, ADR-005's expensive direction.

`targetLinesSatisfiable` therefore walks the lines in order, trying each candidate for a line against the
lines below it, and returns as soon as one whole assignment succeeds. It is **entered only for a multi-line
card**, so every other cast in the game pays nothing; the one card that does reach it prints a first line
enumerating the players, which is two candidates in a two-player game.

### 2.7 Two smaller printed details

- **"Target player or planeswalker" is encoded as target player**, and that is exact rather than a
  narrowing: Pauper's pool contains no planeswalker (CR 306 — planeswalkers are never common), and none is
  in the gauntlet, so the disjunction has one live arm. Said out loud in the card's KDoc rather than
  silently dropped, because a planeswalker entering the pool would need both a wider `TargetSpec` and a
  second line that resolves *a controller* rather than reading a player id.
- **Landfall is `PlayerState.landsEnteredThisTurn`**, counted at the single battlefield-entry announcement
  site and reset each turn beside `drawsThisTurn`. It is **not** `Turn.landsPlayedThisTurn`: that counter is
  on the turn because only the active player may *play* a land (CR 305.1) and it counts the land drop, while
  landfall counts **entries** — a land put onto the battlefield by a search or a return triggers it and
  consumes no drop. Encoding landfall against the land-drop counter would be a plausible-looking wrong card
  in a gauntlet holding fetch effects. It is read on **resolution** (CR 608.2), not at cast, because a land
  really can enter while the spell is on the stack.

---

## 3. Gorilla Shaman: X on an activated ability, announced before targets

### 3.1 What was actually missing

`FW-X` landed the CR 601.2b announcement for **spells only**. The mechanical half — `PendingActivation`
gaining a `chosenX`, an activation surfacing a `ChooseXValue`, `StackEntry.ActivatedAbilityOnStack`
recording it — is exactly that: mechanical.

The blocker was an **ordering** question, and `PendingCastRequest.kt`'s header had already named it.
That header records a deliberate deviation from CR 601.2b's printed order: the cast pipeline settles the
kicker and X announcements **after** the target stage, so their affordability bound can use the *exact*
mana reservation the sibling cost selections produce. It also names the card shape that would force the
order back — "a card printing 'X target creatures'". Gorilla Shaman is that shape from the other side: its
target restriction is a function of the announced value, so CR 601.2c has nothing to enumerate until X is
known.

### 3.2 The decision

Three options were open.

| | Option | Cost | Verdict |
|---|---|---|---|
| (a) | **Reorder globally** — announce X above the target stage on both paths. | Every *other* cast in the game bounds its X against `minimalSacrificeReservation` instead of the exact, choice-aware one. That is the enumerated-then-unpayable direction ADR-005 forbids, paid by the whole pool for one card. | rejected |
| (b) | **Let the path that needs the printed order take it.** The **activation** path announces at CR 601.2b's printed position; the cast path is untouched. | Nothing, because the activation path has **no pool to protect** — no encoded activated ability carries X, so there is no existing bound to weaken and no existing replay to rewrite. Its own bound is inexact only for an ability that *also* chooses an object for another cost component, which nothing in the gauntlet prints and which `requireXBoundIsExact` refuses loudly. | **taken** |
| (c) | **Drop the card.** What `W8-C` did, correctly at the time. | A real line of play, and a framework the next X ability would need anyway. | rejected |

**The resulting order on an activation is CR 601.2b (X) → CR 601.2c (targets) → CR 601.2b/h (the
chosen-object cost selections) → CR 601.2f–g (payment).** The cast path keeps CR 601.2c (targets) →
CR 601.2b (kicker, then X) → CR 601.2f–g (payment).

The asymmetry is not a compromise between the two paths. It is each path taking the order that is *exact*
for the cards it actually has, and the alternative — one order for both — is strictly worse on one side or
the other. Both orders are recorded where a reader will meet them: `PendingCast.chosenX`,
`PendingActivation.chosenX`, and `AbilityXCost.kt`'s header.

### 3.3 The bound gained a second half

On the cast path a value of X is offered when it is **payable**. On the activation path it must be payable
**and leave the ability a legal target**, because the announcement now precedes CR 601.2c and an activation
cannot be abandoned once begun. Gorilla Shaman announcing X = 4 against a board of `{0}` artifacts would
reach the target stage with an empty option list, which both target request kinds refuse in their `init` —
a crash, not a missing option.

The same joint test decides whether the ability is **enumerated at all** (`abilityActivatableAtSomeX`), and
it has to be joint: an ability payable at X = 0 and targetable only at X = 2 is not activatable, and two
independent tests would both say yes.

Consequently `abilityXValueOptions` may return an **empty** list, unlike its cast-path sibling — a cast is
always announceable at X = 0 because the gate priced it there, whereas X = 0 may be perfectly payable and
name nothing.

### 3.4 The restriction

`PermanentRestriction.NONCREATURE_ARTIFACT_WITH_MANA_VALUE_X` compares the **candidate's printed** mana
value (CR 202.3b) with the **ability's announced** X (CR 107.3) — two different quantities that share a
letter, which is why one is read off the permanent and the other off the context. "Noncreature" is an
exclusion rather than the complement of a type list: an artifact creature is a creature (CR 205.1a) and is
never a legal choice however cheap it is.

---

## 4. Weather the Storm: storm, and the spell-copy primitive

### 4.1 Why this card and not a harder one

Storm (CR 702.40a) needs exactly two things the engine lacked: a game-wide count of spells cast so far this
turn, and the ability to put copies of a spell onto the stack. What it *also* prints — "If the spell has any
targets, you may choose new targets for any of the copies" — is the expensive half, and Weather the Storm
does not have it: its whole effect is "You gain 3 life", which targets nothing. The clause is therefore
**vacuous on this card rather than approximated**, which is the difference between an exact encoding and a
plausible-looking wrong one. `requireCopyableWithoutRetargeting` refuses a *targeting* storm spell loudly
instead of copying the original's targets, because copying a Grapeshot's targets is precisely what makes a
storm spell good or bad.

### 4.2 The count

`Turn.spellsCastThisTurn`, incremented at CR 601.2i. On the turn rather than per player, and that placement
*is* the rule: storm counts every seat's spells, so a per-player tally would have to be summed at every read
and could disagree with itself. It is `Turn.landsPlayedThisTurn`'s structural sibling — a per-turn game-wide
counter that resets itself when the next turn's `Turn` is constructed, so nothing has to remember to clear
it.

Three facts about it, each of which is a way the keyword could be silently wrong:

1. **It counts casts, not stack residents.** A countered, fizzled or long-resolved spell still counts
   (CR 702.40a says "cast", and CR 608 does not un-cast anything).
2. **A copy does not count.** A copy is *created* on the stack, never cast (CR 707.10a), so it never reaches
   the increment — which is what stops a storm spell from compounding its own count.
3. **The count is linked information, fixed when the trigger fires.** It rides on `PendingTrigger.amount`
   and is never re-read at resolution. The trigger sits on the stack for a whole priority round in which
   either player may cast more spells; re-reading would let an *opponent* increase a storm count by
   responding to it.

### 4.3 The trigger is a cast trigger, and that is the card's shape

Storm is an ability of the spell on the stack, so nothing *detects* it — the cast pipeline **synthesizes**
the fired trigger, exactly as the discard replacement synthesizes madness's reflexive ability. It needed
`TriggerZoneScope.Stack` (the first scope whose source is an object mid-cast) and
`TriggerCondition.StormCast` (resolved by the engine's copy path, not by a `ResolutionEffect`, for the
reason madness's reflexive cast is).

Being a **cast** trigger, it goes on the stack **above** the spell that produced it and resolves **first**;
the copies it makes go above the original too. So every copy gains its life before the printed spell does.
The reading order of the card is the exact reverse of its resolution order, and nothing in the encoding says
so — it falls out of where a cast trigger goes.

### 4.4 The copy primitive, and the one seam that makes it safe

`copySpellOnStack` copies the original's copiable values (CR 707.2) — its printed card identity and the cast
record that makes it the spell it is — gives the copy a **fresh object id** (CR 400.7) so it is separately
targetable and separately counterable, and marks it `StackEntry.Spell.isCopy`.

**A copy of a spell is a spell but not a card**, and that one sentence is why `isCopy` is a flag rather than
a new `StackEntry` member. Everything a copy does on the stack it does exactly as its original does; what it
must not do is behave like a card, and there was already one seam for that: `cardObject`, which returns
`null` for an ability precisely because an ability is not a card either. A copy returns `null` there too, so
the zone-residence invariant, the card census and the object-conservation machinery ignore it with **no new
cases**.

Two consequences fall out of that seam:

- **It does not go to a graveyard.** CR 608.2m moves a *card*; a copy has none, so `putSpellOffStack`
  removes it and it ceases to exist. The CR reaches the same place by moving it to a graveyard and having
  CR 704.5e delete it, which is unobservable here — state-based actions run before any player receives
  priority, so nothing in the pool can see a copy sitting in a graveyard — and the one-step form keeps the
  census exact.
- **It fires no cast trigger and feeds no storm count**, because it never reaches CR 601.2i.
  `GameEvent.SpellCopied` is a separate event from `SpellCast` for exactly this reason: emitting `SpellCast`
  for a copy would be the single most expensive one-line lie available here.

### 4.5 Why the copy is a primitive rather than a fold left to the card

A stack entry is engine state that only `mtg-rules` may build, the copies need freshly allocated object ids,
and every one of them has to be marked as a non-card so three separate invariants treat it correctly.
Leaving that to a card definition would be three chances to forget, silently.

---

## 5. What this deliberately does not model

- **"You may choose new targets for any of the copies"** (CR 702.40a). Needs N optional re-targetings, each
  with its own CR 601.2c enumeration. Gated loudly; vacuous for the only storm card in the pool (§4.1).
- **A modal card with additional targeting lines.** A mode carries its own line (CR 601.2b), so the two
  together need a list of lines *per mode*. `targetLinesOf` refuses the combination; nothing prints it.
- **A multi-line card with a non-fixed count on any line.** §2.3.
- **An ability that announces X *and* chooses an object for another cost component.** Its X would be bounded
  against a reservation the payment enumeration does not use; `requireXBoundIsExact` refuses it. The fix is
  a joint (X, object) enumeration, not a wider filter.
- **A cost *increase*.** §6 — dropped here, built by `W10-D`; see the note at the head of that section.

---

## 6. Kaervek's Torch — dropped here, built by `W10-D`

> **Superseded.** Everything below is the analysis as of `W9-C` and is preserved because it was right
> about *where* the problem was. It was wrong about one thing, and `W10-D` built the card on that: the
> minimum-over-choices gate does **not** have to run on every cast in every priority window. A cast can
> only be taxed when a taxing spell is on the stack *and* the card being cast can name a spell — a list
> scan and a static read of the definition — and when either is false, pricing at no targets is *exact*
> rather than merely cheap. The trade §3.2 rejected as option (a) was never the trade this needed.
>
> Point 2 below turned out to be the easy half and resolved exactly as predicted: the gate is literally
> `affordableTargetOptions(...).isNotEmpty()`, which is consistent by construction. The half neither this
> note nor the card comment saw is that the pool's counterspells are **modal**, so a modal caster could
> not be refused loudly; `castableModes` is narrowed by affordability instead, which narrows the mode
> offer, the castability gate and the per-mode target request at once. See `StackTargetTax.kt`.

### The analysis as it stood

> "As long as Kaervek's Torch is on the stack, spells that target it cost `{2}` more to cast."

The recorded diagnosis (`FW-COST` §3, `W8-C`) was that honouring the tax would require target enumeration to
consult affordability, which the engine did not do. **That is no longer true, and re-checking it was the
right instruction.** `FW-TGTCOND` coupled the two in wave 8: `affordableTargetOptions` already drops a
target whose resulting total cost the caster cannot pay, which is precisely the shape a counterspell facing
the Torch needs. The **filter** half runs the right way.

The **gate** half runs the wrong way, and it is the half that decides the card.

`cheapestTargetsFor` prices a cast's legality at *no targets at all* for every card without a
target-conditional reduction. For a **reduction** that is the safe direction — pricing without the discount
can only over-charge, so the gate can only be conservative. For an **increase** it is the unsafe one: with
Kaervek's Torch the only spell on the stack, `castIsLegal` would admit a Counterspell at `{U}{U}`,
`affordableTargetOptions` would then remove its only option, and `targetRequest` refuses an empty option
list in its `init`. That is a crash, not a missing line.

Making it correct means pricing the gate at the **minimum over legal target choices** — a payment
enumeration per candidate target, on every cast in every priority window. That is a change to the legality
path of every card in the pool for one card: the same trade §3.2 rejected as option (a), and rejected here
for the same reason.

Two further gaps remain either way:

1. **There is no declaration for a cost increase at all.** `docs/design/cost-modification.md` §3 populates
   the slot in CR 601.2f's formula with nothing, on purpose. The shape needed here is one no existing
   modifier has: a static ability of an object **on the stack**, taxing *another player's* spell, keyed on
   that spell's chosen targets. `CardDefinition.spellCostReductions` is battlefield-scoped and
   `SpellDefinition.costReduction` is the spell's own.
2. **The gate and the filter would have to be provably consistent again.** Today's argument is structural —
   the gate prices the cheapest reachable target and the filter keeps exactly the payable ones, so the
   filtered list always contains that target. A minimum-over-choices gate restores that property (the gate
   becomes `affordableTargetOptions(...).isNotEmpty()`, which is consistent by construction rather than by
   argument), but it must be *made* to, not assumed.

Its damage line is trivial now that `FW-X` has landed. Encoding the card without the tax would delete the
reason it is played, which is what the drop rule forbids.

---

## 7. The dispatch sites

| Site | Module | What it gained |
|---|---|---|
| `TargetContext.kt` | core | the context type (new file) |
| `PermanentRestriction` | core | `NONCREATURE_ARTIFACT_WITH_MANA_VALUE_X`, `CREATURE_CONTROLLED_BY_TARGETED_PLAYER` |
| `SpellDefinition` | core | `additionalTargetSpecs`, `storm` |
| `TriggerCondition` / `TriggerZoneScope` | core | `StormCast`, `Stack` |
| `StackEntry.Spell` | core | `isCopy`; `cardObject` returns `null` for a copy |
| `StackEntry.ActivatedAbilityOnStack` | core | `chosenX` |
| `PendingActivation` | core | `chosenX` |
| `Turn` | core | `spellsCastThisTurn` |
| `PlayerState` | core | `landsEnteredThisTurn` |
| `GameEvent` | core | `SpellCopied` |
| `TargetLines.kt` | rules | the list-of-lines framework (new file) |
| `AbilityXCost.kt` | rules | the activation-side X bound and the ordering argument (new file) |
| `ActivationXGathering.kt` | rules | the activation's CR 601.2b stage (new file) |
| `Storm.kt` | rules | the storm trigger and `copySpellOnStack` (new file) |
| `CastTargetGathering.kt` | rules | the cast's initial target record (new file) |
| `TriggerDescriptions.kt` | rules | the ordering-decision labels (split out) |
| `Targets.kt` | rules | the `context` parameter; `TargetCheck` |
| `PermanentRestrictions.kt` | rules | `satisfiesDependentRestriction` |
| `PendingCastRequest` / `CastGathering` | rules | per-line requests; appending answers |
| `CastingPipeline` / `StackResolution` | rules | per-line CR 601.2c and the whole-spell CR 608.2b |
| `Activation` / `ActivationGathering` / `ActivationExecution` | rules | the joint X offerability test, the X stage, the recorded value |
| `SpellLeftStack.kt` | rules | a copy ceases to exist |
| `effect/Landfall.kt` | rules | `hadLandEnterThisTurn` and `countLandfall` (new file) |
| `TurnDto` / `ViewObjectDtos` / `PendingCastDtos` | protocol | `spellsCastThisTurn`, `landsEnteredThisTurn`, `chosenX` |
