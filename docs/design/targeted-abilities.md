# Design note — abilities that target (`FW-ABILTGT`)

The reference for the framework that lets a **triggered or activated ability choose targets**. Written
to PLAN.md §5's rule that a framework packet gets a design note reviewed **before** implementation
starts, in the style of `docs/design/layer-system.md`.

Three rules anchor the design downward: **CR 603.3d** (a triggered ability's targets are chosen as it
is *put on the stack*), **CR 602.2b** (an activated ability's targets are chosen inside the CR 601.2
sequence, as part of activating it), and **CR 608.2b** (on resolution, targets are re-checked; an
object whose targets are *all* illegal is removed from the stack and does nothing).

Upward, the seams the engine already cut: `legalTargets`/`isTargetLegal` (`Targets.kt`) as the single
source of target-legality truth; `DecisionRequest.ChooseTargets` as the enumerated target decision
(ADR-005); `PendingCast.chosenTargets` / `PendingActivation.chosenDiscard` as the gathering idiom
(ADR-004); and `StackEntry.Spell.targets` as the recorded choice a resolution reads.

This framework was **found twice, independently**, by design passes that went looking at oracle text —
`countering-spells.md` §1.4 ("the engine's triggered abilities cannot target at all") and
`protection.md` §2.4 ("no activated or triggered ability uses `TargetSpec`… 702.16b's ability half has
no call site today"). `gauntlet-card-triage.md` §5.1 then measured it: **32 gauntlet cards name it, in
ten of the thirteen mainboards** — more than any other framework, and twice the counter framework's 16.
Neither the upstream brief nor any tranche plan names it. This note does not re-derive any of that; it
takes it as the premise.

---

## 1. The gap, precisely

`TriggeredAbility` and `ActivatedAbility` are the only two ability shapes that use the stack, and
**neither has a `targetSpec`**. The consequences, verbatim from the code:

- `TriggeredAbility`'s KDoc: *"None of the four MVP halves targets (CR 603.3d), so triggered-ability
  targets are not modeled here; they are the extension point for P5.2/P6."*
- `ActivatedAbility`'s KDoc: *"Targeted activated abilities (CR 602.2b) are the extension point; no MVP
  activated ability targets."*
- `AbilityResolution.resolveAbility` builds `ResolutionContext(targets = persistentListOf())` under the
  KDoc *"A triggered ability has no targets in the MVP pool (CR 603.3d), so no CR 608.2b re-check
  applies."*
- `ActivationExecution.resolveActivatedAbility` does the same with a bare
  `ResolutionContext(entry.controller, persistentListOf())`.
- `StackEntry.Ability` and `StackEntry.ActivatedAbilityOnStack` carry no `targets` field, while
  `StackEntry.Spell` does.
- `legalTargets`' only four callers are casting sites: `ActionEnumeration.targetsAvailable`,
  `CastGathering.beginCastGathering`, `PendingCastRequest.pendingCastRequest`, and
  `StackResolution.resolveSpell` (via `isTargetLegal`).

So: **spells target; abilities do not.** That is the whole framework, and it is why ADR-005's action
space is currently unrepresentative — the entire class of decision "which creature does this
enters-the-battlefield trigger kill" does not exist in the environment.

### 1.1 Scope, and one deliberate inclusion

In scope: `targetSpec` on both ability shapes; CR 603.3d choice at trigger placement; CR 602.2b choice
during activation; CR 608.2b re-check for both; the enumeration, view, protocol, replay, and invariant
consequences.

Deliberately included: **one** new `TargetSpec` member, `TargetSpec.TargetOpponent` (CR 115.1a,
CR 102.1) — precisely what the demonstration card's oracle text says, and nothing wider. Without at
least one restricted spec no real gauntlet card can demonstrate a targeted ability (§7), because
`AnyTarget` is the only spec that exists and no card in the 32 uses it on an ability without a second
framework. It also earns its keep as a test: `TargetOpponent` is the first spec whose enumeration is
**decider-relative** — it excludes whoever is choosing — so it proves the deciding player flows
correctly through the two new choice paths rather than being defaulted to the active player. The
triage calls restricted specs `P-TGT` and assigns the family to packet W1-C; this note takes exactly
one member and leaves the rest (`target creature`, `target permanent`, `target player`,
`target land`, `target artifact`) to W1-C. §9 records the collision.

Out of scope, each with where it slots: **multi-target and "up to N"** (`FW-MULTITGT`, CR 601.2c — the
`ChooseTargets` request is single-select, and `establishTargets` demands exactly one); **targets outside
the battlefield** (`FW-ZONETGT`, a new `Target` member); **targeting a spell on the stack**
(`countering-spells.md` §4); **modal abilities** (`FW-MODAL`, CR 700.2 — mode choice must precede target
enumeration); **protection's source-relative targeting restriction** (`protection.md` §2.4); **the
intervening-if condition** (CR 603.4, `P-INTERVENINGIF`) — a sibling of CR 603.3d that this framework
does *not* deliver and that trap T9 of the triage warns about. Each is a loud gate or a documented
absence, never a silent approximation.

---

## 2. What CR actually requires, and the one distinction that causes silent bugs

**CR 603.3d.** *"…the ability's controller … chooses targets for it. … This is done as the ability is
put on the stack."* Not when it fires; not when it resolves. The window is the moment of placement,
which in this engine is inside `TriggerPlacement.putTriggerOnStack`, reached from `priorityTo` —
**before any player receives priority**. That is the whole design risk: it is not a priority window, so
the engine must be able to pause somewhere it has never paused before (§3).

**CR 602.2b.** Activating an ability follows *"the process described in rules 601.2b–i"* — so targets
(601.2c) are chosen before costs are determined (601.2f) and paid (601.2g–h), exactly the order
`CastingPipeline.executeCastPipeline` already runs for spells and `Activation.advanceActivationGathering`
already runs for costs.

**CR 608.2b.** *"If all its targets … are now illegal, the … ability doesn't resolve."* For an ability
that means it is **removed from the stack and ceases to exist** (CR 113.7a) — *no card moves*, because
an ability on the stack is not a card. A spell instead goes to its owner's graveyard (or exile, CR
702.34e). Same verdict, different consequence. §6 turns that into a decision about code reuse.

### 2.1 The distinction — "no legal target" means opposite things

This is the single most common source of silent bugs in this framework, and the two rules that govern
it live in different chapters:

- **A triggered ability with no legal targets is still put on the stack.** CR 603.3d has no
  "otherwise it doesn't trigger" clause. The ability goes on the stack with **no targets**, and CR
  608.2b removes it on resolution. (The engine never gets to *skip* the trigger: it already fired at
  CR 603.3, and a fired trigger is not undone.)
- **An activated ability with no legal target cannot be activated at all.** CR 601.2c, reached via CR
  602.2b: *"If the … ability … requires … targets, [and] the player can't … choose the required
  number of legal targets, the … ability can't be activated"*, and the activation is reversed
  (CR 602.2e / CR 728).

Under ADR-005 that asymmetry falls out for free **provided the two paths are written separately**:
the activation path consults `targetsAvailable` before enumerating the option; the trigger placement
path never does. A single shared "choose targets or bail" helper would get one of the two wrong, so
there is deliberately no such helper.

**The empty-target list is therefore load-bearing, not a degenerate case.** An ability entry with
`targetSpec != None` and `targets == []` means precisely "the controller had no legal choice at
placement time", and the CR 608.2b predicate `targets.none { legal }` is vacuously true for it — so it
fizzles, which is the correct answer, reached by the mechanism that already exists rather than by a
special case. The invariant checker pins that this state is reachable only for a *trigger* (§8).

---

## 3. Where the pause lives — the design's centre of gravity

### 3.1 The problem

`priorityTo` marks the recipient as holding priority, runs state-based actions, and then — if triggers
are pending — hands off to `placePendingTriggers`, which today either suspends for a
`DecisionRequest.OrderTriggers` (CR 603.3b) or places the single trigger and re-enters `priorityTo`.
Placement therefore happens **inside the advance loop, between two priority windows**, with no window
open. Adding a target choice there means the advance loop gains a second kind of pause in that region.

ADR-004 makes this hard in exactly one way: **the pending request must be a pure function of the
state.** `pendingDecisionRequest` re-derives the request from `GameState` alone, and `applyDecision`
validates the incoming decision against that re-derivation. So "we are halfway through placing this
controller's ordered batch and this one is awaiting its target" must be *written down in the state*.

### 3.2 The two candidate models, and why one is a trap

**Model A — a nullable `targets` field on `PendingTrigger`,** `null` meaning "targets not yet chosen".
No new pending record; the marker rides on the trigger itself. **Rejected.** It cannot express the
CR 603.3b/603.3d interleaving. At the moment a controller's group of two-or-more first needs its
*ordering* decision, every targeting trigger in that group already has `targets == null` — so the
derivation `if (any targets == null) ChooseTargets else OrderTriggers` asks for targets *before*
ordering (a CR 603.3d violation), and the opposite derivation order can never tell an
already-ordered group from a fresh one and re-asks for the ordering forever. The field is not a
sufficient statistic for the placement phase, and every patch that makes it one reintroduces the
record Model B just declares.

**Model B (chosen) — a new `GameState.pendingTriggerTargets: PendingTriggerTargets?`,** non-null
exactly while one fired trigger is awaiting the CR 603.3d target choice:

```kotlin
// mtg-core — additive, flagged.
data class PendingTriggerTargets(
    val controller: PlayerId,   // CR 603.3d: the ability's controller chooses.
    val sourceId: ObjectId,     // the source's last-known id (CR 603.10), for display.
    val sourceCard: CardRef,    // the source's printed identity, for display.
)
```

The record names *who decides* (ADR-004's requirement — the request's seat must be derivable) and
*what it is about* (display). **Which** trigger is being targeted is not stored, because it is
derivable and storing it would be redundant state that could drift: it is
`state.pendingTriggers.first { it.controller == controller }`. That is well-defined because of §3.3.

**`PendingTrigger` itself is unchanged, and gains no `targets` field.** Targets are chosen *and* the
trigger is placed within a single transition, so a pending (unplaced) trigger with settled targets is
never a state the engine can pause in. The chosen targets go straight onto the stack entry. This is
worth stating because the obvious first move — mirroring `PendingCast.chosenTargets` onto
`PendingTrigger` — adds a field that is provably always `null` or absent at every observable state,
and it would ripple into `PendingTriggerView`, `renderTrigger`, and the trigger DTO for nothing.

### 3.3 Ordering and targeting, interleaved (CR 603.3b × CR 603.3d)

**Decision — `GameState.pendingTriggers`' list order *is* the placement order, and the CR 603.3b
ordering answer rewrites the list into the chosen order rather than placing anything.** Placement then
always takes the front of the APNAP-first controller's group.

The full sequence at a placement checkpoint, for one controller's group:

1. `placePendingTriggers` picks the APNAP-first controller with pending triggers (unchanged).
2. If that controller has **two or more**, suspend with `OrderTriggers` (unchanged request, unchanged
   semantics). The answer **reorders `pendingTriggers`** so the group appears in the chosen placement
   order, then falls into step 3. If the controller has exactly one, go straight to step 3.
3. **Drain**: take the front trigger of the controller's group.
   - If its ability targets *and* at least one legal target exists, set `pendingTriggerTargets` and
     suspend with `ChooseTargets` (CR 603.3d). The answer records the target on the stack entry, places
     the trigger, clears the record, and re-enters the drain.
   - Otherwise place it with no targets — either an untargeted ability, or §2.1's
     "no legal target, still goes on the stack" case — and re-enter the drain.
4. When the controller's group is empty, `priorityTo(state, priorityRecipient(state))` — which re-enters
   `placePendingTriggers` for the *next* controller's group, or opens the window. Unchanged.

Two properties make this resumable from the state alone:

- **The only mid-group pause is the target pause**, and it is the *first* thing
  `pendingDecisionRequest` checks in the trigger region. So a state paused mid-group never re-derives
  an `OrderTriggers` request for an already-ordered group — the ambiguity that killed Model A cannot
  arise.
- **After the reorder, the remaining placement order is the surviving list order.** Resuming from a
  serialized state and draining front-to-back reproduces the controller's chosen order exactly.

`applyOrderTriggers`' externally visible behaviour is unchanged for every existing card: with no
targeting trigger in the group, the drain places all of them in the chosen order and grants priority,
exactly as the current fold does.

### 3.4 What the advance loop actually gains

`pendingDecisionRequest` gains **one branch**, placed ahead of the existing pending-triggers branch:

```kotlin
?: when {
    // CR 603.3d: targets are chosen as the ability is put on the stack, before any window opens.
    state.pendingTriggerTargets != null -> pendingTriggerTargetsRequest(state)
    state.pendingTriggers.isNotEmpty()  -> pendingOrderTriggersRequest(state)
    holder != null                      -> chooseActionRequest(state, holder)
    else -> pendingCombatDecision(state) ?: …
}
```

It is **not** a `midTransitionPauseRequest` member. Those pauses all sit inside a *resolution* with the
resolving object on top of the stack (`PendingResolutionInvariant` asserts exactly that); trigger
placement sits between a state-based-action check and a priority window, with the stack possibly empty.
Filing it there would make that invariant false.

Note that the deciding seat is `pendingTriggerTargets.controller`, which need **not** be the player
`priorityTo` marked as holding priority. That is already true of `OrderTriggers` — the APNAP-first
controller may not be the priority recipient — so it introduces no new shape.

### 3.5 Activated abilities need no new record

An activation already has a gathering record, and it already gathers in the CR 601.2 order.
`PendingActivation` gains `chosenTargets: PersistentList<Target>?` with exactly the semantics of
`PendingCast.chosenTargets`: `null` before the CR 601.2c decision, the chosen list after, empty exactly
when the ability targets nothing. `advanceActivationGathering` asks for it **first**, before the
discard selection and the payment plan (CR 602.2b → CR 601.2b–i order). No new pending record, no new
`GameState` field, and `GameState.init`'s existing `pendingActivation` validation extends by one line.

---

## 4. The decision request — reuse `ChooseTargets`, do not add a member

**Decision — `DecisionRequest.ChooseTargets` serves all three flows: a cast, an activation, and a
trigger placement.** Its payload already fits: `cardObjectId` becomes the *ability source's* object id
(the source is a permanent or a hand card in every case), `card` its printed identity, `options` the
enumerated legal targets.

The argument is not only economy, though the economy is decisive. The survey of what a new
`DecisionRequest` member costs in this repo is **~22–29 compile-breaking sites across five modules**:
`DecisionView.kindOf` + its enum, `DecisionApplication`, `DecisionValidation`, the protocol's
`DecisionRequestDto`/`toDto`/`toDomain`/`DecisionRequestKindDto`/round-trip fixture, four CLI `when`s,
`RandomRemoteAgent`, `RandomLegalResponder`, `Responders`, `EnumerationProbe`, and three scripted
acceptance responders. The substantive arguments:

- **It is the same decision.** CR 601.2c is the rule for all three; CR 602.2b and CR 603.3d both defer
  to it. A driver that can answer "pick a target for this object" can answer all three, and a *training
  agent* observing a distinct request kind for the same choice would be learning an artefact of the
  engine's internals.
- **ADR-005 enumeration completeness comes free.** `EnumerationProbe.candidatesFor` already emits one
  probe per `ChooseTargets` option, so a targeted ability is probed the day it lands, with no probe
  change — and the probe is precisely the guard against the enumeration gap this framework could
  introduce.
- **Which flow is answering is already a state question, with precedent.** `applyChosenYesNo`
  disambiguates madness from the optional discard-then-draw by which pending record is open, and
  `applyChosenPaymentPlan` disambiguates plot / activation / cast the same way, both under explicit
  KDoc. `applyChosenTargets` joins them, dispatching in the same order `pendingDecisionRequest` derives
  in: `pendingCast`, then `pendingActivation`, then `pendingTriggerTargets`, then a loud `error`.

The cost of the reuse is honest and small: `ChooseTargets`' KDoc, which today says "the target choice
of a cast in progress", becomes "of a cast, an activation, or a triggered ability being put on the
stack", and its `cardObjectId`/`card` KDoc widens to "the object being cast, or the ability's source".
That documentation change is in this packet.

**A new member is still the right answer for multi-target** (`FW-MULTITGT`): "up to two targets" is a
`MultiSelect` with a different arity rule, not the same decision. Flagged, not built.

---

## 5. Sharing with the spell path — `legalTargets` and its signature

**Decision — reuse `legalTargets`/`isTargetLegal` unchanged, and do not touch their signature in this
packet.**

Reuse, not a parallel implementation, is not close: `Targets.kt`'s file comment already states the
contract — *"'legal' is defined **by** that enumeration"* — and it is the property that keeps
choice-time and resolution-time legality from drifting. Building an ability-side enumerator would
create a second definition of legality that a hexproof grant, a protection quality, or a layered P/T
restriction would eventually desynchronise. The three call classes (cast, activation, trigger
placement) and the three re-check sites (spell, triggered ability, activated ability) all go through
the one function.

The signature question is live, and two notes have already staked out positions:

- `protection.md` §2.4 wants a **prospective-source** parameter (CR 702.16b's "abilities from a source
  with the stated quality" needs the source's characteristics, not the decider's identity).
- `countering-spells.md` §4 / open question 1 wants a **self-exclusion** parameter, so a spell can
  never target itself and cast-time and CR 601.2c re-validation enumerate the same set.

**Neither is needed by this packet, and adding either speculatively would be wrong.** The only
targeting restriction that exists today is hexproof (CR 702.11), which is *opponent-relative*: its
whole input is "who is deciding", and for an ability the deciding player is the ability's controller —
which is exactly the `you` parameter `legalTargets` already takes. Wiring a source parameter now would
change four call sites for no card and would have to be re-decided when protection actually lands. And
self-exclusion is unreachable: an ability is not a legal target for anything in this pool
(`Target` has no stack member), so the divergence countering-spells.md describes cannot occur.

What this packet **does** owe those two notes is to keep the choke point single, and it does: after
this packet, `legalTargets` has seven callers instead of four, all of which would receive the new
parameter together. Both signature changes therefore stay one-file changes. `protection.md`'s
observation that "702.16b's ability half has no call site today" stops being true the moment this lands
— the call site now exists, and protection must wire it.

`Targets.kt`'s KDoc gains one sentence saying that `you` is now also *the ability's controller at
CR 603.3d placement and CR 602.2b activation*, so the parameter's meaning stays documented at its
definition.

---

## 6. CR 608.2b for an ability — reuse the *verdict*, not the *path*

The spell fizzle in `StackResolution.resolveSpell` is two things fused:

```kotlin
val fizzles = spec != TargetSpec.None && entry.targets.none { isTargetLegal(state, spec, it, entry.controller) }
if (fizzles) {
    val (finished, finalId, exiled) = putResolvedSpellOffStack(state, entry)   // ← card → graveyard/exile
    …GameEvent.SpellFizzled…
}
```

**Decision — reuse the predicate; do not reuse `putResolvedSpellOffStack`.** Reusing it is the trap.
That function asserts the entry has a card object, mints a fresh `ObjectId` for the graveyard rebirth
(CR 400.7), and honours the flashback exile-instead replacement (CR 702.34e). **An ability has no card
object** (CR 113.7a): nothing moves, nothing is reborn, no leave-stack replacement can apply. Routing an
ability through it would either not compile or, worse, invent a graveyard object out of the ability's
source's last-known identity — a phantom card, and precisely the "wrong result that looks right" PLAN.md
§7 names as the worst outcome.

So the shared piece is the *predicate*, lifted into `Targets.kt` beside the enumerator it is defined
against:

```kotlin
/** CR 608.2b: whether every chosen target is now illegal, so the spell or ability does not resolve. */
internal fun allTargetsIllegal(state, spec, targets, controller): Boolean =
    spec != TargetSpec.None && targets.none { isTargetLegal(state, spec, it, controller) }
```

used by all three resolution sites. The *consequence* stays per-kind and is the honest difference:
a spell's card leaves the stack for a graveyard or exile; an ability is removed from the stack and
ceases to exist, with a new `GameEvent.AbilityFizzled` alongside the existing
`TriggeredAbilityResolved` / `AbilityResolved` pair.

Both ability resolvers get the check **first**, before the orchestrated branches
(`resolveOrchestratedTrigger`'s madness/discard-draw flows, `resolveActivatedAbility`'s library
search). No orchestrated ability targets today, so the order is unobservable — but CR 608.2b comes
before CR 608.2c, and an ability that does not resolve must not begin an orchestration it would then
have to unwind.

`FizzleVerdictAcceptanceSpec` is untouched and must **stay** untouched and green: it is the regression
guard that the spell path was not disturbed, the same role `countering-spells.md` §10 assigns it.

---

## 7. The demonstration card — Lotleth Giant

Of the 32 cards `FW-ABILTGT` blocks, exactly **three** name it as their only framework blocker
(triage §5's "unlocks alone: 3"): Balustrade Spy, Bojuka Bog, and Lotleth Giant. Of those three, two
need a further Tier-1 primitive that is not this framework — Balustrade Spy needs a mill-until-a-land
variant, Bojuka Bog needs a graveyard-exile primitive *and* enters-tapped.

**Lotleth Giant — `{6}{B}` Creature — Zombie Giant, 6/5. "Undergrowth — When this creature enters,
it deals 1 damage to target opponent for each creature card in your graveyard."** (Oracle text from
`mtg-pauper`'s Scryfall snapshot; note it is a **6/5 with no trample**, and it targets an *opponent*,
not a player. "Undergrowth" is an ability word, CR 207.2c — no rules meaning.) It needs:

| Piece | Status |
|---|---|
| A vanilla 6/5 body | `PrintedPowerToughness` — exists |
| An enters-the-battlefield trigger | `TriggerCondition.EnteredBattlefieldSelf` — exists |
| Damage to a player | `effect/DealDamage.kt` over `Target.Player` — exists |
| "for each creature card in your graveyard" | a pure read of `GameState` in the `ResolutionEffect` — the `Grab the Prize` precedent, card-side vocabulary |
| **A targeted triggered ability** | **this framework** |
| **A "target opponent" spec** | **`TargetSpec.TargetOpponent`, §1.1** |

It is the only real gauntlet card that composes to *this framework plus nothing else*, which is exactly
what a framework packet's proof should be — the card is the framework, with no second mechanic
confounding the demonstration. (Spy Combo maindecks two.)

In a two-player game `TargetOpponent` always enumerates exactly one option, and the request still
surfaces — the engine never auto-collapses a decision (ADR-004), the same rule that always surfaces a
single payment plan. That makes the demonstration crisp: the pause is visible in the transcript even
though the choice is forced.

**What it deliberately does not demonstrate: the CR 608.2b ability fizzle.** A targeted *player* stops
being a legal target only by leaving the game, which in a two-player game is the game ending
(CR 104.2a) — `Targets.kt`'s KDoc already records that the players-only fizzle is unreachable, and
`FizzleVerdictAcceptanceSpec`'s own historical note tells the same story for spells. The ability fizzle
is therefore pinned at rules level with a fixture whose trigger targets a *creature* that dies in
response, which is the same shape P2.x used for spells before P3.2 made the spell fizzle reachable. The
honest statement is that the end-to-end ability fizzle becomes reachable when W1-C lands
`TargetSpec` for a creature, and it is flagged as a follow-up rather than faked.

---

## 8. Blast radius — by file and type

**`mtg-core` (all additive):**

- `definition/TriggeredAbility.kt` — new `targetSpec: TargetSpec = TargetSpec.None`; the "extension
  point for P5.2/P6" KDoc paragraph is replaced by what the field means.
- `definition/ActivatedAbility.kt` — same field, same KDoc replacement.
- `definition/TargetSpec.kt` — new `TargetOpponent` member. Breaks the five exhaustive `when`s in
  `mtg-rules`: `Targets.legalTargets`, `ActionEnumeration.targetsAvailable`,
  `CastGathering.beginCastGathering`, `CastingPipeline.establishTargets`,
  `StackResolution.auraAttachmentTargetOf`. No protocol DTO mirrors `TargetSpec` (it is
  card-definition data, not wire data), so the break stops there.
- `state/PendingTriggerTargets.kt` (new) + the `pendingTriggerTargets` field on `state/GameState.kt`,
  with an `init` validation (seated controller, a matching pending trigger, and that trigger's ability
  targets).
- `state/PendingActivation.kt` — new `chosenTargets: PersistentList<Target>?`.
- `state/StackEntry.kt` — `targets: PersistentList<Target>` on **both** `Ability` and
  `ActivatedAbilityOnStack`, defaulted empty.
- `event/GameEvent.kt` — new `AbilityFizzled`.

**`mtg-rules`:**

- `engine/Targets.kt` — the shared `allTargetsIllegal` predicate; the `TargetOpponent` enumeration
  case; the `you`-parameter KDoc widening.
- `engine/TriggerPlacement.kt` — the §3.3 drain: `putTriggerOnStack` takes the chosen targets,
  `applyOrderTriggers` reorders instead of placing, and the new `placeOrderedTriggers` walks the
  ordered group.
- `engine/TriggerTargeting.kt` (**new**) — `triggerTargetPause`, `pendingTriggerTargetsRequest`, and
  `applyChosenTriggerTarget`. Split out because `TriggerPlacement.kt` would otherwise cross detekt's
  eleven-function-per-file budget, the same reason `ActivationExecution.kt` was split from
  `Activation.kt` in P6.2a.
- `engine/PendingDecision.kt` — one branch (§3.4).
- `engine/DecisionApplication.kt` — `applyChosenTargets` becomes a three-way dispatch.
- `engine/Activation.kt` is split into the enumeration half (which gains the `targetsAvailable` gate)
  and a new `engine/ActivationGathering.kt` holding `beginActivation`, the gathering walk, the pending
  request, and the two apply functions — again a function-budget split, and one that names the CR
  601.2b–i ordering in one place.
- `engine/ActivationExecution.kt` — the CR 601.2c re-validation (`establishActivationTargets`), the
  entry's targets, and the CR 608.2b re-check with its `AbilityFizzled` consequence (§6).
- `engine/AbilityResolution.kt` — the same CR 608.2b re-check for a triggered ability.
- `StackEntryView.kt` / `ViewFor.kt` / `SeatView.kt` — `targets` on the two ability view members;
  `pendingTriggerTargets` on `SeatView`.
- **No** change to `DecisionRequest.kt`'s member list, `DecisionView.DecisionRequestKind`,
  `DecisionValidation`, or `PendingTriggerView` (§3.2, §4).

**`mtg-protocol`:** `ViewObjectDtos.kt` (`targets` on both ability stack DTOs, both mappers),
`SeatViewDto.kt` (+ the new pending field), `PendingCastDtos.kt` (`PendingActivationDto.chosenTargets`),
a `PendingTriggerTargetsDto`. **No** `DecisionRequestDto` change, no `DecisionRequestKindDto` member,
no round-trip fixture. §10 makes the version call.

**`mtg-cli`:** nothing is forced. `MenuRenderer`, `DecisionInput`, `RandomLegalChooser`, and
`DefaultDecision` already handle `ChooseTargets`. `BoardRenderer`'s `StackEntry` `when` gains the
ability targets only as a display improvement, and takes it.

**`mtg-acceptance`:**

- `driver/Responders.kt` — `PASS_AND_DISCARD_LOWEST` currently `error`s on `ChooseTargets` ("never
  casts"). A passive game can now legitimately reach one *from a trigger*, so the arm splits on
  `state.pendingTriggerTargets`: a deterministic first target for the trigger case, and the **loud
  failure preserved** for the cast case. `RandomLegalResponder` needs nothing.
- `fuzz/EnumerationProbe.kt` — nothing (§4).
- `replay/Fingerprint.kt` — the two ability stack entries render their targets; `pendingActivation`'s
  digest gains `chosenTargets`; a `pendingTriggerTargets` token joins the twelve existing `pending*`
  tokens. `FingerprintRenderers.renderTarget` is reused as-is; `renderTrigger` is untouched (§3.2). No
  golden fingerprint strings are stored anywhere and every replay test compares a run against its own
  re-run, so this rebaselines nothing.
- `invariant/` — one new `Invariant.ABILITY_TARGET_SANITY` in a new `AbilityTargetInvariant.kt`
  (DoD item 4), covering both halves of the new state: every ability on the stack whose spec is `None`
  carries no targets and one that targets carries at most one; and, when `pendingTriggerTargets` is
  set, its controller is seated and APNAP-first among pending controllers, the front trigger of that
  controller's group targets, and no cast/activation gathering coexists.

**`mtg-cards`:** one new file for the demonstration card, one line in `MvpCards.kt`.
**`mtg-pauper`:** the `GauntletCoverageSpec` burn-down pin moves by one (§9).

---

## 9. Collisions

- **`MvpCards.kt` and `GauntletCoverageSpec.kt`** are the triage's known guaranteed conflict (§3.3 of
  the triage). This packet's edit is one registry line and four pinned numbers — Spy Combo's mainboard
  encoded count, `TOTAL_ENCODED_MAIN`, `TOTAL_MISSING_MAIN`, `TOTAL_MISSING_BOTH_BOARDS`. Expected to
  conflict textually with every card packet in flight; trivially resolvable.
- **`TargetSpec.TargetOpponent` overlaps packet W1-C's `P-TGT`.** W1-C owns the restricted-spec family
  (target creature / permanent / player / land / artifact, with filters). This packet takes the single member
  the demonstration card needs and no more. If W1-C lands first, this packet consumes its member
  instead of adding one; if this lands first, W1-C adds its members beside it. Either order works; the
  five `when`s break loudly in both.
- **`countering-spells.md` F1.7 (Spellstutter Sprite) is unblocked by this packet**, exactly as that
  note predicted: with `FW-ABILTGT` built first, F1.7 stops being "extend the trigger framework" and
  becomes card composition on top of the counter primitive.

---

## 10. Protocol versioning

`ProtocolVersion.kt`'s KDoc (bumped to `2.0.0` at P8.3) sets the standard: *"Bump this whenever the
wire shape changes in a way a peer must know about"*, and P8.3's own precedent is that a **required
field added to a strictly-decoded payload is a major bump**, because the strict codec rejects unknown
fields — it explicitly absorbed P8.2's unbumped `SeatViewDto.cards` on that reasoning.

This packet does exactly that, three times over, all server→client inside `SeatViewDto`:
`StackEntryViewDto.TriggeredAbilityOnStack` and `.ActivatedAbilityOnStack` gain `targets`,
`PendingActivationDto` gains `chosenTargets`, and `SeatViewDto` gains `pendingTriggerTargets`. A
`2.0.0` peer decoding a `3.0.0` seat view would reject it.

**Decision — bump to `3.0.0`**, with a KDoc paragraph in the same shape as the `2.0.0` one. No
`DecisionRequest` kind is added (§4), so the client→server direction is unchanged and the
`DecisionRequestKindDto` enum — whose `valueOf` mapping fails at *runtime* rather than compile time —
is untouched. That is a smaller break than P8.3's, and still a major one under the recorded standard.

---

## 11. Test strategy

**`mtg-rules` unit tests**, all CR-cited (CONVENTIONS.md):

1. *CR 603.3d: a triggered ability chooses its targets as it is put on the stack* — the pause exists,
   its seat is the trigger's controller, and its options are `legalTargets`.
2. *CR 603.3d: a triggered ability with no legal target is still put on the stack* — no pause, the
   entry carries no targets.
3. *CR 608.2b: a triggered ability whose only target is illegal on resolution does nothing and is
   removed from the stack* — the fixture whose ETB trigger targets a creature that dies in response.
4. *CR 603.3b/603.3d: simultaneous triggers are ordered first, then targeted one at a time in the
   chosen placement order* — the interleaving of §3.3, with two targeting triggers.
5. *CR 602.2b: an activated ability chooses its targets before its costs are paid* — the request order.
6. *CR 601.2c/602.2b: an activated ability with no legal target is not enumerated* — the §2.1
   asymmetry, asserted directly against the trigger case.
7. *CR 608.2b: an activated ability whose only target is illegal on resolution does nothing.*
8. *CR 115.1a/102.1: `TargetSpec.TargetOpponent` enumerates every player but the decider, and no
   permanent* — including that the two seats get different enumerations from the same board.
9. *ADR-004: every new pause re-derives its own request* — the resumability property, applied to the
   trigger-target pause.
10. *CR 603.3d: the target choice belongs to the ability's controller, not the priority recipient* —
    a trigger the **non-active** player controls, which is the case a "the active player decides"
    shortcut would silently get right on every other test.
11. *CR 608.2b: a targeting trigger placed with no targets at all fizzles vacuously* — §2.1's
    load-bearing empty list, asserted as a behaviour rather than left as a comment.

All eleven ship as `mtg-rules/src/test/.../TargetedAbilitySpec.kt`.

**`mtg-acceptance` invariant tests** — five cases on `ABILITY_TARGET_SANITY`, including the pair that
pins §2.1 from both sides: an empty-targeted *triggered* ability on the stack is clean, an empty-targeted
*activated* one is a violation.

**`mtg-cards`** — the demonstration card's declared shape and its graveyard-count damage.

**`mtg-acceptance`** — one scripted game: Lotleth Giant resolves, its trigger pauses for the target
choice, the chosen opponent takes damage equal to the creature cards in the controller's graveyard, and
the invariant checker is clean throughout. Plus the existing corpora, unchanged and green — no MVP
mainboard card has a targeted ability, so no existing fingerprint or coverage number moves except the
pinned burn-down.

---

## 12. Non-goals (explicit)

Out of scope, with where each slots: **multi-target / "up to N"** (`FW-MULTITGT`, CR 601.2c);
**targets in other zones** (`FW-ZONETGT` — a new `Target` member, shared with `countering-spells.md`'s
`Target.SpellOnStack`, and the triage's trap T15 says build both members at once);
**targeting a spell or ability on the stack**; **modal abilities** (`FW-MODAL`, CR 700.2);
**protection's source-relative restriction** (`protection.md` §2.4, whose call site this packet
creates); **intervening-if** (CR 603.4, `P-INTERVENINGIF` — a CR 603 sibling this framework does not
deliver, and the triage's trap T9); **"target creature/permanent/land/artifact"** specs (W1-C's
`P-TGT`); **a target chosen by someone other than the ability's controller** (`FW-NONCTRLDEC`);
**dynamic target restrictions** ("mana value X or less", Spellstutter Sprite — needs the restriction
evaluated live at both choice and re-check, `countering-spells.md` §9.2). Each is a loud gate or a
documented absence.

---

## 13. Open questions for the architect

1. **`ChooseTargets` reuse vs. a new request kind** (§4). Recommendation: reuse. It is the main call in
   this note, it saves ~25 compile-breaking sites, and it keeps a training agent's observed action
   space free of engine-internal distinctions. The counter-argument — that a driver can no longer tell
   from the request alone whether it is targeting for a cast or a trigger — is real, and answered by
   the state the driver already receives.
2. **`TargetSpec.TargetOpponent` in this packet, or wait for W1-C** (§1.1, §9). Recommendation: take it
   here; without a restricted spec the framework has no real card to prove itself on, and a fixture-only
   proof is exactly what a framework packet should not settle for.
3. **Should `pendingTriggerTargets` appear on `SeatView`?** Recommendation: yes, for consistency with
   the other thirteen `pending*` fields. Nothing in it is hidden — the controller, the source id, and
   the source card are all already exposed through `SeatView.pendingTriggers` (ADR-007). The same
   question `countering-spells.md` open question 8 asks of `PendingCounterPayment`, with the same
   answer.
4. **Does `PendingTriggerTargets` need to name its trigger explicitly** rather than deriving it as the
   front of the controller's group (§3.2)? Recommendation: derive, and pin the derivation with the new
   invariant. An explicit index would have to be maintained across the reorder and would be the second
   thing that could drift.
5. **Should the CR 603.4 intervening-if check land with this packet?** It is the other half of CR 603.3
   the triage warns about (trap T9), it touches the same functions, and Faerie Miscreant is Tier 1.
   Recommendation: no — it is a separate rule with a separate failure mode (checked twice, at fire and
   at resolution), and fusing it would make this packet two frameworks. Flagged.
