# Gauntlet card triage — the 187 unencoded cards

A working document, meant to be consulted per packet rather than read once. It classifies every
card the thirteen gauntlet decklists name that `mtg-cards` does not define, from **oracle text**,
into what it actually costs to encode.

Scope and provenance:

- **The list** is derived from the coverage machinery, not by hand: `DefinitionCoverage.checkAll`
  over `GauntletDecks`. It reproduces the pin in
  `mtg-pauper/src/test/kotlin/dev/mtgplay/pauper/GauntletCoverageSpec.kt` exactly — 145 mainboard,
  47 sideboard, **187 distinct**, 42 of them sideboard-only.
- **The oracle text** was fetched on 2026-08-24 from `POST https://api.scryfall.com/cards/collection`
  (three requests of 75/75/37, descriptive `User-Agent`, ~150 ms apart). **187/187 found, `not_found`
  empty.** Two names resolved to two-faced cards and are flagged in §7: `Fang Dragon` →
  *Fang Dragon // Forktail Sweep* (Adventure), `Sagu Wildling` → *Sagu Wildling // Roost Seek* (Omen).
- **The baseline** is `docs/design/countering-spells.md`, `protection.md`, `cost-modification.md`,
  `mana-payment.md`, and `layer-system.md`. Where those notes already settled a question this
  document cites them and does not re-derive.
- **The inventory of what exists** is the 38 definitions in `mtg-cards/src/main/kotlin/dev/mtgplay/cards/`
  and the published vocabulary they compose. What those cards compose is what a Tier-0 card may assume.
- `mtg_ai/docs/ENGINE-CARD-BRIEF.md` §4 is treated as an **unreliable hypothesis** throughout. §6
  records where it is wrong; §5 gives the corrected build order.

---

## 1. Headline numbers

| Tier | Cards | Share |
|---|---:|---:|
| **Tier 0** — buildable today from published primitives | **22** | 11.8 % |
| **Tier 1** — one or two small, named primitives | **40** | 21.4 % |
| **Tier 2** — framework-blocked | **125** | 66.8 % |

**62 of 187 cards (33 %) can be encoded without a single framework packet.** That is the number
worth acting on first: it is enough work to keep six agents busy in parallel, it needs no design
review, and none of it collides with a framework packet in flight.

The other number worth stating up front: **no gauntlet deck becomes playable after any one
framework.** The cheapest deck in the gauntlet still needs five frameworks (§4).

---

## 2. What "published DSL primitive" means today

The Tier-0 boundary is not a judgement call; it is the closed list below, read off `mtg-core`'s
definition types and `mtg-rules`' `effect/` package. A card is Tier 0 iff it composes only these.

**Card-definition slots.** `PrintedCharacteristics` (name, mana cost, supertypes incl. `SNOW`, card
types, subtypes, P/T, `keywords`, `evasions`) · `manaAbilities` · `staticContinuousEffects` ·
`triggeredAbilities` · `triggeredManaAbilities` · `choosesColorAsItEnters` · `activatedAbilities` ·
`timing` · `targetSpec` · `resolution` · `castingPermissions` · `replacementEffects` ·
`additionalCost` · `libraryReveal` · `optionalCostThenDraw` · `drawThenDiscard`.

**The closed vocabularies.**

| Type | Members that exist |
|---|---|
| `Keyword` | `FLYING`, `FIRST_STRIKE`, `VIGILANCE`, `TRAMPLE`, `HEXPROOF`, `LIFELINK` |
| `Evasion` | `BLOCKABLE_ONLY_BY_FLYING` |
| `TargetSpec` | `None`, `AnyTarget`, `Enchantable(restriction)` |
| `Target` | `Player`, `Permanent` |
| `EnchantRestriction` | `CREATURE`, `LAND`, `FOREST`, `CREATURE_YOU_CONTROL` |
| `AbilityCost` | `Mana`, `TapSelf`, `SacrificeSelf`, `DiscardSelf`, `DiscardACard` |
| `AbilityZoneScope` | `Battlefield`, `Hand` |
| `AdditionalCost` | `DiscardCards(n)` |
| `CastingPermission` | `Madness`, `Flashback`, `AlternativeCost`, `Escape`, `Plot` (each `ManaCost` + optional `SacrificeRequirement(count, subtype)`) |
| `TriggerCondition` | `EnteredBattlefieldSelf`, `PutIntoGraveyardFromBattlefieldSelf`, `EnchantedCreatureDealsDamage`, `SpellCast(types/excludedTypes/controlledByYou)`, `DrewNthCardThisTurn(n)`, `MadnessCast` |
| `TriggerZoneScope` | `Battlefield`, `Exile`, `Graveyard` |
| `ReplacementEffect` | `DiscardToExileInstead`, `LeaveStackToExileInstead` |
| `RevealedCardFilter` | `PERMANENT_CARD`, `ENCHANTMENT_CARD` |
| `LibrarySearchFilter` | `BASIC_LAND_CARD` |
| `AffectedSet` | `Enchanted` |
| `StaticContinuousEffect` | `grantedKeywords`, `grantedManaAbilities`, `powerMod`, `toughnessMod` (`Magnitude.Fixed`/`Dynamic`) |
| `ManaAbility` | `options: List<ManaType>` + `viaSacrifice: Boolean` — **exactly one mana per activation** |
| `effect/` | `createToken`, `dealDamage`, `dealDamageToEachOpponent`, `drawCards`, `gainLife`, `loseLife`, `returnFromGraveyardToBattlefieldTapped`, `returnToOwnersHand` |

A `ResolutionEffect` is `(GameState, ResolutionContext) -> GameState`, so **any pure read of
`GameState` is card-side vocabulary** — counting permanents, branching on `discardedForCost`,
drawing from `state.rng`. That is what `Grab the Prize`, `Ethereal Armor`, and `createTapped` already
do, and it is why several cards that look like they need engine work do not (§6.5).

**The five gaps that dominate everything below**, none of which the brief names:

1. **Nothing but a spell can target.** `TriggeredAbility` and `ActivatedAbility` have no
   `targetSpec`; `legalTargets`' only callers are the four casting sites
   (countering-spells.md §1.4, protection.md §2.4).
2. **`TargetSpec` has no restricted members.** There is no "target creature", "target land", "target
   player". `AnyTarget` is players + creatures, and that is all.
3. **There are no counters on permanents** (layer-system.md §1) and **no haste** (PLAN.md §6 P5.3).
4. **There is no effect-invoked `destroy` and no `exile`** (countering-spells.md §8).
5. **There is no duration machinery** — no until-end-of-turn anything (layer-system.md §2).

---

## 3. The wave plan

### 3.1 Tier 0 — two packets, both dispatchable now

Grouped so that one packet's cards share a mechanic and one reviewer's reasoning stays in one head.
Neither needs a design note; neither touches `mtg-rules`.

**W0-A — Colourless lands and artifacts (9).** New `Lands.kt` and `Artifacts.kt` in `mtg-cards`.

> Great Furnace · Seat of the Synod · Vault of Whispers · Snow-Covered Island · Snow-Covered
> Mountain · Snow-Covered Plains · Bonder's Ornament · Ichor Wellspring · Unfathomable Truths

**W0-B — Burn, lifegain, and value bodies (13).** New `Burn.kt` and `Bodies.kt`.

> Gut Shot · Galvanic Blast · Breath Weapon · End the Festivities · Healer of the Glade ·
> Outlaw Medic · Spirit Link · Gnaw to the Bone · Union of the Third Path · Spinewoods Paladin ·
> Wellwisher · Murmuring Mystic · Pursue the Past

### 3.2 Tier 1 — six packets, each owning its primitives

Each packet **owns** a named set of primitives; a card lives in the packet owning its heaviest one.
Cross-packet edges are listed so the orchestrator can sequence or merge.

| Packet | Cards | Owns (new in `mtg-rules`/`mtg-core`) | Touches |
|---|---:|---|---|
| **W1-A — Lands that enter tapped** | 8 | `P-ETBTAPPED` (CR 614.1c), `Keyword.INDESTRUCTIBLE`, `TriggerCondition.EnteredUntapped` | `ReplacementEffect`, `ZoneMove.kt`, `Lands.kt` |
| **W1-B — Search and cycling** | 7 | `P-SEARCH` (`LibrarySearchFilter` widened + battlefield-tapped destination), `Keyword.REACH`, `Keyword.DEFENDER` | `LibrarySearch.kt`, `CombatActions.kt` |
| **W1-C — Targeting, destroy, exile, mill** | 11 | `P-TGT` (restricted `TargetSpec` members), `P-DESTROY` (CR 701.7), `P-EXILE` (CR 701.3), `P-MILL` (CR 701.13) | `Targets.kt`, new `effect/Destroy.kt`, `effect/Exile.kt`, `effect/Mill.kt` |
| **W1-D — Creature and artifact mana sources** | 4 | `P-MANASICK` (CR 302.6 gate in `manaSourceClasses`), `P-MANAEFFECT`, `P-MANACOST` (composite mana-ability cost) | `PaymentEnumeration.kt`, `ManaAbilities.kt` |
| **W1-E — Sacrifice costs and graveyard returns** | 5 | `P-ADDSAC` (`AdditionalCost.Sacrifice(filter)`), `P-ABILCOST` (`SacrificeAnother`), `P-GYTOHAND` | `CastingPipeline.kt` (`paySacrificeCosts`), `Activation.kt` |
| **W1-F — Trigger and reveal extensions** | 5 | `P-TRIGCOND` (colour filter on `SpellCast`), `P-INTERVENINGIF` (CR 603.4), `P-ADDMANA`, `P-ABILZONE` (`Graveyard` + `ExileSelf`), `P-REVEAL` | `TriggerDetection.kt`, `LibraryReveal.kt`, `ManaPool.kt` |

**W1-A (8):** Drossforge Bridge · Mistvault Bridge · Silverbluff Bridge · Slagwoods Bridge ·
Glacial Floodplain · Idyllic Beachfront · Volatile Fjord · Gingerbread Cabin

**W1-B (7):** Contaminated Landscape · Perilous Landscape · Twisted Landscape · Expedition Map ·
Lórien Revealed · Generous Ent · Gatecreeper Vine

**W1-C (11):** Cast Down · Terminate · Smash to Smithereens · Ancient Grudge · Raze ·
Scour from Existence · Last Breath · Skred · Cryoshatter · Mental Note · Thought Scour

**W1-D (4):** Elvish Mystic · Fyndhorn Elves · Elves of Deep Shadow · Lotus Petal

**W1-E (5):** Crop Rotation · Eviscerator's Insight · Reckoner's Bargain · Krark-Clan Shaman ·
Haunted Fengraf

**W1-F (5):** God-Pharaoh's Faithful · Faerie Miscreant · Burning-Tree Emissary · Bramble Wurm ·
Winding Way

### 3.3 Collisions

- **`MvpCards.kt` is a guaranteed conflict for every packet in every wave.** It is a one-line append
  per card, so the merges are trivial but constant. Either split the registry per file
  (`Lands.definitions + Burn.definitions + …`) before dispatching wave 0, or accept the churn
  deliberately. Recommend the split; it is fifteen minutes and it removes the only structural
  collision in the whole plan.
- **W0-A and W1-A both create/extend a lands file.** Sequence W0-A first, or give W1-A its own
  `TappedLands.kt`.
- **W1-C's `P-TGT` is consumed by W1-E's Raze** (target land) — sequence W1-C before W1-E, or move
  Raze into W1-C and let W1-E own only `P-ADDSAC`.
- **W1-B's `P-SEARCH` is consumed by W1-E's Crop Rotation.** Same choice.
- **W1-C and W1-D both land in `mtg-rules` but in disjoint files** (`Targets.kt` vs
  `PaymentEnumeration.kt`); they are safe in parallel.
- **W1-C's `P-DESTROY` and W1-A's `Keyword.INDESTRUCTIBLE` must land together or in that order.**
  Indestructible is inert until destroy exists, and a destroy that does not consult it is silently
  wrong the moment the four artifact lands are on the battlefield. See trap T8.

### 3.4 Recommended first three parallel packets

**W0-A**, **W0-B**, and **W1-C**. W0-A and W0-B touch only `mtg-cards` and no shared file except the
registry; W1-C is the highest-leverage Tier-1 packet because `P-TGT`, `P-DESTROY`, and `P-EXILE` are
prerequisites for a large share of Tier 2 as well, and because "the gauntlet has no removal" is the
single largest gameplay hole today.

---

## 4. Per-deck completion forecast

Counts are **distinct cards**, mainboard first, sideboard after the slash. "FW" is the number of
distinct frameworks that deck's *mainboard* still needs; "P" the number of distinct Tier-1
primitives.

| Deck | Main missing / distinct | T0 | T1 | T2 | FW | P | Frameworks its mainboard needs |
|---|---|---:|---:|---:|---:|---:|---|
| **GW Bogles** | **0 / 18** | — | — | — | **0** | 0 | *complete* (7 sideboard cards remain) |
| **Mono-Red Madness** | **0 / 12** | — | — | — | **0** | 0 | *complete* (5 sideboard cards remain) |
| Mono Red Rally | 10 / 13 | 2 | 1 | 7 | **5** | 1 | CONDSTATIC, DURATION, COUNTERS, EQUIP, OPTCOST |
| Mono-Blue Terror | 13 / 14 | 0 | 3 | 10 | **7** | 3 | COUNTER, COST, LIBLOOK, ABILTGT, DURATION, NONCTRLDEC, **WARD** |
| Gates | 11 / 17 | 2 | 0 | 9 | 10 | 0 | MANA, ABILTGT, DURATION, PREVENT, MODAL, ZONETGT, COUNTERS, COPY, LINKEDEXILE, TRIGSAC |
| Mono Blue Faeries | 13 / 14 | 0 | 2 | 11 | 11 | 3 | ABILTGT, COUNTER, NINJUTSU, TRIGCOMBAT, LIBLOOK, MODAL, COST, DURATION, RESSELECT, TRIGLTB, ABILDRAWDISCARD |
| UWX Familiar | 18 / 20 | 1 | 4 | 13 | 11 | 3 | LIBLOOK, ABILTGT, ZONETGT, COUNTER, BLINK, RESSELECT, MANA, MULTITGT, EVOKE, OPTCOST, COST |
| Elves | 15 / 16 | 1 | 5 | 9 | 12 | 6 | ABILTGT, LIBLOOK, MANA, DURATION, COUNTERS, X, BESTOW, CHANGELING, ALTCOST, ALTFACE, ABILCOST2, **INITIATIVE** |
| Jeskai Ephemerate | 21 / 22 | 5 | 5 | 11 | 12 | 3 | LIBLOOK, COUNTER, ZONETGT, BLINK, ABILTGT, MODAL, COST, EVOKE, OPTCOST, RULESMOD, TGTCOND, REPLACEDEATH |
| Grixis Affinity | 21 / 22 | 5 | 5 | 11 | 13 | 4 | ABILTGT, COST, ZONETGT, MULTITGT, MODAL, COUNTERS, NONCTRLDEC, DURATION, EXPLORE, TYPECHANGE, ABILCOST2, OPTMANA, TRIGLTB |
| Monster Tron | 20 / 21 | 2 | 5 | 13 | 13 | 5 | MANA, ABILTGT, LIBLOOK, MULTITGT, COUNTER, COST, X, ZONETGT, COUNTERS, CASCADE, CHANGELING, PROTOTYPE, STATION |
| Spy Combo | 19 / 21 | 0 | 5 | 14 | 13 | 6 | ABILTGT, MANA, LIBLOOK, ZONETGT, CHANGELING, ALTCOST, ALTFACE, HIDDENCHOICE, LINKEDEXILE, COUNTERS, ABILCOST2, DEFENDERKW, BLOCKSET |
| Jund Wildfire | 19 / 22 | 2 | 7 | 10 | 14 | 7 | ABILTGT, ZONETGT, NONCTRLDEC, COUNTERS, COST, DURATION, LIBLOOK, MULTITGT, X, BESTOW, EXPLORE, OPTMANA, SHUFFLEIN, TRIGSAC |

Sideboards, distinct missing: Elves 5, Gates 5, Grixis 7, Bogles 7, Jeskai 7, Jund 7, Faeries 6,
Terror 6, Madness 5, Rally 5, Tron 6, Spy 7, UWX 6.

### 4.1 What this table actually says

**A deck at 21/22 is not playable, and none of these decks crosses the line after one framework.**
The brief's tranche structure ("F1, then Mono-Blue Terror and Mono Blue Faeries") reads as though a
framework completes a deck. It does not. Concretely:

- **Mono-Blue Terror after F1 alone is 4 cards closer out of 13.** It still needs cost modification
  (Cryptic Serpent), library-look (Brainstorm, Ponder), mill (Mental Note, Thought Scour),
  islandcycling (Lórien Revealed), a target spec and until-EOT-adjacent handling (Sleep of the
  Dead), a non-controller decision (Deem Inferior), **and ward** for Tolarian Terror — which the
  brief never mentions and which cost-modification.md §0 already flagged.
- **Mono Blue Faeries after F1 is 3 cards closer out of 13**, and its two Ninjas need both ninjutsu
  *and* combat-damage triggers, neither of which the counter framework provides.
- **The cheapest deck to finish is Mono Red Rally** — 5 frameworks, 1 primitive, 10 cards. And four
  of its five frameworks are layer/duration work (`FW-DURATION`, `FW-CONDSTATIC`, `FW-COUNTERS`,
  `FW-EQUIP`), not the cost modification the brief assigns it.
- **The second cheapest is Mono-Blue Terror** at 7. Everything else is 10–14.

So the honest framing for planning is: **frameworks buy cards, not decks.** Track the card burn-down
(the pin in `GauntletCoverageSpec`), and expect the first *new* playable deck to arrive only after a
deliberate five-to-seven-framework campaign aimed at one list.

---

## 5. Framework counts, and the corrected build order

Two counts per framework. **Cards** = how many Tier-2 cards name it among their blockers.
**Unlocks** = how many cards become Tier-0/1 the moment it lands *and nothing else has landed*
(i.e. it is their only blocker). **Cumulative** is a greedy build order: at each step, the framework
that completes the most still-blocked cards given everything above it.

| # | Framework | CR / note | Cards | Unlocks alone | Mainboards needing it |
|---:|---|---|---:|---:|---:|
| 1 | **`FW-ABILTGT`** — abilities that target | 603.3d / 602.2b + 608.2b re-check. **Not in the brief.** | **32** | 3 | **10** |
| 2 | `FW-COUNTER` — countering spells | 701.5; countering-spells.md F1.1–F1.3 | 16 | 8 | 5 |
| 3 | `FW-MANA` — variable/conditional/composite mana production | 605.2; mana-payment.md §8 (brief's F10) | 15 | 8 | 5 |
| 4 | `FW-LIBLOOK` — scry / surveil / look-at-top-N + ordering | 701.17/701.44. **Not in the brief.** | 13 | 9 | 8 |
| 5 | `FW-DURATION` — until-EOT and delayed effects | 611.2, explicit timestamps. **Not in the brief.** | 13 | 3 | 7 |
| 6 | `FW-COST` — dynamic cost modification | 601.2f; cost-modification.md (brief's F3) | 11 | 6 | 7 |
| 7 | `FW-ZONETGT` — targets outside the battlefield | 115.1; shares the `Target` extension with F1. **Not in the brief.** | 9 | 2 | 7 |
| 8 | `FW-MULTITGT` — more than one target / "up to N" | 601.2c. **Not in the brief.** | 9 | 1 | 5 |
| 9 | `FW-MODAL` — modal spells | 700.2 / 601.2b; countering-spells.md §8 | 9 | **0** | 5 |
| 10 | `FW-COUNTERS` — counters on permanents and players | 122, layer 7d. **Not in the brief.** | 8 | 1 | 7 |
| 11 | `FW-OPTCOST` — optional additional costs (kicker, bargain, evidence) | 702.33; brief's F8, but only one third of it | 7 | 0 | 4 |
| 12 | `FW-NONCTRLDEC` — mid-resolution decisions by a non-controller | countering-spells.md §7.1 generalised. **Not in the brief.** | 5 | 1 | 4 |
| 13 | `FW-CONDSTATIC` — conditional statics, `AffectedSet` beyond `Enchanted`, haste | 604.3, 613.1f; cost-modification.md L1 | 4 | 1 | 1 |
| 14 | `FW-HIDDENCHOICE` — choosing from a hidden zone you don't own | ADR-007. **Not in the brief.** | 3 | 1 | 1 |
| 15 | `FW-X` — `{X}` costs | unsupported since P1.1 | 3 | 0 | 3 |
| — | `FW-PREVENT` — damage prevention + damage-source identity | 615; protection.md §3 | 2 | 1 | 1 |
| — | `FW-PROTECT` — protection | 702.16; protection.md (brief's **F2**) | **2** | **2** | **0** |
| — | `FW-BLINK`, `FW-ALTFACE`, `FW-COPY`, `FW-RULESMOD`, `FW-INITIATIVE`, `FW-RESSELECT`, `FW-LINKEDEXILE`, `FW-ABILCOST2`, `FW-CHANGELING`, `FW-NINJUTSU`, `FW-TRIGCOMBAT` | | 2 each | 0–2 | |
| — | 20 further one-card frameworks | `FW-PROTOTYPE`, `FW-STATION`, `FW-CASCADE`, `FW-EVOKE`, `FW-BESTOW`, `FW-EQUIP`, `FW-WARD`, `FW-EXPLORE`, `FW-TYPECHANGE`, `FW-ALTCOST`, `FW-SHUFFLEIN`, `FW-OPTMANA`, `FW-DEFENDERKW`, `FW-TGTCOND`, `FW-LANDFALL`, `FW-TRIGLTB`, `FW-REPLACEDEATH`, `FW-BLOCKSET`, `FW-TRIGSAC`, `FW-ABILDRAWDISCARD` | 1 each | 0–1 | |

Greedy cumulative order and running total of the 125:
`LIBLOOK`(+9) → `MANA`(+9, 18) → `COUNTER`(+8, 26) → `ABILTGT`(+7, 33) → `DURATION`(+9, 42) →
`COST`(+6, 48) → `MODAL`(+5, 53) → `ZONETGT`(+5, 58) → `MULTITGT`(+5, 63) → `NONCTRLDEC`(+4, 67) →
`OPTCOST`(+3, 70) → `CONDSTATIC`(+4, 74) → `COUNTERS`(+3, 77) → tail.

### 5.1 The argument: build `FW-ABILTGT` first, not F1

The brief calls F1 the "largest single unlock". By the measured numbers it is second, and it is not
even independent of the real answer.

**`FW-ABILTGT` is referenced by 32 cards — twice F1's 16 — and by ten of the thirteen mainboards.**
It is the single largest structural gap in the engine, and neither the brief nor any tranche plan
names it. It was found twice, independently, by the two design notes that went looking at oracle
text: countering-spells.md §1.4 ("the engine's triggered abilities cannot target at all";
`resolveAbility` hands every trigger `persistentListOf()`) and protection.md §2.4 ("no activated or
triggered ability uses `TargetSpec`… 702.16b's ability half has no call site today"). That is two
frameworks arriving at the same missing concept from opposite directions, which is the strongest
evidence available that it is load-bearing.

Three further reasons it goes first:

1. **F1 depends on it.** countering-spells.md sequences Spellstutter Sprite as F1.7 precisely
   because triggers cannot target. Building `FW-ABILTGT` first turns F1.7 into card composition, and
   turns F1's own last packet from "extend the trigger framework" into "write a card".
2. **It has the lowest measured "unlocks alone" score (3) for the best possible reason.** Almost
   every card it blocks is blocked by exactly one *other* thing as well — usually `P-TGT`,
   `P-EXILE`, or `FW-ZONETGT`, all of which are Tier-1-sized or shared with F1. It is the common
   factor under the long tail, not a leaf.
3. **It is the framework that makes the engine's action space representative.** ADR-005's whole
   value proposition is that a training agent sees every legal option. Today an ability can never
   present a target choice, so entire classes of decision — "which creature does this ETB kill" — do
   not exist in the environment at all.

**Then F1 (`FW-COUNTER`, packets F1.1–F1.3), unchanged from countering-spells.md.** 16 cards, 8 of
them unlocked outright, and the largest share of every sideboard. Take the note's own recommendation
in its open question 7 and **hoist modality (F1.4) and kicker (F1.6) out of F1** — the measurement
supports it: `FW-MODAL` unlocks **zero** cards on its own (every modal card in the gauntlet is also
a counter, a graveyard-targeter, or multi-target), so it is never the thing to build alone; and
`FW-OPTCOST` unlocks zero as well.

**Third: `FW-LIBLOOK`.** 13 cards, 9 of them unlocked outright — the best ratio in the table — needed
by 8 of 13 mainboards, and structurally the cheapest of the big three: a new `DecisionRequest` family
plus a `Pending*` record, following the `LibraryReveal` / `ChooseFromRevealed` pattern that already
exists. No layers, no new characteristics, no `GameState` beyond one pending record. It is also the
framework that most improves the *quality* of the training environment, because scry/Brainstorm
decisions are the densest information-hiding choices in Pauper.

**Then `FW-MANA` (15/8, and all of Monster Tron), then `FW-COST` (11/6, already designed).**

### 5.2 What the corrected order demotes

- **F2 (protection) is not third; it is roughly fifteenth.** `FW-PROTECT` is the sole blocker for
  exactly **two** cards, both sideboard-only, and it appears in **zero** mainboards. protection.md
  §0 already said this ("of the four cards F2 claims to unblock, two are not protection cards at
  all"); the counts confirm it at 2/187 = 1.1 %. What is worth building from that tranche is
  `FW-PREVENT` — Prismatic Strands is a Gates *maindeck* four-of and the deck's best card — and
  prevention needs **no protection code**.
- **F6 (snow) is not a framework at all.** `Supertype.SNOW` already exists in `mtg-core`. The three
  Snow-Covered basics are **Tier 0**; Glacial Floodplain and Volatile Fjord need only
  `P-ETBTAPPED`; Skred needs only `P-TGT` (the snow count is card-side). **No gauntlet card uses
  `{S}` snow mana.** F6's entire content is one Tier-1 primitive shared with eight other lands.
- **F9 (mass self-mill) is not a framework.** Milling is `P-MILL`, a Tier-1 primitive. Spy Combo's
  actual blockers are `FW-ABILTGT` (6 cards) and `FW-MANA` (4).
- **F5 (ninjutsu) unlocks zero cards on its own** — both Ninjas also need a combat-damage-to-a-player
  trigger.
- **F3 does not unblock Mono Red Rally or UWX Familiar** — cost-modification.md §0 established the
  first (Goblin Tomb Raider is a conditional static, not a cost card); the second follows from UWX
  needing eleven frameworks of which `FW-COST` is one.

---

## 6. Where the brief is wrong — the new findings

The three corrections already on record (countering-spells.md §1, protection.md §0,
cost-modification.md §0) stand and are not repeated. Five more from this pass:

**6.1 Masked Vandal has no convoke.** The brief's F8 lists "Elves (Masked Vandal's convoke)". Oracle
text: *"Changeling. When this creature enters, you may exile a creature card from your graveyard. If
you do, exile target artifact or enchantment an opponent controls."* There is no convoke on the card,
and **no gauntlet card has convoke at all.** Masked Vandal is a `FW-ABILTGT` + `FW-CHANGELING` card.
Convoke should leave the F8 scope line.

**6.2 F6 has no snow-mana content** (§5.2). Six of its seven cards are Tier 0 or one shared Tier-1
primitive.

**6.3 The engine's abilities cannot target** (§5.1). This is bigger than anything the brief names and
it appears in no tranche.

**6.4 Tolarian Terror's ward is a Mono-Blue Terror mainboard blocker, not a footnote.**
cost-modification.md §0 flagged ward as omitted; the deck-level consequence is that Mono-Blue Terror
— the brief's headline Tranche 2 deck — cannot be completed by F1 + F3, because ward needs a
pay-or-be-countered trigger that neither delivers.

**6.5 Several cards the brief and the notes route to frameworks are card-side composition.**
Because `ResolutionEffect` is a pure function of `GameState`, a state-dependent *amount* needs no
engine change. **Galvanic Blast's metalcraft is Tier 0**, not a packet (cost-modification.md §0 calls
it "R1 — a state-dependent amount on the existing `DealDamage` effect"; the `Grab the Prize`
precedent means it is already expressible). The same argument makes untargeted sweepers (Breath
Weapon, End the Festivities), count-based lifegain (Gnaw to the Bone, Wellwisher, Union of the Third
Path), and name-counting (Bonder's Ornament) Tier 0.

---

## 7. Traps

Cards and encodings where the obvious answer is subtly wrong, or where the engine would
**silently approximate** rather than fail loudly (CONVENTIONS.md "Fail loudly"; PLAN.md §7).

**T1 — Mana abilities do not check summoning sickness.** `PaymentEnumeration.manaSourceClasses`
filters usable sources on `!it.tapped || isSacrificeSource(...)` and nothing else. `Activation.kt:77`
*does* check `isCreature(state, source) && source.summoningSick` — but only for non-mana abilities.
No MVP source is a creature that taps for mana, so the gap has never been reachable. Encoding
**Elvish Mystic, Fyndhorn Elves, Priest of Titania, Timberwatch Elf, Wellwisher, Saruli Caretaker, or
Elves of Deep Shadow** makes it reachable immediately, and the failure is exactly the worst kind: the
mana is there, the game continues, and the enumerated action space is wrong in the agent's favour.
`P-MANASICK` must land in the same packet as the first creature mana source. **This is the single
most dangerous item in the whole triage.**

> **Resolved.** Landed with Elvish Mystic and Fyndhorn Elves, the pool's first creature mana
> sources. The gate is now one shared predicate, `manaSourceUsable`
> (`mtg-rules/.../engine/ManaSourceUsability.kt`), read by `manaSourceClasses` *and* by
> `resolveTapForMana` — the executor had the same gap, and would have tapped a summoning-sick
> member of a class the planner counted without one. See docs/design/mana-payment.md §2.1 and
> `ManaSourceSummoningSicknessSpec`.

**T2 — Lotus Petal is not `viaSacrifice`.** `ManaAbility.viaSacrifice` means *sacrifice instead of
tapping* (Eldrazi Spawn), and `manaSourceClasses` deliberately treats a sacrifice source as usable
**while tapped**. Lotus Petal's cost is `{T}` **and** sacrifice. Encoding it with `viaSacrifice=true`
gives a tapped Lotus Petal a mana ability. mana-payment.md §9 already records the shape gap
("`isSacrificeSource` asserts the all-or-nothing shape rather than assuming it").

**T3 — "…the rest on the bottom of your library in any order" is a real decision.** Ponder, Impulse,
Ancient Stirrings, Lead the Stampede, Augur of Bolas, Sea Gate Oracle, and Brainstorm ("on top of
your library in any order") all end with an ordering choice over a hidden zone. The obvious encoding
picks a deterministic order and looks perfect — and silently decides the next several draws. Any
`FW-LIBLOOK` design must surface the ordering as an enumerated decision or loud-gate it; a
"library order is unobservable" argument is false, because the player who made the choice observes
it and the opponent's information state differs.

**T4 — Two decklist entries are two-faced cards.** `Fang Dragon` is *Fang Dragon // Forktail Sweep*
(Adventure, `{1}{R}` sorcery face dealing 1 to each creature you don't control) and `Sagu Wildling`
is *Sagu Wildling // Roost Seek* (Omen, `{G}` basic-land tutor). Both look like plain fliers in the
list. Encoding only the creature face silently deletes the half these decks are actually playing.

**T5 — Avenging Hunter and Goliath Paladin are not vanilla beaters.** "You take the initiative" is
the Undercity — a dungeon, a per-game initiative holder, an upkeep trigger, combat-damage transfer of
the initiative. There is no cheap subset. Encoding them as a 5/4 trample and a 3/6 vigilance is a
plausible-looking card that is not the card.

**T6 — Devoid is unmodelled and currently unobservable.** `PrintedCharacteristics.colors` is
`manaCost?.colors ?: emptySet()` (countering-spells.md §5 already flags colour derivation as the
first characteristic the engine derives rather than stores). Unfathomable Truths and Writhing
Chrysalis are devoid — colourless despite coloured mana costs. Nothing reads colour today, so
Unfathomable Truths is honestly Tier 0 **now**; the moment the Blasts (`OfColor`), Hydroblast/
Pyroblast, Prismatic Strands, or protection land, both cards become silently mis-coloured. Record
the debt in the definition's KDoc when encoding them.

**T7 — Nyxborn Hydra looks like a green fatty.** `{X}{G}` 0/1 with bestow, reach, trample and
"enters with X +1/+1 counters". That is `FW-X` + `FW-COUNTERS` + `FW-BESTOW` behind a stat line a
reviewer will skim.

**T8 — Indestructible and "can't be regenerated" are inert, and rot silently.** Terminate's
regeneration clause is genuinely a no-op (no regeneration exists — countering-spells.md §13 lists it
as a non-goal). The four indestructible artifact lands are also currently inert. But `P-DESTROY`
makes indestructible live in the same instant, and a destroy primitive that does not consult it is
wrong on turn one of every Grixis/Jund game. Land them together (§3.3).

**T9 — Faerie Miscreant's intervening-if.** "When this creature enters, **if** you control another
creature named Faerie Miscreant, draw a card." CR 603.4 checks the condition twice: when the trigger
would go on the stack, and again on resolution. The obvious encoding — an `if` inside the
`ResolutionEffect` — implements only the second check and quietly draws a card in a case where the
real rules do not.

**T10 — Sequential `dealDamage` is not simultaneous damage (CR 120.6).** Breath Weapon and End the
Festivities are Tier 0 by folding the published `dealDamage` over the battlefield. Final state is
identical (SBAs run at priority, not mid-resolution), but the log gets N `DamageDealt` events rather
than one batch, and lifelink/damage-trigger batching will read them. Revisit these two the moment
protection.md's Part A threads a damage *source* through `dealDamage`.

**T11 — "That artifact's controller" is last-known information.** Smash to Smithereens destroys and
then damages the destroyed artifact's controller (CR 608.2h). Reading the controller *after* the
destroy finds nothing. Same shape in Searing Blaze's two-target dependency.

**T12 — Ride's End's cost reduction depends on its own target.** cost-modification.md §2.2 moves
`determineTotalCost` to immediately after `establishTargets`; that reorder is what makes Ride's End
encodable at all. If the reorder is ever reverted, Ride's End is silently mispriced rather than
loudly broken.

**T13 — Cryoshatter's `-5/-0` is the first negative layer-7c modifier.** `Magnitude.Fixed(-5)` is
representable today and has never been exercised. Nothing in the MVP pool subtracts, so the
interaction between a negative static modifier and the CR 704.5f zero-toughness SBA is untested.

**T14 — Last Breath's "power 2 or less" must read layered power**, and it is re-checked at CR 608.2b:
a creature pumped in response makes the spell fizzle. Reading printed power is the obvious wrong
answer and it looks right on a board with no Auras.

**T15 — `Target` has exactly two members.** Every "target card in a graveyard" card (nine of them)
needs the same sealed-`Target` extension, the same protocol/CLI/fingerprint/invariant ripple, and the
same `legalTargets` branch that `Target.SpellOnStack` needs for F1 (countering-spells.md §10). Build
the extension once, with both members in mind, or pay the ~10-file mechanical cost twice.

**T16 — Basilisk Gate must not reuse `Magnitude.Dynamic`.** cost-modification.md §6 spells out the
failure: a dynamic magnitude tracks the Gate count for the rest of the turn, so playing a fourth Gate
grows an already-resolved pump. Nothing crashes, no invariant fires. Its X is **snapshotted**
(CR 608.2h, 611.2d) — the opposite semantics to the one dynamic type the engine has.

**T17 — A permanent that is both a mana source and the source of a `{T}`-costed activated ability
crashes the engine.** `abilityCostPayable` and the activation's payment request both call
`enumeratePaymentPlans(state, seat, cost)`, which knows nothing about *which* source is paying. For a
permanent whose own mana ability could fund its own ability's mana component, the enumerator therefore
offers a plan that taps it for mana; `payAbilityCost` then pays the mana component first and
`tapObjectForCost` throws on the CR 602.2a "requires an untapped source" check. This is an
enumerated-but-illegal action (ADR-005), not a rules corner.

No card in the pool reaches it: every existing `{T}` ability either belongs to a non-source (Blood
token, Melded Moxite, Murmuring Mystic's Bird), has no mana component (Wellwisher), or functions from
the hand (Ash Barrens' landcycling). The **first** card to reach it is whichever of **Bonder's
Ornament** (`{T}`: Add one mana of any color / `{4}`, `{T}`: …), **Haunted Fengraf** (`{T}`: Add `{C}` /
`{3}`, `{T}`, Sacrifice: …), **Barrels of Blasting Jelly**, **Bender's Waterskin**, **Conduit Pylons**,
**Giant's Boulder**, or **Basilisk Gate** lands first — so it must be gated in the same packet, exactly
as T1 was. Reproduced by the card-sweep packet with Bonder's Ornament: with four Mountains and an
untapped Ornament, plan **0** for `{4}` taps the Ornament (white is first in the WUBRG candidate order)
and the activation throws `CR 602.2a: a {T} cost requires an untapped source`. The fix belongs to
`manaSourceClasses` / `enumeratePaymentPlans`, because a `ManaActivation` names a payment-equivalence
*class* rather than an object — filtering finished plans is not enough.

**T18 — A played land's enters-the-battlefield trigger is never detected.**
`detectEnterBattlefieldTriggers` is called from the resolving-permanent path (`AsEntersColor.kt`) and
from `returnToBattlefieldTapped`, but **not** from `executePlayLand` — a land is played, not cast
(CR 305.1), and takes its own transition. No gauntlet land encoded so far has an ETB trigger, so the
gap is currently unreachable and completely silent: the land arrives, nothing fires, and no invariant
notices. **Bojuka Bog**, **Mortuary Mire**, **Azorius Chancery**, **Conduit Pylons**, and **Gingerbread
Cabin** each make it reachable, and each would look perfectly encoded while doing nothing. Whichever
lands first must add the detector call to the play-land special action (CR 603.6a applies to a land
exactly as it does to a resolving permanent) and check that the fired trigger reaches the stack at the
priority grant `executePlayLand` already performs.

---

## 8. The 187, classified

Tier key: **0** = composes only published primitives · **1** = one or two small named primitives ·
**2** = framework-blocked. Deck tags: `Elv` Elves · `Gat` Gates · `Grx` Grixis Affinity · `Bog` GW
Bogles · `Jes` Jeskai Ephemerate · `Jnd` Jund Wildfire · `Fae` Mono Blue Faeries · `Ter` Mono-Blue
Terror · `Mad` Mono-Red Madness · `Rly` Mono Red Rally · `Trn` Monster Tron · `Spy` Spy Combo ·
`UWX` UWX Familiar. A number is the copy count; `°` marks a sideboard slot.

| Card | Cost | Tier | Needs | Why / shape it follows | Decks |
|---|---|:-:|---|---|---|
| Ancient Grudge | {1}{R} | 1 | `P-TGT` `P-DESTROY` | Flashback already exists; needs a `TargetSpec` for an artifact permanent and the CR 701.7 destroy primitive. | Jnd°1 Trn°1 |
| Ancient Stirrings | {G} | 2 | `FW-LIBLOOK` | Look at the top five, reveal a colourless card, rest to the **bottom in any order** — an ordering decision the engine has no request for. | Trn4 |
| Annul | {U} | 2 | `FW-COUNTER` | `SpellRestriction.OfAnyCardType({ARTIFACT,ENCHANTMENT})` — countering-spells.md F1.2. | Fae°4 Ter°2 |
| Archaeomancer | {2}{U}{U} | 2 | `FW-ABILTGT` `FW-ZONETGT` | A targeted ETB trigger whose target is a card in a graveyard. | Jes2 UWX3 |
| Augur of Bolas | {1}{U} | 2 | `FW-LIBLOOK` | Untargeted ETB, but look-at-top-three with the rest to the bottom in any order. | Jes4 |
| Avenging Hunter | {4}{G} | 2 | `FW-INITIATIVE` | 'You take the initiative' is the Undercity dungeon — an entire subsystem. Looks like a 5/4 trample; is not (see traps). | Elv4 |
| Azorius Chancery | — | 2 | `FW-MANA` `FW-RESSELECT` | `{T}: Add {W}{U}` is multi-mana production; the ETB bounce is an untargeted mid-resolution choice of a land you control. Also enters tapped. | UWX4 |
| Balustrade Spy | {3}{B} | 2 | `FW-ABILTGT` | Targeted ETB (target player) plus a mill-until-a-land variant. The Spy Combo engine. | Spy4 |
| Barrels of Blasting Jelly | {1} | 2 | `FW-ABILTGT` `FW-MANA` | A targeted activated ability, and a mana ability whose cost is mana with a once-per-turn restriction — `ManaAbility` has no cost field at all. | Trn2 |
| Basilisk Gate | — | 2 | `FW-ABILTGT` `FW-DURATION` | cost-modification.md §7 (L2): a resolution-generated until-EOT pump with a **snapshotted** magnitude, plus sorcery timing and a target on an activated ability. | Gat4 |
| Bender's Waterskin | {3} | 2 | `FW-RULESMOD` | 'Untap this during each other player's untap step' is a CR 613.11 rules-modifying static over a turn-based action. | Jes3 |
| Blood Fountain | {B} | 2 | `FW-ABILTGT` `FW-ZONETGT` `FW-MULTITGT` | The Blood token is already defined; the ability targets up to two creature cards in a graveyard. | Grx2 Jnd1 |
| Blue Elemental Blast | {U} | 2 | `FW-COUNTER` `FW-MODAL` | countering-spells.md §1.2: restricts the **target**, so it is absent from enumeration with no red object anywhere. F1.4. | Jes°3 Fae°2 Ter°2 |
| Bojuka Bog | — | 2 | `FW-ABILTGT` | Targeted ETB (target player's graveyard) plus a graveyard-exile primitive; also enters tapped. | Trn1 |
| Bonder's Ornament | {3} | 0 | **Tier 0** | `{T}`: any-colour `ManaAbility`; the second ability is `Mana`+`TapSelf` with an untargeted draw that counts permanents by name. Shape: **Melded Moxite**'s activated ability. | Trn2 |
| Boulderbranch Golem | {7} | 2 | `FW-PROTOTYPE` | Prototype changes mana cost, colour **and** P/T — a copiable-values alternative (CR 715), not an alternative cost. | Trn2 |
| Brainstorm | {U} | 2 | `FW-LIBLOOK` | Put two hand cards on top of the library **in any order** — an ordered placement decision over a hidden zone. | Jes4 Ter4 |
| Bramble Wurm | {6}{G} | 1 | `P-REACH` `P-ABILZONE` | `Keyword.REACH`; a graveyard-scoped activated ability with an exile-self cost (`AbilityZoneScope.Graveyard` + `AbilityCost.ExileSelf`) — the `Hand` scope of **Ash Barrens** is the precedent. | Trn4 |
| Breath Weapon | {2}{R} | 0 | **Tier 0** | Untargeted sweeper: fold the published `dealDamage` over the battlefield's non-Dragon creatures. Shape: **Guttersnipe** (damage dealt from a resolution effect). | Gat°3 Grx°1 Bog°2 Jnd°2 Trn°3 |
| Brinebarrow Intruder | {U} | 2 | `FW-ABILTGT` `FW-DURATION` | Targeted ETB granting an until-EOT `-2/-0`. | Fae4 |
| Burning-Tree Emissary | {R/G}{R/G} | 1 | `P-ADDMANA` | An `addMana` effect primitive so an ETB trigger can put `{R}{G}` in the pool (CR 106.1); the rest is a plain ETB trigger. | Rly4 |
| Call Damage Control | {1}{G} | 2 | `FW-MODAL` `FW-ZONETGT` `FW-MULTITGT` | 'Choose up to two' modes, each with its own graveyard-card target. | Trn°2 |
| Cast Down | {1}{B} | 1 | `P-TGT` `P-DESTROY` | `TargetSpec` creature with a nonlegendary filter, plus destroy. | Jnd3 |
| Cast into the Fire | {1}{R} | 2 | `FW-MODAL` `FW-MULTITGT` | Modal, and one mode has 'up to two target creatures'. | Jes°2 Mad°2 Rly°3 |
| Citadel Gate | — | 2 | `FW-MANA` | `{T}: Add {W} or one mana of the chosen colour` — production options that depend on an as-enters choice. Also enters tapped. | Gat4 |
| Cleansing Wildfire | {1}{R} | 2 | `FW-NONCTRLDEC` | The *target land's controller* may search — a mid-resolution decision by someone other than the resolving controller; plus search-to-battlefield, a land target and destroy. | Jnd4 |
| Cliffgate | — | 2 | `FW-MANA` | As Citadel Gate. | Gat4 |
| Clockwork Percussionist | {R} | 2 | `FW-DURATION` | 'You may play it until the end of your next turn' — a duration-bounded permission to **play** (not cast) a card from exile, spanning two turns. Haste shipped with `FW-COUNTERS`; the window did not. | Rly4 |
| Conduit Pylons | — | 2 | `FW-LIBLOOK` `FW-MANA` | Surveil 1 on ETB; `{1}, {T}: Add one mana of any colour` is a mana ability with a mana cost. | Trn2 |
| Contaminated Landscape | — | 1 | `P-SEARCH` | The cost (`TapSelf`+`SacrificeSelf`) and cycling both exist; needs a `LibrarySearchFilter` for a listed basic and a battlefield-tapped destination. | UWX2 |
| Counterspell | {U}{U} | 2 | `FW-COUNTER` | The unrestricted counter — F1.2, the reference card of the framework. | Jes4 Fae4 Ter4 |
| Crop Rotation | {G} | 1 | `P-ADDSAC` `P-SEARCH` | `AdditionalCost.Sacrifice(land)` (reuses the existing `ChooseSacrifices` request) + search-any-land-to-battlefield. | Trn3 |
| Cryoshatter | {U} | 1 | `P-TRIGCOND` `P-DESTROY` | The `-5/-0` is `Magnitude.Fixed(-5)` today; needs `TriggerCondition` members for the enchanted creature becoming tapped / being dealt damage, plus destroy. | Fae2 |
| Cryptic Serpent | {5}{U}{U} | 2 | `FW-COST` | cost-modification.md C5: the clean representative of the graveyard-count reduction (Tolarian Terror is not — it has ward). | Ter4 |
| Deem Inferior | {3}{U} | 2 | `FW-COST` `FW-NONCTRLDEC` | A cost reduction counting cards drawn this turn, and the *owner* chooses where in their library it goes. | Ter3 |
| Dispel | {U} | 2 | `FW-COUNTER` | `SpellRestriction.OfCardType(INSTANT)` — F1.2. | Jes2 Fae2 Ter2 |
| Dread Return | {2}{B}{B} | 2 | `FW-ZONETGT` | Targets a creature card in your graveyard; the flashback cost sacrifices three creatures (a card-type `SacrificeRequirement`). | Spy2 |
| Drossforge Bridge | — | 1 | `P-ETBTAPPED` `P-INDESTRUCTIBLE` | `{T}: Add {B} or {R}` already works; needs the enters-tapped replacement and `Keyword.INDESTRUCTIBLE` (only observable against P-DESTROY). | Grx4 Jnd4 |
| Duress | {B} | 2 | `FW-HIDDENCHOICE` | Reveal an opponent's hand and choose from it — an ADR-007 per-seat-filter question, not merely a discard. | Jnd°3 |
| Dust to Dust | {1}{W}{W} | 2 | `FW-MULTITGT` | 'Exile two target artifacts' — two distinct targets of the same spec. Also needs the exile primitive. | Gat°3 Jes°3 UWX°3 |
| Elves of Deep Shadow | {G} | 1 | `P-MANAEFFECT` | A mana ability with a non-mana rider (1 damage to you) resolving mid-payment; also needs the summoning-sickness gate. | Spy2 |
| Elvish Mystic | {G} | 1 | `P-MANASICK` | `{T}: Add {G}` on a creature — `manaSourceClasses` does **not** check summoning sickness (see traps). One filter clause, CR 302.6. | Elv4 |
| End the Festivities | {R} | 0 | **Tier 0** | `dealDamageToEachOpponent` plus a fold of `dealDamage` over their creatures. Shape: **Voldaren Epicure**. | Rly°3 |
| Envelop | {U} | 2 | `FW-COUNTER` | `SpellRestriction.OfCardType(SORCERY)` — F1.2. | Grx°2 Jes°2 UWX°2 |
| Ephemerate | {W} | 2 | `FW-BLINK` | Exile-and-return under owner's control + rebound (a delayed cast from exile). The brief's F4. | Jes4 UWX2 |
| Eviscerator's Insight | {1}{B} | 1 | `P-ADDSAC` | `AdditionalCost.Sacrifice(artifact-or-creature)`; the draw and the flashback already exist. | Jnd2 |
| Expedition Map | {1} | 1 | `P-SEARCH` | The cost is `Mana`+`TapSelf`+`SacrificeSelf` (all published); needs a land-card search filter. | Trn4 |
| Extract a Confession | {1}{B} | 2 | `FW-OPTCOST` `FW-NONCTRLDEC` | Optional collect-evidence 6 with linked information; each opponent chooses their own sacrifice. | Grx°2 |
| Faerie Macabre | {1}{B}{B} | 2 | `FW-ZONETGT` `FW-MULTITGT` | The discard-from-hand activation shape exists (**Ash Barrens**); the targets are up to two cards in graveyards. | Elv°3 Bog°2 Jnd°2 Fae°1 Spy°2 |
| Faerie Miscreant | {U} | 1 | `P-INTERVENINGIF` | A condition on `TriggeredAbility` checked both at trigger time and on resolution (CR 603.4). Putting the 'if' inside the effect is silently wrong — see traps. | Fae4 |
| Faerie Seer | {U} | 2 | `FW-LIBLOOK` | Scry 2 on ETB. | Fae4 |
| Fanatical Offering | {1}{B} | 2 | `FW-ABILTGT` `FW-EXPLORE` | The additional sacrifice cost is small; the Map token's ability targets and explores. | Grx1 Jnd4 |
| Fang Dragon | {5}{R}{R} | 2 | `FW-ALTFACE` | Adventure: two castable faces, cast-then-exile-then-cast-the-creature (CR 715). The decklist names the creature face; the *Adventure* is what gets cast first. | Spy°2 |
| Flaring Pain | {1}{R} | 2 | `FW-PREVENT` `FW-DURATION` | 'Damage can't be prevented this turn' inverts the prevention framework — protection.md §3 names it as the deliberately unpopulated slot. | Bog°2 Rly°2 Spy°1 |
| Force Spike | {U} | 2 | `FW-COUNTER` | countering-spells.md F1.3: `PendingCounterPayment` + `ChooseOptionalPayment`, the first decision made by a non-controller. | Ter4 |
| Fyndhorn Elves | {G} | 1 | `P-MANASICK` | Identical to Elvish Mystic. | Elv4 |
| Galvanic Blast | {R} | 0 | **Tier 0** | `AnyTarget` + `dealDamage` with the amount branched on an artifact count read from state. Shape: **Grab the Prize** (resolution branches on read state). | Grx4 Rly4 |
| Gatecreeper Vine | {1}{G} | 1 | `P-DEFENDER` `P-SEARCH` | `Keyword.DEFENDER` (attack legality) + an optional search for a basic land or a Gate card. | Spy3 |
| Generous Ent | {5}{G} | 1 | `P-REACH` `P-SEARCH` | Food is a `TokenDefinition` with an activated ability (the Blood-token precedent); forestcycling needs a Forest-card search filter. | Elv4 Trn2 Spy4 |
| Ghostly Flicker | {2}{U} | 2 | `FW-BLINK` `FW-MULTITGT` | Two targets across three permanent types, exiled and returned together. | UWX1 |
| Giant's Boulder | {1} | 2 | `FW-LIBLOOK` `FW-MANA` `FW-ABILTGT` | Scry 2, a mana ability with a mana cost, and a targeted destroy ability. | Trn4 |
| Gingerbread Cabin | — | 1 | `P-ETBTAPPED` `P-TRIGCOND` | Conditional enters-tapped ('unless you control three or more other Forests') and an *entered untapped* trigger condition. | Elv1 |
| Gingerbrute | {1} | 2 | `FW-DURATION` `FW-EVASION` | An until-EOT 'can't be blocked except by creatures with haste' bought with an activated ability — a new `Evasion` member *and* a duration. Haste shipped with `FW-COUNTERS`. | Rly3 |
| Glacial Floodplain | — | 1 | `P-ETBTAPPED` | Snow supertype and dual production already exist; the only gap is enters-tapped. | Jes1 |
| Gnaw to the Bone | {2}{G} | 0 | **Tier 0** | `gainLife` with a graveyard-count amount + `CastingPermission.Flashback`. Shape: **Faithless Looting** (flashback) + **Armadillo Cloak** (computed lifegain). | Elv°3 |
| Goblin Bushwhacker | {R} | 2 | `FW-OPTCOST` `FW-DURATION` `FW-CONDSTATIC` | Kicker with linked information, an until-EOT team pump, and an `AffectedSet` wider than `Enchanted`; also haste. | Rly4 |
| Goblin Tomb Raider | {R} | 2 | `FW-CONDSTATIC` | cost-modification.md §0: **not a cost card** — confirmed against Oracle. Haste shipped with `FW-COUNTERS`; what is left is a *conditional* static continuous effect affecting its own source: `AffectedSet.Self`, a condition on `StaticContinuousEffect`, and a 'you control an artifact' predicate. | Rly4 |
| God-Pharaoh's Faithful | {W} | 1 | `P-TRIGCOND` | `TriggerCondition.SpellCast` filters on card types only; needs a colour filter (CR 202.2). | UWX4 |
| Goliath Paladin | {4}{W} | 2 | `FW-INITIATIVE` | As Avenging Hunter. | Jes°1 |
| Gorilla Shaman | {R} | 2 | `FW-X` `FW-ABILTGT` | `{X}{X}{1}` on an activated ability with a mana-value-X target restriction. | Grx°1 |
| Great Furnace | — | 0 | **Tier 0** | `CardDefinition` with `{ARTIFACT, LAND}` card types and `ManaAbility(RED)`. Shape: **Ash Barrens**. | Grx2 Rly4 |
| Guardian of the Guildpact | {3}{W} | 2 | `FW-PROTECT` | protection.md §4: the quality is **monocolored**, not a `Color` — the card that forces `Quality` to be a sealed type. | Gat°2 |
| Gut Shot | {R/P} | 0 | **Tier 0** | `AnyTarget` + `dealDamage`; `{R/P}` Phyrexian is already a payment shape (`SymbolPayment.WithTwoLife`). Shape: **Lightning Bolt**. | Ter°2 |
| Harrier Strix | {U} | 2 | `FW-ABILTGT` `FW-ABILDRAWDISCARD` | Targeted ETB tap, plus a draw-then-discard on an *activated ability* (`DrawThenDiscard` lives on `SpellDefinition` only). | Fae2 |
| Haunted Fengraf | — | 1 | `P-GYTOHAND` | `state.rng` is reachable from a `ResolutionEffect` (ADR-006), so the random pick is card-side; needs a return-from-graveyard-to-hand primitive. | Trn1 |
| Healer of the Glade | {G} | 0 | **Tier 0** | `EnteredBattlefieldSelf` trigger + `gainLife`. Shape: **Abundant Growth**'s ETB draw. | Spy°2 |
| Hydroblast | {U} | 2 | `FW-COUNTER` `FW-MODAL` | countering-spells.md §1.2: restricts the **effect**, so it must stay enumerable against a non-red spell. F1.5. | Grx°4 Jes°1 Fae°4 Ter°4 Trn°4 UWX°4 |
| Ichor Wellspring | {2} | 0 | **Tier 0** | Two triggered abilities: `EnteredBattlefieldSelf` and `PutIntoGraveyardFromBattlefieldSelf`, both `drawCards(1)`. Shape: **Rancor** (graveyard trigger) + **Abundant Growth** (ETB). | Grx4 Jnd3 |
| Idyllic Beachfront | — | 1 | `P-ETBTAPPED` | The only gap is enters-tapped. | UWX1 |
| Impulse | {1}{U} | 2 | `FW-LIBLOOK` | Look at the top four, one to hand, rest to the bottom in any order. | UWX4 |
| Inventor's Axe | {R} | 2 | `FW-EQUIP` `FW-COUNTERS` | Equipment attachment plus energy counters — two frameworks in one uncommon. | Rly4 |
| Journey to Nowhere | {1}{W} | 2 | `FW-ABILTGT` `FW-LINKEDEXILE` | Targeted ETB exile plus a leaves-the-battlefield trigger returning *that* card (CR 610.3 linked abilities). | Gat2 |
| Kaervek's Torch | {X}{R} | 2 | `FW-X` `FW-COST` `FW-COUNTER` | `{X}` damage, a **cost increase** on spells that target it, and being targetable on the stack. | Trn1 |
| Kenku Artificer | {2}{U} | 2 | `FW-TYPECHANGE` `FW-SETPT` | Counters and the targeting spec both shipped; what remains is the layer-4 type change and the layer-7b set-P/T, still unpopulated. Note the counters land on a **noncreature** artifact, which is why the type change must come first. | Grx1 |
| Krark-Clan Shaman | {R} | 1 | `P-ABILCOST` | `AbilityCost.SacrificeAnother(filter)`; the damage-to-each-non-flier fold is Tier-0 composition. | Grx3 Jnd3 |
| Land Grant | {1}{G} | 2 | `FW-ALTCOST` | An alternative cost that is 'reveal your hand', gated on a hidden-zone condition; plus a Forest search. | Elv2 Spy4 |
| Last Breath | {1}{W} | 1 | `P-TGT` `P-EXILE` | `TargetSpec` creature with a **layered** power restriction, plus an exile-a-permanent primitive. | UWX°2 |
| Lead the Stampede | {2}{G} | 2 | `FW-LIBLOOK` | Reveal any number of creature cards, rest to the bottom in any order. | Elv4 Spy4 |
| Lembas | {2} | 2 | `FW-LIBLOOK` `FW-SHUFFLEIN` | Scry 1, plus a graveyard-to-library shuffle-in trigger. | Jnd3 |
| Lotleth Giant | {6}{B} | 2 | `FW-ABILTGT` | Targeted ETB (target opponent) with a graveyard-count amount. | Spy2 |
| Lotus Petal | {0} | 1 | `P-MANACOST` | A mana ability whose cost is `{T}` **and** sacrifice — `ManaAbility.viaSacrifice` is an either/or flag today (see traps). | Spy2 |
| Lórien Revealed | {3}{U}{U} | 1 | `P-SEARCH` | `drawCards(3)` exists; islandcycling needs an Island-card search filter. | Jes4 Ter4 UWX4 |
| Maelstrom Colossus | {8} | 2 | `FW-CASCADE` | Cascade: a cast trigger that exiles until a cheaper nonland card, offers a free cast, then bottoms the rest in random order (ADR-006). | Trn4 |
| Makeshift Munitions | {1}{R} | 2 | `FW-ABILTGT` `FW-ABILCOST2` | Targeted activated ability whose cost sacrifices another permanent matching a filter. | Grx1 |
| Manor Gate | — | 2 | `FW-MANA` | As Citadel Gate. | Gat2 |
| Mask of Law and Grace | {W} | 2 | `FW-PROTECT` | protection.md: an Aura granting two protection qualities (CR 702.16g) — the DEBT rule's forcing card. | Bog°2 |
| Masked Vandal | {1}{G} | 2 | `FW-ABILTGT` `FW-CHANGELING` | Targeted ETB with an optional graveyard-exile cost, plus changeling. **The brief's claim that it has convoke is false** — there is no convoke in the oracle text. | Elv4 Spy3 |
| Mental Note | {U} | 1 | `P-MILL` | A `millCards` primitive (CR 701.13); the draw already exists. | Ter4 |
| Mesmeric Fiend | {1}{B} | 2 | `FW-ABILTGT` `FW-HIDDENCHOICE` `FW-LINKEDEXILE` | Targeted ETB, a choice from an opponent's hand, and a linked leaves-the-battlefield return. | Spy2 |
| Mistvault Bridge | — | 1 | `P-ETBTAPPED` `P-INDESTRUCTIBLE` | As Drossforge Bridge. | Grx4 |
| Monstrous Emergence | {1}{G} | 2 | `FW-OPTCOST` `FW-HIDDENCHOICE` `FW-ABILTGT` | An additional cost with two modes, one of which reveals from hand; damage equal to a chosen permanent's power. | Elv°4 |
| Moon-Circuit Hacker | {1}{U} | 2 | `FW-NINJUTSU` `FW-TRIGCOMBAT` | Ninjutsu (a special action from hand during combat) plus a combat-damage-to-a-player trigger. | Fae4 |
| Mortuary Mire | — | 2 | `FW-ABILTGT` `FW-ZONETGT` | Targeted ETB on a graveyard card; also enters tapped. | UWX1 |
| Mulldrifter | {4}{U} | 2 | `FW-EVOKE` | Evoke: an alternative cost whose *linked information* must survive onto the entering permanent so the sacrifice fires. | Jes4 UWX4 |
| Murmuring Mystic | {3}{U} | 0 | **Tier 0** | `SpellCast(spellTypes={INSTANT,SORCERY}, controlledByYou=true)` + `createToken`. Shape: **Guttersnipe** + **Cartouche of Solidarity**'s token. | Jes2 Ter°2 UWX2 |
| Myr Enforcer | {7} | 2 | `FW-COST` | cost-modification.md C5: affinity for artifacts (CR 702.41a). | Grx4 |
| Negate | {1}{U} | 2 | `FW-COUNTER` | `SpellRestriction.NotOfCardType(CREATURE)` — F1.2. | UWX2 |
| Nihil Spellbomb | {1} | 2 | `FW-ABILTGT` `FW-OPTMANA` | Targeted graveyard exile, plus an optional *mana* payment inside a trigger's resolution (`OptionalCostThenDraw` has discard/sacrifice modes only). | Grx3 Jnd3 |
| Ninja of the Deep Hours | {3}{U} | 2 | `FW-NINJUTSU` `FW-TRIGCOMBAT` | As Moon-Circuit Hacker. | Fae4 |
| Nyxborn Hydra | {X}{G} | 2 | `FW-X` `FW-BESTOW` `FW-ETBCOUNTERS` | Oracle confirms the `{X}` cost. Counters shipped, but 'enters with X +1/+1 counters' is a CR 614.1c enters-with replacement, which is its own absent framework on top of `{X}` and bestow. | Elv4 Jnd2 |
| Of One Mind | {2}{U} | 2 | `FW-COST` | A flat `{2}` reduction gated on a board predicate — cost-modification.md §1's other-object shape, self-sourced. | Fae3 |
| Outlaw Medic | {1}{W} | 0 | **Tier 0** | Printed `Keyword.LIFELINK` + `PutIntoGraveyardFromBattlefieldSelf` -> `drawCards(1)`. Shape: **Sneaky Snacker** body + **Rancor** trigger. | Gat4 |
| Overgrown Battlement | {1}{G} | 2 | **ENCODED** (`FW-COUNTERS`) | Was `FW-MANA` `FW-DEFENDERKW`. `FW-MANA` shipped the variable-amount production; `FW-COUNTERS` added `Keyword.DEFENDER` with its CR 702.3b effect and widened `PermanentFilter` with a card-type and a keyword axis. | Spy4 |
| Perilous Landscape | — | 1 | `P-SEARCH` | As Contaminated Landscape. | Jes4 |
| Pinnacle Kill-Ship | {7} | 2 | `FW-COUNTERS` `FW-STATION` `FW-ABILTGT` `FW-MULTITGT` | Charge counters, station (a type change at a counter threshold), a targeted ETB and an 'up to one' target. The heaviest single card in the gauntlet. | Trn4 |
| Ponder | {U} | 2 | `FW-LIBLOOK` | Reorder the top three, then an optional shuffle (an ADR-006 PRNG draw). | Ter4 |
| Preordain | {U} | 2 | `FW-LIBLOOK` | Scry 2 then draw — the minimal scry card, and the right one to build the framework against. | UWX4 |
| Priest of Titania | {1}{G} | 2 | `FW-MANA` | 'Add {G} for each Elf on the battlefield' — variable-amount production; also needs the summoning-sickness gate. | Elv4 |
| Prismatic Strands | {2}{W} | 2 | `FW-PREVENT` | protection.md §7: **not protection**. A global, symmetric, turn-duration prevention shield keyed on a colour chosen on resolution, plus a tap-a-white-creature flashback cost. | Gat4 |
| Prohibit | {1}{U} | 2 | `FW-COUNTER` `FW-OPTCOST` | countering-spells.md F1.6: kicker (CR 702.33) is the expensive half; mana value is read from the **printed** cost. | UWX2 |
| Pulse of Murasa | {2}{G} | 2 | `FW-ZONETGT` | Targets a creature or land card in *a* graveyard (either player's). | Jnd1 |
| Pursue the Past | {R}{W} | 0 | **Tier 0** | `gainLife` + `OptionalCostThenDraw(2, [DiscardCard])` + `Flashback`. Shape: **Highway Robbery** with a flashback instead of a plot. | Gat4 |
| Pyroblast | {R} | 2 | `FW-COUNTER` `FW-MODAL` | As Hydroblast — the enumeration-completeness card. F1.5. | Grx°4 Bog°3 Mad°4 |
| Quirion Ranger | {G} | 2 | `FW-ABILTGT` `FW-ABILCOST2` | A target, an activation cost that returns a permanent you control to hand, and a once-per-turn restriction. | Elv4 Spy1 |
| Rally at the Hornburg | {1}{R} | 2 | `FW-DURATION` `FW-CONDSTATIC` | The tokens are Tier-0; 'Humans you control gain haste until end of turn' is an until-EOT effect over a computed set, plus haste. | Rly4 |
| Raze | {R} | 1 | `P-ADDSAC` `P-TGT` `P-DESTROY` | Additional sacrifice cost, land target spec, destroy. | Rly°3 |
| Reckless Impulse | {1}{R} | 2 | `FW-DURATION` | 'Until the end of your next turn, you may play those cards' — a duration-bounded play permission over exiled cards, including lands. | Rly3 |
| Reckoner's Bargain | {1}{B} | 1 | `P-ADDSAC` | Additional sacrifice cost carrying the sacrificed permanent's identity as linked information — the `discardedForCost` pattern. | Grx4 |
| Red Elemental Blast | {R} | 2 | `FW-COUNTER` `FW-MODAL` | As Blue Elemental Blast. F1.4. | Gat°4 Jes°3 Jnd°2 Rly°4 |
| Refurbished Familiar | {3}{B} | 2 | `FW-COST` `FW-NONCTRLDEC` | Affinity, plus 'each opponent discards a card' where the *opponent* chooses. | Grx4 Jnd4 |
| Relic of Progenitus | {1} | 2 | `FW-ABILTGT` `FW-NONCTRLDEC` | Target player exiles a card **of their choice** from their own graveyard. | Fae°2 Mad°3 Trn°3 |
| Remove Soul | {1}{U} | 2 | `FW-COUNTER` | `SpellRestriction.OfCardType(CREATURE)` — F1.2. | UWX°2 |
| Ride's End | {4}{W} | 2 | `FW-COST` `FW-TGTCOND` | A reduction conditioned on the chosen target — cost determination must follow target choice (cost-modification.md §2.2 happens to order this correctly). | Jes1 |
| Rooftop Percher | {5} | 2 | `FW-ZONETGT` `FW-MULTITGT` `FW-CHANGELING` | Up to two graveyard-card targets on an ETB, plus changeling. | Trn2 |
| Sacred Cat | {W} | 2 | `FW-COPY` | Embalm creates a **token copy** of the card — a layer-1 copy effect plus a graveyard-scoped, sorcery-timed activation. | Gat4 |
| Sagu Wildling | {4}{G} | 2 | `FW-ALTFACE` | Omen: the same two-face machinery as Adventure, with a shuffle rider. | Elv3 Spy4 |
| Saruli Caretaker | {G} | 2 | `FW-MANA` | A mana ability whose cost taps *another* creature — a `SourceClassKey`/capacity change in payment enumeration (mana-payment.md §9). | Spy4 |
| Scour from Existence | {7} | 1 | `P-TGT` `P-EXILE` | Permanent target spec + exile primitive. The cleanest exercise of both. | Trn°2 |
| Sea Gate Oracle | {2}{U} | 2 | `FW-LIBLOOK` | Look at the top two, one to hand, one to the bottom. | UWX2 |
| Searing Blaze | {R}{R} | 2 | `FW-MULTITGT` `FW-LANDFALL` | Two targets with a dependency between them (the creature must be controlled by the targeted player), plus a landfall condition tracked per turn. | Mad°4 |
| Seat of the Synod | — | 0 | **Tier 0** | Artifact land, `ManaAbility(BLUE)`. Shape: **Ash Barrens**. | Grx3 |
| Sewer-veillance Cam | {U} | 2 | `FW-ABILTGT` `FW-MODAL` `FW-TRIGLTB` | A targeted ETB **and** leaves-the-battlefield trigger, with a tap-or-untap mode choice. | Grx1 Fae3 |
| Silverbluff Bridge | — | 1 | `P-ETBTAPPED` `P-INDESTRUCTIBLE` | As Drossforge Bridge. | Grx2 |
| Skred | {R} | 1 | `P-TGT` | Creature target spec; the snow count is card-side (`Supertype.SNOW` exists). **No gauntlet card needs snow mana.** | Jes4 |
| Slagwoods Bridge | — | 1 | `P-ETBTAPPED` `P-INDESTRUCTIBLE` | As Drossforge Bridge. | Jnd4 |
| Sleep of the Dead | {U} | 2 | `FW-DURATION` `FW-ABILTGT` | Escape already exists; 'doesn't untap during its controller's next untap step' is a delayed rule modification needing scheduled state. | Ter2 |
| Smash to Smithereens | {1}{R} | 1 | `P-TGT` `P-DESTROY` | Artifact target spec + destroy; the damage reads the destroyed artifact's controller as last-known information (CR 608.2h). | Mad°2 |
| Snap | {1}{U} | 2 | `FW-RESSELECT` | The bounce is a plain target; 'untap up to two lands' is an **untargeted** mid-resolution selection. | Fae2 UWX4 |
| Snow-Covered Island | — | 0 | **Tier 0** | `Supertype.SNOW` already exists in `mtg-core`; a basic snow Island is `{BASIC,SNOW}` + `ManaAbility(BLUE)`. Shape: **Island**. | Jes7 |
| Snow-Covered Mountain | — | 0 | **Tier 0** | As Snow-Covered Island. Shape: **Mountain**. | Jes2 |
| Snow-Covered Plains | — | 0 | **Tier 0** | As Snow-Covered Island. Shape: **Plains**. | Jes2 |
| Spell Pierce | {U} | 2 | `FW-COUNTER` | F1.3 — the restricted-and-unless-pay card that makes the counter/fizzle verdict split observable. | Ter2 |
| Spellstutter Sprite | {1}{U} | 2 | `FW-COUNTER` `FW-ABILTGT` | countering-spells.md §1.4 / F1.7: the engine's triggered abilities **cannot target at all**; plus a dynamic mana-value restriction. | Fae4 |
| Spinewoods Paladin | {4}{G} | 0 | **Tier 0** | Printed `TRAMPLE`, ETB `gainLife(3)`, `CastingPermission.Plot({3}{G})`. Shape: **Highway Robbery** (plot) + **Healer of the Glade** (ETB). | Elv°2 |
| Spirit Link | {W} | 0 | **Tier 0** | `aura(CREATURE)` + `TriggerCondition.EnchantedCreatureDealsDamage` -> `gainLife(context.amount)`. Shape: **Armadillo Cloak**'s triggered half with no static half. | Bog°1 |
| Standard Bearer | {1}{W} | 2 | `FW-RULESMOD` | protection.md §8: **not protection**. A CR 601.2c targeting *requirement* implemented as a CR 613.11 rules-modifying static, plus the `Flagbearer` subtype. | Bog°2 |
| Steel Sabotage | {U} | 2 | `FW-COUNTER` `FW-MODAL` | countering-spells.md §1.3: **modal**, and its two modes target different kinds of object, so mode choice must precede target enumeration. | Ter°3 |
| Stonehorn Dignitary | {3}{W} | 2 | `FW-ABILTGT` `FW-DURATION` | 'Skips their next combat phase' is a delayed, scheduled rule modification. | UWX°2 |
| Sunscape Familiar | {1}{W} | 2 | `FW-COST` | cost-modification.md C6: the other-object cost reducer, reading the spell's **printed** colours. | UWX4 |
| Tamiyo's Safekeeping | {G} | 2 | `FW-DURATION` | layer-system.md §2 names this as the forcing function for until-EOT durations; also needs indestructible and a target spec. | Gat°3 |
| Terminate | {B}{R} | 1 | `P-TGT` `P-DESTROY` | Creature target spec + destroy. 'Can't be regenerated' is inert — regeneration is unmodelled (see traps). | Jnd1 |
| Thought Scour | {U} | 1 | `P-TGT` `P-MILL` | Player target spec + mill. | Ter4 |
| Thoughtcast | {4}{U} | 2 | `FW-COST` | Affinity — C5 with Myr Enforcer. | Grx4 |
| Thraben Charm | {1}{W} | 2 | `FW-MODAL` `FW-ZONETGT` | Three modes with three different target kinds, one of them a player's graveyard. | Gat3 Jes1 |
| Timberwatch Elf | {2}{G} | 2 | `FW-ABILTGT` `FW-DURATION` | The Basilisk Gate shape on a creature: a targeted, until-EOT, count-based pump. | Elv4 |
| Tinder Wall | {G} | 2 | `FW-MANA` `FW-ABILTGT` | `Add {R}{R}` from a sacrifice ability, plus a target restricted to 'a creature it's blocking' (a combat relationship). | Spy3 |
| Tolarian Terror | {6}{U} | 2 | `FW-COST` `FW-WARD` | cost-modification.md §0: **ward {2}** (CR 702.21a) is a triggered pay-or-be-countered ability the brief omits. Cryptic Serpent should carry the cost packet instead. | Ter4 |
| Torch the Tower | {R} | 2 | `FW-OPTCOST` `FW-LIBLOOK` `FW-REPLACEDEATH` | Bargain, scry 1, and a turn-duration 'dies -> exile instead' replacement. | Jes1 |
| Toxin Analysis | {B} | 2 | `FW-DURATION` `FW-ABILTGT` | Until-EOT deathtouch + lifelink grant on a target, plus a Clue token. | Grx2 Jnd2 |
| Troll of Khazad-dûm | {5}{B} | 2 | `FW-BLOCKSET` | 'Can't be blocked except by three or more creatures' is a constraint over the whole block declaration; `DeclareBlockers` enumerates **pairwise** options. Also swampcycling. | Spy1 |
| Troublemaker Ouphe | {1}{G} | 2 | `FW-OPTCOST` `FW-ABILTGT` | Bargain with linked information gating a targeted ETB exile. | Jnd°2 |
| Twisted Landscape | — | 1 | `P-SEARCH` | As Contaminated Landscape. | Jnd4 |
| Unexpected Fangs | {1}{B} | 2 | **ENCODED** (`FW-COUNTERS`) | Triage said 'layer 7d'; **counters are 7c** (CR 613.4c) — there is no 613.4e and 7d is P/T switching. Keyword counters (CR 122.1b) are layer 6, and `FW-COUNTERS` built both. | Grx°1 |
| Unfathomable Truths | {4}{U} | 0 | **Tier 0** | `drawCards(3)` + `createToken(eldraziSpawnToken)` — the token is already defined. Shape: **Malevolent Rumble**. *Devoid is unmodelled — see traps.* | Trn4 |
| Union of the Third Path | {2}{W} | 0 | **Tier 0** | `drawCards(1)` then `gainLife(hand size)`. Shape: **Grab the Prize** (draw, then read state). | Jes1 |
| Urza's Mine | — | 2 | `FW-MANA` | cost-modification.md §8 / mana-payment.md §8: conditional multi-mana production, a `SourceClassKey` profile change. | Trn4 |
| Urza's Power Plant | — | 2 | `FW-MANA` | As Urza's Mine. | Trn4 |
| Urza's Tower | — | 2 | `FW-MANA` | As Urza's Mine — note Tower adds **three**, the other two add two. | Trn4 |
| Utrom Monitor | {4}{U} | 2 | `FW-COST` | Affinity; otherwise a vanilla flier. | Grx3 |
| Vault of Whispers | — | 0 | **Tier 0** | Artifact land, `ManaAbility(BLACK)`. Shape: **Ash Barrens**. | Grx3 Jnd2 |
| Vitu-Ghazi Inspector | {1}{G} | 2 | `FW-OPTCOST` `FW-COUNTERS` `FW-ABILTGT` | Collect evidence with linked information gating a targeted +1/+1 counter. | Elv°3 |
| Volatile Fjord | — | 1 | `P-ETBTAPPED` | As Glacial Floodplain. | Jes2 |
| Wall of Roots | {1}{G} | 2 | `FW-MANACOST` `FW-ONCEPERTURN` | Counters shipped (`FW-COUNTERS`; the `-0/-1` kind is expressible). What is left is **not** counters: `ManaAbility` admits only `{T}` and sacrifice costs, and 'Activate only once each turn' has no per-turn activation limiter anywhere. | Spy3 |
| Weather the Storm | {1}{G} | 2 | `FW-COPY` | Storm copies the spell once per spell cast this turn — spell copying plus a per-turn cast counter. | Jnd°3 |
| Wellwisher | {1}{G} | 0 | **Tier 0** | `ActivatedAbility(cost=[TapSelf])`, untargeted, `gainLife` by an Elf count. Shape: **Melded Moxite**'s activated ability (`Activation.kt` already bars a summoning-sick `{T}`). | Elv1 |
| Winding Way | {1}{G} | 1 | `P-REVEAL` | `LibraryReveal` extended with creature/land filters, an unbounded keep count, and a resolution-time filter choice (`ChooseColor` is the precedent request shape). | Elv4 Spy4 |
| Writhing Chrysalis | {2}{R}{G} | 2 | `FW-SELFCASTTRIG` `FW-TRIGSAC` | Counters and reach both shipped (`FW-COUNTERS`). **The cast trigger is not fine**: `TriggerCondition.SpellCast` watches *other* spells being cast; 'when you cast **this** spell' is a self-referential trigger that fires from the stack and has no member. Plus the sacrifice trigger. | Gat4 Jnd4 Spy°4 |

