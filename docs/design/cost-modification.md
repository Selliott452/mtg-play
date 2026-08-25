# Design note — dynamic cost modification (CR 601.2f)

The reference for the cost-modification framework the sibling project's brief calls **F3**, and for the
verdict on its proposed rider **F10** (conditional land mana). Written to the PLAN.md §5 rule that a
framework packet gets a design note reviewed *before* implementation. It modifies exactly what
[`mana-payment.md`](mana-payment.md) describes — the cost a payment plan is enumerated against — so that
note is the prerequisite reading; [`layer-system.md`](layer-system.md) is the model for how a continuous
effect is represented and is where two of the brief's F3 cards actually belong.

Its job is to fix the decisions, not to teach CR 601.

---

## 0. Oracle text first — where the brief is wrong

The brief (`mtg_ai/docs/ENGINE-CARD-BRIEF.md` §4) says its groupings were derived from card names rather
than oracle text, and says so itself. Every card it names for F3 and F10 was re-fetched from Scryfall
(`POST /cards/collection`, 2026-08-24, 11/11 found, 0 not_found). Where the oracle text disagrees with
the brief, **the oracle text wins**. Four disagreements, two of them load-bearing.

| Card | Oracle text (verbatim) | Brief's claim | Verdict |
|---|---|---|---|
| Myr Enforcer `{7}` | "Affinity for artifacts" | cost modification | ✅ correct — CR 702.41a |
| Thoughtcast `{4}{U}` | "Affinity for artifacts" / "Draw two cards." | cost modification | ✅ correct |
| Cryptic Serpent `{5}{U}{U}` | "This spell costs {1} less to cast for each instant and sorcery card in your graveyard." | cost modification | ✅ correct |
| Tolarian Terror `{6}{U}` | same clause **+ "Ward {2}"** | cost modification | ⚠️ correct but **incomplete** — see below |
| Sunscape Familiar `{1}{W}` | "Defender" / "Green spells and blue spells you cast cost {1} less to cast." | cost modification (grouped with affinity) | ⚠️ **is** cost modification, but a **different shape** — see §1 |
| Goblin Tomb Raider `{R}` | "As long as you control an artifact, this creature gets +1/+0 and has haste." | cost modification | ❌ **not cost modification at all** |
| Galvanic Blast `{R}` | "deals 2 damage to any target. Metalcraft — deals 4 damage instead if you control three or more artifacts." | "cost" side, F3 | ❌ **modifies the effect, not a cost** |
| Basilisk Gate | *Land — Gate.* "{T}: Add {C}." / "{2}, {T}: Target creature gets +X/+X until end of turn, where X is the number of Gates you control. Activate only as a sorcery." | "power equal to the number of Gates you control (a characteristic-defining variant)" | ❌ **wrong twice** — not the Gate's power, and not characteristic-defining |
| Urza's Mine | *Land — Urza's Mine.* "{T}: Add {C}. If you control an Urza's Power-Plant and an Urza's Tower, add {C}{C} instead." | conditional land mana | ✅ correct |
| Urza's Power Plant | *Land — Urza's Power-Plant.* "…add {C}{C} instead." | conditional land mana | ✅ correct |
| Urza's Tower | *Land — Urza's Tower.* "…add **{C}{C}{C}** instead." | "different amounts" | ✅ correct — note Tower is **three**, the other two are two |

**Goblin Tomb Raider is not a cost card.** It is a conditional static ability generating a continuous
effect: layer 6 (grants haste) and sublayer 7c (+1/+0), gated on "as long as you control an artifact"
(CR 604.3, CR 613.1f, CR 613.4c). It is also **not** characteristic-defining — CR 604.3a criterion (5)
excludes an ability that sets values "only if certain conditions are met". It needs the layer system plus
two things the MVP deliberately lacks: a *conditional* `StaticContinuousEffect`, an `AffectedSet.Self`
selector (layer-system.md §2 names both as the unbuilt sealed extension point), and **haste** — which
PLAN.md §6 P5.3 states outright is absent from both MVP 75s. **Consequence for the sibling project: F3
does not unblock Mono Red Rally.** That deck's F3 dependency was Goblin Tomb Raider alone.

**Galvanic Blast's metalcraft modifies the effect.** Metalcraft is an *ability word* — CR 207.2c lists it
among the words that "have no special rules meaning and no individual entries in the Comprehensive Rules".
The clause is ordinary spell text resolved at resolution: the count is taken once, when the effect is
applied (CR 608.2h). It is not a cost modification and it is not a CR 614 replacement effect either — the
"instead" is internal to the spell's own text. What it needs is a *conditional damage amount* on the
existing `DealDamage` resolution effect. Nothing in this framework.

**Basilisk Gate is wrong twice.** It is a *land* with no power/toughness box, so "power equal to the number
of Gates you control" describes nothing on the card; the count feeds a **targeted, activated, until-end-of-
turn pump of another creature**. And it is not characteristic-defining — a CDA must define the object's own
values (CR 604.3, 604.3a). See §7: it belongs to the layer system, and its X has the **opposite** timing
semantics to the one existing dynamic-magnitude type, which is precisely why letting it ride along here
would be dangerous.

**Tolarian Terror carries ward {2}**, which the brief omits. Ward is a *triggered* ability (CR 702.21a:
"counter that spell or ability unless that player pays [cost]") — not a cost increase, and not this
framework. It needs countering (the brief's F1) plus an opponent-facing pay-or-be-countered decision the
engine has no shape for. **Tolarian Terror is not encodable by F3**; Cryptic Serpent is the clean
representative of that clause and should carry the packet.

**Sunscape Familiar is cost modification, but not the same shape as affinity** (§1), and it is white while
reducing *green and blue* spells — it reduces spells it shares no colour with. Also worth noting for
sequencing: the brief's own §4 has UWX Familiar depending on F1 and F4 as well, so F3 does not unblock that
deck either.

---

## 1. Two shapes, not one

Every genuine cost modification in the set is a **cost reduction of an amount of generic mana**, but they
arrive from two structurally different places.

- **Self-referential** (affinity, and the Terrors' printed clause). A static ability of the *spell itself*,
  functioning while it is on the stack (CR 702.41a: "Affinity is a static ability that functions while the
  spell with affinity is on the stack"; CR 604.5 generally). The reduction is a count over a zone.
- **Other-object-sourced** (Sunscape Familiar). A static ability of a *battlefield permanent* generating a
  continuous effect that modifies the cost of spells matching a predicate — here the spell's **colours**
  (CR 202.2). Flat, not count-based. Notably this is a continuous effect that affects the *rules* rather
  than an object (CR 613.11), so it is applied at cost determination and **never enters the layer system**.

Both land on the same CR 601.2f hook and share the same arithmetic. They differ in *who declares them* and
*what they read*, so the declaration surface needs both a spell-side slot and a permanent-side slot. Both
are covered here; the counting they share with the non-cost cards is §6.

---

## 2. The CR 601.2f hook — where it goes, and the lock-in rule

> **CR 601.2f** — "The total cost is the mana cost or alternative cost (as determined in rule 601.2b), plus
> all additional costs and cost increases, and minus all cost reductions. If multiple cost reductions apply,
> the player may apply them in any order. If the mana component of the total cost is reduced to nothing by
> cost reduction effects, it is considered to be {0}. It can't be reduced to less than {0}. Once the total
> cost is determined, any effects that directly affect the total cost are applied. Then the resulting total
> cost becomes 'locked in.' If effects would change the total cost after this time, they have no effect."

The CR's own example under 601.2h is Sunscape Familiar's sibling, and is the acceptance test this framework
should be judged by:

> *Example: You cast Altar's Reap, which costs {1}{B} and has an additional cost of sacrificing a creature.
> You sacrifice Thunderscape Familiar, whose effect makes your black spells cost {1} less to cast. Because a
> spell's total cost is "locked in" before payments are actually made, you pay {B}, not {1}{B}, even though
> you're sacrificing the Familiar.*

### 2.1 The engine computes the cost four times

`determineTotalCost` (`mtg-rules/.../engine/CastingPipeline.kt`) is today a pure function of the stack entry
alone — `entry.castVia?.cost ?: entry.definition.manaCost`. It has three siblings that must agree with it and
with each other:

| Site | File | What it prices |
|---|---|---|
| cast legality (enumeration) | `engine/ActionEnumeration.kt` — `castIsLegal` → `castableCost` | the printed cost |
| permission legality | `engine/CastLegality.kt` — `targetsAndCostAvailable`, `permissionCastIsLegal`, `madnessCastViable` | `permission.cost` |
| request derivation | `engine/PendingCastRequest.kt` — the `else` branch | `cast.castingPermission?.cost ?: definition.manaCost` |
| execution | `engine/CastingPipeline.kt` — `determineTotalCost` | as above |

Today these agree because the expression is a constant. The moment the cost depends on state, agreement
becomes a property that can silently break, and its failure mode is exactly ADR-005's silent defect: a cast
option is enumerated against one cost, then `ChoosePaymentPlan` is derived against another. If the second is
*more expensive* the option set is empty and the `require` in `ChoosePaymentPlan.init` throws — loud, and
survivable. If it is *cheaper*, the agent is offered plans that underpay a cost the pipeline then validates
against a different number, and `validatePlanShape` throws mid-cast. Either way the fuzz corpus finds it; but
a divergence that happens to be *payable both ways* is silently wrong, and nothing catches it.

**Decision — one cost function, four call sites, zero re-derivation.** A single
`totalCost(state, seat, definition, permission, castObjectId): ManaCost` in a new `engine/CostModification.kt`,
called by all four. `castableCost` collapses into it. This is the whole point of the packet; every other
decision here is downstream of it.

**Decision — do not store the determined cost on `PendingCast`.** Storing it would make lock-in structural
rather than conventional, which is tempting, but it introduces a second source of truth for a value that is
already a pure function of the paused state, and ADR-004 requires every pending request to be re-derivable
from state alone. It would also cost a new fingerprint token and a new invariant (DoD item 4). The state
*cannot* change between determination and payment, because gathering never mutates the game and the whole
execution half is one atomic transition — provided §2.2 holds. Listed as open question 1 because it is the
one decision here a reviewer might reasonably reverse.

### 2.2 The stage order is currently wrong, and must be fixed

`executeCastPipeline` runs, in order:

```
proposeSpell → chooseModes → establishTargets
  → payAdditionalCosts        (escape: exile N cards from the graveyard)
  → paySacrificeCosts         (Fireblast: sacrifice two Mountains)
  → payAdditionalDiscardCost  (Grab the Prize: discard a card — through the CR 614 replacement framework)
  → determineTotalCost
  → payCosts
```

**Cost determination runs after three payment stages.** That is harmless today because the cost is a constant.
It is wrong the instant a reduction counts anything, and each of the three stages breaks it in a different way:

- **`paySacrificeCosts`** removes permanents from the battlefield. Fireblast sacrifices two Mountains; the
  engine's `viaSacrifice` mana sources (Eldrazi Spawn) remove a permanent as a *mana* payment. Under affinity
  — "for each artifact you control" — sacrificing an artifact between determination and payment re-prices the
  spell upward. This is the Altar's Reap example, in this engine, with the stages in the wrong order.
- **`payAdditionalDiscardCost`** puts cards into the **graveyard** (and, through the CR 614 framework, may
  exile a madness card instead). The Terrors' clause counts instant and sorcery *cards in your graveyard*.
  Discarding an instant as an additional cost after the count was taken would make the spell one cheaper.
- **`payAdditionalCosts`** exiles cards *from the graveyard* (escape). Those cards must still count: CR 601.2f
  precedes CR 601.2h, so escape fodder is counted and then exiled. Getting this right is free if the order is
  right, and wrong in the *other* direction if it is not.

**Decision — move `determineTotalCost` to immediately after `establishTargets`,** ahead of all three payment
stages, and thread the resulting `ManaCost` down to `payCosts`. This makes the pipeline read as CR 601.2a →
601.2c → **601.2f** → 601.2g–h, which is what the CR actually says. It is a change to a function the packet
owns, but it reorders a public-ish pipeline — flag it in the packet report (CONVENTIONS.md "do not change a
public API you do not own").

Do **not** hoist determination above `proposeSpell`. The card must already have left its source zone
(CR 601.2a) when the count is taken; that is what stops a graveyard-cast spell from counting itself, and what
stops a hand card being counted as a permanent. It falls out for free, and only in this position.

### 2.3 The one remaining asymmetry: gathering-time versus execution-time

The cost is priced at enumeration and request-derivation time with the card still in its **source zone**, and
at execution time with the card on the **stack**. For a hand cast this is invisible: a hand card is in no
counted zone either way. For a **graveyard cast of a card whose reduction counts the graveyard** (flashback or
escape of an instant, under the Terrors' clause) the two differ by exactly one — the card counting itself.

**Decision — `totalCost` takes the cast object's id and excludes it from every zone-count predicate**, with a
KDoc citing CR 601.2a. That makes the gathering-time and execution-time answers identical by construction, and
makes the exclusion a named, testable rule rather than an accident of stage placement. No card in the current
or brief-named pool hits this, which is exactly why it must be a test rather than a comment.

---

## 3. Order of application, and the floor at zero

CR 601.2f's formula is: **alternative-or-printed cost, plus additional costs, plus cost increases, minus cost
reductions**, clamped so the mana component never goes below `{0}`. Increases before reductions is not a
convention, it is the printed order of the formula.

Which parts of a cost a reduction may touch is CR 118.7:

- **CR 118.7a** — "Effects that reduce a cost by an amount of generic mana affect only the generic mana
  component of that cost. They can't affect the colored or colorless mana components of that cost."
  Cryptic Serpent `{5}{U}{U}` with seven instants and sorceries in the graveyard costs `{U}{U}`, never less.
- **CR 118.7b–d** — coloured/colorless reductions spill into generic; **118.7e** — a hybrid reduction makes the
  payer choose a half *at the time the reduction is applied* (an actual decision, cross-referenced to 601.2f);
  **118.7f** — Phyrexian reduces by one mana of its colour; **118.7g** — snow reduces generic.
- **CR 118.7** — "Paying a cost changed or reduced by an effect counts as paying the original cost."

**Decision — implement CR 118.7a only, and gate 118.7b–g loudly.** Every reduction in the whole named set is
"costs {1} less", i.e. purely generic. Following layer-system.md's "full skeleton, sparse population" would
mean building five reduction kinds nothing exercises; here the skeleton is small enough that the honest move is
a narrow implementation behind an `error(...)` for anything else, in the CONVENTIONS.md loud-failure style.
118.7e is the one that would introduce a *new decision request*, so it is the one that must never be faked.

**Decision — implement the increase slot in the formula, populate it with nothing, gate it loudly.** No named
card increases a cost. Ward is a triggered ability (CR 702.21a), not an increase.

**"If multiple cost reductions apply, the player may apply them in any order" surfaces no decision, and that is
provable rather than assumed.** With only generic reductions, the result is `max(0, generic − Σ reductions)`:
integer subtraction commutes and the floor is a single clamp applied once at the end, so every application
order yields the same cost. The player's freedom is real but unobservable, so ADR-005 loses nothing by not
enumerating it — no legal outcome is missing from the option set. This stops being true the moment a coloured
reduction (118.7c spillover) or a hybrid reduction (118.7e) enters the pool, which is a second reason the gate
on those must be loud. **Make the order-independence a property test, not a comment** — it is the argument that
justifies the absent decision.

**The `{0}` floor is a trap in `mtg-core`.** `ManaCost` requires a non-empty symbol list — "a card with no mana
cost is modeled as the absence of a `ManaCost`". Reducing `{7}` by seven must therefore produce
`ManaCost.parse("{0}")`, a single `Generic(0)` symbol, and **not** an empty list, which would throw in
`ManaCost.init`. Reducing `{5}{U}{U}` by five must produce `{U}{U}`, not `{0}{U}{U}`: `expandToUnits` maps
`Generic(0)` to zero units so both behave identically during payment, but `render()` would print `{0}{U}{U}` in
the CLI menu and on the wire. **Decision — normalise: drop a zeroed generic symbol unless it is the entire
cost.** Cheap, and it keeps `ManaCost.render()` honest for `mtg-cli` and `mtg-protocol`.

---

## 4. What must be inspectable, and when

Every read below happens **exactly once per cast, at CR 601.2f**, against the paused gathering state, with the
cast object excluded (§2.3).

| Reduction | Reads | Zone | Notes |
|---|---|---|---|
| Affinity for artifacts (CR 702.41a) | permanents you control with the artifact card type | battlefield (CR 403) | Myr Enforcer is itself an artifact creature: a resolved copy counts, the one being cast never does |
| Cryptic Serpent / Tolarian Terror | instant and sorcery **cards** | your graveyard (CR 404) | cards, not stack objects; escape fodder counts and is exiled afterwards (CR 601.2h) |
| Sunscape Familiar | the spell's **colours** (CR 202.2) + the Familiar on the battlefield under its controller | battlefield + the spell on the stack | no count; a flat reduction gated on a predicate |

Three specifics the implementation must get right:

- **Card types are read printed, not layered — deliberately, with a named seam.** Affinity counts *artifacts
  you control*, i.e. the in-game card type, which is layer 4 (CR 613.1d). The engine has no layer-4 effect and
  `LayeredCharacteristics` does not even carry card types. Reading
  `state.definitions[obj.card].characteristics.cardTypes` matches the existing `CardDefinition?.isLand()` and
  `sacrificeableFor` pattern, and matches the identical argument layer-system.md §6 makes for enchant
  restrictions. **When the first type-changing effect arrives, this count must route through the layer engine**;
  say so in the KDoc, in one place, so there is one thing to change.
- **Control is ownership**, as everywhere else in the MVP (no layer-2 effect exists).
- **A spell's colour comes from its *printed* mana cost (CR 202.2), not the cost being paid.** This is a live
  hazard here: the engine passes `permission.cost` around for madness, flashback, escape, and plot, and
  `ManaCost.colors` on a `Plot` permission's `{0}` yields the empty set. A Sunscape-style reducer must read
  `definition.manaCost`, never the alternative cost. One line to get wrong, invisible when wrong.

---

## 5. Payment enumeration and ADR-005 — `ChoosePaymentPlan` barely changes

The brief predicts "expect the `ChoosePaymentPlan` option set to change shape". **It does not, and the reason
matters.**

A `PaymentPlan` is a flat list of `SymbolPayment` aligned to the expanded symbols of *the cost it pays*
(mana-payment.md). Cost modification changes *which cost is expanded*; it introduces no new payment kind, no
new source class, and no new choice. `expandToUnits({4})` and `expandToUnits({7})` differ only in length.
`PaymentEnumeration.kt`, `SourceClassKey`, `SymbolPayment`, the DFS, the non-decreasing dedup rule, and the
brute-force oracle are all untouched.

Three things *do* change, and only one of them is agent-visible:

1. **`ChoosePaymentPlan` should carry the determined cost.** Today the request carries `cardObjectId`, `card`,
   `options`, and the driver infers what is being paid from the card. With modification the printed cost is no
   longer what the plan pays: `mtg-cli`'s `PaymentLabels`/`MenuRenderer` would render "pay {7}" beside a
   four-payment plan, and an agent replaying a log could not tell a reduced cast from a bug. **Decision — add
   the determined `ManaCost` to `ChoosePaymentPlan`.** Additive, display-and-audit only; the *option set* is
   unchanged, so no enumerated option gains, loses, or reorders. This is the ADR-005-relevant change, and it is
   smaller than the brief expects — worth stating explicitly in the packet report so the review does not go
   looking for a reshape that is not there.
2. **A `{0}` cost enumerates exactly one plan — the empty plan.** Already true (`Plot`'s `{0}` relies on it),
   and it keeps `ChoosePaymentPlan.init`'s non-empty invariant satisfied. A cost reduced to nothing therefore
   still surfaces a payment decision with one option, which is the same "no auto-pass, replay logs stay
   canonical" rule the request's KDoc already states.
3. **Completeness moves up one level.** `PaymentEnumerationSpec`'s brute-force oracle proves the enumerator is
   complete *for the cost it is given*. It says nothing about the cost being right. The new completeness
   obligation is **cost agreement**: legality, request derivation, and execution must price identically. That
   is one function and one test (§9), and it is the single most important test in the packet — per ADR-005 a
   missing or illegal enumerated option is a silent defect for a training agent, and a cost divergence is the
   only way this framework can manufacture one.

`EnumerationProbe`'s existing end-to-end property — every enumerated option executes through the full pipeline
without error — remains the fuzz-side defence, and it is exactly the right shape: a cost divergence that is
payable under one cost and not the other shows up there as a crash, not as a wrong game.

---

## 6. Is "count permanents matching a predicate" one primitive? Share the noun, not the verb

Six consumers want a count:

| Consumer | Counts | Feeds | **When it is read** |
|---|---|---|---|
| Affinity | battlefield, you control, card type | a cost reduction | **locked in** at CR 601.2f |
| Terrors | your graveyard, card type | a cost reduction | **locked in** at CR 601.2f |
| Metalcraft (Galvanic Blast) | battlefield, you control, card type, **threshold ≥3** | a damage amount | once, on resolution (CR 608.2h) |
| Basilisk Gate | battlefield, you control, **subtype** | a layer-7c magnitude | once, on resolution, then **frozen** for the duration (CR 611.2d, 608.2h) |
| Ethereal Armor / Ancestral Mask *(shipped)* | battlefield, card type | a layer-7c magnitude | **live, on every characteristic read** (CR 613.5) |
| Tron | battlefield, you control, subtype **existence** | a mana ability's output | at ability resolution, **mid-payment**, never frozen (CR 605.2) |

**Verdict: extract the predicate and the count. Do not extract the consumer.**

The shared part is real and small: an `ObjectPredicate` (card type, subtype, controller) and
`countMatching(state, zone, predicate)`. It is worth extracting on its own merits — it replaces six near-
identical `battlefield.count { … }` lambdas, two of which are already hand-rolled in
`mtg-cards/.../Auras.kt` (`perEnchantmentYouControl`, `perOtherEnchantment`), and it is trivially unit-
testable against fixture boards with no cast, no resolution, and no layer engine involved.

**Collapsing the consumers is a false economy, and the reason is the last column.** Four different read
semantics — locked in, resolution-once, live, and unfrozen-mid-payment — none of which is convertible into
another. A single `Magnitude`-shaped abstraction spanning them would make the *wrong* semantics the easy
default, and the wrong semantics does not crash. Concretely: reusing the existing `Magnitude.Dynamic` for
Basilisk Gate — the obvious move, since it is the one dynamic-count type the engine has — gives the +X/+X a
value that **tracks the Gate count for the rest of the turn**. Play a fourth Gate and the pump grows; lose one
and it shrinks. The game state stays internally consistent, no invariant fires, no test that is not looking for
it fails. That is precisely the "wrong result that looks right" PLAN.md §7 calls the worst outcome, and it is
what a shared count-and-consume primitive would invite.

So: **one `ObjectPredicate` noun and one counting function; four consumers, each of which names its own read
point in its own type.** That is the project's "nouns in core, verbs in rules" line, drawn in the same place it
is drawn everywhere else.

**Where the predicate lives, and why it should not be a lambda.** `Magnitude.Dynamic` is a `fun interface` in
`mtg-core` taking `GameState`, so a pure-function-of-state declaration in core has precedent. A *declarative*
predicate is nonetheless better than a lambda for this one, for a reason specific to this engine: a lambda has
no structural equality. `SourceClassKey` — the payment-equivalence key — is a data class whose equality decides
whether two mana sources collapse into one enumerated option, and `Fingerprint` digests definition-derived
state. If a Tron land's conditional production is ever expressed as a lambda, two structurally identical lands
stop being equal and the payment enumeration silently doubles. Declarative predicates are also serialisable,
comparable, and renderable in a CLI menu. Open question 2.

---

## 7. Basilisk Gate belongs to the layer system, not here

Beyond the two oracle disagreements in §0, the positive claim: Basilisk Gate is the first card that forces
three things `layer-system.md` explicitly deferred, and it forces all three at once.

- **Resolution-generated continuous effects (CR 611.2).** layer-system.md §2 states that "every MVP continuous
  effect is generated by a static ability of a permanent"; `StaticContinuousEffect` carries no timestamp and no
  duration because of it. An activated ability's until-end-of-turn pump is CR 611.2, a different generator.
- **Duration.** "Decision: build no duration machinery" (§2), hooked at the effect-collection step, with
  Tamiyo's Safekeeping named as the forcing function. Basilisk Gate is a second one, in a maindeck.
- **Explicit timestamps.** layer-system.md §3's decision — reuse battlefield-entry `ObjectId` order, add no
  field — is justified by every MVP effect having a permanent whose entry *is* its timestamp. A
  resolution-generated effect has no such permanent. This retires that decision and answers layer-system.md's
  own open question 1.

It also needs `ActivatedAbility` to gain a timing restriction ("Activate only as a sorcery") and a target,
neither of which `AbilityCost`/`ActivatedAbility` currently expresses, and a **snapshotted** magnitude — the
opposite of `Magnitude.Dynamic` (§6).

**Verdict: split it out entirely.** Its only overlap with cost modification is the counting primitive from §6.
Bundling it into F3 would import three deferred layer decisions and one inverted timing semantic into a packet
about costs. It is a layer packet, and a substantial one.

---

## 8. The production side (F10) is its own framework

The brief calls F10 "related to F3 but on the production side". The shared surface is the counting primitive
and nothing else, and F10's hard part is not in this note at all — it is in `mana-payment.md`.

**Different rule, different lifecycle.** Tron is CR 605.1a/605.2, not CR 601.2f. There is no lock-in: the
condition is evaluated when the mana ability *resolves*, mid-payment, after the cost was already fixed. CR
605.2's own example is a count-based mana ability — "{T}: Add {G} for each creature you control" — so the CR
treats this as ordinary mana-ability business, not cost business. Note the consequence: with Tron assembled, a
cost locked in at `{7}` is paid by three activations producing 2+2+3; nothing about the count feeds back into
the cost, and nothing about the cost constrains the count.

**The engine surface it touches is entirely the payment model.**

- `ManaAbility(options, viaSacrifice)` adds **exactly one** mana per activation, and its KDoc says so, and says
  multi-mana production "arrives with the composite-cost work in Phase 5 as new vocabulary, not by stretching
  this type". Tron needs two or three. That is a `ManaAbility` reshape, and the KDoc already blesses it as new
  vocabulary rather than a widening.
- `productionProfile` returns the *options* a tap may choose between, which cannot express "adds {C}{C}".
- `SymbolPayment.WithMana(mana, ByTapping(class))` pays **one symbol per tap**. A tap adding `{C}{C}{C}` pays up
  to three symbols with any remainder floating. **This is where `ChoosePaymentPlan` genuinely changes shape** —
  not in F3. The existing `SourceClassKey.bonus` trick (extra mana floats into the pool and is deliberately not
  a candidate payment) does not scale here, because with Tron the extra mana *is* the point: the enumerator's
  `PaymentResources.pool` is seeded from the current pool and is never credited by a tap, so a naive Tron would
  enumerate plans that tap once per symbol and would omit every plan that pays two symbols off one Tower. A
  missing legal plan is exactly the ADR-005 silent gap.
- The good news, and it is genuinely reassuring: the **equivalence relation survives untouched**. Two Urza's
  Towers are payment-equivalent to each other; a Tower with Tron assembled forms a different class from one
  without, automatically, because the profile is computed from state and the profile is what the key hashes on.
  That is mana-payment.md's "Phase 5 refines the profile, never the relation" argument extending exactly one
  more step, and it means F10 is a *profile* problem, not a *relation* problem.
- `MANA_POOL_EMPTY_AT_PAUSE` already carries a declared floating-mana exception for triggered mana abilities;
  Tron makes floating mana routine rather than exceptional, which is an invariant-KDoc change, not a new
  invariant.

**Verdict: F10 is its own framework packet with its own design note, written against `mana-payment.md`.** It is
a *variable-amount mana production* framework that happens to have a board-state condition, not a cost
framework. Folding it into F3 would put a payment-plan reshape inside a packet whose whole thesis (§5) is that
the payment plan does not reshape.

---

## 9. Blast radius, by file and type

**`mtg-rules`**
- `engine/CastingPipeline.kt` — `determineTotalCost` gains state and the pending cast; **stage order changes**
  (§2.2); `payCosts` receives the determined cost rather than re-deriving it.
- `engine/ActionEnumeration.kt` — `castIsLegal`; the private `castableCost` is absorbed into the shared function.
- `engine/CastLegality.kt` — `targetsAndCostAvailable`, `permissionCastIsLegal`, `madnessCastViable`.
- `engine/PendingCastRequest.kt` — the `else` branch that prices `ChoosePaymentPlan`.
- `engine/Activation.kt` — `abilityCostPayable`, `pendingActivationRequest`. Activated-ability costs are
  modifiable in general (CR 602.2); nothing in the pool does it, so route through the same function and gate.
- `engine/Plot.kt` — the plot special action pays `plotCost`; same routing question.
- `engine/PaymentValidation.kt` — `validatePlanShape` becomes the lock-in assertion rather than a formality.
- `engine/PaymentEnumeration.kt` — shape unchanged; `expandToUnits` must tolerate `Generic(0)`.
- **new** `engine/CostModification.kt` (the 601.2f applier) and `engine/ObjectCount.kt` (predicate evaluation).
- `decision/DecisionRequest.kt` — `ChoosePaymentPlan` gains the determined cost.

**`mtg-core`**
- `mana/ManaCost.kt` — reduction arithmetic must not produce an empty symbol list (the `init` `require`);
  zeroed-generic normalisation.
- `definition/CardDefinition.kt` / `definition/SpellDefinition.kt` — a declaration slot for each of §1's two
  shapes.
- **new** `definition/ObjectPredicate.kt`, `definition/CostModifier.kt`.

**`mtg-protocol`** (versioned, ADR-008) — `DecisionRequestDto.kt`, `DecisionRequestToDto.kt`,
`DecisionRequestToDomain.kt`, `PaymentPlanDto.kt`, `DecisionRequestRoundTripSpec.kt`, and `PROTOCOL_VERSION` in
`Envelope.kt`.

**`mtg-cli`** — `PaymentLabels.kt`, `MenuRenderer.kt`, `DefaultDecision.kt`.

**`mtg-acceptance`** — `fuzz/EnumerationProbe.kt` (payment branch mechanically unchanged; its execute-every-
option property becomes the primary defence); `Fingerprint.kt` and `invariant/Invariant.kt` are untouched **if**
open question 1 resolves to not storing the cost, and gain a token plus an invariant if it does not.

**Tests directly affected** — `mtg-rules/src/test/.../PaymentEnumerationSpec.kt`, `CastingPipelineSpec.kt`,
`CastingTestSupport.kt`, `ActivatedAbilitySpec.kt`; `mtg-acceptance/.../CastFromElsewhereAcceptanceSpec.kt`,
`ManaConstrainedWindowAcceptanceSpec.kt`. All should pass **unchanged** after packet C2 (§10), which is what
makes C2 independently reviewable.

`mtg-pauper` and `mtg-server` are unaffected.

---

## 10. Packet decomposition

Sequenced, each independently testable, primitive before card, one agent at a time (PLAN.md §5).

- **C1 — object-count primitive.** `ObjectPredicate` in `mtg-core`; `countMatching(state, zone, predicate)` in
  `mtg-rules`. No costs, no cards, no callers. *Accept:* unit tests over fixture boards for card type, subtype,
  controller, and each zone; an unsupported predicate kind fails loudly. `Auras.kt`'s two hand-rolled counts are
  **not** migrated here — that is a follow-up, flagged in the report, so C1 stays behaviour-neutral.

- **C2 — the CR 601.2f hook, with an empty modifier set.** One `totalCost` function; all four call sites (§2.1)
  rerouted; the pipeline stage reordered (§2.2); the cast object excluded from zone counts (§2.3);
  `ChoosePaymentPlan` gains the determined cost; protocol and CLI follow. **Behaviour-neutral by design.**
  *Accept:* every existing test passes unchanged; a rules-fixture modifier reducing by `{1}` proves the hook
  fires; **the CR 601.2f lock-in test** — a fixture spell with a sacrifice additional cost whose sacrifice would
  change the count still pays the determined cost (the Altar's Reap / Thunderscape Familiar example, cited by
  rule in the test name); the same for the additional-discard stage against a graveyard count; a test pinning
  legality, request derivation, and execution to the same cost.

- **C3 — reduction arithmetic (CR 118.7a, CR 601.2f).** Generic-only reduction, order-independence, clamp at
  `{0}`, zeroed-generic normalisation. Loud gates on CR 118.7b–g and on any cost *increase*. *Accept:* property
  test that application order is unobservable; `{5}{U}{U}` reduced by 7 is `{U}{U}`; `{7}` reduced by 9 is
  `{0}` and enumerates exactly one empty plan; a coloured or hybrid reduction throws.

- **C4 — count-based self reductions** (affinity, CR 702.41a; the Terrors' printed clause). The spell-side
  declaration and the two read points. `mtg-rules` still names no card — exercised by fixtures. *Accept:* the
  spell being cast is never counted; a graveyard-cast spell does not count itself; escape's exile fodder counts
  and is exiled afterwards; the reduction tracks the board across two casts in one turn.

- **C5 — the affinity/reduction cards** (`mtg-cards`): Myr Enforcer, Thoughtcast, Cryptic Serpent. **Tolarian
  Terror is excluded** — ward (CR 702.21a) needs the countering framework. *Accept:* per-card cost tests at
  several artifact/graveyard counts, including the colour floor on Cryptic Serpent.

- **C6 — other-object cost reduction** (Sunscape Familiar). A permanent-sourced `CostModifier` with a colour
  predicate read from the **printed** mana cost (§4). *Accept:* the CR 601.2f example as a CR-cited test —
  sacrifice the reducer as an additional cost and still pay the reduced cost; a plot/madness cast's colour comes
  from the printed cost, not the alternative one; Defender is a separate, existing keyword.

**Sequenced separately, explicitly not part of this framework:**

- **L1 — conditional static continuous effects** (Goblin Tomb Raider): a condition on `StaticContinuousEffect`,
  `AffectedSet.Self`, and **haste**. A layer packet.
- **L2 — resolution-generated, duration-bounded continuous effects** (Basilisk Gate): CR 611.2, until-EOT
  duration, explicit timestamps, snapshotted magnitude (CR 608.2h, 611.2d), and activated-ability sorcery timing
  and targeting. A layer packet; retires layer-system.md §3's timestamp decision (§7).
- **R1 — conditional resolution values** (Galvanic Blast): a state-dependent amount on the existing `DealDamage`
  effect, read once on resolution (CR 608.2h). Small, and unrelated to costs.
- **M1 — variable-amount and conditional mana abilities** (F10 / Tron): its own design note against
  `mana-payment.md` (§8).

---

## 11. Test strategy

In the `mana-payment.md` brute-force-oracle style, because the state space is again small and finite.

- **Cost oracle.** A naive recomputation — walk the zone, count with a dumb loop, subtract, clamp — set-compared
  against `totalCost` over random boards. Proves the arithmetic and the read points together.
- **Cost agreement.** For every enumerated `CastSpell` option, the cost used by legality, the cost used to derive
  `ChoosePaymentPlan`, and the cost the pipeline pays are equal. The §5 property, and the packet's headline test.
- **Lock-in.** For each of the three cost-payment stages, the determined cost is invariant to what that stage
  does. CR-cited names, one per stage.
- **Order independence.** Applying reductions in any permutation yields the same cost (§3) — the property that
  *justifies* surfacing no decision.
- **Loud gates.** Coloured/colorless/hybrid/snow reductions, cost increases, and unimplemented predicate kinds
  each throw rather than approximating.
- **ADR-005 end-to-end.** `EnumerationProbe`'s execute-every-enumerated-option property over a corpus containing
  affinity cards; and constructed scenarios where a cast is legal only *because* of a reduction must be
  enumerated, and illegal without it must not.

---

## 12. Non-goals (explicit)

Cost **increases** (no card); CR 118.7b–g coloured/colorless/hybrid/Phyrexian/snow reductions; **X** costs
(deliberately unsupported since P1.1); **convoke, improvise, delve** (alternative ways to *pay*, not cost
modifications — the brief's F8); **kicker** (an additional cost — F8); **ward** (CR 702.21a, a triggered ability
needing the countering framework); reduction of an **activated ability's** cost (routed through the same
function, gated, unpopulated); **metalcraft as a cost mechanic** (it is not one — §0). Each is a slot the
formula reserves and a loud gate refuses to fake.

---

## 13. Open questions for the architect

1. **Store or re-derive the determined cost** (§2.1): re-derive from the paused state (recommended — no second
   source of truth, no new fingerprint token, no new invariant), versus storing it on `PendingCast`, which makes
   lock-in structural rather than conventional. Main call.
2. **`ObjectPredicate`: declarative or lambda, core or rules** (§6). Recommended: declarative, in `mtg-core`, for
   structural equality (`SourceClassKey`), serialisability, and CLI rendering.
3. **Does `ChoosePaymentPlan` gain the determined cost** (§5), with the `mtg-protocol` version bump that implies,
   or do drivers re-derive it? Recommended: gain it; the option set is unchanged either way.
4. **One declaration slot or two** (§1): a single `CostModifier` list resolved by the engine regardless of source,
   versus a spell-side slot (affinity, the Terrors) and a permanent-side slot (Sunscape Familiar).
5. **Confirm the pipeline stage reorder is in scope for C2** (§2.2). It is a correctness fix the framework
   requires, but it touches a pipeline the packet does not otherwise own (CONVENTIONS.md).
6. **Confirm F3 stops at cost modification and F10 gets its own design note** (§8) — the recommendation here, and
   the one place this note most directly contradicts the sibling brief.
7. **Confirm Tolarian Terror is deferred to the countering framework** (§0) rather than shipped with a ward gap.
8. **Confirm L1/L2/R1 are layer/resolution packets, not F3** (§0, §7) — and that L2 is where layer-system.md §3's
   `ObjectId`-as-timestamp decision is retired.
9. **Drive-by, unrelated to this framework but found while reading:** `engine/Layers.kt` documents `PT_COUNTERS`
   as sublayer 7d citing CR 613.4e. The current CR puts **counters in 7c** (613.4c: "Effects **and counters** that
   modify power and/or toughness") and **P/T switching in 7d** (613.4d); there is no 613.4e. The enum is
   unpopulated so nothing is wrong today, but the citation and the slot's meaning are stale. Confirm whether to
   correct it, and where.

---

## 14. As implemented (`FW-COST`)

Written after the packet landed. The note above is the design as reviewed; this section records where
implementation diverged from it and how the open questions resolved.

### 14.1 Open questions, resolved

| # | Question | Resolution |
|---|---|---|
| 1 | Store or re-derive the determined cost | **Re-derive** (as recommended). No `PendingCast` field, no new fingerprint token, no new invariant. Lock-in is enforced *positionally* — by where the pipeline calls `totalCost` — not structurally. |
| 2 | `ObjectPredicate` declarative or lambda | **Declarative, in `mtg-core`** (as recommended), for structural equality, serialisability, and rendering. |
| 3 | Does `ChoosePaymentPlan` gain the determined cost | **Yes.** Additive `cost: ManaCost`, display and audit only. Protocol `5.0.0` → `6.0.0`. |
| 4 | One declaration slot or two | **Two.** `SpellDefinition.costReduction` (self) and `CardDefinition.spellCostReductions` (other-object). The reader and the subject are different objects, and a reducer need not be castable. |
| 5 | Is the pipeline stage reorder in scope for C2 | **Yes**, and it was a live correctness bug — see §14.3. |
| 6 | Does F3 stop at cost modification | **Yes.** F10 stayed out and landed separately as `FW-MANA`. |
| 7 | Is Tolarian Terror deferred | **Yes**, on ward (CR 702.21a). Cryptic Serpent carries the clause. |
| 8 | Are L1/L2/R1 separate packets | **Yes**, untouched here. |
| 9 | The stale `PT_COUNTERS` / CR 613.4e citation in `Layers.kt` | **Not addressed** — out of scope for this packet, still open. |

### 14.2 Deviations from the note

- **`ObjectPredicate` gained an `AnyOf` member** the note did not anticipate. "Each instant and sorcery
  card" is a *disjunction* (CR 205.2a — no card is both), and encoding it with `And` would make the
  reduction permanently zero. That is the one way to get the Terrors' clause silently wrong, so the
  disjunction is a first-class member rather than a De Morgan spelling of `Not`/`And`.
- **`CostReduction` has two members, not one.** The note modelled every reduction as a count; Of One
  Mind is a **flat amount gated on a board condition** (`IfAll`), which a count cannot express — it is
  worth `{2}` or nothing, never `{1}`. The note never saw that card.
- **The CR 118.7b–g and cost-increase gates are type-level, not `error(...)` calls.** The note asked
  for loud runtime gates. `CostReduction` carries only `Int` amounts of generic mana and no
  declaration can express a coloured, hybrid, or increasing modifier, so a runtime gate would be
  unreachable code under a zero-warning policy. The constraint is enforced by what is constructible;
  the reasoning is in `totalCost`'s KDoc, including what must be revisited if such a shape arrives.
- **Six cost sites, not four.** The note's table named four. `Activation.kt` and `Plot.kt` also build a
  `ChoosePaymentPlan` and had to supply the new field. Neither routes through `totalCost`: an
  activated ability's cost is modifiable in general (CR 602.2f) but nothing in the pool does it, and a
  plot cost is paid by a special action that CR 601.2f never runs over. Both say so at the call site.

### 14.3 The ordering bug was real

`executeCastPipeline` ran `determineTotalCost` **after** `payAdditionalCosts`, `paySacrificeCosts`,
and `payAdditionalDiscardCost`. Harmless while the cost was a constant, wrong the instant a reduction
counts anything — and wrong in the direction CR 601.2h's own Altar's Reap example calls out. Fixed by
moving determination to immediately after `establishTargets` and threading the result to `payCosts`;
three lock-in tests, one per payment stage, now pin it.

### 14.4 Cards

Encoded: Myr Enforcer, Thoughtcast, Utrom Monitor, Cryptic Serpent, Of One Mind.

Dropped, each needing a framework this packet does not own: **Tolarian Terror** (`FW-WARD`),
**Refurbished Familiar** and **Deem Inferior** (`FW-NONCTRLDEC`; Deem Inferior additionally needs
cards-drawn-this-turn tracking that no state field carries), **Ride's End** (`FW-TGTCOND` — cost
determination correctly follows target choice inside the pipeline, but cast *legality* is decided
before any target exists, so enumerating it needs "exists a target making this affordable"), and
**Sunscape Familiar** (`FW-DEFENDERKW`).

**Sunscape Familiar is the note's one factual error.** §10's C6 says "Defender is a separate, existing
keyword". It is not: `Keyword.kt` has no `DEFENDER`, and `FW-DEFENDERKW` is already the recorded
reason Overgrown Battlement is absent. The C6 *framework* — the other-object reducer — ships and is
exercised by the `Fixture Warden` rules fixture; only the card is deferred.
