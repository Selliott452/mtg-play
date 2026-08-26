# The deferred ten

The gauntlet's last cards, and the only ones wave 8 did not dispatch. Each needs a mechanic no
other card in the thirteen decks touches, so each would be built once, for one card, and then never
exercised again. That is the property that separated them from the fifty-one that *were* dispatched
— not difficulty.

Oracle text throughout is from the repo's own snapshot
(`mtg-pauper/src/main/resources/scryfall-mvp.json`), which is the authority.

## Verdicts

| Card | Deck | Blocker | Verdict |
|---|---|---|---|
| Avenging Hunter | Elves | the initiative | drop |
| Goliath Paladin | Jeskai Ephemerate (SB) | the initiative | drop |
| Boulderbranch Golem | Monster Tron | prototype | ~~drop~~ — **built** (`W9-G`) |
| Maelstrom Colossus | Monster Tron | cascade | ~~drop~~ — **built** (`W9-G`) |
| Fang Dragon | Spy Combo (SB) | adventure | drop |
| Sagu Wildling | Elves, Spy Combo | omen | drop |
| Pinnacle Kill-Ship | Monster Tron | station / Spacecraft | ~~drop~~ — **built** (`W10-C`) |
| Weather the Storm | Jund Wildfire (SB) | storm | **build** — see below |
| Nyxborn Hydra | Elves, Jund Wildfire | bestow + CR 614.1c | ~~drop~~ — **built** (`W10-C`) |
| Kaervek's Torch | Monster Tron | cost *increase* | drop |

Nine drops and one build was the honest answer *at the time*, and the reason it was not depressing is
arithmetic: these ten are seven mainboard slots across four decks, and three of the four are already
close to playable without them.

Four of the nine drops have since been overturned by re-checking their diagnoses against the code
rather than against this table — prototype and cascade (`W9-G`), then station and bestow (`W10-C`).
That is the pattern worth carrying forward: **a verdict here is a snapshot of what the engine could do
on the day it was written**, and every one of those four was reversed not by building the framework the
table named but by discovering that most of it already existed under a different name.

## The two that share a blocker

**Avenging Hunter** (`{4}{G}`, 5/4 trample) and **Goliath Paladin** (`{4}{W}`, 3/6 vigilance) both
print "When this creature enters, you take the initiative." The initiative (CR 701.51) is not a
counter or a marker — it is possession of a *dungeon*, the Undercity, with a position in it that
advances on a trigger at the beginning of your upkeep, and which changes hands when a player is
dealt combat damage by a creature an opponent controls. That is a second board object with its own
state machine, its own triggered abilities, and its own room-choice decisions.

**Why this is a drop and not a deferral.** Both cards are ordinary large creatures with the
initiative stapled on. Encoding the body and silently dropping the initiative line would delete the
reason either card is played, which is the thing the standing policy forbids. Encoding the
initiative properly means a dungeon subsystem for two cards in thirteen decks.

## The four that need a cast-time mechanic the engine has no seam for

> **`W9-G` correction — the paragraph below was wrong when it was written.** The engine had **two**
> cast-without-paying paths already: `CastingPermission.Plot` (P6.2a) and `CastingPermission.Rebound`
> (`FW-BLINK`) both fix their cost at `{0}`, each carrying the comment *"cast without paying its mana
> cost — a `{0}` cost yields a single empty payment plan"* in its own KDoc, and Ephemerate had been
> driving that path end to end since `FW-BLINK`. Cascade needed no new cast machinery at all. The
> failure mode is the one the shared brief warns about: a blocker was diagnosed once and never
> re-checked against the code that had moved underneath it. **Both Monster Tron cards are now built** —
> see `mtg-cards/.../AlternateCastings.kt`, `mtg-rules/.../Cascade.kt` and
> `mtg-rules/.../Prototype.kt`. The two adventure/omen cards stay dropped, with the two-faces
> diagnosis expanded in `AlternateCastings.kt`.

~~The engine has **no "cast without paying its mana cost"** path at all — nothing in `mtg-core` or
`mtg-rules` matches, and `CastingPermission` (Madness, Flashback, AlternativeCost, Escape, Plot,
Rebound) is uniformly a permission to cast *for some cost*, never for none.~~

- **Maelstrom Colossus** (`{8}`, cascade). Cascade exiles from the top of the library until a
  cheaper nonland card, casts that card for free, then bottoms the exiled cards **in a random
  order**. Three separate absences: cast-without-paying, exile-until-a-predicate, and a seeded
  shuffle of a known set. Only the last one is already solved (`Rng`).
- **Fang Dragon** (`{5}{R}{R} // {1}{R}`, adventure) and **Sagu Wildling** (`{4}{G} // {G}`, omen).
  Both are one card with two castable halves, where casting the cheap half exiles the card — on an
  adventure, or shuffled back — from where the creature half may later be cast. `CastingPermission`
  could carry the permission; `PrintedCharacteristics` cannot carry two faces, and that is the real
  blocker.
- **Boulderbranch Golem** (`{7}`, prototype `{3}{G}` — 3/3). Prototype is an alternative cost that
  *also* changes the spell's mana cost, colour, and power/toughness. `FW-ALTCOST` landed (Land
  Grant) and handles the cost half. ~~The characteristic half is a CR 613 layer 1/7b effect keyed to
  how the spell was cast~~ — **also wrong**: CR 718.2a makes the alternative characteristics *copiable
  values*, so nothing is applied to a prototyped object and no layer is involved; it simply starts
  from a different base. That is why `W9-G` built it in one seam (`baseCharacteristics`) with no
  dependency on the layer packet running beside it. The instinct that it was "closer to reachable
  than anything else here" was right, and for a better reason than the one given.

## The two that are one framework each — both built by `W10-C`

- **Pinnacle Kill-Ship** (`{7}` Artifact — Spacecraft). Station: tap another creature you control to
  put charge counters equal to its power on this, sorcery-speed only, and the permanent becomes an
  artifact creature at 7+ counters. Counters exist (`FW-COUNTERS`), tapping as a cost exists,
  sorcery-speed activation exists (`TimingClass.SORCERY_SPEED`). What does not exist is ~~**a
  characteristic threshold keyed to a counter count** — "it's an artifact creature at 7+" is a layer
  4 type change *and* a layer 7b P/T setting, both conditional on state.~~

  **Two thirds of that was wrong.** The layer-4 type change is real and is a *static ability of the
  permanent* (CR 604.3) rather than an effect a resolving ability creates — `FW-TYPECHANGE` had put
  layer 4 on the timed generator alone, so the field had to be added to the static declaration. The
  layer-7b P/T setting is not merely unnecessary but **wrong**: a Spacecraft prints its 7/7 on the card
  (CR 208.1b) and only the *creature type* arrives at seven counters, so what actually blocked the card
  was `PrintedCharacteristics` asserting that only creature cards carry a P/T box. And "conditional on
  state" was never a blocker at all: `FW-CONDSTATIC` had already made a static ability's condition a
  live re-read on every characteristic computation. The one genuinely new thing was the **cost** —
  "tap another creature you control" is a cost with a chosen object whose power the resolution then has
  to read.
- **Nyxborn Hydra** (`{X}{G}`, bestow `{X}{G}{G}`). Dropped once already by the X-costs packet, and
  ~~the diagnosis stands~~ the diagnosis was right about *what* the card needs and wrong about how much
  of it was missing. Of the five things bestow turns out to be, three were already built by the time
  `W10-C` looked: entering attached (the Aura attachment read already went through the spec in force),
  `{X}` on an alternative cost, and — the surprising one — "reverts to a creature when unattached",
  which needs no rule of its own because the type change is *conditioned* on being attached and stops
  applying the instant the host leaves, leaving CR 704.5m no Aura to act on.

  The two that were real: CR 613 layer 4 gained type **removal** ("an Aura enchantment and not a
  creature"), the first printed instance in the pool, and the CR 614.1c enters-with-counters
  replacement, which cost one declaration and one line beside `entersTapped` at the entry seam. The
  genuinely new seam is that **how a spell was cast now decides what it targets**: one card in hand
  offers two casts and only the bestow one names a creature.

## Kaervek's Torch — the one that is blocked on purpose

> **Built by `W10-D`.** The section below is preserved as the analysis of the day. Its two claims were
> half right. Target enumeration *did* have to consult affordability, and `FW-TGTCOND` had already made it
> do so by the time this was written. And `FW-COST` really did leave the increase slot empty on purpose —
> `StackTargetTax` fills it. What neither this note nor its successors saw is that the expensive gate can
> be entered conditionally: only a spell that can name a spell, in a position where something on the stack
> taxes that, is priced at the minimum over its target choices. Everything else is priced exactly as it
> was.

```
{X}{R} Sorcery
As long as Kaervek's Torch is on the stack, spells that target it cost {2} more to cast.
Kaervek's Torch deals X damage to any target.
```

`FW-X` landed and the second line is now trivial. The first line is blocked by a *deliberate*
decision rather than an accident: `FW-COST` represents cost **reductions** only, and leaves
increases unrepresentable on purpose.

The sharper obstacle is not the increase but where it lands. The tax is keyed on *another* spell's
chosen targets, so honouring it means target enumeration (`Targets.kt`) would have to consult
affordability — you cannot offer "counter Kaervek's Torch" as a legal target to a player who cannot
pay the extra `{2}`, and under ADR-005 offering it anyway is an enumerated-but-illegal action. That
couples two subsystems the engine currently keeps apart, for one card.

## Weather the Storm — build this one

```
{1}{G} Instant
You gain 3 life.
Storm (When you cast this spell, copy it for each spell cast before it this turn.)
```

The odd one out, and the recommendation is to build it.

Storm (CR 702.40) looks like the most exotic mechanic on the list and is nearly the least. It needs
exactly one new fact — **a count of spells cast this turn by all players** — and one new behaviour,
copying a spell on the stack N times. `PlayerState` already carries per-turn tallies (`drawsThisTurn`
is right there, added for a cost reduction), so the counter is the same shape as something the state
already keeps. The copies do not target and the effect is "you gain 3 life", so there is no
target-copying machinery to write: this is the gentlest possible first client for a spell-copying
primitive.

It is a sideboard card in one deck, so it is not urgent. But it is the only one of the ten where the
framework is small, the card exercises it end to end, and the primitive is one the pool would
plausibly want again.

## What this costs the gauntlet

**Monster Tron is the deck these drops hurt.** Four of its mainboard cards are here — Boulderbranch
Golem, Maelstrom Colossus, Pinnacle Kill-Ship, Kaervek's Torch — and three of the four are big
artifact finishers, the top of its curve and the point of the archetype. Monster Tron should be
treated as the gauntlet's last deck to reach playable, and prototype is the cheapest single step
toward it.

`W9-G` took that step and the one beside it: the deck's mainboard coverage moved 17 → 19 of 21, and
`W10-C` took it to 20 of 21 with Pinnacle Kill-Ship. What it now lacks is Kaervek's Torch alone (a
cost *increase* keyed on another spell's chosen targets), whose diagnosis was re-checked and stands.

Elves loses Avenging Hunter and Sagu Wildling; Spy Combo loses Sagu Wildling. **Nyxborn Hydra was on
both Elves' and Jund Wildfire's lists and is no longer missing from either** (`W10-C`). Those decks
have enough else that they reach playable without the rest, with a slightly wrong top end.

The three sideboard cards (Goliath Paladin, Fang Dragon, Weather the Storm) cost nothing until
sideboarding exists, which is past the current milestone — the MVP is a single game, not a match.
