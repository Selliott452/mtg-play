# Design note — printed characteristics in the seat view (P8.2)

Why `SeatView` now carries a per-seat card table (`SeatView.cards`), what is in it, what is
deliberately still withheld, and why the obvious simpler shape — shipping the state's
`definitions` registry — is an ADR-007 leak.

This note reverses an explicit P7.1 ruling. `SeatView`'s KDoc used to justify *dropping*
`definitions` as "static match data an agent already holds; cards are referenced by name
throughout, so the view never ships Oracle text". That reasoning holds for **cards** and does not
hold for **tokens**, which is the whole of the change.

## 1. What the consumer actually needs

The sibling consuming project (`mtg-ai`) reconstructs a board from a `SeatView`. Every object in
the view — battlefield permanent, graveyard card, stack entry — is identified by a `CardRef`, i.e.
a printed name. For a real card that is enough: the consumer holds the same static card data the
match was configured with (`MatchConfig.definitions` is built from `MvpCards`, itself derived from
the pinned pool), so a name resolves to characteristics on its side.

**Tokens break that.** A token is not a card (CR 111). Its `TokenDefinition` is not a top-level
registry entry: `createToken` registers it under its name-`CardRef` *at resolution time*, the first
time a card makes one. So a token's name appears in the view — `"Blood"`, `"Warrior"`, `"Eldrazi
Spawn"`, `"Robot"` — with no way for the consumer to learn its types, power/toughness, or keywords.
The consumer's workaround is a hand-maintained token table that goes stale, silently, every time
`mtg-cards` gains a token-making card. The gauntlet expansion adds several.

The gap is structural, not cosmetic: the engine is the only party that knows a token's
characteristics, and it never told anyone.

## 2. What is genuinely public

CR 111 is unambiguous: a token is a game object on the battlefield, and every characteristic of a
public object is public. There is nothing to hide about the 1/1 Warrior with vigilance that
Cartouche of Solidarity makes — both players can read it off the table. The same is true of every
*other* object a `SeatView` already exposes: if the view is willing to tell a seat that a card named
"Slippery Bogle" is on the battlefield, it is equally willing to tell it that Slippery Bogle is a
1/1 hexproof creature. Naming an object and describing it are the same disclosure.

That equivalence is the load-bearing claim of this note, and it is what fixes the scope:

> **Scope rule.** `SeatView.cards` has an entry for exactly those `CardRef`s that this same
> `SeatView` already names, and no others.

Every key is therefore a card whose *identity* the view has already disclosed under a rule
documented on `SeatView`. The table adds no new identities; it only describes identities already
present. A leak audit reduces to the audit that already exists.

## 3. What is still withheld

- **The registry itself.** See §4.
- **Behaviour.** The table carries `PrintedCharacteristics` — name, mana cost, type line, printed
  P/T, printed keywords, printed evasions — and one derived public fact (`isToken`). It does **not**
  carry `CardDefinition`: spell resolution effects, triggered/activated/static abilities, casting
  permissions. Those are function-valued (`ResolutionEffect` and friends), have reference equality
  only by ADR-009, are not serializable, and are not what was asked for. Rules text as data is
  unmodeled project-wide (`PrintedCharacteristics` KDoc) and stays that way.
- **Everything the view already withholds.** The table is derived *from the finished view*, so an
  opponent's hand card cannot contribute a key: the opponent's hand is a `HandView.Concealed` count
  with no objects in it. Library contents likewise contribute nothing.

## 4. The alternative that was rejected: ship the whole registry

The obvious shape is `cards = state.definitions.mapValues { ... }` — the whole match registry,
projected. It is simpler, it is stable turn to turn, and there is a real argument that a match's
card pool is public (in sanctioned play both decklists are on file with the judge).

It is rejected, for three reasons in increasing order of severity.

1. **It is not the claim we can defend.** "Tokens are public" (CR 111) is a rules fact. "Every card
   name in this match is public to both seats" is a *format* claim about decklist disclosure, and
   the format does not support it: in real Magic an opponent does not see your decklist during a
   game. Reversing an explicit design ruling on the strength of the weaker claim is the wrong
   trade.

2. **It makes engine-side filtering depend on caller discipline.** `MatchConfig.definitions` is
   caller-supplied. Today the acceptance and protocol suites pass `MvpCards.definitions` — the whole
   encoded pool, which reveals nothing about *these two decks*. A caller that instead passes exactly
   the two decklists' definitions (an obvious optimization, and the natural thing to do once the
   pool is 193 cards and a match uses 120) would turn the same code into a full decklist leak
   without a line of it changing. ADR-007 exists precisely so that hiding is not "a courtesy each
   client is trusted to implement"; a filter whose correctness depends on how the caller populated
   a registry has moved the boundary back out to the caller.

3. **It fails the existing leak guard, correctly.** `ViewLeakPropertySpec` byte-scans an opponent's
   serialized view for any card name that is in the owner's hand or library and in no zone the
   opponent may see. With `MvpCards.definitions` shipped whole, those names appear as map keys and
   the property fails. The right reading of that failure is not "relax the property" — it is "the
   property caught the leak it was written to catch".

The narrow scope has one acknowledged cost, and it is the consumer's original complaint in a new
place: the key set changes as the game goes on. A token's entry appears the moment the token does.
That is fine — and is in fact the fix — because the consumer no longer *predicts* the table; it
reads it. Staleness came from maintaining a copy, not from the shape changing.

## 5. The shape

```kotlin
data class PrintedCardView(
    val characteristics: PrintedCharacteristics,
    val isToken: Boolean,
)

// on SeatView
val cards: Map<CardRef, PrintedCardView>
```

Three notes on the choices.

- **Keyed by `CardRef`, not by `ObjectId`.** Characteristics are a property of the printed card, and
  a `CardRef` is conserved across the CR 400.7 rebirths while an `ObjectId` is not. One entry serves
  every copy of Lightning Bolt in the view, and the entry for a token survives the object's
  death-and-rebirth in the graveyard.

- **Named `cards`, not `definitions`.** The request was phrased as "add `definitions` to
  `SeatView`", but what crosses the boundary is deliberately *not* a `CardDefinition` (§3). Reusing
  the name for a different type would mislead exactly the reader who knows the codebase.

- **`isToken` is carried explicitly** rather than left to be inferred. "This object is a token" is
  `definitions[card] is TokenDefinition` inside the engine (see `TokenDefinition`), and that test is
  not reconstructible from characteristics alone — a token's type line is an ordinary type line.
  It is public (CR 111: a token is visibly a token) and it is what the CR 704.5d state-based action
  keys on, so a consumer modelling the board needs it.

- **Absent definitions are absent, not defaulted.** A `CardRef` with no registry entry is an inert
  card by the P2.1 ruling (`CardDefinition` KDoc) — legal to shuffle, draw and discard. Such a card
  simply has no entry in `cards`; the engine does not invent characteristics for it. So the exact
  invariant is: *the key set is the set of `CardRef`s this view names, intersected with the match's
  registry.*

## 6. What the pending request contributes: nothing

One case sits just outside the scope rule and is excluded on purpose. A library search
(`ChooseFromLibrary`, CR 701.18) enumerates matching **library** cards as private options in the
deciding seat's own `DecisionView.ToDecide`. Those `CardRef`s are legitimately visible to that seat
at that instant, so including them would not leak — but it would make the key set a function of the
pending request as well as of the zones, and the clean audit of §2 ("every key is an identity a zone
in this view already disclosed") would become conditional. It would also, uniquely, put library
contents into a state projection that is otherwise built to keep them out.

The cost of excluding it is nil in practice: a search option carries its own `CardRef` inline, and
those are real cards, which the consumer resolves by name. If a future effect ever enumerates a
*token* as a request option, this decision must be revisited — no such effect exists.

## 7. Properties and guards

- **Purity is unchanged.** `cards` is derived from the same `GameState` by the same pure function;
  two `viewFor` calls on the same state and seat are still equal, and nothing about engine behaviour
  changes. No new game state was introduced, so the invariant checker is untouched.
- **Determinism on the wire.** The table is emitted in canonical card-name order, so a view's JSON
  is byte-stable for a given state.
- **`ViewLeakPropertySpec` was extended, never relaxed.** Its byte-scan already covers the new field
  (map keys are quoted names in the JSON it scans, so over-exposure fails it). It gained three
  complementary checks, run at every pause on both seats and computed in the test directly from the
  view's own public lists, independently of the production collector: *completeness* (every `CardRef`
  the view names that the match defines has an entry), *scope* (no entry for a ref the view does not
  name), and *fidelity* (each entry carries the definition's own characteristics and the CR 111 token
  fact). The corpus run reports how many token entries it described — currently 3,724 over 8 seeds —
  and fails if that is zero, so the token path is provably exercised rather than assumed.
- **The wire version is unchanged.** `SeatViewDto.cards` is a new *required* field and the codec is
  strict, so this is a breaking wire change in principle; the protocol is pre-release with no released
  consumer, so `PROTOCOL_VERSION` stays at 1.0.0 on the same reasoning P7.3 recorded, now restated
  there to name this change.
