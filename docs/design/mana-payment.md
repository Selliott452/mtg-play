# Design note — mana payment enumeration (P2.1, amended P8.3, P-MANASICK and FW-MANA)

The reference for the payment model built in P2.1, extended by P2.2 (real basics) and Phase 5
(triggered mana abilities, additional/alternative costs), **reshaped in P8.3** so that one
activation of a mana ability can pay more than one cost symbol, corrected by `P-MANASICK`
(§2.1) when the pool gained its first creature mana source, and **extended on the production side
by `FW-MANA`** (§8) when it gained its first sources whose amount is read off the board. PLAN.md §7
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

data class ManaActivation(val sourceClass: SourceClassKey, val produced: List<ManaType>)

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

Two notes on scope. The same shape does *not* affect a **spell's** cost: a cast's sacrifice
additional cost (Fireblast's two Mountains) is paid after CR 601.2g, and sacrificing an
already-tapped Mountain is legal, so no plan is offered that execution cannot carry out. And the
reservation is a property of the *cost*, not of the source class, so — like usability — it stays out
of the equivalence relation.

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
- **A mixed multiset is expressible in the key but not yet in a definition.** Azorius Chancery's
  "{T}: Add {W}{U}" is a legal `profile` value today; what is missing is `ManaAbility` vocabulary
  to *declare* it, because `ManaAmount` multiplies a single chosen type. That is deliberate — the
  key is the thing that is expensive to change, so it was made general; the definition vocabulary
  is cheap to extend and so was kept to what a card in the pool prints.

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
- **Alternative activation costs are the class key, not a choice.** A source with both a tap
  and a sacrifice mana ability would need `viaSacrifice` to become a per-activation choice.
  The MVP and gauntlet pools never mix the two on one source, and `isSacrificeSource` asserts
  the all-or-nothing shape rather than assuming it.

- **A mana ability's cost is still only `{T}` or "sacrifice this".** Nothing else is expressible,
  and four gauntlet cards want more: Saruli Caretaker's "{T}, Tap an untapped creature you
  control", Conduit Pylons' and Giant's Boulder's "{1}, {T}", and Wall of Roots' "put a -0/-1
  counter on this". This is a payment-**capacity** problem, not a production one, and it is the
  larger of the two: an activation that taps a *second* permanent has to name that permanent's
  class too, and the capacity check has to account for one class's activation consuming another
  class's membership. `FW-MANA` deliberately did not build it, and Saruli Caretaker is absent
  rather than approximated.

- **An activation can, in principle, change another activation's CR 605.2 count.** The engine
  throws rather than mispaying (§8.3). Unreachable in the gauntlet pool; the fix when it becomes
  reachable is an execution-order rule, not a change to what execution reads.

- **A mixed-type multiset is not declarable.** `SourceClassKey` holds arbitrary multisets, but
  `ManaAmount` multiplies one chosen type, so Azorius Chancery's "{T}: Add {W}{U}" needs a new
  `ManaAmount`-sized addition in `mtg-core` and **no** change to the payment model (§8.1).

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

P8.3 adds a third property, the one a reshape most needs: **planner/executor correspondence.**
For every enumerated plan, executing it must succeed, and the resulting pool must equal
`pool_before ⊎ yields(activations) ⊖ demand` with every count non-negative. A plan that
enumerates but cannot execute, or executes to something other than what it declared, is the
worst defect this model can have, so it is asserted directly rather than inferred.

On top of those sit the two end-to-end completeness properties (ADR-005): every enumerated
cast option must execute through the full CR 601 pipeline without error (no phantoms), and
constructed scenarios where a cast is legal/illegal must/must-not enumerate it (no gaps). The
fuzz harness then defends all of it across seeds.
