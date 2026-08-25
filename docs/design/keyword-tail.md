# Design note — the remaining keyword tail, conditional statics, and granted evasions

Written **after** its implementation packet rather than before it, because unlike `FW-DURATION` or
`FW-PROTECT` this packet had no single new mechanism to argue about in advance. It is four small
pieces that happened to block the same four cards, and what is worth recording is where each one
touched the engine and — for two of them — why the obvious implementation is silently wrong.

It extends [`layer-system.md`](layer-system.md) and [`duration.md`](duration.md); everything about
CR 613 layer classification, timestamps, compute-on-read and the CR 514.2 wear-off stands unchanged.

---

## 0. Oracle text first, and where the triage and the brief are wrong

All five cards were re-fetched from `POST https://api.scryfall.com/cards/collection` (descriptive
`User-Agent`, 5/5 found, `not_found` empty) before any code was written. Oracle text beats both
`docs/gauntlet-card-triage.md` and the packet brief. Four disagreements, one of them load-bearing:

| Card | What the triage/brief says | What the oracle says |
|---|---|---|
| Toxin Analysis | "deathtouch, and it blocks on deathtouch" | Confirmed **plus `Investigate`**, which the brief omits entirely. Investigate is a keyword *action* (CR 701.50a) creating a Clue token, and it composed from published primitives at zero cost (§4). Had it not, it — not deathtouch — would have been the card's blocker. |
| Rooftop Percher | "changeling; blocks on changeling" | Confirmed, and the **rest of the card was already buildable**: `FW-MULTITGT` had landed, so "exile up to two target cards from graveyards" is Faerie Macabre's spec verbatim. `FW-ZONETGT` recorded this exact card as blocked on that cardinality. |
| Goblin Tomb Raider | "`AffectedSet.Self`, a condition on `StaticContinuousEffect`, and a 'you control an artifact' predicate" | Confirmed exactly, and it is the one triage row in this packet that needed no correction. |
| Gingerbrute | "a haste-conditional evasion granted until end of turn" | Confirmed, but the oracle says "**this turn**", not "until end of turn" — the same CR 514.2 duration, differently worded — and the card has a **third** printed line the brief does not mention (`{2}`, `{T}`, Sacrifice: gain 3 life), which is [`foodToken`]'s cost shape because Gingerbrute *is* a Food. |
| Clockwork Percussionist | "likely its own framework; drop it if so" | Confirmed and dropped (§7). |

The pattern earlier packets found holds again: the triage is reliable about *which* framework a card
sits in and unreliable about *how many other* frameworks it also needs — except that this time the
extra needs had all already landed, so three of four cards shipped whole.

---

## 1. Deathtouch — the enum member is the smaller half

`Keyword.DEATHTOUCH` is one line. What it costs is that CR 702.2 **redefines the word *lethal***, and
the engine decides lethality in three places that had no reason to agree before.

### 1.1 CR 704.5h is a separate rule from CR 704.5g, and this is the crux

The brief (and an easy reading of the CR) says deathtouch "changes the CR 704.5g lethal-damage SBA".
It does not. CR 704.5g destroys a creature whose *marked damage total* is at least its toughness;
CR 704.5h destroys a creature that "has been dealt damage by a source with deathtouch", **whatever
the amount and whatever the toughness**. One damage from a deathtoucher destroys a 5/5 that is four
short of its 704.5g threshold, so no arithmetic on the marked-damage total can express it. They are
two conditions, and the implementation has two branches.

### 1.2 The consequence: new state, because `damageMarked` is an `Int`

CR 704.5h asks *what dealt* the damage, and `GameObject.damageMarked` is a bare integer that
remembers nothing about its source. By the time state-based actions are checked the source may have
left the battlefield (CR 113.7a), so the fact cannot be recovered later. Hence
`GameObject.dealtDeathtouchDamage`: decided once, in `dealDamage`, where the `DamageSource` is in
hand, and latched onto the recipient.

**The deviation from CR 704.5h's wording is recorded rather than hidden.** The rule says "since the
last time state-based actions were checked"; the flag instead persists until cleanup. The two are
observationally identical over this engine's closed list of effects, and the argument is a case split
rather than a hope:

- a flagged creature is destroyed at the *very next* check unless it is indestructible (CR 702.12b),
  in which case it is never destroyed by it at any later check either;
- prevented damage is never dealt (CR 615.6), so it sets nothing;
- nothing in the pool regenerates or otherwise survives one check to face another.

Clearing on every check would mean writing to every battlefield object every time any player would
receive priority — state churn the replay fingerprint would carry for no observable difference. The
first effect that lets a creature survive a check while flagged (a regeneration shield, totem armour)
makes the distinction real and must move the clear into the check.

### 1.3 The five honoured sites

| Site | Rule | What deathtouch changes |
|---|---|---|
| `assignBlockedDamage` / `lethalTo` | CR 510.1c, CR 702.2b | lethal to each blocker becomes **1** |
| `trampleExcess` | CR 702.19b | the excess grows, and the excess **is** the enumerated option list (ADR-005) |
| `creatureDeathActions` | CR 704.5h | a new destruction branch, exempted by CR 702.12b |
| `dealDamage` → `markDamage` | CR 702.2b | the source characteristic is recorded when the damage lands |
| `InvariantChecker.checkCreatureLethalityResolved` | CR 704.5h | the acceptance checker re-derives lethality *independently*, so it needed the branch too — and, once deathtouch became grantable alongside Tamiyo's Safekeeping's indestructible, it needed the CR 702.12b exemption it had never had |

That last row is the one a packet is most likely to miss: it lives in a different module, is not a
rules seam, and would have failed the invariant on a perfectly legal board.

---

## 2. Changeling — a rule, not a characteristic, and the gate that makes it correct

CR 702.73a: "this card is every creature type", and it "works everywhere, even outside the game".

`Subtype` is a value class over the printed word (its own KDoc argues the space is too large for an
enum), so "every creature type" has no finite value to expand into: the answer has to be computed per
query. That is `PrintedCharacteristics.hasSubtype(subtype)`, which follows `Keyword.DEVOID`'s path
exactly — a characteristic-defining ability read off the printed characteristics, correct in every
zone.

### 2.1 The half that would have been silently wrong

Changeling grants **creature** types and nothing else. A naive "changeling matches every subtype"
would make a 5-mana Shapeshifter a Forest for Gingerbread Cabin's enters-untapped count, a Mountain
for Fireblast's sacrifice cost, and a legal Utopia Sprawl target — three wrong results that look
right, all reachable, none crashing.

CR 205.3 categorises a subtype by the word itself, and `Subtype` carries no category. So the packet
added `CreatureType`: two listed vocabularies (creature types; land, artifact and enchantment types)
and `Subtype.isCreatureType()`, which **fails loudly** on a word in neither. Guessing either way
would put the silent wrongness straight back. `mtg-cards` owns the test that every subtype the
registry prints is classified, so a new card that forgets its word breaks the build rather than a
future game.

### 2.2 Every subtype predicate, routed

Before this packet, subtype matching was `subtype in characteristics.subtypes` in six places plus two
**private copies inside `mtg-cards`** (Breath Weapon's "each non-Dragon creature" and Wellwisher's
"for each Elf you control"). Both copies would have ignored changeling. All eight now go through one
of two accessors:

| Site | Zone | Accessor | Changeling answer |
|---|---|---|---|
| `PermanentCount.countMatchingPermanents` | battlefield | `hasSubtype(state, id, …)` | yes for a creature type — Priest of Titania counts it |
| `ObjectCount.matches` (`HasSubtype`) | battlefield, graveyard | `PrintedCharacteristics.hasSubtype` | yes — a Human cost reduction counts it in a graveyard too |
| `LibrarySearchFilters` | library | `PrintedCharacteristics.hasSubtype` | no — land types only |
| `EnchantRestrictions` (FOREST) | battlefield | `PrintedCharacteristics.hasSubtype` | no — Forest is a land type |
| `CastLegality.sacrificeableFor` | battlefield | `PrintedCharacteristics.hasSubtype` | no — Fireblast wants Mountains |
| Breath Weapon, Wellwisher (`mtg-cards`) | battlefield | the published `hasSubtype` seam | yes — the private copies are gone |
| `cli/Labels.isAura` | any | `PrintedCharacteristics.hasSubtype` | no — Aura is an enchantment type |

The battlefield seam reads the **layered** keyword set so a *granted* changeling would work; nothing
in the pool grants one, so today the two accessors agree everywhere.

---

## 3. `FW-CONDSTATIC` — the narrow half, taken

Goblin Tomb Raider needs three things, and deliberately not a fourth:

- **`AffectedSet.Self`** — the first member beside `Enchanted`, and the simpler of the two: the
  collection walk is over battlefield permanents, so the source is present by construction and the
  affected set is never empty.
- **`StaticCondition`** on `StaticContinuousEffect` — sealed, one member `YouControl(filter, atLeast)`,
  deliberately the same `(filter, atLeast)` pair `EntersTapped.UnlessYouControl` already uses for
  Gingerbread Cabin, so both count through the one `countMatchingPermanents` seam.
- the **evaluation point**, which is `staticEffectsOn` — on every read.

That last is CR 604.3 rather than an optimisation: a conditional static ability's effect applies
exactly while its condition is true, with no trigger, no stack, and no player receiving priority in
between. Because characteristics are computed on read and never cached, that continuity costs one
filter and no invalidation machinery. **Encoding the card as an enters-the-battlefield trigger
granting an until-end-of-turn effect would look identical in the common case and be wrong the moment
the artifact is removed in response.**

**Not built: computed affected sets.** "Creatures you control" (Goblin Bushwhacker, Rally at the
Hornburg) is still absent, and it is a genuinely harder problem than `Self` rather than more of the
same: CR 611.2c locks a *resolution*-generated set at creation while a *static* one is re-evaluated
continuously, so one `AffectedSet` member cannot serve both. `duration.md` §9.5 makes the same call
from the other side.

---

## 4. The granted evasion — the seam `Evasion` reserved

`Evasion`'s KDoc had said: "nothing in the MVP pool grants or removes an evasion, so combat reads it
straight from the printed characteristics; a future granting effect would route it through the layer
system exactly as keyword grants are." Gingerbrute is that future. Its `{1}` ability *creates* the
restriction, so there is no printed value to read at all, and `printsFlyingOnlyEvasion` — which read
the definition registry directly, bypassing CR 613 — had to go.

- `LayeredCharacteristics.evasions`, unioned in layer 6 (a "can't be blocked except by …" line **is**
  a static ability, CR 613.1f).
- `ContinuousModification.grantedEvasions`, and its do-something gate widened to count it.
- `effectiveEvasions`, the fifth `effective*` seam, which `canBlock` now reads.
- **No** `grantedEvasions` on `StaticContinuousEffect`: no card grants an evasion through a
  permanent's static ability, and an always-empty field would be an untested branch of the layer-6
  union. This is `duration.md` §5.1's own call about mana abilities, applied in the mirror direction.

`Evasion.BLOCKABLE_ONLY_BY_HASTE` reads the *blocker's* haste through the effective-keyword seam, so
a blocker whose haste is itself conditional (Goblin Tomb Raider with an artifact out) may block.
Nothing here is about summoning sickness — CR 302.6 never restricted blocking.

---

## 5. Blast radius, by file

| Module / file | Change |
|---|---|
| `mtg-core/card/Keyword.kt` | **+2 members** `DEATHTOUCH`, `CHANGELING` |
| `mtg-core/card/Evasion.kt` | **+1 member** `BLOCKABLE_ONLY_BY_HASTE`; KDoc reversed on "printed only" |
| `mtg-core/card/CreatureType.kt` | **new** — the CR 205.3 vocabulary split and `Subtype.isCreatureType` |
| `mtg-core/card/PrintedCharacteristics.kt` | **+1 accessor** `hasSubtype` (the changeling-aware read) |
| `mtg-core/definition/AffectedSet.kt` | **+1 member** `Self` |
| `mtg-core/definition/StaticCondition.kt` | **new** — sealed, one member |
| `mtg-core/definition/StaticContinuousEffect.kt` | **+1 field** `condition` |
| `mtg-core/state/ContinuousModification.kt` | **+1 field** `grantedEvasions`; do-something gate widened |
| `mtg-core/state/GameObject.kt` | **+1 field** `dealtDeathtouchDamage`, with a construction guarantee tying it to `damageMarked` |
| `mtg-rules/engine/Deathtouch.kt` | **new** — `hasDeathtouch`, `sourceHasDeathtouch` |
| `mtg-rules/engine/Changeling.kt` | **new** — the published `hasSubtype` seam |
| `mtg-rules/engine/EffectiveEvasions.kt` | **new** — the fifth `effective*` seam |
| `mtg-rules/engine/Layers.kt` | `ActiveEffect.grantedEvasions`; the layer-6 test extracted to `grantsAnAbility` |
| `mtg-rules/engine/LayeredCharacteristics.kt` | `evasions`; `AffectedSet.Self`; the CR 604.3 condition filter |
| `mtg-rules/engine/CombatActions.kt` | `canBlock` reads `effectiveEvasions`; the haste-evasion conjunct |
| `mtg-rules/engine/TrampleAssignment.kt` | `lethalTo` takes the **attacker**; CR 702.2b flat 1 |
| `mtg-rules/engine/StateBasedActions.kt` | **+1 branch** CR 704.5h; the CR 702.12b exemption hoisted to cover both destructions |
| `mtg-rules/engine/MarkedDamage.kt`, `effect/DealDamage.kt` | the deathtouch fact recorded where the source is known |
| `mtg-rules/engine/TurnBasedActions.kt` | CR 514.2 clears the record with the damage, one transition |
| `mtg-rules/engine/{PermanentCount,ObjectCount,LibrarySearchFilters,EnchantRestrictions,CastLegality}.kt` | subtype reads routed through the accessors |
| `mtg-cards/KeywordTailCards.kt` | **new** — four cards and `clueToken` |
| `mtg-cards/{GauntletBurn,GauntletLifegain}.kt` | private `hasSubtype` copies deleted, composing the published seam |
| `mtg-cli/Labels.kt` | `isAura` routed through the accessor |
| `mtg-protocol` | **+1 required field** on `GameObjectDto`; version **8.0.0** |
| `mtg-acceptance` invariant checker | `MARKED_DAMAGE_SCOPE` covers the record; `CREATURE_LETHALITY_RESOLVED` gains CR 704.5h **and** the CR 702.12b exemption |
| `mtg-acceptance/replay/Fingerprint.kt` | **+1 token** `:deathtouched` |

Not touched, and deliberately: `PermanentRestriction`/`Targets.kt`, `ManaAbility` /
`PaymentEnumeration` / `ActivationEnumeration`, `LibrarySearch`, damage prevention,
`mtg-pauper/src/main/resources/`.

---

## 6. Protocol

`PROTOCOL_VERSION` **7.0.0 → 8.0.0**, and not folded into `7.0.0` the way that version absorbed
several packets. Two independent breaks: a **required** `dealtDeathtouchDamage` on `GameObjectDto`
(every seat view fails a strict `7.0.0` decode), and two new `Keyword` values plus one new `Evasion`
value riding in `PrintedCardDto`, whose `parseVocabulary` is strict — a **runtime** decode failure
mid-match, the harsher of the two break modes this file's history distinguishes. Client→server is
unchanged: no new request kind, no new `Target` member. Deathtouch and the new evasion do change
which options an agent is *offered*, but an option list is a payload the wire already carries.

---

## 7. Dropped, with its precise blocker

**Clockwork Percussionist** — `{R}` Artifact Creature — Monkey Toy, 1/1, haste. "When this creature
dies, exile the top card of your library. You may play it **until the end of your next turn**."

The dies trigger and the exile are both published (`exilePermanent`'s graveyard sibling, and the
`PutIntoGraveyardFromBattlefieldSelf` condition). The blocker is the last clause, and it is *two*
non-goals of `FW-DURATION` at once, both named in that note's §12:

1. **Duration-bounded *permission*.** "You may play those cards" is a `CastingPermission` with a
   deadline, not a CR 613 continuous effect. It is consulted at cast-legality time, never in the
   layer walk, so it cannot live in the `timedEffects` store however the duration is spelled.
2. **A duration that is not end of turn.** `EffectDuration` is sealed with one member precisely so
   that "until the end of your next turn" breaks the cleanup `when` rather than silently expiring a
   turn early. Spanning two turns also needs the turn *number* recorded, in the shape
   `GameObject.plottedTurn` and `reboundTurn` already have.

**What it needs, precisely:** an `EffectDuration.UntilEndOfYourNextTurn` member with the turn-number
field its cleanup comparison requires, and a `CastingPermission` that reads it. Neither is duration
machinery as built; both belong to the packet that owns casting permissions. Reckless Impulse is the
same shape and unblocks with it.

---

## 8. Open questions for the architect

1. **The CR 704.5h flag's lifetime** (§1.2): persisting until cleanup, with the case-split argument
   for observational equivalence — versus clearing it inside every state-based-action check, which is
   the letter of the rule and writes to every battlefield object on every priority pass.
2. **`CreatureType`'s loud failure** (§2.1): failing on an unclassified subtype word, backed by a
   registry-wide classification test — versus defaulting to "not a creature type", which would be
   quiet and would under-grant rather than over-grant.
3. **The CR 702.12b exemption in the acceptance invariant** (§1.3): it was missing before this packet
   and unreachable only by luck (no indestructible *creature* existed until Tamiyo's Safekeeping).
   Confirm that fixing it here, rather than in a packet of its own, is right.
4. **Protocol** (§6): a full major to `8.0.0` rather than absorbing into the unreleased `7.0.0`.
5. **Card scope** (§7): four of five carried, one dropped to casting-permission work.
