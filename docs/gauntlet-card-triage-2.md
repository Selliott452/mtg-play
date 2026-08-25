# Gauntlet card triage 2 — the 89 remaining cards

A refresh of [`gauntlet-card-triage.md`](gauntlet-card-triage.md), which was written against a
187-card backlog and is now **stale in roughly one row in three**. Ninety-eight cards and twenty
frameworks have landed since. That document is left untouched as the historical record; this one
supersedes it for planning.

Read this per packet, not once.

---

## 0. Provenance

- **The list** is the coverage machinery's, not a hand-maintained one: the 89 distinct cards with no
  `CardDefinition` across both boards of `GauntletDecks.all`, reproducing the pin in
  `mtg-pauper/src/test/kotlin/dev/mtgplay/pauper/GauntletCoverageSpec.kt` exactly —
  **178 distinct mainboard cards, 108 encoded, 70 missing; 48 distinct sideboard cards, 26 encoded,
  22 missing; 89 distinct across both boards, 19 of them sideboard-only.**
- **The oracle text** was fetched 2026-08-25 from `POST https://api.scryfall.com/cards/collection`
  (two requests of 45/44, descriptive `User-Agent`, ~1 s apart). **89/89 found, `not_found` empty
  on both responses.** Two names resolve to two-faced cards and are flagged in the table:
  `Fang Dragon` → *Fang Dragon // Forktail Sweep* (Adventure) and `Sagu Wildling` →
  *Sagu Wildling // Roost Seek* (Omen).
- **The inventory of what exists** is the ~150 definitions in
  `mtg-cards/src/main/kotlin/dev/mtgplay/cards/` (136 registry entries plus tokens) and the
  vocabulary they compose, read off `mtg-core`'s `definition/` and `card/` packages and
  `mtg-rules`' `effect/` package. What those cards compose is what a Tier-0 card may assume.
- **Every "still absent" claim below was verified by grep against `mtg-core/src/main`,
  `mtg-rules/src/main`, and `mtg-cards/src/main`**, not inherited from the previous triage. The
  `.claude/worktrees/` copies were excluded — they are stale.

### Naming hazard, stated once

**`FW-COUNTER` and `FW-COUNTERS` are different frameworks.** `FW-COUNTER` (singular) is *countering
spells* — CR 701.5, `TargetSpec.SpellOnStack`, `counterSpell`, Counterspell/Dispel/Negate.
`FW-COUNTERS` (plural) is *counters on permanents and players* — CR 122, layer 7c, `putCounters`,
Unexpected Fangs. Both have landed. Neither substitutes for the other, and the original triage's
ranking table lists them four rows apart. This document writes them as
**`FW-COUNTER` (countering)** and **`FW-COUNTERS` (+1/+1)** wherever they appear near each other.

---

## 1. Headline numbers

| Tier | Cards | Share |
|---|---:|---:|
| **Tier 0** — composable from what exists today, no engine change at all | **6** | 6.7 % |
| **Tier 1** — one or two small, named, self-contained primitives | **34** | 38.2 % |
| **Tier 2** — genuinely framework-blocked | **49** | 55.1 % |

**40 of 89 cards (45 %) need no framework packet.** That is up sharply from the original triage's
33 %, and it is the number to act on first: it is five packets' worth of work, it needs no design
note, and none of it collides with a framework in flight.

The 2:1 Tier-1-to-Tier-0 ratio is the honest shape of the remaining tail. The easy cards are gone;
what is left is mostly cards that are *one enum member*, *one effect function*, or *one trigger
condition* away — which is a very different backlog from the one the original triage described, and
it wants many small packets rather than a few large ones.

---

## 2. What landed since the original triage

Twenty frameworks the original triage lists as blockers are now in `main`. Each is verified by the
types named, not by the completion log.

| Framework | Status | Evidence |
|---|---|---|
| `FW-ABILTGT` — abilities that target | **landed** | `TriggeredAbility.targetSpec`, `ActivatedAbility.targetSpec`; `TriggerTargeting.kt` |
| `FW-COUNTER` (countering spells) | **landed** | `TargetSpec.SpellOnStack`, `SpellRestriction`, `effect/CounterSpell.kt`, `Counters.kt`'s eight cards |
| `FW-COUNTERS` (+1/+1 on permanents) | **landed** | `effect/PermanentCounters.kt` (`putCounters`), layer 7c in `Layers.kt`, `unexpectedFangs` |
| `FW-MULTITGT` | **landed** | `TargetCount.Exactly` / `UpTo`, `DecisionRequest.ChooseMultipleTargets`, `MultiTargets.kt` |
| `FW-MODAL` | **landed** | `ModalSpell`, `SpellMode`, `DecisionRequest.ChooseModes`, `SpellModes.kt`, `steelSabotage` |
| `FW-MANA` | **landed** | `ManaAmount.Fixed/PerPermanent/Conditional`, `priestOfTitania`, `overgrownBattlement` |
| `FW-MANACOST` | **landed** | `ManaAbilityCost` (`TapSelf`, `SacrificeSelf`, `Mana`, `TapAnotherCreature`, `PutCounterOnSelf`), `ManaAbility.oncePerTurn`, `ActivatedAbility.timing`, `CostedManaSources.kt` |
| `FW-COST` | **landed** | `CostReduction.PerMatching/IfAll`, `SpellCostReduction`, `CardDefinition.spellCostReductions`, `CostReductionCards.kt` |
| `FW-DURATION` | **landed** | `effect/UntilEndOfTurn.kt` (`applyUntilEndOfTurn`), `TimedContinuousEffect`, `ContinuousModification`, `timberwatchElf` |
| `FW-LIBLOOK` | **landed** | `LibraryLook`, `LibraryLookMode.Scry/ReorderTop/OneToHandRestToBottom/HandToTop`, `LibraryLookCards.kt` |
| `FW-ZONETGT` | **landed** | `TargetSpec.CardInGraveyard`, `GraveyardCardRestriction`, `GraveyardScope`, `GraveyardTargets.kt` |
| `FW-CLAUSEHOOK` | **landed** | `ResolutionClauses` (seven clause slots), `ResolutionClauseHook.kt` |
| `FW-ADDSAC` | **landed** | `AdditionalCost.Sacrifice`, `SacrificeFilter`, `SacrificeCosts.kt`, `SacrificeCostCards.kt` |
| `FW-BLINK` | **landed** | `effect/ExileReturn.kt` (`flickerPermanent`), `CastingPermission.Rebound`, `ephemerate` |
| `FW-TRIGLTB` | **landed** | `TriggerCondition.LeftBattlefieldSelf`, `journeyToNowhere` |
| `FW-LINKEDEXILE` | **landed** | `effect/LinkedExile.kt`, `ResolutionContext.linkedExiled`, `LinkedExileRecord.kt` |
| `FW-HIDDENCHOICE` | **landed** | `HandRevealChoice`, `DecisionRequest.ChooseRevealedHandCard`, `duress`, `mesmericFiend` |
| `FW-NONCTRLDEC` | **landed** | `EachOpponentDiscards`, `DecisionRequest.ChooseOpponentDiscards`, `refurbishedFamiliar` |
| `FW-SHUFFLEIN` | **landed** | `effect/ShuffleIntoLibrary.kt` |
| `FW-DEFENDERKW` | **landed** | `Keyword.DEFENDER`, `canBlock` split in `CombatActions.kt`, `overgrownBattlement` |
| `FW-PROTECT` | **partial — substrate only, zero cards** | `Quality.OfColor` / `Quality.Monocolored`, `PrintedCharacteristics.protections`, `StaticContinuousEffect.grantedProtections`, `engine/Protection.kt`, `Prevention.kt`'s CR 702.16e clause. See §5.1. |
| `FW-PREVENT` | **partial — application point only** | `engine/Prevention.kt` exists and CR 702.16e is built; the CR 615.1 shield store and the CR 615.9 inversion are named-but-unbuilt in that file's own KDoc |

**Frameworks still fully absent** (each verified by grep returning no implementation):
`FW-CHANGELING`, `FW-RULESMOD`, `FW-ALTCOST`, `FW-BLOCKSET`, `FW-CONDSTATIC`, `FW-X`,
`FW-OPTCOST` (kicker / bargain / collect evidence), `FW-WARD`, `FW-TYPECHANGE`, `FW-NINJUTSU`,
`FW-TRIGCOMBAT`, `FW-EVOKE`, `FW-BESTOW`, `FW-CASCADE`, `FW-PROTOTYPE`, `FW-STATION`,
`FW-EQUIP`/energy, `FW-EMBALM`/`FW-COPY`, `FW-ALTFACE` (Adventure/Omen), `FW-STORM`,
`FW-INITIATIVE`, `FW-EXPLORE`, `FW-LANDFALL`, `FW-REPLACEDEATH`, `FW-PLAYFROMEXILE`,
`FW-MODALMULTI` (choose *up to two* modes), surveil, deathtouch, and CR 603.4 intervening-if.

---

## 3. The Tier-0 boundary today

A card is Tier 0 iff it composes only the following. This is read off the code, not judged.

**Card-definition slots** (`CardDefinition` / `SpellDefinition` / `ModalSpell`):
`characteristics` · `manaAbilities` · `staticContinuousEffects` · `triggeredAbilities` ·
`triggeredManaAbilities` · `choosesColorAsItEnters` · `activatedAbilities` · `entersTapped` ·
`spellCostReductions` · `timing` · `targetSpec` · `resolution` · `modes` · `castingPermissions` ·
`replacementEffects` · `additionalCost` · `counterUnlessPaid` · and the seven `ResolutionClauses`
(`libraryReveal`, `libraryLook`, `librarySearch`, `optionalCostThenDraw`, `drawThenDiscard`,
`handRevealChoice`, `eachOpponentDiscards`).

| Type | Members that exist today |
|---|---|
| `Keyword` | `FLYING`, `FIRST_STRIKE`, `VIGILANCE`, `TRAMPLE`, `HEXPROOF`, `LIFELINK`, `DEVOID`, `INDESTRUCTIBLE`, `HASTE`, `DEFENDER`, `REACH` |
| `Evasion` | `BLOCKABLE_ONLY_BY_FLYING` |
| `Quality` | `OfColor(color)`, `Monocolored` |
| `TargetSpec` | `None`, `AnyTarget`, `TargetPlayer`, `TargetOpponent`, `TargetPermanent(restriction, count)`, `Enchantable(restriction)`, `SpellOnStack(restriction)`, `CardInGraveyard(restriction, scope, count)` |
| `TargetCount` | `Exactly(n)`, `UpTo(n)` |
| `PermanentRestriction` | `ANY_PERMANENT`, `CREATURE`, `NONLEGENDARY_CREATURE`, `CREATURE_POWER_2_OR_LESS`, `ARTIFACT`, `PERMANENT_YOU_CONTROL`, `CREATURE_AN_OPPONENT_CONTROLS`, `RED_PERMANENT`, `BLUE_PERMANENT`, `CREATURE_YOU_CONTROL` |
| `GraveyardCardRestriction` | `INSTANT_OR_SORCERY`, `CREATURE_OR_LAND`, `ANY_CARD`, `CREATURE` |
| `SpellRestriction` | `Any`, `OfCardType`, `NotOfCardType`, `OfAnyCardType`, `OfColor` |
| `AbilityCost` | `Mana`, `TapSelf`, `SacrificeSelf`, `DiscardSelf`, `DiscardACard`, `Sacrifice(filter)` |
| `ManaAbilityCost` | `Mana`, `TapSelf`, `SacrificeSelf`, `TapAnotherCreature`, `PutCounterOnSelf(n)` |
| `ManaAbility` | `options: List<ManaType>` + `cost: List<ManaAbilityCost>` + `amount: ManaAmount` + `oncePerTurn` |
| `ManaAmount` | `Fixed(n)`, `PerPermanent(filter)`, `Conditional(requires, ifMet, otherwise)` |
| `TriggeredManaAbility` | `AddChosenColor(n)`, `AddFixedMana(type, n)` |
| `AdditionalCost` | `DiscardCards(n)`, `Sacrifice(filter)` |
| `OptionalCostMode` | `DiscardCard`, `SacrificeLand` (resolution-time only, via `OptionalCostThenDraw`) |
| `CastingPermission` | `Madness`, `Flashback`, `AlternativeCost`, `Escape`, `Plot`, `Rebound` |
| `TriggerCondition` | `EnteredBattlefieldSelf`, `EnteredBattlefieldUntappedSelf`, `PutIntoGraveyardFromBattlefieldSelf`, `LeftBattlefieldSelf`, `ReboundCast`, `EnchantedCreatureDealsDamage`, `SpellCast(types/excluded/controlledByYou)`, `DrewNthCardThisTurn(n)`, `MadnessCast` |
| `EntersTapped` | `Never`, `Always`, `UnlessYouControl(filter)` |
| `LibraryLookMode` | `Scry(n)`, `ReorderTop(n)`, `OneToHandRestToBottom(n)`, `HandToTop(n)` |
| `LibraryReveal` | `count` + `toHand: RevealedCardFilter` + `toHandCount`; rest to **graveyard** |
| `RevealedCardFilter` | `PERMANENT_CARD`, `ENCHANTMENT_CARD` |
| `LibrarySearchFilter` | `basic: Boolean` + `landTypes: Set<Subtype>` + `LAND_CARD`; destinations `REVEALED_TO_HAND`, `BATTLEFIELD`, `BATTLEFIELD_TAPPED` |
| `AffectedSet` | `Enchanted` — **only** |
| `PermanentFilter` | `subtype?`, `cardType?`, `keyword?`, `controlledByYou` — **no name axis** |
| `CostReduction` | `PerMatching(amount, scope, predicate)`, `IfAll(amount, conditions)`; `CountScope` = `BATTLEFIELD_YOU_CONTROL`, `YOUR_GRAVEYARD` |
| `effect/` | `counterSpell` · `createToken` · `dealDamage` · `dealDamageToEachOpponent` · `dealDamageToEachPermanent` · `destroy` · `drawCards` · `exilePermanent` · `exileCardFromGraveyard` · `exileLinkedToSource` · `returnExiledToBattlefield` · `returnExiledToOwnersHand` · `flickerPermanent` · `gainLife` · `loseLife` · `mill` · `putCounters` · `returnFromGraveyardToBattlefieldTapped` · `returnToOwnersHand` · `returnPermanentToOwnersHand` · `shuffleIntoOwnersLibrary` · `applyUntilEndOfTurn` · `targetIsColor` |

A `ResolutionEffect` is `(GameState, ResolutionContext) -> GameState`, so **any pure read of
`GameState` remains card-side vocabulary** — counting permanents, branching on `sacrificedForCost`,
drawing from `state.rng`. `Subtype` is a value class over the printed word, so *no subtype is ever a
gap*: "Gate", "Wall", "Ninja" all exist for free.

### The gaps that dominate everything below

1. **No `tap` or `untap` effect primitive.** `tapPermanent` is `private` inside
   `CombatDecisionApplication.kt`; nothing in `effect/` taps or untaps. Four cards want it.
2. **No untargeted "choose a permanent you control" resolution clause.** The seven `ResolutionClauses`
   cover libraries, hands and opponents' hands; none offers your own battlefield.
3. **`AffectedSet` has one member (`Enchanted`).** No static can affect *itself* or *creatures you
   control*, and no static is conditional. That is `FW-CONDSTATIC`, still absent.
4. **No `{X}` anywhere.** `ManaCost` has no variable component; `X` is unsupported since P1.1.
5. **No cast-time optional additional costs.** `OptionalCostThenDraw` is a *resolution* clause tied
   to a draw; kicker, bargain and collect evidence all change the spell's effect and are absent.
6. **No blocking restrictions or requirements.** `canBlock` reads flying, defender and reach; nothing
   models "can't be blocked except by N creatures" or "except by creatures with haste".
7. **No CR 603.4 intervening-if.** `TriggeredAbility` has no condition gate, so "when this enters,
   **if** …" can only be approximated by checking at resolution, which is the wrong rule.
8. **No composite mana production.** `ManaAbility.options` is a *choice* and `amount` a count of the
   chosen type; `Add {W}{U}` and `Add {R}{G}` — two *different* types at once — cannot be said.

---

## 4. The 89 cards

Deck codes: **Elv** Elves · **Gat** Gates · **Grx** Grixis Affinity · **Bog** GW Bogles ·
**Jes** Jeskai Ephemerate · **Jnd** Jund Wildfire · **Fae** Mono Blue Faeries · **Ter** Mono-Blue
Terror · **Mad** Mono-Red Madness · **Rly** Mono Red Rally · **Trn** Monster Tron · **Spy** Spy
Combo · **UWX** UWX Familiar. A `°` marks sideboard-only.

### 4.1 Tier 0 — six cards, composable today

| Card | Cost | Follows the shape of | What it composes | Decks |
|---|---|---|---|---|
| **Basilisk Gate** | land | **Timberwatch Elf** (`TimedEffectCards.kt`) | `{T}: Add {C}` + an activated ability with `cost = [Mana({2}), TapSelf]`, `timing = SORCERY_SPEED` (`FW-MANACOST`), `targetSpec = TargetPermanent(CREATURE)` (`FW-ABILTGT`), and a card-side count of `PermanentFilter(subtype = Subtype("Gate"), controlledByYou = true)` snapshotted into `applyUntilEndOfTurn` (`FW-DURATION`). **All three of its recorded blockers landed.** | Gat |
| **Cast into the Fire** | `{1}{R}` | **Steel Sabotage** (`Blasts.kt`) + **Faerie Macabre** (`MultiTargets.kt`) | `ModalSpell` with two `SpellMode`s: `TargetPermanent(CREATURE, UpTo(2))` + `dealDamage(1)` per target, and `TargetPermanent(ARTIFACT)` + `exilePermanent`. **`FW-MULTITGT` + `FW-MODAL` unblocked it.** | Jes° Rly° Mad° |
| **Dust to Dust** | `{1}{W}{W}` | **Ancient Grudge** (`Removal.kt`) + **Faerie Macabre** | `TargetPermanent(ARTIFACT, Exactly(2))` + `exilePermanent` on each. The noun already existed; only the count was missing. **`FW-MULTITGT` unblocked it.** | Gat° Jes° UWX° |
| **Giant's Boulder** | `{1}` | **Faerie Seer** + **Barrels of Blasting Jelly** + **Scour from Existence** | ETB `libraryLook = LibraryLook(Scry(2))`; `ManaAbility(options = five colours, cost = [Mana({1}), TapSelf])`; activated `[Mana({7}), TapSelf, SacrificeSelf]` → `TargetPermanent(ANY_PERMANENT)` + `destroy`. **`PermanentRestriction.ANY_PERMANENT` now exists.** | Trn |
| **Lotus Petal** | `{0}` | **Saruli Caretaker** (`CostedManaSources.kt`) | `ManaAbility(options = five colours, cost = [TapSelf, SacrificeSelf])`. The original triage's trap T2 called this out; `FW-MANACOST` replaced the `viaSacrifice` flag with the cost list that says it. | Spy |
| **Sunscape Familiar** | `{1}{W}` | **Overgrown Battlement** (`ManaCreatures.kt`) | A 0/3 `DEFENDER` Wall with `spellCostReductions = [SpellCostReduction(1, {GREEN, BLUE})]` — the slot `FW-COST` built naming this exact card in its KDoc. **`Keyword.DEFENDER` now exists.** | UWX |

### 4.2 Tier 1 — thirty-four cards, one or two small primitives each

Primitives are named exactly. A card listing two is marked **(2)**.

| Card | Cost | Primitive(s) needed | Note | Decks |
|---|---|---|---|---|
| **Ancient Stirrings** | `{G}` | `LibraryLookMode.RevealMatchingToHandRestToBottom(count, filter)` + `RevealedCardFilter.COLORLESS_CARD` | Look at five, *optionally* reveal one matching and keep it, rest to the **bottom in any order**. `OneToHandRestToBottom` is close but its keep is mandatory and unfiltered. | Trn |
| **Augur of Bolas** | `{1}{U}` | same as Ancient Stirrings + `RevealedCardFilter.INSTANT_OR_SORCERY_CARD` | The identical mode on an ETB trigger; `TriggeredAbility.libraryLook` already exists. | Jes |
| **Balustrade Spy** | `{3}{B}` | `millUntil(predicate)` in `effect/Mill.kt` | Trigger targeting a player is done (`FW-ABILTGT` + `TargetSpec.TargetPlayer`). Mill-until-a-land is deterministic, no decision. **The Spy Combo engine.** | Spy |
| **Bojuka Bog** | land | `exileGraveyard(state, player)` in `effect/ExileFromGraveyard.kt` | `EntersTapped.Always` + ETB `TargetPlayer` both exist; only the whole-zone fold is missing. Shared with three cards below. | Trn |
| **Bonder's Ornament** | `{3}` | `PermanentFilter.name` axis **(2)** + an each-player draw | The mana half is `FW-MANACOST`-complete. "Each player who controls a permanent named Bonder's Ornament draws" needs a name predicate and a draw that is not controller-scoped. | Trn |
| **Burning-Tree Emissary** | `{R/G}{R/G}` | `TriggeredManaAbility.AddFixedManaMultiset(types)` | Hybrid cost is Slippery Bogle's. "Add `{R}{G}`" is two *different* types in one production, which `AddFixedMana(type, amount)` cannot say. Shared with Azorius Chancery. | Rly |
| **Cleansing Wildfire** | `{1}{R}` | `PermanentRestriction.LAND` **(2)** + routing `librarySearch` to a non-controller | Destroy + draw are published; `LibrarySearchDestination.BATTLEFIELD_TAPPED` exists; `FW-NONCTRLDEC` landed for discards but not for a search. | Jnd |
| **Elves of Deep Shadow** | `{G}` | a self-damage axis on `ManaAbility` | `{T}: Add {B}. This creature deals 1 damage to you.` — a mana ability with a rider. Must stay a mana ability (no stack), so the rider belongs on the type. | Spy |
| **Gatecreeper Vine** | `{1}{G}` | a disjunctive `LibrarySearchFilter` (basic land **or** a named subtype) | Defender, the search clause and `REVEALED_TO_HAND` all exist; today `basic` and `landTypes` conjoin. | Spy |
| **Ghostly Flicker** | `{2}{U}` | `PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL` | `ExileAndReturn.kt`'s own header records this card as blocked **only** on cardinality — `flickerPermanent` covers the blink half entirely. **`FW-MULTITGT` landed; only the union noun is left.** | UWX |
| **God-Pharaoh's Faithful** | `{W}` | a `spellColors` axis on `TriggerCondition.SpellCast` | Guttersnipe's cast trigger filters card *types*, not colours. | UWX |
| **Guardian of the Guildpact** | `{3}{W}` | `P-ABILSOURCE` — thread a prospective source through the four ability targeting sites | `PrintedCharacteristics.protections = {Quality.Monocolored}` already exists and `Prevention.kt` implements CR 702.16e. `Targets.kt` **throws** on any protected object reached from an ability enumeration; the fix is naming a source in `Activation.kt`, `ActivationGathering.kt`, `ActivationExecution.kt`, `TriggerTargeting.kt`. See §5.1. | Gat° |
| **Haunted Fengraf** | land | `returnRandomCardFromGraveyardToHand` in `effect/ReturnToHand.kt` | `{T}: Add {C}` beside `[Mana({3}), TapSelf, SacrificeSelf]` is **done** — the two-costs-one-source shape `FW-MANACOST` was built for, and that was this card's only recorded blocker. What is left is one function: ADR-006 keeps randomness a rules verb, so a card reaching into `state.rng` itself would put a seeded draw in `mtg-cards`. | Trn |
| **Harrier Strix** | `{U}` | `tapPermanent` in `effect/` | Flying, the ETB target, and `drawThenDiscard` on an activated ability all exist. | Fae |
| **Land Grant** | `{1}{G}` | `CastingPermission.AlternativeCost` gated on a hand predicate + a public hand reveal | `FW-ALTCOST` is smaller than its name: `AlternativeCost(ManaCost.parse("{0}"))` exists; what is missing is the *condition* ("if you have no land cards in hand") and the reveal that pays it. The search half is `cropRotation`'s. | Elv Spy |
| **Lead the Stampede** | `{2}{G}` | a rest-to-**bottom** destination on `LibraryReveal` + `RevealedCardFilter.CREATURE_CARD` | `LibraryReveal(count, toHand, toHandCount)` is otherwise exactly this card; its rest goes to the graveyard. Shared with Winding Way. | Elv Spy |
| **Mask of Law and Grace** | `{W}` | `P-ABILSOURCE` (as Guardian of the Guildpact) | `StaticContinuousEffect.grantedProtections = {OfColor(BLACK), OfColor(RED)}` exists; the Aura is otherwise Sentinel's Eyes. | Bog° |
| **Mortuary Mire** | land | `putGraveyardCardOnTopOfLibrary` in `effect/` | Enters tapped, the `CardInGraveyard(CREATURE, YOURS)` target and its `UpTo(1)` optionality all exist. | UWX |
| **Nihil Spellbomb** | `{1}` | `exileGraveyard` (shared with Bojuka Bog) | `{T}, Sacrifice: Exile target player's graveyard` is Ash Barrens' cost shape; the dies-trigger "you may pay `{B}`; if you do, draw" is Ichor Wellspring's condition plus `counterUnlessPaid`-style payment — reuse `DecisionRequest.ChooseCounterPayment`. | Grx Jnd |
| **Quirion Ranger** | `{G}` | `AbilityCost.ReturnPermanentYouControl(filter)` **(2)** + `untapPermanent` | Also wants `oncePerTurn` on `ActivatedAbility`, which `ManaAbility` already has — a one-line lift. | Elv Spy |
| **Rally at the Hornburg** | `{1}{R}` | `AffectedSet.PermanentsYouControlMatching(filter)` for `applyUntilEndOfTurn` | Two Human Soldier tokens are `createToken`'s; "Humans you control gain haste until end of turn" is a *mass* until-EOT grant, and `applyUntilEndOfTurn` takes one `affected` object. A loop over the matching set is the whole change. | Rly |
| **Raze** | `{R}` | `PermanentRestriction.LAND` (shared with Cleansing Wildfire) | `AdditionalCost.Sacrifice(SacrificeFilter(LAND))` and `destroy` both exist. **`FW-ADDSAC` unblocked its cost half.** | Rly° |
| **Relic of Progenitus** | `{1}` | `exileGraveyard` (shared) | `{T}: Target player exiles a card from their graveyard` is a non-controller choice from a *public* zone; `{1}, Exile this: Exile all graveyards, draw a card` needs `AbilityCost.ExileSelf` too. **(2)** | Fae° Mad° Trn° |
| **Ride's End** | `{4}{W}` | `CostReduction` conditioned on the spell's own chosen **target** | "Costs `{3}` less if it targets a tapped permanent" reads the target during cost determination — a new `CostReduction` member, not a new framework. Exile + creature target exist. | Jes |
| **Sewer-veillance Cam** | `{U}` | `tapPermanent` / `untapPermanent` (shared with Harrier Strix) | Flash is `TimingClass.INSTANT_SPEED` on a permanent (no `Keyword.FLASH` by design); enters-**or**-leaves as one printed ability is Ichor Wellspring's; the tap-or-untap choice is a `ChooseYesNo`-shaped mode. | Grx Fae |
| **Sleep of the Dead** | `{U}` | `tapPermanent` **(2)** + a doesn't-untap-next-untap-step marker | Escape is `CastingPermission.Escape(cost, exileOthers = 3)`, already shipped for Sentinel's Eyes. | Ter |
| **Snap** | `{1}{U}` | `untapPermanent` **(2)** + a choose-up-to-N-permanents-you-control resolution clause | "Untap up to two lands" names no target; it is an untargeted mid-resolution selection over your own battlefield, which no clause offers. Shared with Azorius Chancery. | Fae UWX |
| **Spellstutter Sprite** | `{1}{U}` | `SpellRestriction.OfManaValueAtMost(dynamic count)` | `FW-ABILTGT` + `TargetSpec.SpellOnStack` + `counterSpell` are all shipped; countering-spells.md sequenced this as F1.7 precisely because triggers could not target, and now they can. | Fae |
| **Stonehorn Dignitary** | `{3}{W}` | a skip-next-combat-phase delayed effect | `TargetOpponent` on an ETB trigger already exists; `TurnProgression.kt` owns the phase sequence. | UWX° |
| **Thraben Charm** | `{1}{W}` | `PermanentRestriction.ENCHANTMENT` + `TargetCount` on `TargetSpec.TargetPlayer` + `exileGraveyard` | Modal structure and the doubled-count damage are card-side today. `TargetPlayer` is a `data object` with `count` hard-wired to `ONE`; making it a `data class` is the whole change. **`FW-MODAL` + `FW-MULTITGT` unblocked its structure.** | Gat Jes |
| **Tinder Wall** | `{G}` | `PermanentRestriction.CREATURE_THIS_IS_BLOCKING` | Defender, `ManaAbility(options=[RED], cost=[SacrificeSelf], amount=Fixed(2))` and `dealDamage` all exist; only the combat-relative target noun is missing. | Spy |
| **Tolarian Terror** | `{6}{U}` | `FW-WARD` — `Keyword.WARD(cost)` plus a becomes-the-target trigger | Its cost half **is done**: `CostReduction.PerMatching(1, YOUR_GRAVEYARD, AnyOf(INSTANT, SORCERY))` is exactly `crypticSerpent`'s. Ward is the only thing left, and it is one keyword plus one trigger seam — small enough to sit here rather than in Tier 2. | Ter |
| **Winding Way** | `{1}{G}` | a rest-to-bottom/graveyard destination on `LibraryReveal` (shared with Lead the Stampede) + a `libraryReveal` slot on `SpellMode` | "Choose creature or land" is `FW-MODAL`, now landed; the reveal-all-matching is Kruphix's Insight with `toHandCount = count` and two new `RevealedCardFilter` members. **The original triage filed this under `FW-MODAL`; that has landed.** | Elv Spy |
| **Writhing Chrysalis** | `{2}{R}{G}` | `TriggerCondition.SpellCastSelf` **(2)** + `TriggerCondition.YouSacrificedAnother(filter)` | Devoid, Reach and the Eldrazi Spawn token all exist. `SpellCast(controlledByYou = true)` fires on *every* spell you cast — a self-referential cast trigger is its own condition, functioning from the stack. | Gat Jnd Spy° |

### 4.3 Tier 2 — forty-nine cards, framework-blocked

| Card | Cost | Framework(s) | Note | Decks |
|---|---|---|---|---|
| **Avenging Hunter** | `{4}{G}` | `FW-INITIATIVE` | "You take the initiative" is the Undercity dungeon — an entire subsystem. Looks like a 5/4 trample; is not. | Elv |
| **Azorius Chancery** | land | composite mana production + choose-a-permanent-you-control clause | `Add {W}{U}` is two types at once (with Burning-Tree Emissary); the ETB bounce is an untargeted choice of your own land (with Snap). Two Tier-1 primitives that no single packet should own alone — grouped here so one packet owns both. | UWX |
| **Bender's Waterskin** | `{3}` | `FW-RULESMOD` | The `{T}: Add one mana of any colour` half needed nothing from `FW-MANACOST`. The card is blocked *entirely* on "Untap this artifact during each other player's untap step" — a CR 613.11 rules-modifying static over the CR 502.2 turn-based action. **Verified unchanged.** | Jes |
| **Boulderbranch Golem** | `{7}` | `FW-PROTOTYPE` | Prototype changes mana cost, colour **and** P/T — a copiable-values alternative (CR 715), not an alternative cost. | Trn |
| **Bramble Wurm** | `{6}{G}` | `AbilityCost.ExileSelfFromGraveyard` + `AbilityZoneScope.Graveyard` | Reach, trample and the ETB lifegain are Spinewoods Paladin's; the graveyard-activated `{2}{G}, Exile this card from your graveyard` is a zone scope `AbilityZoneScope` does not have (it has `Battlefield` and `Hand`). Borderline Tier 1; grouped here with Relic of Progenitus' `ExileSelf`. | Trn |
| **Call Damage Control** | `{1}{G}` | `FW-MODALMULTI` | "Choose up to two" *modes*, each carrying its own instance of "target". `FW-MODAL` models choose-one; `MultiTargets.kt`'s header names this card as the choose-up-to-N case it deliberately does not model. | Trn° |
| **Citadel Gate** | land | composite mana production | `{T}: Add {W} **or** one mana of the chosen colour` — `choosesColorAsItEnters` exists (Utopia Sprawl) but a production whose option list mixes a fixed colour with the chosen one does not. Also enters tapped. | Gat |
| **Cliffgate** | land | as Citadel Gate | | Gat |
| **Clockwork Percussionist** | `{R}` | `FW-PLAYFROMEXILE` | Haste and the dies trigger both exist. "You may play it until the end of your next turn" is a duration-bounded permission to **play** (not cast — it may be a land) a specific exiled object, spanning two turns. | Rly |
| **Conduit Pylons** | land | surveil | The mana half is **done** (`FW-MANACOST`: a free `{T}: Add {C}` beside a costed `{1}, {T}`). What blocks it is the ETB **surveil 1** — `LibraryLookMode` has no graveyard destination, a non-goal its own KDoc records. **Verified unchanged.** | Trn |
| **Cryoshatter** | `{U}` | trigger conditions for *becomes tapped* and *is dealt damage* | The Aura and its `-5/-0` are Ethereal Armor's. Nothing in `TriggerDetection.kt` watches either event. | Fae Fae° |
| **Deem Inferior** | `{3}{U}` | a cards-drawn-this-turn `CostReduction` + a library-position insertion + `FW-NONCTRLDEC` for the owner's choice | Three new shapes, one of which (putting a permanent second-from-top or on the bottom of a library) is a zone move nothing performs. | Ter |
| **Dread Return** | `{2}{B}{B}` | a card-**type** predicate on `SacrificeRequirement` + return-to-battlefield-**untapped** | `FW-ZONETGT` landed and does not unblock it. `SacrificeRequirement(count, subtype)` predicates on a printed *subtype*; "sacrifice three creatures" needs a card type. `returnFromGraveyardToBattlefieldTapped` enters tapped by contract. **Verified unchanged.** | Spy |
| **Extract a Confession** | `{1}{B}` | `FW-OPTCOST` (collect evidence) + each-opponent-sacrifices | Optional collect-evidence 6 with linked information, and each opponent chooses their own sacrifice. | Grx° |
| **Faerie Miscreant** | `{U}` | CR 603.4 intervening-if + `PermanentFilter.name` | "When this enters, **if** you control another creature named Faerie Miscreant" must be checked when the trigger would go on the stack *and* on resolution. `TriggeredAbility` has no condition gate. | Fae |
| **Fanatical Offering** | `{1}{B}` | `FW-EXPLORE` | The additional sacrifice cost is `FW-ADDSAC`-complete; the Map token's ability targets and **explores**, which nothing models. | Grx Jnd |
| **Fang Dragon** | `{5}{R}{R}` | `FW-ALTFACE` | *Fang Dragon // Forktail Sweep* — an Adventure: two castable faces, cast-then-exile-then-cast-the-creature (CR 715). The decklist names the creature face; the Adventure is what gets cast first. | Spy° |
| **Flaring Pain** | `{1}{R}` | `FW-PREVENT` (CR 615.9 inversion) | Flashback is shipped. `Prevention.kt`'s KDoc names this card as the reason its application point is a function rather than booleans — the inversion clause is unbuilt. | Bog° Rly° Spy° |
| **Gingerbrute** | `{1}` | `FW-BLOCKSET` | Haste and the `{2}, {T}, Sacrifice: gain 3 life` ability both exist. `{1}: can't be blocked this turn except by creatures with haste` is a blocking restriction; `canBlock` models flying, defender and reach only. | Rly |
| **Goblin Bushwhacker** | `{R}` | `FW-OPTCOST` (kicker) + mass until-EOT grant | "If it was kicked" is a cast-time optional additional cost with linked information. The pump half is Rally at the Hornburg's. | Rly |
| **Goblin Tomb Raider** | `{R}` | `FW-CONDSTATIC` | "As long as you control an artifact, this gets +1/+0 and has haste" — a *conditional* static affecting *itself*. `AffectedSet` has one member, `Enchanted`, and `StaticContinuousEffect` has no condition. **Verified unchanged; and cost-modification.md §0 is still right that this is not a cost card.** | Rly |
| **Goliath Paladin** | `{4}{W}` | `FW-INITIATIVE` | As Avenging Hunter. | Jes° |
| **Gorilla Shaman** | `{R}` | `FW-X` | `{X}{X}{1}: Destroy target noncreature artifact with mana value X` — X twice in a cost *and* in the target restriction. **Verified unchanged.** | Grx° |
| **Inventor's Axe** | `{R}` | `FW-EQUIP` + energy counters | Equipment, attachment to a non-Aura object, `{E}` counters and an equip cost paid in them. Four absent concepts on one card. | Rly |
| **Kaervek's Torch** | `{X}{R}` | `FW-X` + `FW-RULESMOD` | X damage, plus "spells that target it cost `{2}` more while it is on the stack" — a rules-modifying static from the stack. **Verified unchanged.** | Trn |
| **Kenku Artificer** | `{2}{U}` | `FW-TYPECHANGE` (layer 4) | Three `+1/+1` counters is `FW-COUNTERS`-complete; "that artifact becomes a 0/0 Homunculus artifact creature with flying" is a layer-4 type change plus a layer-7b P/T set, neither of which `Layers.kt` implements. **Verified unchanged.** | Grx |
| **Maelstrom Colossus** | `{8}` | `FW-CASCADE` | Exile until a cheaper nonland card, cast it for free, bottom the rest in a random order. | Trn |
| **Manor Gate** | land | as Citadel Gate | | Gat |
| **Masked Vandal** | `{1}{G}` | `FW-CHANGELING` | The ETB half is now fully expressible (`FW-ABILTGT` + an artifact-or-enchantment-an-opponent-controls restriction + `exileCardFromGraveyard` as an intervening cost). "This card is every creature type" is not, and it is not cosmetic in a gauntlet with Elf and Faerie counts. **Verified unchanged. No convoke — the upstream brief is still wrong about this card.** | Elv Spy Spy° |
| **Monstrous Emergence** | `{1}{G}` | a choose-or-reveal additional cost carrying linked information | "Choose a creature you control **or** reveal a creature card from your hand", then deal damage equal to *its* power. Two cost modes with a linked characteristic read. | Elv° |
| **Moon-Circuit Hacker** | `{1}{U}` | `FW-NINJUTSU` + `FW-TRIGCOMBAT` | Ninjutsu is an alternative way onto the battlefield from hand, tapped and attacking, mid-combat; the draw trigger needs combat damage to a player. Neither exists. | Fae |
| **Mulldrifter** | `{4}{U}` | `FW-EVOKE` | Flying and the ETB draw-two are trivial. Evoke is an alternative cost plus a sacrifice-on-enter replacement. **The single most-wanted Tier-2 card in the gauntlet by deck count among blue decks.** | Jes UWX |
| **Ninja of the Deep Hours** | `{3}{U}` | `FW-NINJUTSU` + `FW-TRIGCOMBAT` | As Moon-Circuit Hacker. | Fae |
| **Nyxborn Hydra** | `{X}{G}` | `FW-X` + `FW-BESTOW` + enters-with-X-counters | Three frameworks behind a stat line. | Elv Jnd |
| **Pinnacle Kill-Ship** | `{7}` | `FW-STATION` | Spacecraft: charge counters from tapping creatures, and a type change at a counter threshold. | Trn |
| **Prismatic Strands** | `{2}{W}` | `FW-PREVENT` (CR 615.1 shield store) + a tap-a-creature flashback cost | `Prevention.kt` names this card as its unbuilt second clause: a turn-duration prevention store on `GameState`. Its flashback cost taps an untapped white creature, a cost shape `CastingPermission.Flashback` cannot express. **A Gates maindeck four-of and the deck's best card.** | Gat |
| **Prohibit** | `{1}{U}` | `FW-OPTCOST` (kicker) + `SpellRestriction.OfManaValueAtMost` | The MV restriction is shared with Spellstutter Sprite; kicker is the framework. **Verified unchanged.** | UWX |
| **Reckless Impulse** | `{1}{R}` | `FW-PLAYFROMEXILE` | As Clockwork Percussionist, and the same duration window. | Rly |
| **Rooftop Percher** | `{5}` | `FW-CHANGELING` | Its targeting half is **complete and unused** — `MultiTargets.kt` says so explicitly: `CardInGraveyard(ANY_CARD, ALL, UpTo(2))` plus `exileCardFromGraveyard` plus `gainLife`. Only changeling is left. **Verified unchanged.** | Trn |
| **Sacred Cat** | `{W}` | `FW-EMBALM` / `FW-COPY` | Lifelink is printed vocabulary. Embalm creates a **token copy of a card** with altered characteristics — the copy machinery does not exist. | Gat |
| **Sagu Wildling** | `{4}{G}` | `FW-ALTFACE` | *Sagu Wildling // Roost Seek* — an Omen: the same two-castable-faces problem as Fang Dragon, with a shuffle-back instead of an exile. | Elv Spy |
| **Searing Blaze** | `{R}{R}` | `FW-LANDFALL` + two separate instances of "target" | "Target player **and** target creature that player controls" is a *list* of targeting lines, which `TargetCount` deliberately does not model; landfall is a per-turn state read the engine does not keep. | Mad° |
| **Standard Bearer** | `{W}` | a targeting *requirement* (CR 720 flagbearer) | Inverts targeting: while it is on the battlefield, an opponent choosing targets **must** choose it if able. That is a constraint on every enumeration, not a property of an object. | Bog° |
| **Torch the Tower** | `{R}` | `FW-OPTCOST` (bargain) + `FW-REPLACEDEATH` | Bargain is an `OptionalCostMode` member away *if* optional costs moved to cast time; "if a permanent dealt damage by this would die, exile it instead" is a replacement effect keyed to a damage source. | Jes |
| **Toxin Analysis** | `{B}` | `Keyword.DEATHTOUCH` + `FW-EXPLORE`-adjacent investigate | Deathtouch is absent from `Keyword` (only CR 122's KDoc mentions it); lifelink and the until-EOT grant exist. The Clue token needs a sacrifice-to-draw ability, which is Ash Barrens-shaped. Small, but two absences. **Verified unchanged.** | Grx Jnd |
| **Troll of Khazad-dûm** | `{5}{B}` | `FW-BLOCKSET` | Swampcycling is Ash Barrens' `{1}, Discard this card` from `AbilityZoneScope.Hand` with `LibrarySearchFilter(landTypes = {Swamp})` — **fully expressible today**. "Can't be blocked except by three or more creatures" is not. **Verified unchanged.** | Spy |
| **Troublemaker Ouphe** | `{1}{G}` | `FW-OPTCOST` (bargain) + intervening-if | "When this enters, **if** it was bargained" — a cast-time optional cost feeding a CR 603.4 condition. | Jnd° |
| **Vitu-Ghazi Inspector** | `{1}{G}` | `FW-OPTCOST` (collect evidence) + intervening-if | As Troublemaker Ouphe, with collect evidence 6 instead of bargain. | Elv° |
| **Weather the Storm** | `{1}{G}` | `FW-STORM` | Gain 3 life is one line; storm copies the spell once per spell cast before it this turn, which needs a per-turn cast count and spell copying. | Jnd° |

*(Azorius Chancery and Bramble Wurm are listed in Tier 2 above for packet-grouping reasons; both are
technically two-small-primitive cards. They are counted as Tier 2 in the headline table.)*

---

## 5. Frameworks ranked by cards blocked

**Cards** = how many still-unencoded cards name it. **Unlocks alone** = how many become writable the
moment it lands and nothing else does. **Mainboards** = distinct gauntlet mainboards affected.

| # | Framework | Cards | Unlocks alone | Mainboards | Note |
|---:|---|---:|---:|---:|---|
| 1 | `FW-OPTCOST` — cast-time optional additional costs (kicker, bargain, collect evidence) | 6 | 3 | 4 | Goblin Bushwhacker, Prohibit, Torch the Tower, Troublemaker Ouphe, Vitu-Ghazi Inspector, Extract a Confession. The single largest remaining framework, and it was ranked 11th when the backlog was 187. |
| 2 | `FW-X` — `{X}` in costs | 3 | 2 | 3 | Gorilla Shaman, Kaervek's Torch, Nyxborn Hydra. Unsupported since P1.1; `ManaCost` has no variable component at all. |
| 3 | `FW-BLOCKSET` — blocking restrictions and requirements | 3 | 3 | 3 | Gingerbrute, Troll of Khazad-dûm, and (as a requirement) Standard Bearer. **Best unlock ratio in the table**: every card it blocks is blocked by nothing else. |
| 4 | `FW-PREVENT` — the CR 615 shield store and the CR 615.9 inversion | 2 | 1 | 1 | Prismatic Strands (also needs a flashback cost shape), Flaring Pain. The application point already exists and names both. Prismatic Strands is a Gates maindeck four-of. |
| 5 | `FW-CHANGELING` — CR 702.73 every creature type | 2 | 2 | 2 | Masked Vandal, Rooftop Percher. Both cards' *other* halves are now complete and unused — a rare case where a framework is the last thing standing. |
| 6 | `FW-NINJUTSU` + `FW-TRIGCOMBAT` — ninjutsu and combat-damage triggers | 2 | 0 / 0 | 1 | Moon-Circuit Hacker, Ninja of the Deep Hours. **Neither unlocks a card alone**; they only pay off together, which is why Mono Blue Faeries stays expensive. |
| 7 | `FW-PLAYFROMEXILE` — a duration-bounded permission to *play* an exiled card | 2 | 2 | 1 | Clockwork Percussionist, Reckless Impulse. Both Mono Red Rally, both maindeck. |
| 8 | `FW-ALTFACE` — Adventure / Omen split faces | 2 | 2 | 1 | Fang Dragon, Sagu Wildling. |
| 9 | `FW-RULESMOD` — CR 613.11 rules-modifying statics | 2 | 1 | 2 | Bender's Waterskin (untap during each other player's untap step), Kaervek's Torch (a cost increase from the stack). **Verified still absent.** |
| 10 | `FW-INITIATIVE` — the Undercity dungeon | 2 | 2 | 1 | Avenging Hunter, Goliath Paladin. An entire subsystem for two bodies; correctly last-ranked. |
| 11 | `FW-CONDSTATIC` — conditional statics and `AffectedSet` beyond `Enchanted` | 1 (+2 partial) | 1 | 1 | Goblin Tomb Raider outright; Rally at the Hornburg and Goblin Bushwhacker want the `AffectedSet` half only. **Verified still absent.** |
| 12 | `FW-EXPLORE`, `FW-EVOKE`, `FW-CASCADE`, `FW-PROTOTYPE`, `FW-STATION`, `FW-EQUIP`, `FW-EMBALM`, `FW-STORM`, `FW-LANDFALL`, `FW-TYPECHANGE`, `FW-MODALMULTI`, `FW-WARD`, surveil, deathtouch | 1 each | 0–1 | | The one-card tail. Fourteen frameworks for fourteen cards. |
| — | **CR 603.4 intervening-if** | 3 | 0 | 2 | Faerie Miscreant, Troublemaker Ouphe, Vitu-Ghazi Inspector. Not a framework so much as one nullable field on `TriggeredAbility` plus a check at two sites — but it unlocks nothing alone, so it should ride along with `FW-OPTCOST`. |
| — | **`P-ABILSOURCE`** — a prospective source at the four ability targeting sites | 2 | 2 | 0 | Guardian of the Guildpact, Mask of Law and Grace. Not a framework: `Targets.kt` already names the four files. Both cards are sideboard-only, but the fix also removes a live `error()` from the engine. |

### 5.1 `FW-PROTECT` is done; the cards are not

Protection's substrate landed complete: `Quality.OfColor` and `Quality.Monocolored`,
`PrintedCharacteristics.protections`, `StaticContinuousEffect.grantedProtections`,
`engine/Protection.kt`'s quality predicate, and the CR 702.16e prevention clause in `Prevention.kt`.
**Zero cards shipped with it**, and the reason is precise and recorded in `Targets.kt`: every
*spell* call site passes the choosing object as `self`, and every *ability* call site passes `null`,
so CR 702.16b's "abilities from a source with the stated quality" half has no source to read. The
code does not approximate — it `error()`s, deliberately, and the gate is unreachable only because
nothing in the pool has protection yet.

**The moment Guardian of the Guildpact or Mask of Law and Grace lands without `P-ABILSOURCE`, the
engine throws.** The two must ship together, and the fix is threading one `ObjectId` through
`Activation.kt`, `ActivationGathering.kt`, `ActivationExecution.kt`, and `TriggerTargeting.kt`.

### 5.2 What `FW-MULTITGT` unblocked

All three cards dropped for it are now writable, and two of the three need **nothing else**:

- **Dust to Dust** — Tier 0. `TargetPermanent(ARTIFACT, Exactly(2))` + `exilePermanent`.
- **Cast into the Fire** — Tier 0. `ModalSpell` (also landed) with `UpTo(2)` on one mode.
- **Thraben Charm** — Tier 1, and its remaining needs are three enum-sized items, none of them the
  cardinality it was dropped for.

**Ghostly Flicker** was dropped for `FW-MULTITGT` too, and `ExileAndReturn.kt`'s header says the
blink half is entirely expressible — it now needs exactly one `PermanentRestriction` member.

### 5.3 Four stale blockers, all confirmed free

| Card | Recorded blocker | Status now |
|---|---|---|
| **Ghostly Flicker** | `FW-MULTITGT` | **Free.** One `PermanentRestriction` member left (Tier 1). |
| **Giant's Boulder** | a plain-permanent `PermanentRestriction` | **Free.** `ANY_PERMANENT` exists; the card is **Tier 0**. |
| **Sunscape Familiar** | `FW-DEFENDERKW` / `FW-COST` | **Free.** `Keyword.DEFENDER` and `SpellCostReduction` both exist — the latter's KDoc names this card. **Tier 0.** |
| **Basilisk Gate** | `FW-ABILTGT` + `FW-DURATION` + a plain-permanent restriction + sorcery-speed activation | **Free on all four.** `ActivatedAbility.timing` shipped with `FW-MANACOST`, and Timberwatch Elf is the snapshotted-magnitude precedent. **Tier 0.** |

And two more the original triage's traps flagged, both now resolved: **Lotus Petal** (T2 —
`ManaAbilityCost` list, Tier 0) and **Basilisk Gate**'s magnitude (T16 — `applyUntilEndOfTurn` takes
an `Int`, so `Magnitude.Dynamic` cannot be reached by accident).

---

## 6. Recommended packets

Five parallel packets, **disjoint file ownership**, none blocked on another. Together they land
**38 of the 89**. Every one is dispatchable today; none needs a design note first.

### W7-A — the six Tier-0 cards (no engine change)

**Cards (6):** Basilisk Gate · Cast into the Fire · Dust to Dust · Giant's Boulder · Lotus Petal ·
Sunscape Familiar

**Owns:** new `mtg-cards/.../GatesAndBoulders.kt` (Basilisk Gate, Giant's Boulder),
new `mtg-cards/.../ArtifactMana.kt` (Lotus Petal), new `mtg-cards/.../ModalRemoval.kt` (Cast into the
Fire, Dust to Dust), new `mtg-cards/.../Familiars.kt` (Sunscape Familiar); `MvpCards.kt` registry
lines only.

**Touches nothing in `mtg-core` or `mtg-rules`.** This is the packet to dispatch first and the only
one whose review is pure card reading.

### W7-B — tap, untap, and choosing your own permanents

**Cards (7):** Harrier Strix · Sewer-veillance Cam · Snap · Azorius Chancery · Quirion Ranger ·
Sleep of the Dead · Stonehorn Dignitary

**Owns:** new `mtg-rules/.../effect/TapUntap.kt` (`tapPermanent`, `untapPermanent`); a
choose-up-to-N-permanents-you-control `ResolutionClauses` member and its `DecisionRequest` leaf; a
doesn't-untap-next-untap-step marker and a skip-next-combat-phase marker in `TurnProgression.kt` /
`TurnBasedActions.kt`; `AbilityCost.ReturnPermanentYouControl`; `oncePerTurn` lifted onto
`ActivatedAbility`; a composite `ManaAbility` production for `Add {W}{U}`.

**Files:** `effect/TapUntap.kt`, `ResolutionClauses.kt`, `DecisionRequest.kt`,
`TurnProgression.kt`, `TurnBasedActions.kt`, `AbilityCost.kt`, `ActivatedAbility.kt`,
`ManaAbility.kt`, plus new `mtg-cards/.../TapEffects.kt`.

**Overlap warning:** `ManaAbility.kt` is also touched by W7-C for Burning-Tree Emissary's
`{R}{G}`. Give the composite-production type to **W7-B** and have W7-C consume it, or sequence
W7-C's single mana card behind this packet.

### W7-C — library reveals and the graveyard fold

**Cards (9):** Ancient Stirrings · Augur of Bolas · Lead the Stampede · Winding Way ·
Bojuka Bog · Nihil Spellbomb · Relic of Progenitus · Thraben Charm · Haunted Fengraf

**Owns:** `LibraryLookMode.RevealMatchingToHandRestToBottom`; new `RevealedCardFilter` members
(`COLORLESS_CARD`, `INSTANT_OR_SORCERY_CARD`, `CREATURE_CARD`, `LAND_CARD`); a rest-destination
axis on `LibraryReveal`; a `libraryReveal` slot on `SpellMode`; `exileGraveyard` in
`effect/ExileFromGraveyard.kt`; `returnRandomCardFromGraveyardToHand` in `effect/ReturnToHand.kt`;
`AbilityCost.ExileSelf`; `TargetCount` on `TargetSpec.TargetPlayer`;
`PermanentRestriction.ENCHANTMENT`.

**Files:** `LibraryLook.kt`, `LibraryReveal.kt`, `SpellMode.kt`, `TargetSpec.kt`,
`PermanentRestriction.kt`, `AbilityCost.kt`, `engine/LibraryLook.kt`, `engine/LibraryReveal.kt`,
`effect/ExileFromGraveyard.kt`, `effect/ReturnToHand.kt`, plus new
`mtg-cards/.../RevealAndBottom.kt` and `mtg-cards/.../GraveyardHate.kt`.

**Overlap warning:** `AbilityCost.kt` with W7-B, `PermanentRestriction.kt` with W7-D. Both are
additive enum/sealed extensions — the union merge policy handles them, but assign the *file's*
KDoc-union resolution to one packet.

### W7-D — target nouns, counting, and the protection unlock

**Cards (9):** Ghostly Flicker · Raze · Cleansing Wildfire · Tinder Wall · Spellstutter Sprite ·
Ride's End · Guardian of the Guildpact · Mask of Law and Grace · Balustrade Spy

**Owns:** `PermanentRestriction.LAND`, `.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL`,
`.CREATURE_THIS_IS_BLOCKING`; `SpellRestriction.OfManaValueAtMost`; a target-conditioned
`CostReduction` member; `millUntil` in `effect/Mill.kt`; and **`P-ABILSOURCE`** — threading a
prospective source through `Activation.kt`, `ActivationGathering.kt`, `ActivationExecution.kt`,
`TriggerTargeting.kt`, which removes the live `error()` in `Targets.kt`.

**Files:** `PermanentRestriction.kt`, `SpellRestriction.kt`, `CostModifier.kt`, `effect/Mill.kt`,
`engine/Activation.kt`, `engine/ActivationGathering.kt`, `engine/ActivationExecution.kt`,
`engine/TriggerTargeting.kt`, `engine/Targets.kt`, plus new `mtg-cards/.../ProtectionCards.kt`,
`mtg-cards/.../LandDestruction.kt`, `mtg-cards/.../SpyEngine.kt`.

**This is the highest-value non-Tier-0 packet**: nine cards, and it closes an engine defect that is
currently only unreachable by luck.

### W7-E — `FW-OPTCOST` and intervening-if

**Cards (6 + 1 partial):** Goblin Bushwhacker · Prohibit · Torch the Tower · Troublemaker Ouphe ·
Vitu-Ghazi Inspector · Extract a Confession *(and Faerie Miscreant rides on the intervening-if half)*

**Owns:** cast-time optional additional costs (CR 601.2b/f) — kicker, bargain, collect evidence — as
a `CardDefinition` slot with a `DecisionRequest` for the choice and a linked-information flag on
`ResolutionContext`; plus CR 603.4 intervening-if as a condition on `TriggeredAbility` checked at
trigger placement and again at resolution; plus a `name` axis on `PermanentFilter`.

**Files:** new `mtg-core/.../definition/OptionalAdditionalCost.kt`, `CardDefinition.kt`,
`ResolutionContext.kt`, `TriggeredAbility.kt`, `PermanentFilter.kt`, `decision/DecisionRequest.kt`,
`engine/CastCostPayment.kt`, `engine/CastLegality.kt`, `engine/TriggerPlacement.kt`, plus new
`mtg-cards/.../OptionalCostCards.kt`.

**This is the largest framework left and the only one blocking four separate mainboards.** It needs
a design note; dispatch it as a design-then-build pair rather than a single packet.

### Optional sixth: W7-F — `FW-BLOCKSET`

**Cards (3):** Gingerbrute · Troll of Khazad-dûm · Standard Bearer

**Owns:** blocking restrictions and requirements in `engine/CombatActions.kt` (`canBlock` and the
blocker enumeration), plus the CR 720 flagbearer targeting *requirement* in `engine/Targets.kt`.

Best unlock ratio of any remaining framework — every card it blocks is blocked by nothing else — but
it collides with W7-D on `Targets.kt`. Run it after W7-D, or drop Standard Bearer from its scope and
run it in parallel.

### Sequencing

W7-A, W7-C, W7-D and W7-E are mutually disjoint modulo the two additive-enum overlaps noted.
W7-B shares `AbilityCost.kt` with W7-C and `ManaAbility.kt` with nothing else; give it
`ManaAbility.kt` outright. Dispatch **A, B, C, D in parallel now**; **E** after its design note;
**F** after D.

---

## 7. Per-deck completion forecast

| Deck | Main encoded | Missing (main) | After the five packets | Frameworks still needed |
|---|---:|---:|---:|---|
| **GW Bogles** | 18/18 | **0** | **playable today** | — (sideboard: Flaring Pain, Mask of Law and Grace, Standard Bearer) |
| **Mono-Red Madness** | 12/12 | **0** | **playable today** | — (sideboard: Cast into the Fire ✓W7-A, Relic of Progenitus ✓W7-C, Searing Blaze) |
| **Mono-Blue Terror** | 11/14 | 3 | **1 left** (Deem Inferior) | Sleep of the Dead ✓W7-B, Tolarian Terror needs `FW-WARD` |
| **Gates** | 9/17 | 8 | **5 left** | Basilisk Gate ✓A, Thraben Charm ✓C, Writhing Chrysalis (2 trigger conditions); then Citadel/Cliff/Manor Gate (composite mana), Prismatic Strands (`FW-PREVENT`), Sacred Cat (`FW-EMBALM`) |
| **UWX Familiar** | 12/20 | 8 | **3 left** | Sunscape Familiar ✓A, Ghostly Flicker ✓D, God-Pharaoh's Faithful, Mortuary Mire, Snap ✓B, Azorius Chancery ✓B; then Mulldrifter (`FW-EVOKE`), Prohibit (`FW-OPTCOST`) |
| **Monster Tron** | 9/21 | 12 | **7 left** | Giant's Boulder ✓A, Haunted Fengraf + Bojuka Bog ✓C, Ancient Stirrings ✓C, Bonder's Ornament; then Conduit Pylons (surveil), Boulderbranch Golem, Bramble Wurm, Kaervek's Torch, Maelstrom Colossus, Pinnacle Kill-Ship, Rooftop Percher |
| **Grixis Affinity** | 17/22 | 5 | **3 left** | Sewer-veillance Cam ✓B, Nihil Spellbomb ✓C; then Fanatical Offering, Kenku Artificer, Toxin Analysis |
| **Jund Wildfire** | 16/22 | 6 | **4 left** | Cleansing Wildfire ✓D, Nihil Spellbomb ✓C; then Fanatical Offering, Nyxborn Hydra, Toxin Analysis, Writhing Chrysalis |
| **Jeskai Ephemerate** | 16/22 | 6 | **4 left** | Augur of Bolas ✓C, Thraben Charm ✓C; then Bender's Waterskin, Mulldrifter, Ride's End ✓D, Torch the Tower |
| **Elves** | 8/16 | 8 | **5 left** | Lead the Stampede + Winding Way ✓C, Quirion Ranger ✓B; then Avenging Hunter, Land Grant, Masked Vandal, Nyxborn Hydra, Sagu Wildling |
| **Spy Combo** | 8/21 | 13 | **8 left** | Lotus Petal ✓A, Balustrade Spy ✓D, Tinder Wall ✓D, Lead/Winding ✓C, Quirion Ranger ✓B; then Dread Return, Elves of Deep Shadow, Gatecreeper Vine, Land Grant, Masked Vandal, Sagu Wildling, Troll of Khazad-dûm |
| **Mono Blue Faeries** | 6/14 | 8 | **5 left** | Harrier Strix + Sewer-veillance Cam + Snap ✓B, Spellstutter Sprite ✓D; then Cryoshatter, Faerie Miscreant, Moon-Circuit Hacker, Ninja of the Deep Hours |
| **Mono Red Rally** | 5/13 | 8 | **6 left** | Rally at the Hornburg (Tier 1, `AffectedSet`); then Burning-Tree Emissary, Clockwork Percussionist, Gingerbrute, Goblin Bushwhacker, Goblin Tomb Raider, Inventor's Axe, Reckless Impulse |

### Which decks are closest

1. **GW Bogles and Mono-Red Madness are playable now** — both mainboards are complete. The gauntlet
   already has a live matchup, and the MVP acceptance pair remains the only fully-encoded one.
2. **Mono-Blue Terror is the next deck to finish**: three cards, one of them (Sleep of the Dead) in
   W7-B, and only `FW-WARD` — a single keyword plus one trigger seam — standing between it and
   playable. **This is the cheapest new playable deck in the gauntlet and should be the next
   deliberate target.**
3. **Grixis Affinity is second cheapest** at five, three of which are one-framework cards
   (`FW-EXPLORE`, `FW-TYPECHANGE`, deathtouch).
4. **UWX Familiar is third** and the best value per framework: six of its eight fall out of the five
   packets, leaving `FW-EVOKE` and `FW-OPTCOST`.
5. **Mono Red Rally is now the most expensive deck**, not the cheapest — the original triage called
   it the cheapest at five frameworks, and the layer/duration work that made it cheap has since
   landed while its remaining seven cards spread across seven *different* frameworks
   (`FW-CONDSTATIC`, `FW-PLAYFROMEXILE` ×2, `FW-BLOCKSET`, `FW-OPTCOST`, `FW-EQUIP`, composite mana).
   **This is the single largest reversal in the triage.**

The framing from the original triage still holds and is worth restating: **frameworks buy cards, not
decks.** The one place that is now false is Mono-Blue Terror, where `FW-WARD` plus one W7-B card
genuinely completes a mainboard.

---

## 8. Corrections to the original triage

1. **Mono Red Rally is no longer the cheapest deck to finish; it is the most expensive.** Four of the
   five frameworks it was costed at (`FW-DURATION`, `FW-CONDSTATIC`, `FW-COUNTERS`, `FW-EQUIP`) —
   three have landed, and what remains is seven cards across seven unrelated frameworks.
2. **`FW-ABILTGT`, ranked #1 at 32 cards, is done.** The argument for building it first was correct
   and is now spent; nothing in the remaining 89 is blocked on it.
3. **`FW-OPTCOST`, ranked #11 with "unlocks alone: 0", is now #1 at six cards and three
   solo-unlocks.** The original count was right at the time — every optional-cost card was also
   blocked by something else — and every one of those something-elses has since landed.
4. **`FW-MODAL`'s "unlocks alone: 0" has inverted the same way.** It has landed, and it is what makes
   Cast into the Fire Tier 0, Thraben Charm Tier 1 and Winding Way Tier 1.
5. **Ghostly Flicker, Giant's Boulder, Sunscape Familiar and Basilisk Gate are all free.** Three are
   Tier 0.
6. **Protection is finished and shipped zero cards, exactly as protection.md §0 predicted its
   priority** — but the reason the cards are still absent is not protection, it is `P-ABILSOURCE`, a
   four-file threading change that also removes a live `error()` from `Targets.kt`.
7. **Masked Vandal still has no convoke**, and it is now blocked on `FW-CHANGELING` *alone* — its
   ETB half became expressible with `FW-ABILTGT`. The same is true of Rooftop Percher, whose
   targeting half `MultiTargets.kt` describes as "complete and unused".
8. **Toxin Analysis is not one primitive away.** It needs `Keyword.DEATHTOUCH` *and* investigate;
   the original triage's single-framework filing understates it.
9. **Bramble Wurm and Relic of Progenitus share a blocker the original triage did not name**:
   `AbilityZoneScope` has `Battlefield` and `Hand` but no `Graveyard`, and `AbilityCost` has no
   `ExileSelf`. Two cards, one small addition.
10. **`FW-COUNTER` and `FW-COUNTERS` are one letter apart and both landed.** The original triage
    lists them four rows apart with no disambiguation; anything reading that table for planning
    should treat the pair as a known trap.
