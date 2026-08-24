# Design note — mana payment enumeration (P2.1, amended P8.3)

The reference for the payment model built in P2.1, extended by P2.2 (real basics) and Phase 5
(triggered mana abilities, additional/alternative costs), and **reshaped in P8.3** so that one
activation of a mana ability can pay more than one cost symbol. PLAN.md §7 names payment
combinatorics a top risk; the mitigation is this model: **declarative plans over collapsed
source classes**, enumerated exhaustively, chosen by index (ADR-005).

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

data class ManaActivation(val sourceClass: SourceClassKey, val produced: ManaType)

sealed interface SymbolPayment {
    data class WithMana(val mana: ManaType) : SymbolPayment
    data object WithTwoLife : SymbolPayment
}
```

- **`activations`** is a multiset of mana-ability activations, held in a canonical order
  (§3). Each names a **source class** (§2) and the `ManaType` chosen for that source's own
  ability — the choice an "add one mana of any color" source offers. Its **yield** is
  everything the activation puts in the pool: `[produced] + sourceClass.bonus`, the bonus
  being the CR 605.1b triggered mana the attached Auras add. One activation may yield several
  mana; nothing in the plan shape cares how many.
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
   class's untapped (or, for a sacrifice ability, usable) membership.
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

## 2. Equivalence and collapsing (unchanged)

Without collapsing, "tap 2 of my 5 Mountains for `{1}{R}`" is C(5,2)·permutations of plans
that differ only in which physically-identical land taps. The model collapses them at the
representation level: a plan never names a source object. It names a **source class** — the
equivalence class of interchangeable sources — and execution picks concrete members
deterministically.

**Equivalence relation.** Two usable battlefield objects controlled by the caster are
payment-equivalent iff they have the same printed card (`CardRef`) **and** the same
*production profile* — the canonical list of mana-type options their tap-for-mana abilities
can add — **and** the same CR 605.1b bonus, **and** the same activation cost (tap vs
sacrifice). Same-profile is the load-bearing half (it is what makes activating either
indistinguishable in every future game state); same-card is a deliberate safety margin: costs
that care about the card itself (Fireblast's "sacrifice two Mountains", Lava Dart's
flashback — docs/decklists.md) can never be wronged by an over-eager merge, at the price of
occasionally enumerating two plans where one would do.

The P8.3 reshape **does not touch the relation**. An Utopia-Sprawl-enchanted Forest was
already a distinct class from a bare Forest, because `bonus` is part of the key. What changed
is only what the enumerator *does* with that bonus: it is now the activation's yield rather
than a tag that exists to keep classes apart. This is the same "refine the profile, never the
relation" line Phase 5 drew, taken one step further — and it is the reason F10 (§8) is a
profile problem rather than a relation problem.

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

## 8. What F10 (Tron / conditional multi-mana production) still owes

`docs/design/cost-modification.md` §8 predicted this reshape and assigned it here. It is now
done, and the parts of the payment model F10 was going to have to reshape — `PaymentPlan`,
`SymbolPayment`, the payment search, the dedup rule and the executor — are **settled**. An
activation that yields three mana is already the ordinary case: `yield(a)` is a list, coverage
sums over it, the no-idle matching reads it, and execution simply adds whatever the ability
resolves to.

What F10 still has to add is entirely on the **production** side:

1. **A production descriptor richer than `SourceClassKey.profile`.** Today `profile` is the
   list of mana types one activation may *choose between*, and yield is
   `[produced] + bonus` — exactly one mana of choice plus a fixed bonus. Urza's Tower adds
   `{C}{C}{C}` and Urza's Mine/Power Plant `{C}{C}`: a *count*, and in general a multiset, on
   the primary production. The key needs the alternatives a member's ability may produce as
   multisets (`List<List<ManaType>>`) rather than as single types, and `ManaActivation.produced`
   becomes a choice among them. This is a `SourceClassKey` change and a change to the single
   function `activationYield`; it is the seam, and it is the only one.
2. **`ManaAbility` multi-mana vocabulary.** `ManaAbility(options, viaSacrifice)` adds exactly
   one mana per activation and its KDoc says so. Tron needs a definition-level way to say
   "adds `{C}{C}{C}`". That is new DSL vocabulary in `mtg-core`/`mtg-rules`, per ADR-003.
3. **The board-state condition, evaluated at activation time (CR 605.2).** The Urza count is
   read when the mana ability *resolves*, mid-payment, and is never locked in the way a CR
   601.2f cost reduction is. `productionProfile` already reads live state through
   `layeredCharacteristics`, so the condition belongs there; what F10 must prove is that the
   count the enumerator used to build the plan is the count execution produces — the same
   planner/executor correspondence P8.3 tests directly (§9), re-run for a state-conditional
   profile. A Tron piece leaving the battlefield between enumeration and payment is impossible
   inside one payment window, but the test is what keeps it that way.
4. **A revisit of the no-idle bound.** With three-mana activations, "one yielded mana is
   spent" admits plans that waste two. That is still legal Magic and still bounded
   (`|activations| ≤ |payments|`), but the Tron packet should measure the option count on a
   real Monster Tron board before accepting it, exactly as §4 does here.

Nothing on that list touches the plan shape. That is the point of this amendment.

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

## 10. Why enumeration completeness is testable

The plan space for a fixed `(cost, sources, pool, life)` is finite and small, so tests keep a
**brute-force oracle**: naively generate every raw payment assignment and every activation
multiset up to the length bound, keep the ones satisfying legality clauses 1–5 computed
independently of the enumerator, canonicalize, deduplicate, and set-compare against the
enumerator's output. Equality proves both directions at once — no missing plan, no phantom
plan, collapsing exactly right.

P8.3 adds a third property, the one a reshape most needs: **planner/executor correspondence.**
For every enumerated plan, executing it must succeed, and the resulting pool must equal
`pool_before ⊎ yields(activations) ⊖ demand` with every count non-negative. A plan that
enumerates but cannot execute, or executes to something other than what it declared, is the
worst defect this model can have, so it is asserted directly rather than inferred.

On top of those sit the two end-to-end completeness properties (ADR-005): every enumerated
cast option must execute through the full CR 601 pipeline without error (no phantoms), and
constructed scenarios where a cast is legal/illegal must/must-not enumerate it (no gaps). The
fuzz harness then defends all of it across seeds.
