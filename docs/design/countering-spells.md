# Design note — countering spells (F1)

The reference for the countering framework: what "counter" means, how a spell on the stack becomes a
target, and how the fifteen counter-adjacent cards of the gauntlet decompose into sequenced packets.
Written to PLAN.md §5's rule that a framework packet gets a design note reviewed **before**
implementation starts, in the style of `docs/design/layer-system.md`.

Two things anchor the design. Downward, CR 701.5 (the counter action) and CR 608.2b (the
target re-check) — two rules that produce nearly the same state transition for entirely different
reasons, and whose conflation is the central trap here. Upward, the seams the engine already cut:
`legalTargets`/`isTargetLegal` (`Targets.kt`) as the single source of target-legality truth, the
`Pending*` mid-resolution pause pattern (`ResolutionDiscard.kt` and friends), and the
`castVia.exilesOnLeaveStack` hook whose KDoc has promised since P5.2 that it covers "resolution,
**countering**, and fizzling". Countering is the first client of that promise.

The upstream request for this framework is `mtg_ai`'s `ENGINE-CARD-BRIEF.md` §4 "F1". That brief is
a proposal, not authority, and it says so: its groupings were derived from card names rather than
oracle text. §1 below records where checking the oracle text changed the answer.

---

## 1. The oracle text, and four places the brief is wrong

Fetched from Scryfall (`POST /cards/collection`, 2026-08-24), all fifteen found, no ambiguity:

| Card | Cost | Oracle text | Shape |
|---|---|---|---|
| Counterspell | `{U}{U}` | Counter target spell. | unrestricted |
| Dispel | `{U}` | Counter target **instant** spell. | restricted target |
| Negate | `{1}{U}` | Counter target **noncreature** spell. | restricted target |
| Annul | `{U}` | Counter target **artifact or enchantment** spell. | restricted target |
| Envelop | `{U}` | Counter target **sorcery** spell. | restricted target |
| Remove Soul | `{1}{U}` | Counter target **creature** spell. | restricted target |
| Blue Elemental Blast | `{U}` | Choose one — • Counter target **red** spell. • Destroy target **red** permanent. | modal + restricted target |
| Red Elemental Blast | `{R}` | Choose one — • Counter target **blue** spell. • Destroy target **blue** permanent. | modal + restricted target |
| Steel Sabotage | `{U}` | Choose one — • Counter target **artifact** spell. • Return target artifact to its owner's hand. | **modal** + restricted target |
| Force Spike | `{U}` | Counter target spell **unless its controller pays `{1}`**. | unless-pay |
| Spell Pierce | `{U}` | Counter target **noncreature** spell **unless its controller pays `{2}`**. | restricted + unless-pay |
| Hydroblast | `{U}` | Choose one — • Counter target spell **if it's red**. • Destroy target permanent **if it's red**. | modal + **conditional effect** |
| Pyroblast | `{R}` | Choose one — • Counter target spell **if it's blue**. • Destroy target permanent **if it's blue**. | modal + **conditional effect** |
| Prohibit | `{1}{U}` | **Kicker `{2}`.** Counter target spell **if its mana value is 2 or less**. If this spell was kicked, counter that spell if its mana value is 4 or less instead. | **kicker** + conditional effect |
| Spellstutter Sprite | `{1}{U}` | Flash. Flying. When this creature enters, counter target spell with mana value X or less, where X is the number of Faeries you control. | **targeted trigger** + dynamic restricted target |

### 1.1 Disagreement — "cost-conditional counters … need the *paid cost* of a spell on the stack to be inspectable"

**The brief is wrong twice over, and it groups three cards that do not belong together.** No card in
the list inspects a paid cost.

- Force Spike and Spell Pierce demand a **new payment by the countered spell's controller** — an
  amount printed on the counter itself (`{1}`, `{2}`), unrelated to what the target cost or what was
  paid for it. Nothing about the target's cost is read.
- Prohibit reads **mana value** (CR 202.3, CR 203) — a characteristic derived from the *printed*
  mana cost (CR 202.3b), explicitly **not** from what was paid. A Fiery Temper cast for its madness
  cost still has mana value 3. The engine already exposes this correctly:
  `PrintedCharacteristics.manaValue`, and `castVia` never rewrites `characteristics.manaCost`.

So the capability the brief asks for is not needed, and the capability that *is* needed already
exists. Meanwhile the genuinely hard part of Prohibit — **kicker** (CR 702.33), an optional
additional cost the engine has no framework for whatsoever — the brief does not mention at all.
Because the brief mis-derived the requirement, it also mis-ordered the work: Prohibit is the *most*
expensive card in the list, not one of three cheap siblings, and it belongs behind a kicker packet
(the brief's own F8), not inside F1.

*Consequence for the design:* there is no "paid cost on the stack entry" field in §5, and Prohibit
is packet F1.6 (§11), gated on kicker.

### 1.2 Disagreement — the four Blasts are two different cards wearing the same art

The brief treats Blue/Red Elemental Blast, Hydroblast, and Pyroblast as one group ("modal — counter
or destroy — and colour-conditional"). The oracle text splits them cleanly:

- **Blue/Red Elemental Blast** restrict the **target**: "Counter target *red* spell", "Destroy
  target *red* permanent". A non-red spell is not a legal target, so under ADR-005 the mode is not
  enumerable at all when no red object exists — and if the target stops being red, CR 608.2b
  fizzles the Blast.
- **Hydroblast/Pyroblast** restrict the **effect**: "Counter target spell *if it's red*". The target
  spec is unrestricted. Pyroblast may legally target a Forest, resolve, and do nothing.

This is not pedantry; it changes enumeration. `targetsAvailable` (`ActionEnumeration.kt`) excludes a
cast with no legal target, so a Blue Elemental Blast with no red object on either the stack or the
battlefield is correctly absent from the priority window — while a Pyroblast in the same position
must still be offered, because casting it is legal (and, against a real opponent, sometimes right as
a bluff or to bin a dead card). An implementation that copied Blue Elemental Blast's shape onto
Pyroblast would produce a **gap in enumeration completeness** — precisely the failure ADR-005's
tested property exists to catch.

### 1.3 Disagreement — Steel Sabotage is modal

The brief lists Steel Sabotage among the plain restricted-target counters, and separately calls "the
Blasts" the modal ones. Steel Sabotage is modal (CR 700.2): "Choose one — Counter target artifact
spell; or Return target artifact to its owner's hand." It therefore needs the same CR 601.2b mode
machinery as the Blasts, and its two modes have **different target types** (a spell on the stack vs.
a permanent), which means mode choice must precede target enumeration. It cannot be encoded in the
restricted-target packet.

### 1.4 Disagreement of emphasis — Spellstutter Sprite needs a framework the brief never names

The brief correctly says Spellstutter "couples countering to a board-state condition (Faerie count),
so build the plain counter first and the conditional variant second." True, but understated. The
blocking fact is that **the engine's triggered abilities cannot target at all**:
`TriggeredAbility` has no `targetSpec`, `PendingTrigger` carries no targets, and `resolveAbility`
(`AbilityResolution.kt`) hands every trigger `persistentListOf()` with the KDoc "A triggered ability
has no targets in the MVP pool (CR 603.3d), so no CR 608.2b re-check applies." Spellstutter needs
CR 603.3d target choice at trigger-placement time *and* a CR 608.2b re-check on ability resolution —
a trigger-framework extension, not a counter-framework one. §9 and packet F1.7.

### 1.5 What §1 means for the framework ordering

The brief's F1 card list is **not one framework — it is four**: countering (11 cards), modality
(4 cards), kicker (1 card), and targeted triggered abilities (1 card). Only the first is what this
note designs. The other three are named where they bite and sequenced behind it in §11, and the
architect should consider hoisting modality and kicker out of F1 entirely (open question 7).

---

## 2. The counter action (CR 701.5)

**To counter a spell is to remove it from the stack so that it does not resolve** (CR 701.5a). The
full content of the action, and what it deliberately is not:

- **The spell does not resolve.** None of its instructions are performed — including its own
  conditional and "unless" clauses. A countered Force Spike never asks anyone to pay.
- **It goes to its owner's graveyard** (CR 701.5a) as a **new object** (CR 400.7), exactly like the
  CR 608.2m move a resolved instant makes. In this engine that means a fresh `ObjectId`, which is
  what makes §4's "already left the stack" case correct by construction.
- **Its costs stay paid.** There is no refund (CR 701.5a). Mana spent is spent; a sacrificed
  Mountain stays sacrificed; life paid stays paid. Nothing to implement — but it is the reason a
  countered spell must *not* run any part of the cast pipeline in reverse.
- **It was still cast.** "Whenever a player casts a spell" triggers (CR 603.2) fired at CR 601.2i and
  are unaffected by the counter. The engine already has this shape — `TriggerCondition.SpellCast`
  with a filter — and a countered spell must leave those triggers on the stack. Free, provided the
  counter is implemented as a stack removal rather than as a rewind.
- **It is not "destroy" (CR 701.7).** Destruction applies only to permanents on the battlefield, is
  replaceable by regeneration and stopped by indestructible, and has nothing to do with the stack.
  The Blasts' second mode *is* destruction (§8), and the two halves of one card must not share a
  code path.
- **It is not a zone-change replacement client in the ordinary way** — with one exception the engine
  already models: a spell cast via a permission with `CastingPermission.exilesOnLeaveStack`
  (flashback, CR 702.34e) is **exiled instead of going to the graveyard** when it leaves the stack
  *for any reason*, countering included. `StackEntry.Spell.castVia`'s KDoc already says this.

**Decision — a published rules primitive, `counterSpell(state, targetId)`, in
`mtg-rules/effect/CounterSpell.kt`**, alongside `dealDamage`, `returnToOwnersHand`, and the rest.
Signature shape: state plus the stack object's id, returning the successor state; it locates the
entry by id, removes it from the stack at its index, and puts its card into its owner's graveyard
(or exile, per `castVia`) as a fresh object, emitting `GameEvent.SpellCountered`. Card definitions
compose it (ADR-003 vocabulary discipline); `mtg-rules` never names Counterspell.

**Decision — the countered spell is located by id, not by stack position.** A countered spell is
almost always directly below the counter, but nothing guarantees it: two counters can stack above
one spell, and Spellstutter's trigger sits above whatever was on the stack when it fired. See §10 for
the existing assertion this breaks.

---

## 3. Countered (CR 701.5) vs. did-not-resolve (CR 608.2b)

These produce the same *state transition* and are different *game events*. The engine must reuse the
first and separate the second.

| | Countered (CR 701.5) | Did not resolve, all targets illegal (CR 608.2b) |
|---|---|---|
| When | While **another** object resolves | When **this** object begins to resolve |
| Position on the stack | Anywhere below the resolving object | Necessarily the topmost object (CR 608.1) |
| Cause | An effect that says "counter" | The game rules, on the CR 608.2b re-check |
| Where the card goes | Owner's graveyard (CR 701.5a) | Owner's graveyard (CR 608.2b) |
| Flashback exile-instead | Yes (CR 702.34e) | Yes (CR 702.34e) |
| Is it "countered"? | **Yes** | **No** — modern CR 608.2b says "doesn't resolve. It's removed from the stack", deliberately dropping the older "is countered" wording |

That last row is the whole point. The two verdicts were the same thing in pre-2010 templating and
are not now; a card that watches for "whenever a spell is countered" does not see a fizzle, and a
spell that "can't be countered" still fizzles for lack of a legal target. Nothing in *this* pool
observes the difference — which is exactly the condition under which a wrong merge survives review
and ships as silently-wrong-but-plausible behaviour (PLAN.md §7).

**Decision — reuse the mechanism, split the verdict.**

- **Reuse, and it is right:** `isTargetLegal`/`legalTargets` (`Targets.kt`) — a Counterspell whose
  target has left the stack is an ordinary CR 608.2b fizzle and needs no new logic at all (§4).
  Reuse also the *zone move*: the graveyard-or-exile logic inside
  `StackResolution.putResolvedSpellOffStack`, generalised (§10).
- **Do not reuse, and it is a trap:** `GameEvent.SpellFizzled`. A countered spell gets its own
  `GameEvent.SpellCountered`; a fizzled one keeps `SpellFizzled`. One event carrying both, or a
  boolean discriminator, buries the distinction in a field nobody reads. Two events also make the
  acceptance assertions unambiguous — `FizzleVerdictAcceptanceSpec` asserts on `SpellFizzled`
  counts today, and a merged event would silently change its meaning.

---

## 4. Targeting a spell on the stack

The engine targets players (CR 115.1a) and battlefield permanents (CR 115.1b). A spell on the stack
is a third kind of targetable object (CR 115.1, CR 111.1 — a spell *is* an object).

**Decision — `Target.SpellOnStack(id: ObjectId)`, a new member of the existing sealed `Target`.**
The id is the object's id for its **stack residence**, freshly minted by `proposeSpell` (CR 400.7)
and dying with that residence. Three consequences, all of which the engine gets for free:

- **A spell that has left the stack can never be matched.** When a spell resolves or is countered it
  is reborn in the graveyard with a *different* id (`putResolvedSpellOffStack` allocates one). A
  stale `Target.SpellOnStack` therefore names nothing in any zone; it cannot accidentally address the
  graveyard card. Freshness-per-residence, already load-bearing everywhere else, does this work.
- **CR 608.2b then handles the interesting case with no new code.** `legalTargets` for a
  stack-targeting spec scans the current stack; a target that has left is absent from the
  enumeration, `isTargetLegal` is membership in that enumeration, so a Counterspell whose target
  resolved (or was countered by someone else's counter underneath it) fizzles. That is the correct
  answer, and it arrives through the mechanism that already exists.
- **Abilities are structurally untargetable.** `StackEntry.Ability` and
  `StackEntry.ActivatedAbilityOnStack` carry no card object and no residence id (CR 113.7a), so no
  `Target.SpellOnStack` can name one. Nothing in this pool counters an ability (that is Stifle
  territory), and the type system makes the mistake unrepresentable. When an ability-countering card
  arrives it needs a stack-entry identity distinct from `ObjectId` — flagged, not built.

**Decision — target legality for a stack target is *not* re-derived from a zone scan but from the
stack itself**, via the same `legalTargets` function extended with the new spec. The single-source
rule (`Targets.kt`'s file comment: "'legal' is defined *by* that enumeration") is what keeps cast
time and resolution time from drifting, and it is worth more here than anywhere else, because the
stack is the one zone that churns during a single priority round.

**The self-targeting seam — a real ordering divergence, and it must be closed explicitly.** The
engine's cast pipeline is atomic: while a `PendingCast` gathers decisions the card is *still in its
source zone*, so the `ChooseTargets` enumeration runs with the counter **in hand**. But
`CastingPipeline.executeCastPipeline` runs `proposeSpell` (CR 601.2a, card onto the stack) *before*
`establishTargets` (CR 601.2c, re-validation) — so the re-validation runs with the counter **on the
stack**, where an unrestricted "target spell" enumeration would include the counter itself. The two
computations disagree about one element. Today no spec can express this, so it has never mattered.

**Decision — `legalTargets` for a stack-targeting spec takes the casting/resolving object's id and
excludes it.** A spell is never a legal target for itself. This closes the divergence at its
source rather than papering over it at one call site, and it keeps the invariant that the
enumeration set is the same set at cast time and at re-validation. Flagged as open question 1
because it changes `legalTargets`' signature, which several call sites share.

---

## 5. What a stack entry must expose

Every predicate in §6–§9 is a question about the spell on the stack. The inventory, exhaustively,
across all fifteen cards:

| Question | Card(s) | Source | CR |
|---|---|---|---|
| Is this entry a **spell** (not an ability)? | all | `StackEntry` sealed `when` | 111.1, 113.7a |
| Its **card types** | Dispel, Negate, Annul, Envelop, Remove Soul, Steel Sabotage | `definition.characteristics.cardTypes` | 205.2 |
| Its **colours** | the four Blasts | `characteristics.colors` (derived from mana cost) | 105, 202.2 |
| Its **mana value** | Prohibit, Spellstutter Sprite | `characteristics.manaValue` | 202.3, 203 |
| Its **controller** | Force Spike, Spell Pierce | `StackEntry.Spell.controller` | 108.4 |

Every one already exists on `StackEntry.Spell` via the definition captured at cast time. **No new
field on the stack entry is required by any of the fifteen cards.**

**Decision — reads go through one new accessor, `spellCharacteristics(state, entry)` in
`mtg-rules`, not through `entry.definition.characteristics` directly.** Today it returns the printed
characteristics unchanged. It exists because CR 613 applies to spells on the stack as much as to
permanents, and the engine's `layeredCharacteristics` is battlefield-only; when the first
type-changing or colour-changing effect arrives, one function body changes and every counter
predicate follows. This is the same discipline P3.1 used with `effectiveKeywords`/`effectivePower`,
and the reason those seams survived Phase 4 untouched.

**Flagged: colour is derived from the mana cost only.** `PrintedCharacteristics.colors` is
`manaCost?.colors ?: emptySet()`, and its KDoc records that colour indicators (CR 204) are
deliberately unmodeled. Every card the Blasts care about in this gauntlet is coloured by its cost, so
this is correct today — but a colour-indicator card, or any card whose colour is not its cost's
colour, would be silently mis-Blasted. Colour is the first characteristic a counter predicate reads
that the engine derives rather than stores; it deserves a loud gate or a note, not silence.

---

## 6. Restricted-target counters, as a predicate

Six cards (plus two Blast modes and one Steel Sabotage mode) counter only a subset of spells. Every
one of them expresses the subset as a **targeting restriction**: an ineligible spell is not a legal
target at all, so it can never be chosen (ADR-005 exclusion), and a spell that stops qualifying makes
the counter fizzle (CR 608.2b).

**Decision — `TargetSpec.SpellOnStack(restriction: SpellRestriction)`, with `SpellRestriction` a new
sealed noun in `mtg-core/definition/`.** Core declares *what* the restriction is; `mtg-rules`
decides what satisfies it (ADR-009: no game-rule decisions in core), exactly as
`TargetSpec.Enchantable`/`EnchantRestriction` already split.

| Member | Cards |
|---|---|
| `Any` | Counterspell, Force Spike, Prohibit, Hydroblast, Pyroblast |
| `OfCardType(CardType)` | Dispel (`INSTANT`), Envelop (`SORCERY`), Remove Soul (`CREATURE`), Steel Sabotage mode 1 (`ARTIFACT`) |
| `NotOfCardType(CardType)` | Negate, Spell Pierce (`CREATURE`) |
| `OfAnyCardType(Set<CardType>)` | Annul (`ARTIFACT`, `ENCHANTMENT`) |
| `OfColor(Color)` | Blue Elemental Blast (`RED`), Red Elemental Blast (`BLUE`) |
| `ManaValueAtMost(Magnitude)` | Spellstutter Sprite (dynamic; §9) |

Two things to note about the shape. First, `ManaValueAtMost` reuses the existing
`dev.mtgplay.core.definition.Magnitude` (`Fixed`/`Dynamic`) from the layer system — the same "pure
function of `GameState`" pattern that Ethereal Armor's enchantment count uses, so Spellstutter's
Faerie count introduces no new concept. Second, `NotOfCardType` is a real member rather than a
generic negation combinator: Negate's "noncreature" is the only negation in the pool, and a
combinator algebra (`And`/`Or`/`Not`) is speculative structure with nothing to constrain it. If a
third card needs composition, that is when the algebra earns its place.

**These predicates restrict targeting, not effect.** Prohibit and Hydroblast/Pyroblast look similar
in English and are not the same thing — §7.2.

---

## 7. Cost-conditional and condition-conditional counters

Two shapes hide behind the brief's single "cost-conditional" heading.

### 7.1 "unless its controller pays {N}" — Force Spike, Spell Pierce

The counter resolves; before it takes effect, the **target spell's controller** may pay. This is the
first decision in the engine made by someone other than the resolving object's controller, and it is
the part most likely to change existing types.

*Why it cannot be a `ResolutionEffect`.* `ResolutionEffect` is `(GameState, ResolutionContext) ->
GameState`, pure and total. A payment is a decision, and ADR-004 forbids callbacks: "a choice
mid-resolution … must surface as a `DecisionRequest`". The engine already has the exact pattern for
this — a declarative clause on `SpellDefinition` that the engine orchestrates, alongside
`libraryReveal` (CR 701.16), `optionalCostThenDraw` (CR 601.3b), and `drawThenDiscard` (CR 601.2c),
each with a `Pending*` record in `GameState`, a branch in `midTransitionPauseRequest`
(`PendingDecision.kt`), and an apply function. `ResolutionDiscard.kt` is the model to copy: ~60 lines,
three functions (orchestrate → request → apply).

**Decision — a declarative `counterUnlessPaid: CounterUnlessPaid?` on `SpellDefinition`**, carrying
the amount as a `ManaCost`, plus:

- **New core noun** `PendingCounterPayment(decider: PlayerId, cost: ManaCost, counteredObjectId: ObjectId)`
  in `mtg-core/state/`, and a `pendingCounterPayment` field on `GameState` (the fourteenth `pending*`
  field; the pattern is well worn).
- **New request** `DecisionRequest.ChooseOptionalPayment`, a `Decision.SingleSelect` whose options are
  **decline at index 0** (mirroring `ChooseYesNo.DECLINE`) followed by the enumerated
  `PaymentPlan`s from `enumeratePaymentPlans`. **One fused request, not a yes/no followed by a
  payment request.** A separate yes/no would have to offer "yes" to a player who cannot pay — an
  option that dead-ends mid-flow, which ADR-005 forbids ("illegal actions are unrepresentable rather
  than rejected"). Fusing makes the option set exactly the legal answers: with no affordable plan the
  request has one option, and per the `ChoosePaymentPlan` precedent ("always surfaced, even when
  exactly one plan exists … a uniform decision sequence keeps replay logs canonical") it is still
  surfaced.
- **No new payment machinery.** `payManaPlan` (`CastCostPayment.kt`) is already shared by casts,
  activations, and plot; a fourth caller is a call, not a change. Mana abilities may be activated for
  this payment because a resolving spell asked for it (CR 605.3b), and they resolve immediately
  without using the stack (CR 605.3a) — the existing declarative-plan execution model transfers
  unchanged. Any excess mana floats until the step ends (CR 500.4), already a declared exception in
  the mana-pool invariant.

*Two rules subtleties the tests must pin.* (a) The payment is not a cast and grants nobody priority —
the opponent cannot respond to the decision to pay. (b) If the payment is declined **or impossible**,
the spell is countered; if it is made, the counter's own effect simply does nothing and the counter
still goes to its owner's graveyard as a resolved spell (`SpellResolved`, not `SpellCountered`).

*And the interaction that makes the fizzle/counter split observable:* Spell Pierce is
`NotOfCardType(CREATURE)` **and** unless-pay. If the target becomes an illegal target before Spell
Pierce resolves, the CR 608.2b path runs and **nobody is ever asked to pay** — the pause must not be
entered for a spell that is about to fizzle. Ordering inside `resolveSpell` matters: fizzle check
first, orchestration second. That is already the order in `resolveSpell`; the note is that it must
stay so.

### 7.2 "if its mana value is N or less" / "if it's red" — Prohibit, Hydroblast, Pyroblast

**These are not targeting restrictions.** The target spec is unrestricted; the *effect* is
conditional, evaluated on resolution (CR 608.2). Pyroblast legally targets a white spell, resolves,
counters nothing, and goes to the graveyard.

**Decision — conditional-effect counters are ordinary `ResolutionEffect`s that compose the published
`counterSpell` primitive behind a guard**, using the published `spellCharacteristics` accessor for
the predicate. This is card-side composition of published primitives, not a card-local special
case — the same latitude `layer-system.md` §2 grants Ethereal Armor's dynamic magnitude ("a pure
function of the current `GameState`"). No new engine concept, no pause, no new state.

The enumeration consequence is the important one, and it is the §1.2 finding restated as a test:
Pyroblast **must** appear in the priority window whenever any spell is on the stack, regardless of
its colour. An enumeration that filters by the condition is a gap; ADR-005's completeness property
is what catches it.

Prohibit additionally needs kicker (CR 702.33) and the kicked-ness recorded on the cast record as
linked information — the same shape as `StackEntry.Spell.discardedForCost`. That is a cost
framework, not a counter framework, and it is why Prohibit is last among the non-trigger cards.

---

## 8. The modal Blasts and Steel Sabotage (CR 700.2)

Four cards are modal. `CastingPipeline.chooseModes` is currently a documented no-op:

> Stage CR 601.2b — modes. A documented no-op hook: no modal spell exists in the MVP … When modal
> spells arrive, this stage gains the mode decision.

This is that arrival. What the modal half needs that the counter half does not:

- **A mode decision at CR 601.2b, before targets (CR 601.2c).** The order is fixed by the rules and
  is load-bearing here: Steel Sabotage's two modes target different *kinds* of object (a spell on the
  stack vs. an artifact permanent), so target enumeration is undefined until the mode is known.
  Mechanically this is a fourth field on `PendingCast` (`chosenMode: Int?`) and a branch in
  `pendingCastRequest`, ahead of the existing targets branch — the same gather-one-decision-at-a-time
  shape the record already has.
- **Mode-dependent castability.** A modal spell is castable iff **at least one** mode is fully
  legal (CR 601.2b: you must choose a legal mode; CR 601.2c: each chosen mode's targets must be
  choosable). `targetsAvailable` currently answers one spec; it must answer "some mode's spec".
  Blue Elemental Blast with a red permanent but no red spell offers exactly one mode.
- **A `destroy` primitive (CR 701.7), which does not exist.** `StateBasedActions.kt` destroys
  creatures with lethal damage, and `CreatureDeathCause` already distinguishes destruction from
  zero-toughness for the sake of a regeneration/indestructible hook that has no clients yet — those
  are SBAs, not an effect-invoked destroy. Blue/Red Elemental Blast destroy *any* permanent of a
  colour, not just creatures, so the primitive must handle a non-creature permanent's death and its
  aura fall-off (CR 704.5m) too.
- **Steel Sabotage's second mode needs nothing new**: `returnToOwnersHand` already exists.

Modality is a framework in its own right, is needed by cards well outside F1 (the brief's F8), and
should be sequenced accordingly.

---

## 9. Spellstutter Sprite, and why it is last

Spellstutter is a Faerie Wizard with flash and flying whose enters-the-battlefield trigger counters
target spell with mana value X or less, X being the Faeries its controller controls. The plain
counter must be built first, and the reason is stronger than "it is more complicated":

1. **The trigger framework cannot target.** `TriggeredAbility` has no `targetSpec`; `PendingTrigger`
   has no `targets`; `resolveAbility` passes the empty list and documents that no CR 608.2b re-check
   applies. Spellstutter needs targets chosen **as the ability is put on the stack** (CR 603.3d) —
   which happens in `TriggerPlacement.kt`, before any player receives priority — and re-checked on
   resolution (CR 608.2b). That is a genuine extension of P5.1's framework, touching trigger
   placement, `PendingTrigger`, `StackEntry.Ability`, `PendingTriggerView`, and `resolveAbility`.
2. **X is dynamic and evaluated twice.** It is computed when the target is chosen (to know which
   spells are legal targets) and again on resolution (the CR 608.2b re-check, because the legal set
   may have shrunk). If the Faeries die in response, a previously legal target becomes illegal and the
   trigger fizzles. `Magnitude.Dynamic` gives this for free *provided* the restriction is evaluated
   live rather than snapshotted — the same compute-on-read decision `layer-system.md` §5 made, and for
   the same reason.
3. **It is a permanent spell that counters.** Countering Spellstutter Sprite itself means the creature
   never enters and no trigger ever fires — the tidy end-to-end acceptance case that only exists once
   both halves are built, and a good final test of the whole framework.

Building the trigger-targeting extension first would mean building it with no card to exercise it and
no counter primitive for it to invoke. Building the plain counter first means Spellstutter is
composition.

---

## 10. Blast radius — what this touches, by file and type

**`mtg-core` (new nouns, all additive):**

- `state/Target.kt` — new `Target.SpellOnStack`. `Target` is sealed and exhaustively matched, so this
  **breaks compilation** in `rules/effect/DealDamage.kt`, `acceptance/replay/FingerprintRenderers.kt`,
  the CLI's target menus, and `protocol/`'s target DTO. That is the design working: each site must
  say what a stack target means to it (for damage: loudly unsupported).
- `definition/TargetSpec.kt` — new `TargetSpec.SpellOnStack(SpellRestriction)`; breaks the `when`s in
  `rules/engine/Targets.kt`, `ActionEnumeration.targetsAvailable`,
  `CastingPipeline.establishTargets`, `StackResolution.auraAttachmentTargetOf`, and
  `acceptance/invariant/InvariantChecker.kt`.
- `definition/SpellRestriction.kt` — new sealed noun (§6).
- `definition/SpellDefinition.kt` — new optional `counterUnlessPaid` (F1.3); later `modes` (F1.4) and
  `kicker` (F1.6).
- `state/PendingCounterPayment.kt` + a `pendingCounterPayment` field on `state/GameState.kt`.
- `event/GameEvent.kt` — new `SpellCountered`.

**`mtg-rules`:**

- `engine/StackResolution.kt` — **the two invasive changes.**
  (a) `resolveSpell`'s `require(resolved.sharedZones.stack == state.sharedZones.stack)` ("a
  resolution effect must not move the resolving spell") is **false for every counter**: a counter's
  whole job is to modify the stack. It must be relaxed to what it actually means — *the resolving
  entry is still the topmost object* — not deleted. The identical `require` in
  `AbilityResolution.resolveAbility` (CR 113.7a) needs the same treatment for Spellstutter.
  (b) `putResolvedSpellOffStack` asserts `stack.lastOrNull() == entry` and removes at `lastIndex`.
  A countered spell is **not** topmost. Extract the graveyard-or-exile-as-a-new-object logic into a
  position-agnostic helper that both the CR 608.2m/608.2b path (topmost, keeps its assertion) and the
  CR 701.5a path (by id, anywhere) call.
- `engine/Targets.kt` — `legalTargets`/`isTargetLegal` gain the stack spec and (per §4) the
  self-exclusion parameter; `satisfiesSpellRestriction` joins `satisfiesEnchantRestriction`.
- `engine/SpellCharacteristics.kt` (new) — the §5 seam.
- `effect/CounterSpell.kt` (new), `effect/Destroy.kt` (new, F1.4).
- `engine/CounterPayment.kt` (new, F1.3) — the three-function orchestrate/request/apply trio.
- `engine/PendingDecision.kt` — one branch in `midTransitionPauseRequest`.
- `decision/DecisionRequest.kt` — `ChooseOptionalPayment`; `engine/DecisionValidation.kt` and
  `engine/DecisionApplication.kt` gain its cases.
- `StackEntryView.kt` / `SeatView.kt` / `ViewFor.kt` / `DecisionView.kt` — pass-through only. **ADR-007
  costs nothing here:** the stack is public (CR 405), targets are public (CR 115.1), the countered
  card lands in a public graveyard, and a payment request reveals only the decider's own board. No new
  hidden information exists, so the view filter needs no new rule — but `ViewLeakPropertySpec` should
  still cover the new members.
- `engine/CastingPipeline.kt` / `engine/PendingCastRequest.kt` / `state/PendingCast.kt` — modes, F1.4
  only.
- `engine/TriggerPlacement.kt`, `engine/AbilityResolution.kt`, `core/definition/TriggeredAbility.kt`,
  `core/state/PendingTrigger.kt`, `PendingTriggerView.kt` — targeted triggers, F1.7 only.

**`mtg-protocol` / `mtg-cli` / `mtg-server`:** every new `DecisionRequest` member and every new
`Target` member is mirrored in `DecisionRequestDto.kt`, `DecisionRequestToDto.kt`,
`DecisionRequestToDomain.kt` (+ `DecisionRequestRoundTripSpec`), the CLI's `MenuRenderer.kt`,
`DecisionInput.kt`, `SelectionMenus.kt`, `SelectionInput.kt`, `DefaultDecision.kt`,
`RandomLegalChooser.kt`, and `server/client/RandomRemoteAgent.kt`. Mechanical, but it is ~10 files per
new request member — budget for it, and prefer **one** new request (§7.1's fused shape) over two.

**`mtg-acceptance` — blast radius on existing tests:**

- `driver/RandomLegalResponder.kt` and `driver/Responders.kt` need a case for the new request, or
  every fuzz seed that reaches a counter payment throws.
- `fuzz/EnumerationProbe.kt` must probe the new request, or ADR-005 completeness is unenforced
  exactly where it is most likely to be wrong (§1.2's Pyroblast gap).
- `invariant/Invariant.kt` + `invariant/PendingResolutionInvariant.kt` — `PENDING_RESOLUTION_SANITY`
  gains the counter payment: decider is seated, and the stack is non-empty (the resolving counter must
  still be on it).
- `replay/Fingerprint.kt` — a `pendingCounterPayment` token, matching the twelve existing `pending*`
  tokens. Cheap: no golden fingerprint strings are stored anywhere, and every replay test compares a
  run against its own re-run, so adding a token rebaselines nothing.
- `FizzleVerdictAcceptanceSpec.kt` — unchanged, and it must **stay** unchanged and green. It is the
  regression guard for §3: if implementing countering changes what that spec asserts, the two verdicts
  have been merged.
- `ResponseWarAcceptanceSpec.kt`, `DeathMidStackAcceptanceSpec.kt` — the natural homes for the new
  counter-war scenarios (§12).
- Existing fuzz corpora (`MvpMatchupCorpusSpec`, `MixedMatchupCorpusSpec`, `BoglesAuraCorpusSpec`) are
  **unaffected** as long as the two MVP decklists gain no counters; they are Bogles and Mono-Red. The
  new cards need their own corpus decklist, per the brief's own point about sideboard cards never
  being exercised by the fuzzer.

---

## 11. Packet decomposition

Sequenced so that each packet is independently testable and every primitive lands before any card
composes it. Framework packets are serial (PLAN.md §5: "one agent at a time"); the card packets
inside F1.2 are parallelizable.

- **F1.0 — this design note.** Architect review before F1.1 starts.
- **F1.1 — Stack targeting and the counter primitive** (`mtg-rules` + `mtg-core`). `Target.SpellOnStack`,
  `TargetSpec.SpellOnStack` with the full `SpellRestriction` vocabulary, `spellCharacteristics`,
  `counterSpell`, `GameEvent.SpellCountered`, the two relaxed `require`s and the position-agnostic
  off-stack move, self-exclusion, view/protocol/CLI pass-through, invariant + fingerprint updates.
  Exercised entirely by **`mtg-rules` fixture spells** — no card names (`FixtureCards.kt` is the
  existing home). *Accept:* a fixture counter removes a spell from mid-stack to its owner's graveyard
  and its effect never runs; a fixture counter whose target left the stack fizzles with `SpellFizzled`
  and **not** `SpellCountered`; a countered flashback fixture is exiled (CR 702.34e); cast triggers
  that fired for the countered spell still resolve; a counter cannot target itself; each
  `SpellRestriction` member excludes the right spells from enumeration.
- **F1.2 — The six pure counters** (`mtg-cards`). Counterspell, Dispel, Negate, Annul, Envelop, Remove
  Soul. Mechanical, parallelizable in two batches of three. *Accept:* per-card targeting legality and
  a counter-resolves test each; Negate cannot target a creature spell and Remove Soul cannot target a
  noncreature one.
- **F1.3 — Unless-pay** (`mtg-rules` + `mtg-cards`). `PendingCounterPayment`, `ChooseOptionalPayment`,
  the orchestration trio; then Force Spike and Spell Pierce. *Accept:* the target's controller — not
  the counter's — is the decider; paying makes the counter resolve with no effect; declining counters;
  an unaffordable payment surfaces a decline-only request and counters; a target that becomes illegal
  fizzles **without** opening the pause; the mana pool is empty at end of step (CR 500.4).
- **F1.4 — Modality and destroy** (`mtg-rules` + `mtg-cards`). CR 601.2b modes on `PendingCast`,
  mode-dependent castability, the CR 701.7 `destroy` primitive; then Steel Sabotage, Blue Elemental
  Blast, Red Elemental Blast. *Accept:* mode is chosen before targets; a card with one legal mode
  offers one; Blue Elemental Blast is absent from enumeration with no red object anywhere; destroy
  kills a non-creature permanent and its auras fall off (CR 704.5m).
- **F1.5 — Conditional-effect counters** (`mtg-cards`, on F1.4). Hydroblast and Pyroblast. *Accept:*
  Pyroblast is enumerable targeting a non-blue spell, resolves, and counters nothing; targeting a blue
  spell it counters. Could merge into F1.4; kept separate because §1.2 is the finding most likely to
  be implemented wrongly and deserves its own review.
- **F1.6 — Kicker, then Prohibit** (`mtg-rules` + `mtg-cards`). CR 702.33 optional additional cost,
  kicked-ness as linked information on the cast record; then Prohibit. **Candidate for hoisting out of
  F1** into the brief's F8 — kicker serves several gauntlet decks and nothing about it is about
  countering. *Accept:* mana value is read from the printed cost even for a spell cast at an
  alternative cost.
- **F1.7 — Targeted triggered abilities, then Spellstutter Sprite** (`mtg-rules` + `mtg-cards`).
  CR 603.3d target choice at trigger placement, CR 608.2b re-check on ability resolution,
  `ManaValueAtMost(Magnitude.Dynamic)`; then the Sprite. *Accept:* X recomputes live between placement
  and resolution; killing the Faeries in response makes the trigger fizzle; countering the Sprite spell
  means no trigger ever fires.
- **F1.8 — Counter corpus** (`mtg-acceptance`). A dedicated blue decklist plus a counter-heavy mirror
  in the fuzz corpus, per the brief's "put sideboard cards into dedicated test decklists" point.
  *Accept:* thousands of seeds clean under the invariant checker and the enumeration probe.

---

## 12. Test strategy

**CR-cited unit tests (F1.1–F1.7)** per CONVENTIONS.md. The named scenarios that carry the design:

- *Counter war.* A counters B's spell; B counters A's counter. The bottom spell resolves. Exercises
  mid-stack removal, LIFO, and the fact that the countered counter's own effect never runs.
- *The verdict split.* Two runs reaching the same final state — one where the target was countered,
  one where it fizzled — asserted to emit **different** events. This is the test that fails if §3 is
  ever collapsed.
- *Counter a permanent spell.* A countered creature spell never enters the battlefield and fires no
  ETB trigger, while its cast trigger (already on the stack) still resolves.
- *Counter a flashback spell.* Exiled, not graveyarded (CR 702.34e) — the promise `castVia`'s KDoc
  has been carrying since P5.2, finally cashed.
- *Target leaves the stack.* Counterspell targets a spell that another counter, underneath it,
  removes first. CR 608.2b fizzle, no new code, and the fresh-id rule proves the graveyard object is
  not accidentally matched.
- *Pay to save.* Force Spike, opponent taps a land and pays; Spell Pierce, opponent cannot pay and
  the spell is countered; Spell Pierce whose target dies to a response, so nobody is asked.
- *Enumeration completeness.* Pyroblast offered with only white spells on the stack; Blue Elemental
  Blast **not** offered with no red object anywhere. Both directions, per ADR-005.

**Property/oracle tests (F1.8), in the mana-payment.md style.** Over random stacks and random
restrictions: the set of legal stack targets computed by the engine equals a naïve oracle
(filter the stack's spells by the predicate, drop self), proving no gap and no phantom. Plus
**conservation** (a countered spell's card is in exactly one zone afterwards, and the card census is
unchanged), and **purity** (the counter primitive is a function of state and target id alone).

**Fuzz.** The F1.8 corpus with a counter-heavy list, under the extended invariant checker and the
enumeration probe. The probe matters more here than anywhere since Phase 2: the counter cards are the
first whose *legality* depends on the stack's contents, which change several times per priority round.

---

## 13. Non-goals (explicit)

Out of scope, with where each would slot: **countering activated or triggered abilities** (Stifle —
needs a stack-entry identity distinct from `ObjectId`, §4); **"can't be countered"** (a
counter-restriction predicate on the target side; note it does *not* stop a CR 608.2b fizzle);
**"counter unless its controller sacrifices/discards"** (non-mana unless-costs — the fused request of
§7.1 generalises, the payment enumeration does not); **counter-and-do-something-else** (Exclude's
draw, Remand's return-to-hand — trivial composition once `counterSpell` is published, but no card in
this list needs it); **splice, replicate, storm, or any other cast-time copy** (a countered copy is
not a countered card); **triggers on countering** ("whenever a spell is countered" — the reason §3's
verdict split must be real, but nothing here observes it); **regeneration and indestructible** against
the Blasts' destroy mode (`CreatureDeathCause` already reserves the distinction, CR 701.15/702.12);
**colour indicators** (CR 204) that would make §5's derived colour wrong. Each is a slot the design
reserves and a loud gate should refuse to fake.

---

## 14. Open questions for the architect

1. **`legalTargets` signature** (§4). Add a "self" parameter so a spell can never target itself and
   the cast-time and re-validation enumerations agree — versus excluding self only at the stack-spec
   call site, or reordering `proposeSpell`/`establishTargets` in the pipeline. Recommendation: the
   parameter. Main call; it touches shared signatures.
2. **One fused `ChooseOptionalPayment` vs. a `ChooseYesNo` + `ChoosePaymentPlan` pair** (§7.1).
   Recommendation: fused, always surfaced (including when the only option is decline). The pair
   costs a second ~10-file protocol/CLI round and can offer an unaffordable "yes".
3. **`SpellRestriction` shape** (§6): a closed member list, versus an `And`/`Or`/`Not` combinator
   algebra. Recommendation: closed list; revisit at the third negation.
4. **The `spellCharacteristics` seam** (§5): confirm that a stack entry's characteristics are read
   through one accessor now, even though it returns printed values unchanged, on the P3.1
   `effective*` precedent.
5. **Colour derivation** (§5): accept "colour = mana cost's colours" silently, or add a loud gate for
   a card whose colour cannot be derived from its cost (CR 204 colour indicator)?
6. **`GameEvent.SpellCountered` payload:** does it carry the *countering* object's identity as well
   as the countered one? Nothing in the pool reads it, but the event log is the CLI's and the
   protocol's narration surface, and "Counterspell countered Lightning Bolt" needs both.
7. **Should modality (F1.4) and kicker (F1.6) leave F1?** (§1.5) Both serve decks well outside the
   counter cards, and F1's headline value — Mono-Blue Terror, Mono Blue Faeries, and most sideboards —
   is delivered by F1.1–F1.3 plus F1.7. Hoisting them would make F1 a genuinely single framework and
   land the unlock sooner.
8. **Does `PendingCounterPayment` belong in `SeatView`?** Every other `pending*` record is exposed
   there. Nothing about this one is hidden, so exposure is safe — confirm it is also wanted.
