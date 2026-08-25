# Design note — mana payment enumeration (P2.1, amended P8.3, P-MANASICK, FW-MANA and FW-MANACOST)

The reference for the payment model built in P2.1, extended by P2.2 (real basics) and Phase 5
(triggered mana abilities, additional/alternative costs), **reshaped in P8.3** so that one
activation of a mana ability can pay more than one cost symbol, corrected by `P-MANASICK`
(§2.1) when the pool gained its first creature mana source, **extended on the production side
by `FW-MANA`** (§8) when it gained its first sources whose amount is read off the board, and
sharpened on the reservation side by `FW-ADDSAC` (§2.3) when a cost first sacrificed a *chosen*
permanent, and **extended on the consumption side by `FW-MANACOST`** (§11) when a mana ability first
cost something other than tapping or sacrificing its own source. PLAN.md §7
names payment combinatorics a top risk; the mitigation is this model: **declarative plans over
collapsed source classes**, enumerated exhaustively, chosen by index (ADR-005).

> **P8.3 amendment, in one paragraph.** Up to P8.2 a plan was a flat list of per-symbol
> payments, each naming its own source, so *one tap paid exactly one symbol*. That made a
> multi-mana source unrepresentable: Utopia Sprawl's and Wild Growth's CR 605.1b bonus was
> carried on `SourceClassKey.bonus` purely to keep the enchanted land a distinct class, and
> could never be spent inside the cast that produced it. P8.3 splits a plan into the CR's own
> two halves — **CR 601.2g activations** and **CR 601.2h payments** — which is what makes the
> surplus spendable. §§1–4 below are the amended model; §7 is superseded and kept only as the
> record of what changed and why.

---

## 1. The payment-plan model (amended)

A **payment plan** is a declarative description of how one `ManaCost` will be paid — chosen
*before* anything is executed, surfaced as the enumerated options of a `ChoosePaymentPlan`
request. It has two halves, mirroring the two CR steps it stands for:

```kotlin
data class PaymentPlan(
    val activations: List<ManaActivation>,  // CR 601.2g — which mana abilities to activate
    val payments: List<SymbolPayment>,      // CR 601.2h — what each cost symbol is paid with
)

data class ManaActivation(                       // amended by FW-MANACOST (§11.1)
    val sourceClass: SourceClassKey,
    val alternative: ProductionAlternative,      // what it costs and what it adds
    val costPayment: List<ManaType> = emptyList() // CR 601.2g — which mana pays that cost
)

sealed interface SymbolPayment {
    data class WithMana(val mana: ManaType) : SymbolPayment
    data object WithTwoLife : SymbolPayment
}
```

- **`activations`** is a multiset of mana-ability activations, held in a canonical order
  (§3). Each names a **source class** (§2) and the *production alternative* chosen for that
  source's own ability — the choice an "add one mana of any color" source offers, and, since
  `FW-MANA`, itself a multiset rather than a single type (§8.1). Its **yield** is everything the
  activation puts in the pool: `produced + sourceClass.bonus`, the bonus being the CR 605.1b
  triggered mana the attached Auras add. One activation may yield several mana; nothing in the
  plan shape cares how many.
- **`payments`** has one entry per expanded cost symbol, in printed order (`{N}` expands to
  `N` copies of `{1}`, `{0}` to none). A payment names only *what* pays the symbol: one mana
  of a `ManaType`, or the CR 107.4 Phyrexian alternative of 2 life. It no longer names a
  source, because after CR 601.2g there is only one place mana can come from — the pool.

`ManaSourceChoice` (`FromPool` / `ByTapping`) is **deleted**. It was the thing that pinned one
tap to one symbol, and with production hoisted out of the payment list it has nothing left to
say: whether a paid mana was floating beforehand or was produced by this plan is fully
determined by `activations`, and tagging it per symbol would only manufacture duplicates
(§3.4).

**Legality.** A plan is legal for `(cost, state, seat)` iff all of:

1. **Shape** — one payment per expanded symbol, each satisfying its symbol: `{R}` demands
   `RED`; `{C}` demands `COLORLESS` specifically (CR 107.4c); `{G/U}` accepts `GREEN` or
   `BLUE`; `{R/P}` accepts `RED` or `WithTwoLife`; generic accepts any type (CR 107.4d).
2. **Capacity** — for each source class, the number of activations of it does not exceed the
   class's **usable** membership (§2.1).
3. **Life** (CR 118.8) — `2 ×` the number of `WithTwoLife` payments does not exceed the
   caster's life total. Paying *down to* 0 or into a lethal SBA is legal; per CR 704.5a the
   death follows.
4. **Coverage** — per mana type, the payments' demand is met by the pool plus the
   activations' yields: `demand(t) ≤ pool(t) + Σ_a yield(a).count(t)` for every `ManaType t`.
   Types do not substitute for one another, so coverage decomposes type by type.
5. **No idle activation** (§4) — every activation has at least one of its yielded mana
   actually spent. This is the bound on plan explosion, and the only clause with no CR
   counterpart: it is an enumeration policy, stated here so it is reviewable.

**Execution** is separate and mechanical, and now follows the CR literally:

```
CR 601.2g  for each activation, in plan order: activate the class's first usable member,
           resolve it immediately (no stack, no priority — CR 605.3), add its mana and any
           CR 605.1b triggered bonus mana to the pool
CR 601.2h  for each payment, in symbol order: remove one mana of that type from the pool,
           or deduct 2 life
```

All activations run before any payment. That is what the CR prescribes — 601.2g is a distinct
step from 601.2h — and it is also what makes the model work: with production hoisted ahead of
consumption, a plan's feasibility no longer depends on the order in which its parts appear,
which is exactly the property the dedup argument in §3 needs. Whatever a plan produces and
does not spend **floats** until the step ends (CR 500.4), which stays legal and is
deliberately enumerable: tapping a Sprawl'd Forest for a single `{G}` is a legal plan whose
bonus mana is simply never claimed.

## 2. Usability, equivalence, and collapsing

Without collapsing, "tap 2 of my 5 Mountains for `{1}{R}`" is C(5,2)·permutations of plans
that differ only in which physically-identical land taps. The model collapses them at the
representation level: a plan never names a source object. It names a **source class** — the
equivalence class of interchangeable sources — and execution picks concrete members
deterministically.

### 2.1 What "usable" means (P-MANASICK)

Before the relation partitions anything, a filter decides which battlefield objects are *usable*
mana sources at all — the membership every capacity check counts. It is one predicate,
`manaSourceUsable`, and it has exactly two callers by design: `manaSourceClasses` (the planner)
and `resolveTapForMana` (the executor). They must agree exactly; two filter expressions in two
files is how they stop agreeing.

- A **sacrifice**-cost mana ability (CR 605.1a) is usable whether or not the source is tapped —
  its cost has no `{T}`.
- A `{T}` mana ability needs an untapped source (CR 602.2a).
- **CR 302.6** — a creature's activated ability with `{T}` in its cost can't be activated unless
  the creature has been under its controller's control continuously since their most recent turn
  began. A mana ability *is* an activated ability, so a summoning-sick Elvish Mystic taps for
  nothing.

The third clause was **missing** until the first creature mana source was encoded, and it could
not be observed before then: every mana source in the MVP pool was a land or an artifact. It is
the failure mode this whole document exists to avoid — not a crash, but mana in the enumerated
action space (ADR-005) that the rules do not permit, wrong *in the agent's favour*. Note the
clause is a property of the **object**, not of its printed card, so it never belongs in the
equivalence relation below: two otherwise-identical Elves, one sick, are the same class with a
membership of one.

### 2.2 Sources reserved by a sibling cost component (`FW-MANA`, triage trap T17)

`enumeratePaymentPlans` is given a cost and a seat, and until `FW-MANA` nothing else — so it did not
know *what* the cost belonged to. For an activated ability like "{1}, {T}: …" printed on a permanent
that is **also** a mana source, it would offer a plan that taps that very permanent to pay the `{1}`.
The plan enumerated, the agent picked it, mana was paid, and the `{T}` component then threw
"CR 602.2a: a {T} cost requires an untapped source". A crash, but the defect is the enumeration, not
the throw: an action in the enumerated space that the rules do not permit is the ADR-005 failure
§2.1 exists to prevent, and it is the same failure the summoning-sickness gap was.

`enumeratePaymentPlans` and `manaSourceClasses` therefore take a `reserved: Set<ObjectId>`, computed
at the two activation call sites by `manaSourcesReservedBy`. The exclusion is **by object, not by
class**: it shrinks a class's capacity by one and deletes the class only when it had a single member,
which is right — a second copy of the same card is a perfectly good payer.

What is reserved is deliberately exact, because over-reserving would trade a crash for a *silently
missing* legal plan, which is the worse of the two:

- **`TapSelf`** reserves the source outright. Every way of producing mana from it either taps it
  (breaking the `{T}`) or sacrifices it (removing it).
- **`SacrificeSelf`** reserves the source only when it is a *sacrifice*-cost mana source, which would
  consume it before the cost's own sacrifice. Tapping a permanent for mana and then sacrificing it is
  legal Magic, and that plan stays enumerated.
- **A mana-only cost reserves nothing.** An ability whose cost is just `{1}` may be paid by tapping
  its own source, and always could.

Two notes on scope. The reservation is a property of the *cost*, not of the source class, so — like
usability — it stays out of the equivalence relation. And a **spell's** cost was originally recorded
here as unaffected, on the grounds that a cast's sacrifice cost is paid after CR 601.2g and
sacrificing an already-tapped Mountain is legal; `FW-ADDSAC` (§2.3) found that half-right and
sharpened it.

**Equivalence relation.** Two usable battlefield objects controlled by the caster are
payment-equivalent iff they have the same printed card (`CardRef`) **and** the same
*production profile* — the canonical list of production alternatives their tap-for-mana abilities
offer, each a multiset of mana types (§8.1) — **and** the same CR 605.1b bonus, **and** the same
activation cost (tap vs sacrifice). Same-profile is the load-bearing half (it is what makes activating either
indistinguishable in every future game state); same-card is a deliberate safety margin: costs
that care about the card itself (Fireblast's "sacrifice two Mountains", Lava Dart's
flashback — docs/decklists.md) can never be wronged by an over-eager merge, at the price of
occasionally enumerating two plans where one would do.

The P8.3 reshape **does not touch the relation**. An Utopia-Sprawl-enchanted Forest was
already a distinct class from a bare Forest, because `bonus` is part of the key. What changed
is only what the enumerator *does* with that bonus: it is now the activation's yield rather
than a tag that exists to keep classes apart. This is the same "refine the profile, never the
relation" line Phase 5 drew, taken one step further — and it predicted that F10 (§8) would be a
profile problem rather than a relation problem.

**`FW-MANA` confirmed the prediction and did not touch the relation either.** The clause above still
reads exactly as written; what changed is only what a profile *is* (§8.1). An Urza's Tower with Tron
assembled forms a distinct class from one without, automatically, because the profile is computed
from state and the profile is what the key hashes on — and two Towers in the same state are still
payment-equivalent to each other, which is the whole point. The relation is now three packets old
and has survived every one of them; that is evidence it is the right cut.

### 2.3 Sacrifice costs with a *chosen* permanent (`FW-ADDSAC`)

`FW-ADDSAC` added two costs whose sacrificed permanent is **chosen** rather than named:
`AdditionalCost.Sacrifice(count, filter)` on a spell (CR 601.2b, paid at CR 601.2h) and
`AbilityCost.Sacrifice(filter)` inside an activated ability's composite cost (CR 602.1). Both raise
§2.2's question again, and the answer §2.2 gave for a spell — "reserves nothing" — turned out to be
right for the wrong reason and wrong in one case.

**The ordering had to move first.** `executeCastPipeline` paid every non-mana cost *before* the mana
plan, which was unobservable while the only such costs were escape's exile and Fireblast's
permission sacrifice (both on `{0}` casts). For Eviscerator's Insight — `{1}{B}` **and** sacrifice an
artifact or creature — it is not: the CR runs CR 601.2g (activate mana abilities) before CR 601.2h
(pay costs), so tapping an artifact land for the `{B}` and *then* sacrificing it is legal. The
intrinsic sacrifice is therefore paid **after** `payCosts`. The permission-side sacrifice was left
where it was; see the flag at the end of this section.

**What is reserved is one object, exactly.** A chosen permanent is excluded from funding its own
cost's mana **iff it is a sacrifice-cost mana source** (`isSacrificeSource` — an Eldrazi Spawn's
"Sacrifice this token: Add {C}"), because producing mana from it consumes it before the cost's own
sacrifice can. A tap-for-mana permanent reserves nothing: that is the legal play above, and deleting
it would be the silently-missing-plan failure §2.2 exists to prevent. This is the `SacrificeSelf`
rule of §2.2 applied to the chosen object instead of the source.

**Why an exact reservation is available at all** is the gathering order. CR 601.2b–i, which CR 602.2b
defers to wholesale, settles cost selections *before* the payment plan, so by the time
`enumeratePaymentPlans` runs, the chosen permanents are known: `sacrificeSourcesAmong` turns them
into the `reserved` set directly.

**Legality runs earlier than the choice**, and needs the other half:

- For a **spell**, `minimalSacrificeReservation` answers "is there *some* choice that leaves the cost
  payable" by reserving the minimal set any choice could force — candidates that are not
  sacrifice-cost mana sources first, so the reservation is empty whenever enough of them exist. This
  is exact for a one-permanent cost, which is every such cost the pool prints.
- For an **ability**, the mana and sacrifice components are checked *jointly*:
  `abilitySacrificeCandidates` keeps a candidate only if a plan exists with that candidate reserved,
  and the same function supplies the selection's options. One derivation, two callers — the
  discipline §2.1 established for `manaSourceUsable`, for the same reason: an ability enumerated
  against one candidate set and gathered against another dead-ends mid-activation.

**Two flags left open.**

1. `AdditionalCost.Sacrifice.count > 1` on a board whose every matching permanent is a sacrifice-cost
   mana source can make `minimalSacrificeReservation` optimistic (the greedy prefix is not a
   sufficient search over subsets). No pool card prints such a count; if one arrives, the payment
   fails **loudly** in `sacrificePermanents` rather than producing a wrong state, and the fix is a
   joint (selection, plan) enumeration.
2. The **permission**-side `SacrificeRequirement` (Fireblast, Lava Dart) is still paid before the
   mana plan and reserves nothing. That is under-reserving, not over-reserving, and it is currently
   unobservable because both pool cards have `{0}` mana costs — but a permission with a non-zero cost
   *and* a sacrifice would enumerate plans that spend a permanent already gone. Closing it means
   moving `paySacrificeCosts` after `payCosts` and giving it the same reservation.

### 2.4 Return-a-permanent costs, and the one place the reservation is *un*conditional (`FW-TAPUNTAP`)

`AbilityCost.ReturnPermanentYouControl(filter)` — Quirion Ranger's "Return a Forest you control to its
owner's hand: Untap target creature" — is §2.3's shape again with a different destination, and it
gathers, enumerates and reserves through the same three-way discipline: `abilityReturnCandidates` is
the joint (mana, chosen object) answer, and it is both the payability check and the selection's option
list.

**One rule differs, and the difference is a zone-change rule rather than a judgement call.** A chosen
*sacrifice* is reserved only when it is a sacrifice-cost mana source; a chosen *return* is reserved
**unconditionally**. Tapping a land for mana and then sacrificing it is legal Magic — CR 601.2g
precedes CR 601.2h and CR 701.17 does not care that the permanent is tapped — but a permanent returned
to its owner's hand becomes a **new object** (CR 400.7) in a zone with no tapped status at all
(CR 110.5). A plan that taps a Forest for mana and then returns it is therefore not a legal sequencing
of one payment; it is paying with an object the cost then cannot find. Reserving it is what stops that
plan being enumerated (ADR-005).

The asymmetry is invisible in the pool today — Quirion Ranger's cost is a bare return with **no** mana
component, so nothing is competing for the Forest — which is exactly why it is written down here
rather than left to be rediscovered by the first card that prints both.

**One flag left open.** No gauntlet card carries a chosen *sacrifice* and a chosen *return* in one
cost. If one arrives, the two joint answers would each reserve without seeing the other's choice, so
`abilityCostPayable` **fails loudly** on the pairing rather than over-offering; the fix is the same
joint enumeration flag 1 of §2.3 names.

## 3. Ordering, dedup, and why the enumeration is duplicate-free

**Stable ordering** (ADR-005 indices; ADR-006 replay). Source classes are ordered by first
appearance in battlefield order (insertion-stable, see the `GameState` iteration rule).
Nothing in the ordering reads the PRNG or any hash iteration order, so equal states always
enumerate equal lists.

Enumeration is a two-level search: **payments outer, activations inner**.

### 3.1 The payment level

Depth-first over the expanded symbols in printed order. Each symbol's candidate payments are
tried in a fixed order — mana types in WUBRG-then-colorless order (CR 105.1), then
`WithTwoLife` last for a Phyrexian symbol. Within a **run of identical symbols** the chosen
candidate indices are constrained to be **non-decreasing**.

The non-decreasing rule survives the reshape, and it survives it for a better reason than
before. Previously the rule was a canonicalization laid on top of a prefix-sensitive resource
check: a payment consumed resources as the search passed it, so the feasibility of a run's
assignments depended on their order, and choosing the non-decreasing representative was only
safe because every payment was independent of every other. Under the amended model that
dependence is gone outright — legality clauses 1–5 are **predicates on the whole plan**, and
each is invariant under permuting payments within a run (shape, because a run's symbols are
equal; capacity and no-idle, because they read `activations`; life and coverage, because they
read multisets). So a run's assignment is feasible iff every permutation of it is, and the
non-decreasing representative may be chosen with no loss.

That is precisely the property the old model lost the moment a tap could yield two mana. Under
the old prefix-consuming search, `{1}{1}` paid off one Sprawl'd Forest needed the run's units
assigned tap-then-pool — a *decreasing* pair in the old candidate order, since `FromPool`
sorted before `ByTapping` — so the canonical representative was infeasible while a
non-canonical permutation was feasible, and the plan vanished. Hoisting production out of the
payment list is what removes the ordering from the predicate, and removing it from the
predicate is what lets one canonicalization rule stay correct.

### 3.2 The activation level

For a fixed payment assignment, the activations are enumerated as **sorted multisets** over
the flat option list `(class in battlefield-class order) × (type in the class's profile, in
WUBRG-then-colorless order)`, by non-decreasing option index, in increasing length. Each
distinct multiset is therefore generated exactly once, and lengths are bounded by §4.

### 3.3 The dedup argument

*Claim.* Every legal plan, in its canonical form, is enumerated exactly once.

*At most once.* Two enumerated plans are equal as data iff their `activations` lists and their
`payments` lists are equal. The payment DFS generates each per-run multiset of payments
exactly once — standard combinations-with-repetition: a non-decreasing sequence over a fixed
candidate list is the unique sorted representative of its multiset. The activation search
generates each sorted multiset exactly once for the same reason. A plan is the pair, so the
pair is generated at most once. ∎

*At least once.* Take any legal plan `P`. Sort its activations into canonical order and sort
each run of its payments into candidate order; call the result `P'`. `P'` is legal, because
every legality clause is permutation-invariant as argued in §3.1. `P'` is exactly the form the
two searches build, and neither search prunes a leaf that satisfies clauses 1–5, so `P'` is
reached. ∎

*Semantic distinctness.* Two enumerated plans that differ as data also differ in outcome. The
post-payment state is determined by `(activations, demand multiset)`: activations fix which
permanents are tapped or sacrificed and what enters the pool, and the demand fixes what
leaves it — `pool_after = pool_before ⊎ yields(activations) ⊖ demand`. Differing activations
therefore leave a different battlefield or a different pool; differing payments with equal
activations leave a different pool. This is a *strengthening* over the pre-P8.3 model, which
had a latent duplicate: because a payment named its own source, paying `{1}{G}` by tapping
Forest-A then Forest-B and by tapping Forest-B then Forest-A were two enumerated plans with
one outcome (the units are in different runs, so the non-decreasing rule never saw them).
Hoisting activations into an order-free multiset removes that class of duplicate entirely.

### 3.4 Why a payment must not name its source

A tempting smaller change is to keep `WithMana(mana, source)` and let `source` distinguish
"mana that was already floating" from "mana this plan produced". It manufactures duplicates.
With two green floating, a Sprawl'd Forest, and a cost of `{1}{G}`, the tags
`[Produced, Produced]`, `[Pool, Produced]` and `[Produced, Pool]` are three distinct plans
with one identical outcome — the units sit in different runs, so no dedup rule collapses them,
and the tag adds no information the `activations` list did not already carry. The source tag
is removed for that reason, not for tidiness.

## 4. The bound on plan explosion

Surplus mana widens the search, so the enumeration is bounded by one policy clause, legality
clause 5:

> **No idle activation.** Every activation in a plan must have at least one of its yielded
> mana actually spent by that plan.

Formally: there must exist an injective, type-respecting assignment of activations to spent
mana. Because mana types do not substitute for one another, this is a bipartite matching
between activations and mana types, where activation `a` may claim type `t` iff
`t ∈ yield(a)`, and type `t` has capacity `demand(t)`. It is checked by the deficiency form of
Hall's theorem over the (at most 64) subsets of the six mana types: for every type set `T`,
the activations whose yield lies wholly inside `T` must number no more than
`Σ_{t ∈ T} demand(t)`. That formulation is exact — any violating activation set can be
enlarged to one of this form — and it is a fixed 64-iteration check rather than a search.

Two consequences worth stating:

- **`|activations| ≤ |WithMana payments|`.** Each activation claims a distinct unit of demand,
  and every unit of demand is one `WithMana` payment. So a `{7}` cost admits at most seven
  activations however wide the battlefield is, and the activation search's length bound is a
  consequence of the rule rather than an arbitrary cap.
- **Deliberately over-tapping is not enumerated.** Tapping a source whose mana this cast will
  not spend — floating it for a *later* spell in the same step — is legal Magic and is not an
  option here. This is not a P8.3 regression: the pre-P8.3 model, with one tap per symbol,
  could not express it either. It is recorded as a known gap in §9.

The rule is deliberately weaker than strict minimality ("no activation is removable"). Strict
minimality would delete the distinction between paying a `{R}` from a floating red and paying
it by tapping a Mountain while the floating red survives — two genuinely different board
states, both enumerated before P8.3 and both still enumerated now.

**Measured effect** (`BoglesRampBudgetAcceptanceSpec`, which pins both columns' shape as tests).
Board: six lands, the deck's own mix — two Utopia-Sprawl'd Forests (green chosen), a
Wild-Growth'd Forest, an Abundant-Growth'd Forest (any colour, a layer-6 *grant*, not a
CR 605.1b bonus), a bare Forest and a Plains — plus a Gladecover Scout for the Auras to enchant.
Pool empty. "Lands" is the fewest activations any enumerated plan uses.

| Card | Cost | Options before | Options after | Lands before | Lands after |
|---|---|---|---|---|---|
| Rancor | `{G}` | 3 | 3 | 1 | 1 |
| Malevolent Rumble | `{1}{G}` | 18 | **16** | 2 | **1** |
| Ancestral Mask | `{2}{G}` | 35 | **32** | 3 | **2** |
| Kruphix's Insight | `{2}{G}` | 35 | **32** | 3 | **2** |
| Armadillo Cloak | `{1}{G}{W}` | 20 | **16** | 3 | **2** |

Two things to read off it.

**The last two columns are the packet.** Before P8.3 a plan activated exactly one mana ability
per symbol, so on an empty pool the lands-tapped count *was* the symbol count, always — that is
a structural property of the old shape, not a measurement. Malevolent Rumble could not be cast
off one land; Ancestral Mask could not be cast off two. Both now can, and a GW Bogles agent's
action space contains those lines for the first time.

**The option count went down, not up.** The feared explosion did not happen, and the reason is
§3.3: the reshape adds the fewer-activation lines but deletes a whole class of cross-run
permutation duplicates that the old per-symbol source tag manufactured. On this board the second
effect is the larger one. The counts are pinned as assertions so a later change that does inflate
the action space fails loudly rather than quietly making the environment harder to consume.

## 5. The cost shapes, and why they fit

- **Hybrid `{G/U}`** — the symbol accepts two mana types; a plan records which side was
  chosen because the `WithMana` payment names its type. Both colors available ⇒ exactly two
  plans (they leave genuinely different battlefields).
- **Phyrexian `{R/P}`** — `WithTwoLife` is a peer payment, not a special case; the life-total
  bound is checked at enumeration so an unaffordable life plan never appears.
- **Any-color sources** — a source's profile lists every type it can produce, and
  `ManaActivation.produced` fixes the choice, so "add one mana of any color" needs no extra
  machinery.
- **`{C}`** — `COLORLESS` is an ordinary `ManaType`; `{C}` simply demands it while generic
  accepts it, exactly the CR 107.4c distinction the core mana model already draws.
- **Sacrifice-cost mana abilities** (CR 605.1a — an Eldrazi Spawn's "Sacrifice this token: Add
  `{C}`") — an activation, not a tap; `SourceClassKey.viaSacrifice` keeps it a distinct class
  and execution sacrifices instead of tapping. Nothing else differs.

## 6. Triggered mana abilities mid-payment (Phase 5, amended by P8.3)

Utopia Sprawl's "whenever enchanted Forest is tapped for mana, its controller adds an
additional one mana of the chosen color" and Wild Growth's printed additional `{G}`
(both CR 605.1b) are **part of an activation's yield**, not a separate event the plan is blind
to. Execution is unchanged — resolving an activation fires the triggered mana abilities
immediately, no stack and no priority (CR 605.3), and the mana lands in the same pool. What
changed in P8.3 is that the enumerator now *knows* it lands there, so the surplus can pay
another symbol of the same cost instead of only a later spell.

The mana-pool-emptiness invariant keeps its declared float exception (a seat controlling a
permanent enchanted by an Aura with a triggered mana ability may hold mana at a pause); P8.3
does not widen it, because the reshape spends float rather than creating more of it.

## 7. Superseded — the pre-P8.3 claim that nothing reshapes

The P2.1 note asserted that CR 605.1b "slots into execution, not the plan shape … plans stay
declarative and single-mana-typed … nothing reshapes." **That was wrong**, and it is left here
because the reason is instructive. It is true that the extra mana reaches the pool without any
plan change; what it misses is that the *enumerator* takes its pool snapshot once, before the
plan is built, so a bonus produced by the plan's own activation is invisible to the plan. The
consequence was an ADR-005 gap, not a cosmetic one: a Wild-Growth'd or Sprawl'd land could not
be enumerated as paying `{1}{G}` in a single cast, and GW Bogles — whose list runs **five** ramp
Auras (4 Utopia Sprawl + 1 Wild Growth; the 4 Abundant Growth are colour *fixing*, a layer-6
grant, and were never affected) — systematically under-counted its available mana in the action
space presented to a training agent. The lesson for future notes: "the extra state reaches the right place at
runtime" does not imply "the enumerator can see it", and for an enumerated-action engine only
the second one counts.

## 8. Conditional and variable-amount production (`FW-MANA`)

P8.3 predicted this section and settled the hard half of it in advance. Everything §8 of the P8.3
note listed as "the parts F10 would reshape" — `PaymentPlan`, `SymbolPayment`, the payment search,
the dedup rule and the executor — **did not change**, and the prediction that `activationYield` was
"the seam, and the only one" held, with one qualification recorded in §8.2.

### 8.1 The production descriptor

`SourceClassKey.profile` was the list of mana types one activation may *choose between*, and an
activation's yield was `[produced] + bonus` — exactly one mana of choice plus a fixed bonus. It is
now the list of **alternatives**, each a multiset:

```kotlin
data class SourceClassKey(
    val card: CardRef,
    val profile: List<List<ManaType>>,   // the alternatives; never empty, none empty
    val bonus: List<ManaType> = emptyList(),
    val viaSacrifice: Boolean = false,
)

data class ManaActivation(val sourceClass: SourceClassKey, val produced: List<ManaType>)

fun activationYield(key: SourceClassKey, produced: List<ManaType>) = produced + key.bonus
```

A Forest is `[[G]]`, a Bridge offering a choice is `[[U], [R]]`, an Urza's Tower with Tron
assembled is `[[C, C, C]]`, a Priest of Titania with three Elves out is `[[G, G, G]]`. One
*option* in the activation search is one alternative, not one mana, which is why nothing in that
search had to change: the option list's length tracks the choices a source offers, and how much
each choice adds is only ever read through `activationYield`.

Three consequences worth stating.

- **An alternative that evaluates to zero mana is dropped**, and a source all of whose alternatives
  do is not a mana source in that state (`productionProfile` returns `null`). A Priest of Titania
  with no Elf on the battlefield taps for nothing, and no legal plan could have contained it anyway
  — the no-idle rule (§4) rejects any plan with an activation that spends nothing. Pruning it at the
  profile keeps the key's "no empty alternative" invariant exact rather than aspirational.
- **The alternatives are canonically ordered** lexicographically by `ManaType` ordinal, shorter
  first. On the singleton alternatives that make up almost the whole pool this is exactly the old
  WUBRG-then-colorless type order, so no ordinary board's enumeration order moved (ADR-006).
- **A mixed multiset is declarable since `FW-TAPUNTAP`, and the prediction held exactly.** This
  section used to read "expressible in the key but not yet in a definition": a mixed `profile` value
  was already legal, and what was missing was `ManaAbility` vocabulary to *declare* it, because
  `ManaAmount` multiplies a single chosen type. `ManaAmount.FixedMultiset` is that vocabulary, and
  adding it cost **nothing** below the definition layer — no change to `SourceClassKey`, to the
  equivalence relation, to plan shape, to the payment search, or to the executor. It is the one
  `ManaAmount` member that supplies its own types rather than a count, so `productionProfile` builds
  **one** alternative from it instead of one per option: "add {W}{U}" is not "add one mana of white
  or blue", and collapsing the two would halve the card. `ManaAbility`'s `init` requires its
  `options` to be exactly the multiset's distinct types in WUBRG order, so the two halves of the
  declaration cannot drift.

### 8.2 The definition vocabulary, and CR 605.2 versus CR 601.2f

`ManaAbility` keeps `options` — the types the activator chooses between — and gains `amount`,
defaulting to `ManaAmount.Fixed(1)`, so every existing definition is untouched:

```kotlin
sealed interface ManaAmount {
    data class Fixed(val count: Int)
    data class PerPermanent(val each: PermanentFilter)                                   // Priest of Titania
    data class Conditional(requires: List<PermanentFilter>, ifMet: Int, otherwise: Int)  // the Urza lands
}

data class PermanentFilter(val subtype: Subtype, val controlledByYou: Boolean)
```

**The two rules are different, and the difference is the point of the packet.** A cost reduction is
CR 601.2f: the total cost is *determined* once, early in casting, and nothing that happens while
paying can change it — that is what makes cost-modification.md's lock-in tests meaningful. A mana
ability's amount is never determined in advance at all. The ability resolves during CR 601.2g, in
the middle of paying a cost that was already fixed, and the count is read at that moment; CR 605.2's
own worked example is a counted mana ability. So with Tron assembled a cost locked in at `{7}` is
paid by three activations producing 2 + 2 + 3, and nothing about the count feeds back into the cost
or vice versa.

For this engine the practical consequence is uncomfortable rather than academic: **the enumerator
must build a plan around a number it does not own.** §8.3 is how that is made safe.

The one qualification to P8.3's "`activationYield` is the only seam": the seam is only one
*function*, but the derivation around it — `productionProfile`, `triggeredManaBonus`,
`sourceClassKeyOf` — had to move into one file (`ManaSourceClass.kt`) and gain a single caller-pair
discipline, because `resolveTapForMana` used to rebuild the key inline. `P-MANASICK` had already
flagged that inline rebuild as "a standing hazard of the same shape: a future change to the key that
misses this call site fails the same way", and making the key state-dependent *was* that future
change. The hazard is closed structurally, not by care.

### 8.3 Planner/executor correspondence under a state-conditional count

This is the packet's central risk, and the answer is that **the source class key is the
correspondence certificate.**

The state-derived count lives *inside* `profile`, which lives inside the key, and the key is what a
plan names. Execution does not take the count from the plan: `resolveTapForMana` re-derives the
whole key from live state via the shared `sourceClassKeyOf` and activates the first usable member
whose re-derived key **equals** the planned one. So an activation whose count moved between
enumeration and payment matches no member and fails loudly, rather than quietly adding a different
amount of mana than the plan declared. Correspondence is structural: there is no code path that
adds a re-derived amount to the pool while the plan says something else.

**What the argument rests on, given the oracle's blind spot.** `PaymentEnumerationOracle` imports
`manaSourceClasses` by design (§10), so it can never independently catch a source-derivation bug —
if the profile were wrong, the oracle would be wrong in exactly the same way and the set comparison
would still pass. The correctness of production therefore rests on three things that are *not* the
oracle:

1. **The correspondence property** (`assertExecutesAsDeclared`), which executes every enumerated
   plan and asserts the resulting pool equals `pool_before ⊎ yields ⊖ demand`. Execution reaches the
   pool through `resolveTapForMana`, which re-derives its own key; the expectation reaches it
   through `activationYield` on the *planned* key. Two different routes to the same number, so a
   derivation bug shows up as a mismatch rather than cancelling out. It now runs over six
   board-dependent scenarios as well as the static ones.
2. **Per-card definition tests** in `mtg-cards`, which assert the printed shape against the oracle
   text — that the Tower's `ifMet` is three and the Mine's is two, that the Urza conditions name
   subtypes and that `Urza's Power-Plant` is hyphenated where the card's name is not, that Priest of
   Titania's filter is `controlledByYou = false`. A profile computed correctly from a wrong
   declaration is the failure mode the oracle structurally cannot see, and this is what sees it.
3. **The end-to-end acceptance measurements** (`MonsterTronBudgetAcceptanceSpec`), which reach the
   options through a real cast and pin both an assembled and a broken board — a control column, so
   "the condition is read" is asserted by difference rather than by a single number.

**The residual gap, precisely.** Execution runs activations in plan order, and one activation can in
principle change another's count — only by *removing* a counted permanent, which only a
sacrifice-cost mana ability does. No board in the gauntlet pool can build it: the sole sacrifice
mana source is an Eldrazi Spawn, which is neither an Elf nor an Urza land. A rules fixture that
does build it is tested, and it throws (`CR 605.2: a production count that moves mid-payment fails
loudly…`). Tapping, by contrast, can never move a count in this pool, because every condition reads
control or presence and none reads tapped-ness. When a card does make the combination reachable the
fix is an execution-order rule — battlefield-removing activations last — or a per-plan order the
enumerator validates; it is **not** a licence to let execution use the planned amount, because that
would be CR 601.2f behaviour on a CR 605.2 ability.

### 8.4 The no-idle bound, re-measured

§4's bound is deliberately weak: "every activation spends at least one of its yielded mana" admits
a plan that taps an Urza's Tower to pay one symbol and wastes two. The consequence it *does* still
guarantee — `|activations| ≤ |WithMana payments|`, because each activation claims a distinct unit of
demand — is unaffected by how much a single tap yields, so the search's length bound is unchanged.

Measured on a realistic Monster Tron board (`MonsterTronBudgetAcceptanceSpec`, pinned as tests).
Board: five lands — an Urza's Mine, an Urza's Tower, an Urza's Power Plant, a second Urza's Mine and
a Forest — plus a Gladecover Scout for the Auras. Assembled, that is eleven mana in four source
classes. The **control** column is the same five lands with the Power Plant swapped for a Forest,
which fails all three conditions at once: every Urza land back to one mana, which is also what the
engine did for every source before this packet. Pool empty. "Lands" is the fewest activations any
enumerated plan uses.

| Card | Cost | Options, broken | Options, assembled | Lands, broken | Lands, assembled |
|---|---|---:|---:|---:|---:|
| Rancor | `{G}` | 1 | 1 | 1 | 1 |
| Malevolent Rumble | `{1}{G}` | 3 | 3 | 2 | 2 |
| Ancestral Mask | `{2}{G}` | 4 | **7** | 3 | **2** |
| Scour from Existence | `{7}` | **0** | **7** | — | **3** |

Three things to read off it.

**The feared explosion did not happen.** Seven options is the *largest* number on the board, for a
seven-symbol cost across four source classes — comfortably smaller than the Bogles board's 32 for a
three-symbol cost, because the Urza lands all produce the same type and so collapse hard. The weak
no-idle rule costs very little here: a plan that wastes two of a Tower's three mana exists, but only
where wasting is the only way to pay the symbol at all.

**The coloured costs get no discount, and that is correct.** The Urza lands add colorless, so `{G}`
still needs the Forest and `{1}{G}` still needs two lands. The framework is credited with exactly
the all-generic gain and nothing more, which is what the Rancor and Malevolent Rumble rows pin.

**`{7}` went from unpayable to seven options.** That is the packet in one number: Monster Tron's
whole plan is a cost bracket the engine could not previously reach, and a Monster Tron agent's
action space now contains it.

**The Bogles board is unchanged.** `BoglesRampBudgetAcceptanceSpec`'s pinned counts (3 / 16 / 32 /
32 / 16) and its fewest-lands column pass **unmodified**. That is the expected result and is worth
saying explicitly: no card on that board has a board-dependent amount, so every profile there is a
singleton alternative and the reshape is a pure widening.

## 9. Known gaps

- **Deliberate over-tapping is not enumerated** (§4). Activating a mana ability purely to
  float mana for a later spell in the same step is legal and unavailable. Pre-existing, not a
  P8.3 regression; it needs a standalone "activate a mana ability" priority action to fix, not
  a payment-model change, and it should be weighed against the action-space blow-up that
  action would cause.
- ~~**Alternative activation costs are the class key, not a choice.**~~ **Closed by
  `FW-MANACOST`** (§11). The cost moved off `SourceClassKey` and into the production alternative, so a
  source printing a free `{T}` ability beside a costed one offers both and the activator chooses. What
  survives is a narrower fact: `isSacrificeSource` still asserts that no source *mixes* a sacrifice
  mana ability with another kind, because the T17 reservation reads the source as a whole.

- ~~**A mana ability's cost is still only `{T}` or "sacrifice this".**~~ **Closed by
  `FW-MANACOST`** (§11), and the prediction it was recorded with held: it *was* a payment-capacity
  problem rather than a production one, and it *was* the larger of the two. What the entry did not
  predict is the third clause the capacity problem needed — acyclicity (§11.2) — which is neither
  production nor capacity but ordering.

- **A mana ability's cost cannot need a mid-payment decision.** [ManaAbilityCost] admits mana, tap,
  sacrifice, tap-another-creature and put-a-counter, and every one of those is either forced or
  recorded in the plan before execution starts. CR 601.2g resolves mana abilities *inside* another
  cost's payment, where the decision-point engine has nowhere to suspend (ADR-004), so a component
  like "discard a card" or "sacrifice a permanent of your choice" cannot be added without either
  recording the choice in the plan (multiplying every plan by the board) or reshaping payment into a
  resumable sub-pipeline. No gauntlet card prints one on a *mana* ability.

- **An activation can, in principle, change another activation's CR 605.2 count.** The engine
  throws rather than mispaying (§8.3). Unreachable in the gauntlet pool; the fix when it becomes
  reachable is an execution-order rule, not a change to what execution reads.

- ~~**A mixed-type multiset is not declarable.**~~ **Closed by `FW-TAPUNTAP`** (Azorius Chancery).
  The gap was called correctly: it needed exactly one `ManaAmount`-sized addition in `mtg-core`
  (`ManaAmount.FixedMultiset`) and **no** change to the payment model — see §8.1. Left listed rather
  than deleted, because a gap that was predicted and then cost what it was predicted to cost is the
  evidence that the model's seams are where this document says they are.

## 10. Why enumeration completeness is testable

The plan space for a fixed `(cost, sources, pool, life)` is finite and small, so tests keep a
**brute-force oracle**: naively generate every raw payment assignment and every activation
multiset up to the length bound, keep the ones satisfying legality clauses 1–5 computed
independently of the enumerator, canonicalize, deduplicate, and set-compare against the
enumerator's output. Equality proves both directions at once — no missing plan, no phantom
plan, collapsing exactly right.

**The oracle's blind spot, stated plainly.** It imports `manaSourceClasses` rather than re-deriving
source classes, so it shares the enumerator's view of what each source produces. That is deliberate
— re-implementing the layer system and the CR 605.2 evaluation inside a test would be
re-implementing the engine — but it means the oracle proves *the search* correct and can never prove
*the production* correct. What carries production instead is listed in §8.3.

**`FW-MANACOST` widened the oracle rather than working around it.** Its naive halves now enumerate
cost assignments as a raw cartesian product (so the enumerator's non-decreasing canonicalisation is
*proved* lossless rather than assumed), net activation costs out of coverage, decide orderability by
walking **every permutation** where the enumerator walks a memoized subset DP, run the no-idle
matching as an exhaustive assignment with the same per-pair exclusion, and count the creature budget
by naming objects where the enumerator uses per-class prefix arithmetic. Four different algorithms
reaching the same set is what makes agreement evidence rather than a shared assumption; the blind
spot is unchanged, and §8.3's three non-oracle legs are what cover it.

P8.3 adds a third property, the one a reshape most needs: **planner/executor correspondence.**
For every enumerated plan, executing it must succeed, and the resulting pool must equal
`pool_before ⊎ yields(activations) ⊖ demand` with every count non-negative. A plan that
enumerates but cannot execute, or executes to something other than what it declared, is the
worst defect this model can have, so it is asserted directly rather than inferred.

On top of those sit the two end-to-end completeness properties (ADR-005): every enumerated
cast option must execute through the full CR 601 pipeline without error (no phantoms), and
constructed scenarios where a cast is legal/illegal must/must-not enumerate it (no gaps). The
fuzz harness then defends all of it across seeds.

## 11. Mana abilities that cost something (`FW-MANACOST`)

`FW-MANA` closed the production half of a mana ability and left the cost half open, recording it in
§9 as "a payment-**capacity** problem, not a production one, and the larger of the two". It was, and
the packet that built it found a third thing underneath: **ordering**.

Four cost shapes arrive, and they are deliberately different problems rather than four instances of
one:

| Shape | Card | What it consumes | Where it lands |
|---|---|---|---|
| `{1}, {T}` | Conduit Pylons, Giant's Boulder | the pool | §11.1, §11.2 |
| `{1}` alone, once each turn | Barrels of Blasting Jelly | the pool, and nothing else | §11.2, §11.4 |
| `{T}`, Tap an untapped creature you control | Saruli Caretaker | a creature that belongs to no class | §11.3 |
| Put a `-0/-1` counter on this, once each turn | Wall of Roots | nothing at all | §11.4 |

### 11.1 The cost moved into the production alternative

`SourceClassKey.profile` was a list of produced multisets and the key carried one cost flag,
`viaSacrifice`. That shape is wrong the moment one permanent prints two mana abilities with
*different* costs — Conduit Pylons' free "{T}: Add {C}" beside its "{1}, {T}: Add one mana of any
color" — because which cost applies is then a per-activation choice, not a property of the source.
So the profile's element became a whole alternative:

```kotlin
data class SourceClassKey(val card: CardRef, val profile: List<ProductionAlternative>, val bonus: List<ManaType>)

data class ProductionAlternative(
    val cost: List<ManaAbilityCost>,   // printed order; [TapSelf] for an ordinary source
    val produced: List<ManaType>,
    val oncePerTurn: Boolean = false,
)

data class ManaActivation(
    val sourceClass: SourceClassKey,
    val alternative: ProductionAlternative,
    val costPayment: List<ManaType> = emptyList(),   // CR 601.2g, one per expanded cost symbol
)
```

`viaSacrifice` is **deleted** from the key; it is now `SacrificeSelf in alternative.cost`. This is
the same "refine the profile, never the relation" line §2.2 has now held across three packets: two
Walls of Roots are payment-equivalent, one that has spent its once-each-turn activation is not a
mana source at all, and the equivalence relation itself is unchanged.

**`costPayment` is the one genuinely new piece of plan data**, and it is there because paying a `{1}`
activation cost with green and paying it with red leave different pools. The executor may not ask —
CR 601.2g runs inside another cost's payment, where ADR-004 offers no suspension point — so the
choice has to be settled in the plan. Legality clause 4 (coverage) grows the corresponding term:

> `demand(t) + Σ_a cost(a).count(t) ≤ pool(t) + Σ_a yield(a).count(t)` for every `ManaType t`.

### 11.2 Acyclicity: the clause with no pre-`FW-MANACOST` counterpart

Coverage is necessary and **not sufficient**. Two Giant's Boulders on an empty pool produce two mana
and cost two mana; the arithmetic balances exactly and neither can go first. Worse shapes exist in
principle — a source costing `{R}` to add `{G}{G}` beside one costing `{G}` to add `{R}{R}` passes
coverage type by type and still deadlocks — so this is not a special case to test for but a
constraint the model has to carry.

The fix is *not* to record an execution order in the plan. That would multiply every plan by its
permutations and falsify the §3.3 dedup argument, whose whole content is that two plans differing as
data differ in outcome — and the same multiset run in two feasible orders leaves the identical state.
The order is **derived** instead, by `manaActivationOrder`, from the multiset, the pool and the
recorded `costPayment`s. Two facts make that cheap:

1. **Free activations run first, in plan order.** A free activation only ever adds to the pool, so
   moving one earlier can never make another unpayable. Every plan on every board before this packet
   is entirely free, which is why no existing board's execution order moved (ADR-006).
2. **The pool after a *set* of costed activations depends only on the set** — `base ⊎ Σ yields ⊖ Σ
   costs` — not on the order within it. So feasibility is a subset property, and the search is a DP
   over subsets rather than over permutations.

`canFinish[remaining]` is filled bottom-up over subsets, and the canonical order is read off it
greedily: take the lowest-indexed remaining activation whose cost the current pool covers **and**
whose removal leaves a finishable remainder. The second condition is load-bearing — plain "run
whatever is payable" strands a later activation that needed exactly the mana just spent.

**One derivation, two callers**, the discipline `P-MANASICK` established for `manaSourceUsable` and
`FW-MANA` for `sourceClassKeyOf`. `manaActivationOrder` is what the enumerator calls to decide a
candidate multiset is feasible at all, and what `payManaPlan` calls to decide what to run first. A
plan accepted on one ordering rule and executed on another is exactly the enumerated-but-unexecutable
defect ADR-005 forbids.

### 11.3 A budget that belongs to no source class

Saruli Caretaker's "{T}, Tap an untapped creature you control" consumes a resource the class model
has no name for: an untapped creature, which need not be a mana source at all and may belong to a
different class or to none. Two Caretakers and no other creature make **one** mana between them, and
no amount of per-class capacity counting sees that.

The model adds one shared budget — the seat's untapped creatures — and one drain per activation:

- the member itself, when the alternative taps or sacrifices it *and* that member is an untapped
  creature (for a tap alternative every member is untapped by availability; for a sacrifice
  alternative it varies, which is why the drain is read per **member index** rather than per class —
  members are spent in battlefield order by planner and executor alike);
- plus one for a `TapAnotherCreature` component.

**Why the plain count is exact.** All the consumptions are distinct objects — a source tapped for its
own `{T}`, a source sacrificed, and a creature tapped as somebody's second cost are three different
permanents, the source never being its own helper because its `{T}` has already tapped it. With no
type or restriction on which creature may be tapped, Hall's condition on that budget degenerates to
"drain ≤ untapped creatures", which is therefore both necessary and sufficient.

**The helper is an engine choice, not a player one.** CR 601.2g admits no decision point, so the
executor takes the first untapped creature in battlefield order that is neither the source nor a
member the *rest* of the plan still needs. One always exists: the drain still to come is exactly the
reserved members plus this helper, and the budget check already bounded the total. It fails loudly if
one ever does not, because that would mean the enumerator offered a plan execution cannot carry out.

### 11.4 "Activate only once each turn" (CR 602.5b)

`ManaAbility` gains `oncePerTurn`, and `GameObject` gains `manaAbilitiesActivatedThisTurn` — the
indices of its **printed** mana abilities activated during the turn in progress, cleared for every
object as a turn begins (CR 500.1: "each turn" is each *player's* turn, so a Wall of Roots spent on
your turn taps again on your opponent's).

The restriction needs no new machinery in the payment model, because **the source class key already
carries it**. An ability spent this turn is unavailable, an unavailable ability contributes no
alternative, and a source with no alternative is no mana source — so a spent Wall of Roots simply
leaves `manaSourceClasses`, and the executor's re-derivation of the key agrees by construction. This
is the third thing the §8.3 correspondence certificate has been asked to carry (production count,
activation cost, and now availability) without changing shape.

Two guards make it exact rather than aspirational, both `require`s that fail loudly:

- **A mana ability that neither taps nor sacrifices its source must be `oncePerTurn`.** The capacity
  model counts one activation per class member, and that is only a bound if activating a member stops
  it being available. Wall of Roots' counter cost and Barrels' bare `{1}` leave the source untouched,
  so CR 602.5b is the *only* thing bounding them; without either, no finite plan enumeration exists.
- **At most one `oncePerTurn` mana ability per card, and it must be printed rather than granted.**
  The record indexes printed abilities (the layered list's prefix), so a granted ability's index is
  unstable and two identically-costed restricted abilities could not be told apart when the executor
  marks the activation.

### 11.5 The no-idle rule, when an activation can be a customer

§4's bound is a bipartite matching of activations to units of demand, decided by Hall's theorem over
the 64 subsets of the six mana types. A costed activation may legitimately spend its mana on
*another activation's cost*, so the sinks are no longer the demand alone — and the exclusion that
comes with them is per-**pair**, not per-type: an activation may fund any cost except its own.

Hall's condition over type subsets cannot express a per-pair exclusion, so the costed path is a real
matching: activations on the left, one node per unit of demand and per unit of each activation's
cost on the right, an edge when the activation yields that type and does not own that unit, decided
by Kuhn's algorithm. Both sides are bounded by the plan's activation count.

**The old check is kept and still runs** on every set in which no activation costs mana. The two
agree there — with no cost units the sinks *are* the demand — and keeping it means no pre-packet
board pays for the generality, which is what lets §4's and §8.4's measurements stand unmodified.

The exclusion earns its place on a real card. Barrels of Blasting Jelly reads "{1}: Add one mana of
any color"; an activation that eats a green and adds a green has changed nothing, and its yield's
only possible consumer is the cost it just paid. Without the exclusion Hall's condition counts it as
gainfully employed and every cast on a Barrels board grows a family of per-colour no-ops.

**The activation bound moved with it.** "`|activations| ≤ |WithMana payments|`" was a *consequence* of
no-idle: each activation claims a distinct unit of demand. With cost units among the sinks the bound
becomes `demand + Σ (class capacity × widest cost)`, capped by total class capacity — and it
evaluates to exactly the old `demand` on a board with no costed mana ability.

### 11.6 What it costs, measured

`CostedManaSourceAcceptanceSpec` pins a Spy-Combo-shaped board: two Forests, a Wall of Roots, a
Saruli Caretaker and a Barrels of Blasting Jelly, all settled, pool empty.

| Card | Cost | Options |
|---|---|---:|
| Rancor | `{G}` | 10 |
| Malevolent Rumble | `{1}{G}` | 80 |
| Ancestral Mask | `{2}{G}` | 106 |

**This is the widest action space any board has produced**, against the Bogles board's 32 and
assembled Tron's 7 for comparable costs, and the cause is worth naming precisely: it is not the
costed ability as such but the **filter**. Rancor's ten options are three direct lines plus seven
that route a mana through the Barrels — one per (funder × colour) — and every one of them is a
genuinely distinct position, because it spends the Barrels' once-each-turn activation. The weak
no-idle rule cannot prune them, because a filter's output always has a sink.

Stated as a rule for whoever adds the next filter: **a mana filter multiplies the plan space by
roughly (colours it can add × mana that can fund it)**, and two of them multiply. If a gauntlet deck
ever runs four Conduit Pylons alongside another any-colour source, this is the number to re-measure
before assuming the enumeration stays tractable.

**The two pinned budget boards did not move.** `BoglesRampBudgetAcceptanceSpec`'s counts (3 / 16 / 32
/ 32 / 16) and `MonsterTronBudgetAcceptanceSpec`'s (1 / 3 / 7 / 7) pass unmodified, and structurally
must: no card on either board has a costed mana ability, so every alternative there still carries the
same `[TapSelf]` cost, the creature budget is never consulted, the ordering search returns the free
prefix immediately, and the no-idle check takes the Hall path.

### 11.7 The four cards it did not build, and exactly what each needs

`FW-MANACOST` was offered seven cards and encoded three. The other four are **not** blocked on
anything in this note — every one of them has a mana ability the framework now expresses — and each
is blocked on exactly one thing outside it. Recorded here because "the mana half is done" is the
piece a later packet will otherwise re-derive.

| Card | Mana ability | What still blocks it |
|---|---|---|
| Conduit Pylons | `{T}: Add {C}` **and** `{1}, {T}: Add one mana of any color` — the two-costs-one-source shape §11.1 was built around | **Surveil 1** on its enters trigger. `LibraryLookMode` distributes to {hand, top, bottom}; a graveyard destination is a documented non-goal of `FW-LIBLOOK` (library-look.md §12). |
| Giant's Boulder | `{1}, {T}: Add one mana of any color` | **"Destroy target permanent."** `PermanentRestriction` has `CREATURE`, `NONLEGENDARY_CREATURE`, `CREATURE_POWER_2_OR_LESS` and `ARTIFACT`, and no plain-permanent member; adding one is a `Targets.kt` change. Its scry 2 enters trigger is already expressible. |
| Basilisk Gate | `{T}: Add {C}` — expressible since P2.1 | A **snapshotted** `+X/+X` where X counts Gates (triage T16: `Magnitude.Dynamic` is the opposite semantics), plus the same missing plain-permanent target. Its "Activate only as a sorcery" **is** now expressible — `ActivatedAbility.timing`, CR 602.5d — which is the half this packet supplied. |
| Bender's Waterskin | `{T}: Add one mana of any color` — expressible since P2.1 | "Untap this artifact during each other player's untap step", a CR 613.11 rules-modifying static over the CR 502.2 turn-based action. It needs **nothing** from this framework. |

The `ActivatedAbility.timing` field is worth calling out separately, because it is the one addition
here that is not about mana at all. Without it "Activate only as a sorcery" is unexpressible, and
Basilisk Gate and Timberwatch Elf would encode as instant-speed tricks — an enumerated-but-illegal
action (ADR-005), not a cosmetic inaccuracy. It reuses the *same* window predicate a sorcery's cast
is checked against, because CR 602.5d says the two windows are identical and one function is how they
stay identical.
