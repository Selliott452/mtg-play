# Design note — searching a library, and shuffling back into one (`P-SEARCH` / `FW-SHUFFLEIN`)

The reference for two things the CR 701.18 search grew in this packet — a **destination** other than the
hand and a **two-axis filter** — plus the CR 701.20 shuffle-into-library that `FW-SHUFFLEIN` names.
Written to PLAN.md §5's rule that a framework packet gets a design note, in the style of
`docs/design/library-look.md` and `docs/design/graveyard-targeting.md`.

Two rules anchor the design downward: **CR 701.18** (search: look at all the cards in the named zone and
find matching ones; searching your own library always permits failing to find, CR 701.18b) and
**CR 400.7** (an object that changes zones becomes a new object). Upward, the seams the engine already
cut: `PendingLibrarySearch` as the pending record whose *fact* is public but whose *cards* are not;
`DecisionView.Elsewhere` as the mechanism that withholds a request's options from every non-deciding
seat (ADR-007); `announceBattlefieldEntry` as the single home every battlefield-entry path shares
(triage T18); `entersTappedNow` as the CR 614.1c read; and `shuffled(rng)` as the one sanctioned
shuffle (ADR-006).

---

## 1. The oracle text, and three places the triage is wrong

Fetched from Scryfall (`POST /cards/collection`, 2026-08-24), all eight found.

| Card | Cost | Oracle text | Verdict |
|---|---|---|---|
| Contaminated Landscape | — | `{T}`: Add `{C}`.<br>`{T}`, Sacrifice this land: Search your library for a **basic Plains, Island, or Swamp** card, put it onto the battlefield **tapped**, then shuffle.<br>Cycling `{W}{U}{B}` | shipped |
| Twisted Landscape | — | As above, **basic Swamp, Mountain, or Forest**; Cycling `{B}{R}{G}` | shipped |
| Perilous Landscape | — | As above, **basic Island, Mountain, or Plains**; Cycling `{U}{R}{W}` | shipped |
| Generous Ent | `{5}{G}` | Reach<br>When this creature enters, create a Food token.<br>**Forestcycling** `{1}` | shipped |
| Crop Rotation | `{G}` | As an additional cost to cast this spell, sacrifice a land.<br>Search your library for a **land card**, put that card onto the battlefield, then shuffle. | shipped |
| Lembas | `{2}` | When this artifact enters, **scry 1, then draw a card**.<br>`{2}`, `{T}`, Sacrifice this artifact: You gain 3 life.<br>When this artifact is put into a graveyard from the battlefield, **its owner shuffles it into their library**. | shipped |
| Troll of Khazad-dûm | `{5}{B}` | This creature **can't be blocked except by three or more creatures**.<br>**Swampcycling** `{1}` | dropped — `FW-BLOCKSET` |
| Land Grant | `{1}{G}` | **If you have no land cards in hand, you may reveal your hand rather than pay this spell's mana cost.**<br>Search your library for a Forest card, reveal that card, put it into your hand, then shuffle. | dropped — `FW-ALTCOST` |

Three corrections:

**1.1 — The brief says the Landscapes' cycling half "is reportedly composable already". It is, and it had
never been exercised.** `AbilityCost.Mana` + `AbilityCost.DiscardSelf` from `AbilityZoneScope.Hand` is
exactly CR 702.29a, and the ordinary `ActivatedAbility.effect` draws. But every cycling card in the pool
before this packet — Ash Barrens, Lórien Revealed — prints **typecycling**, whose effect is a *search*
(CR 702.29f), so plain cycling's draw had no client and no test. The Landscapes are the first, which is
why `LibrarySearchAcceptanceSpec` cycles one and watches a card arrive with no find-one pause opening.

**1.2 — The triage tags Troll of Khazad-dûm `FW-BLOCKSET` and is right; it does not say that the card is
otherwise *complete*.** Swampcycling `{1}` is `LibrarySearchFilter.SWAMP_CARD`, which this packet
publishes and tests through its Forest twin. The card is one framework and zero primitives away.

**1.3 — The triage says of Lembas only "Scry 1, plus a graveyard-to-library shuffle-in trigger", which
undersells the scry half and is exactly right about the other.** "Scry 1, **then draw a card**" is
`LibraryLook(Scry(1), thenDraw = 1)` — the clause `FW-LIBLOOK` shipped and `FW-CLAUSEHOOK` lifted onto
triggered abilities, so Lembas' *enters* trigger carries it the way Faerie Seer's does. Nothing new.

The triage's separate claim that **no gauntlet card has convoke** (§6.1) held everywhere this packet
looked: none of the eight prints convoke, and none prints any cost-reduction-by-tapping shape.

---

## 2. The search is a post-resolution clause, not a field of `ActivatedAbility`

`LibrarySearch` was declared on `ActivatedAbility` alone, with a KDoc arguing it was "not one of the
four" clauses because it searches a *whole* library and shuffles. That distinguishes its **contents**,
not its **shape**: the only property `ResolutionClauses` is about is needing a mid-resolution enumerated
decision that a pure `ResolutionEffect` cannot make (ADR-004), and a search needs exactly one.

Keeping it off the carrier cost two things, and both are fixed by moving it:

- **A spell that searches was inexpressible.** Crop Rotation and Land Grant are sorceries and instants;
  neither has an activated ability to hang a search on. This is the same generalisation `FW-CLAUSEHOOK`
  already made for the other four, arriving one packet late.
- **The ability path ran the search *instead of* its ordinary effect.** `resolveActivatedAbility`
  early-returned into `orchestrateLibrarySearch` before `effect.resolve`, so an ability with both would
  silently drop its effect. Invisible only because all three existing clients declared a no-op effect.
  As a clause it runs **after** the effect, like the other four, and `requireAtMostOneClause` now covers
  it.

So `ResolutionClauses` has five members, `orchestrateLibrarySearch` takes a plain `StackEntry`, and
`completeSearch` was deleted in favour of the shared `completeClauseResolution` — which is the one place
CR 608.2m (a spell's card to the graveyard) and CR 113.7a (an ability simply ceasing) differ.

---

## 3. The ADR-007 position: `library-look.md` §3 applies, not `graveyard-targeting.md` §3

The two precedents reached **opposite** conclusions, and this packet sits squarely on the first.

`graveyard-targeting.md` §3 added no filtering rule, because a graveyard is a **public** zone (CR 400.2)
and both seats' graveyards already ride in `SeatView.cards` — an option naming a graveyard card
discloses nothing. `library-look.md` §3 added its one clause precisely because a look's pool is
**library** cards.

A search's options are library cards. So the library-look ruling governs, and it was already satisfied
before this packet — nothing here changes it, and nothing here needed to:

- **The fact is public, the cards are not.** `PendingLibrarySearch` carries only `decider`, and
  `PendingLibrarySearchDto`'s KDoc already says "only the searching seat; the options stay secret". An
  opponent may know *that* you are searching (they watched you sacrifice the land) and must not learn
  *what you were offered* — knowing your library held exactly one basic Swamp is real information.
- **The options reach one seat.** `DecisionRequest.ChooseFromLibrary` travels through
  `DecisionView.Elsewhere` to every non-deciding seat, which is the ADR-007 mechanism, unchanged.
- **`SeatView.cards` gains no clause.** A library card is not in any seat's card table, and neither new
  destination changes that: a card put onto the battlefield becomes public when it *arrives*, which is
  strictly after the choice was made and answered.

The one thing worth stating plainly, because a future destination could break it: the ruling holds
because the **pool** is hidden, not because the **destination** is. Adding a destination does not
re-open ADR-007; adding a *source* (searching an **opponent's** library, CR 701.18a — no gauntlet card
does) would, because then the deciding seat learns another player's hidden zone.

---

## 4. The types

```kotlin
// mtg-core — where the found card goes. The reveal is a property of the destination.
enum class LibrarySearchDestination { REVEALED_TO_HAND, BATTLEFIELD, BATTLEFIELD_TAPPED }

// mtg-core — two independent axes over a land search.
data class LibrarySearchFilter(
    val basic: Boolean = false,                                 // CR 205.4 supertype
    val landTypes: PersistentSet<Subtype> = persistentSetOf(),  // CR 205.3b, any one suffices
)
```

**Why the reveal rides on the destination.** Every printed search that ends in the **hand** says "reveal
it"; no printed search that ends on the **battlefield** does. That is not a coincidence to be encoded as
a separate `reveal: Boolean` — it is CR 400.2 showing through. A search into a hidden zone is
unverifiable unless the card is shown; a search onto the battlefield is self-evidently public the moment
it lands. Folding the reveal in records the rule and makes "into your hand without revealing" —
which nothing prints — unexpressible.

**Why the filter stopped being a closed enum.** The old `LibrarySearchFilter` was an enum with three
members for three cards, on `graveyard-targeting.md` §4's principle that a closed set makes a new
restriction break the rules-side `when`. The Landscapes break that principle's *other* half, the one the
same note states: the two axes here are genuinely independent, and folding them together "multiplies out
into a member per pairing, the combinatorial shape a closed restriction enum exists to avoid". Three
Landscapes are three more pairings; the printed cycle has ten. Splitting them costs the exhaustive
`when` — but there is nothing left to be exhaustive *about*, because the matcher reads two fields and no
case can fall through. `LAND_CARD`, `BASIC_LAND_CARD`, and `ISLAND_CARD` survive as companion constants,
so every existing call site and test reads unchanged.

**Why a land search and not a general one.** Every search the gauntlet prints finds a land, so the card
type is a constant of the shape rather than a third axis, demanded unconditionally by the matcher. A
`cardTypes` axis is the extension point and the honest one to add when a card needs it.

---

## 5. The dispatch sites

| Site | Module | What it gained |
|---|---|---|
| `LibrarySearch.kt` (definition) | core | `destination`; `toHand` → `find`; the two-axis filter |
| `ResolutionClauses` | core | `librarySearch` as the fifth clause; `declaredClauses` |
| `ActivatedAbility` | core | `librarySearch` becomes an `override` |
| `GameEvent` | core | `CardShuffledIntoLibrary` |
| `ResolutionClauseHook.orchestrateResolutionClauses` | rules | the fifth arm |
| `ActivationExecution.resolveActivatedAbility` | rules | the early return deleted |
| `LibrarySearch.kt` (engine) | rules | destination dispatch; `moveLibraryCardToBattlefield` |
| `LibrarySearchFilters.kt` | rules | the two-axis matcher (new file) |
| `ShuffleIntoLibrary.kt` | rules | `shuffleIntoOwnersLibrary` (new effect primitive) |

No `mtg-protocol` site: nothing this packet adds crosses the wire (§8).

---

## 6. What is dropped, and exactly what each needs

| Card | Blocked on | Precisely |
|---|---|---|
| **Land Grant** | `FW-ALTCOST` | "**If you have no land cards in hand**, you may **reveal your hand** rather than pay this spell's mana cost." `CastingPermission.AlternativeCost` exists and carries mana plus an optional `SacrificeRequirement` — and this cost is neither. It needs (i) a **non-mana, non-sacrifice cost component** whose payment is revealing your hand (CR 701.16), and (ii) a **legality condition on the permission itself**, read against the caster's hidden hand at CR 601.2f; no `CastingPermission` member is conditional on the game state at all, and `ActivationEnumeration`/`CastLegality` have no hook to make one. Its search half is `LibrarySearchFilter.FOREST_CARD` to `REVEALED_TO_HAND` — one line, already published and tested via Generous Ent. Shipping only that would be a `{1}{G}` Lay of the Land wearing Land Grant's name: the free cast *is* the card in Stompy, so the omission would not look like a gap (PLAN.md §7). |
| **Troll of Khazad-dûm** | `FW-BLOCKSET` | "This creature **can't be blocked except by three or more creatures**" is a constraint over the whole block *declaration* (CR 509.1b): it is satisfiable only by counting the blockers assigned to this attacker, and `DecisionRequest.DeclareBlockers` enumerates blocks **pairwise**, one blocker to one attacker. `Evasion` has a single member and it is a per-blocker predicate (`BLOCKABLE_ONLY_BY_FLYING`), so the shape does not fit either. It needs a set-cardinality evasion member plus a block-declaration validity pass that sees the whole assignment. Its swampcycling `{1}` is `LibrarySearchFilter.SWAMP_CARD`, published here. |

Both drops are pinned by `LibrarySearchCardsSpec`'s last case, so a later packet that ships one must
delete an assertion rather than quietly add a half-card.

---

## 7. Acceptance

`LibrarySearchAcceptanceSpec` runs all six shipped cards end to end under the invariant checker. Four
cases are the ones a plausible wrong encoding would still pass:

- a Landscape's fetch offers **Plains, Island, Swamp** and **not** Idyllic Beachfront — which has the
  Island land type but no Basic supertype, so a bare type filter would wrongly offer it;
- Crop Rotation's find enters **untapped**, and a found Drossforge Bridge enters **tapped** anyway,
  which separates "the destination decided it" from "the permanent's own CR 614.1c clause decided it";
- Generous Ent's forestcycling offers **Gingerbread Cabin**, a nonbasic Forest — the CR 702.28b
  discriminator between typecycling and basic landcycling;
- Lembas eaten from the battlefield leaves the graveyard **empty** and the library **one larger**, which
  both a "return to hand" and a "put on top" mis-encoding would fail.

`ShuffleIntoLibrarySpec` (`mtg-rules`) covers the primitive: the CR 400.7 rebirth, the CR 603.10
already-moved no-op, the owner rule, and **two seed-pinned assertions** — a known-answer library order
for seed `0` (the frozen ADR-006 replay contract) and a different order for a different seed.
`LibrarySearchCardsSpec` (`mtg-cards`) pins the printed half of each card against the oracle text.

---

## 8. Protocol

Held at **`6.0.0`**. Nothing this packet adds crosses the wire: `LibrarySearch`, `LibrarySearchFilter`,
and `LibrarySearchDestination` are card-definition data, and definitions are not serialised;
`DecisionRequest.ChooseFromLibrary` keeps its shape (an option list of object id + card ref, whatever
the destination turns out to be); `PendingLibrarySearchDto` still carries only the decider; no
`SeatViewDto` field is added and no `DecisionRequestKindDto` member. `GameEvent.CardShuffledIntoLibrary`
is new but `GameEvent` has no DTO at all — the event log is engine-internal.

This is the first packet since `2.0.0` with no wire change, so the bump is not a judgement call in the
other direction either: there is nothing for a `6.0.0` peer to fail to decode.
