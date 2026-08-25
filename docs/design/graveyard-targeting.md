# Design note — targeting a card in another zone (`FW-ZONETGT`)

The reference for the zone-targeting framework: what it means to target a card that is not a permanent
and not a spell, why the answer turned out to be narrower than the packet name suggests, and how the
gauntlet's nine "target card in a graveyard" cards decompose into sequenced packets.

Two things anchor the design. Downward, **CR 400.2** (public and hidden zones) and **CR 400.7** (an
object that changes zones becomes a new object), the two rules that between them decide both what may be
enumerated and what a stale target names. Upward, the seams `FW-COUNTER` cut and this packet reuses
unchanged: `legalTargets`/`isTargetLegal` (`Targets.kt`) as the single source of target-legality truth,
and the fresh-id-per-residence rule that makes the CR 608.2b re-check work with no new code.

Written to PLAN.md §5's rule that a framework packet gets a design note, in the style of
`docs/design/countering-spells.md`.

---

## 1. The oracle text, and three places the triage is wrong

Fetched from Scryfall (`POST /cards/collection`, 2026-08-24), all eight found, no ambiguity:

| Card | Cost | Oracle text | Shape |
|---|---|---|---|
| Archaeomancer | `{2}{U}{U}` | When this creature enters, return target **instant or sorcery** card from **your** graveyard to your hand. | targeted ETB, one target |
| Pulse of Murasa | `{2}{G}` | Return target **creature or land** card from **a** graveyard to its owner's hand. You gain 6 life. | targeted spell, one target |
| Dread Return | `{2}{B}{B}` | Return target creature card from your graveyard to the battlefield.<br>Flashback—**Sacrifice three creatures.** | one target + non-mana flashback cost |
| Mortuary Mire | — | This land enters tapped.<br>When this land enters, **you may** put target creature card from your graveyard **on top of your library**.<br>`{T}`: Add `{B}`. | land ETB, optional, new zone move |
| Faerie Macabre | `{1}{B}{B}` | Flying<br>Discard this card: Exile **up to two** target cards from graveyards. | multi-target |
| Rooftop Percher | `{5}` | **Changeling**<br>Flying<br>When this creature enters, exile **up to two** target cards from graveyards. You gain 3 life. | multi-target + changeling |
| Blood Fountain | `{B}` | When this artifact enters, create a Blood token.<br>`{3}{B}`, `{T}`, Sacrifice this artifact: Return **up to two** target creature cards from your graveyard to your hand. | multi-target |
| Call Damage Control | `{1}{G}` | **Choose up to two.** Return those cards from your graveyard to your hand.<br>• Target artifact card. • Target creature card. • Target enchantment card. • Target land card. | modal + multi-target |

Three corrections, all in the packet's favour for scoping and against it for card count:

**1.1 — The packet name says "hidden or non-battlefield zone". Every card in it targets a *graveyard*.**
Not one of the eight targets a library or a hand card, and neither does any of the nine
`FW-ZONETGT` rows in `docs/gauntlet-card-triage.md` (Thraben Charm's third mode is "a player's
graveyard" too). That is not an accident of this sample: "target card in your library" is close to
nonexistent in Magic, because a library is hidden and a target must be nameable. The framework this
packet actually owes is therefore **graveyard** targeting, and §3 turns that from a coincidence into a
guarantee.

**1.2 — Six of the eight are blocked, and the triage understates it by one.** The triage tags Faerie
Macabre, Rooftop Percher, Blood Fountain and Call Damage Control with `FW-MULTITGT`, which is right. It
tags **Dread Return** with `FW-ZONETGT` alone and mentions the sacrifice cost only in prose — but
`SacrificeRequirement` predicates on a printed **subtype** (`count`, `subtype`), and "sacrifice three
creatures" is a **card type** predicate that the type cannot express at all. Dread Return is
framework-blocked, not primitive-blocked, and its blocking framework is one this packet does not own.
And **Mortuary Mire** is blocked three times over, not once: triage T18 (`executePlayLand` never calls
`detectEnterBattlefieldTriggers`), the optional "you may" trigger, and "on top of your library" as a zone
move that does not exist.

**1.3 — "Pulse of Murasa targets a creature or land card in *a* graveyard (either player's)" is right,
and it matters more than the triage row implies.** It is the first card in the pool that targets an
**opponent's** object outside the battlefield, which is precisely the case §3's ruling has to clear.

So this packet ships **two** cards, Archaeomancer and Pulse of Murasa, and the framework that unblocks
the other seven. §6 gives each drop in full.

---

## 2. What "target a card in a graveyard" is, and what it is not

A graveyard card is a fourth kind of thing. It is not a *permanent* — CR 110.1, only the battlefield has
permanents — so nothing about `TargetSpec.TargetPermanent` reaches it. It is not a *spell* — CR 111.1, a
spell is a card on the stack. And it is not a player. So it needs its own `Target` member and its own
spec, exactly as `Target.SpellOnStack` did, and for the same reason: the sealed hierarchy is one member
per kind of thing.

Three consequences fall out of the zone rather than out of the targeting:

- **No layer system.** CR 613 applies to permanents and to objects on the stack; a card in a graveyard
  has only its printed characteristics (CR 109.3). So `GraveyardCardRestriction` reads printed card types
  and always will. This is the opposite of `PermanentRestriction`, which deliberately admits a computed
  characteristic (`CREATURE_POWER_2_OR_LESS` reads layered power). A "graveyard card with power 2 or
  less" restriction would be a category error, not a missing feature.
- **No hexproof.** Hexproof is a quality of a permanent (CR 702.11). The enumeration does not consult
  `targetableBy`, and nothing in the gauntlet makes a graveyard card untargetable.
- **A reachable fizzle by a route the battlefield does not have.** Anything that moves the chosen card
  out of the graveyard rebirths it under a fresh id (CR 400.7), so a second effect returning or exiling
  it first makes the stale `Target.CardInGraveyard` name nothing, and CR 608.2b fizzles the first through
  the enumeration that already exists. Same mechanism as a stale `Target.SpellOnStack`; no new code.

---

## 3. The ADR-007 ruling

This is the one thing `FW-COUNTER` could not pre-pay. Its own answer was easy — a stack object is public
(CR 405), so countering needed no filter — and it recorded on `SeatView.pendingTriggerTargets` that *"the
moment `Target` gains a member naming a card in a hidden or semi-hidden zone (`FW-ZONETGT`, a graveyard or
library card), this ruling must be revisited together with `cards`."* `docs/design/library-look.md` §3
reserved the same revisit from the other side.

**Decision — no filtering rule is added, and the reason is structural rather than circumstantial: the
`Target` hierarchy is given a member that cannot name a hidden-zone card at all.**

Three parts, each with its reason.

**(a) Visibility is decided by the zone, not by the fact of targeting.** CR 400.2 makes the graveyard a
**public** zone and the library and hand **hidden** ones. An option list naming a graveyard card
discloses nothing: `VisibleCards.kt` already feeds *both* seats' graveyards into `SeatView.cards`
("both seats' graveyards (CR 404, public)"), and `PlayerView` already carries both graveyards in full. A
seat offered `Target.CardInGraveyard(#312)` can already read #312 off its own view. So
`pendingTriggerTargets` stays public and carried in full, `visibleCardRefs` gains **no** clause, and
`SeatView.cards` is unchanged. This is the opposite outcome to `library-look.md` §3 — correctly so, because
a look's pool is *library* cards, and that note added its one clause precisely because the pool was
hidden.

**(b) The member is `Target.CardInGraveyard`, not `Target.CardInZone(id, zone)`.** This is the part that
makes (a) durable. A zone-parameterised member would put a public referent and a hidden referent in one
type, and the ADR-007 boundary would then have to be a runtime check on a field that nothing forces a
future author to consult — the enumeration could quietly start offering library cards and every `when` in
the codebase would keep compiling. With a zone in the type, the guarantee is a property of the hierarchy:
**no value of any `Target` member can name a card in a hidden zone.** ADR-005's enumeration and ADR-007's
filter then agree *by construction* rather than by review, which is the same standard `SpellRestriction`
holds for the CR 608.2b re-check.

A future "target card in a library" — none exists in the gauntlet — is therefore a **new member**, and
adding one breaks every `when` in the codebase, the view-side ones included. That break is the review
moment ADR-007 wants, and it is not skippable.

**(c) Both halves are pinned by tests that fail together.** The ruling has two halves and either alone is
worthless: the enumeration must name only public-zone objects, *and* both seats' card tables must already
describe what it names. `ViewLeakPropertySpec.checkTargetOptionZones` runs on every `ChooseTargets` pause
of the matchup corpus and asserts both, deriving "hidden" from the raw state's library and hand contents
rather than from the `Target` subtype — so it stays a true property even if a future member is added.
Stated plainly: that corpus is Madness vs Bogles and contains **neither** shipped card, so today the
graveyard branch there is a standing guard rather than an exercised path — it starts biting the moment a
graveyard-targeting card enters a corpus deck.
`GraveyardTargetingAcceptanceSpec`'s third case exercises the same two halves on a board that really does
hold graveyard-card options, and checks the **non-deciding** seat's card table, which is the load-bearing
one: if `visibleCardRefs` ever narrowed to the viewer's own graveyard, half one would still pass while a
seat received an option it could not name.

`ViewLeakPropertySpec` was **extended and not relaxed**; no assertion in it was weakened.

---

## 4. The types

```kotlin
// mtg-core — the fourth kind of thing.
data class CardInGraveyard(val id: ObjectId) : Target

// mtg-core — the spec, on two independent axes.
data class CardInGraveyard(
    val restriction: GraveyardCardRestriction,   // the noun: INSTANT_OR_SORCERY, CREATURE_OR_LAND
    val scope: GraveyardScope,                   // the possessive: YOURS, ANY
) : TargetSpec
```

The two axes are separate because they are genuinely independent — the gauntlet prints "your graveyard"
with three different nouns and "a graveyard" with two — and folding them together would multiply out into
a member per pairing, the combinatorial shape a closed restriction enum exists to avoid. Both are closed
enums for the reason `PermanentRestriction` is one: a new restriction must break the rules-side `when`
rather than slip through. Members exist only where a shipped card prints them, so the enums have two
members each and not the six the family will eventually want.

`GraveyardScope` makes this the **second decider-relative spec** after `TargetSpec.TargetOpponent`, and
the first whose *objects* depend on who is choosing. `legalTargets` already threads the deciding player
through every site — the caster at CR 601.2c, the ability's controller at CR 603.3d, the same controller
at the CR 608.2b re-check — so "your graveyard" cannot be cast against one graveyard and re-checked
against another. Nothing new was needed for that; it is the parameter `FW-ABILTGT` already added.

---

## 5. The dispatch sites

Ten, all mechanical, all forced by the compiler — the ~10-file cost triage T15 predicted, paid once:

| Site | Module | What it gained |
|---|---|---|
| `Targets.kt` `legalTargets` | rules | the enumeration branch + `graveyardsInScope` |
| `GraveyardCardRestrictions.kt` | rules | `satisfiesGraveyardCardRestriction` (new file) |
| `ActionEnumeration.targetsAvailable` | rules | grouped arm |
| `CastGathering.beginCastGathering` | rules | grouped arm |
| `CastingPipeline.establishTargets` | rules | grouped arm |
| `StackResolution.auraAttachmentTargetOf` | rules | grouped arm (an Aura never attaches to a graveyard card) |
| `DealDamage` | rules | a second loud arm — CR 120.3 damage never reaches a graveyard card |
| `TargetDto` + both mappers | protocol | `card_in_graveyard_target` |
| `Labels.targetLabel` | cli | looks the card up across both graveyards |
| `FingerprintRenderers.renderTarget` | acceptance | `graveyard<id>` prefix |

Plus four test-fixture `when`s (`FixtureCards`, `CastFromElsewhereFixtures`, `SacrificeCostSpec`,
`CastFromElsewhereAcceptanceSpec`), each of which fails loudly on a target shape its fixture cannot
produce.

---

## 6. What is dropped, and exactly what each needs

| Card | Blocked on | Precisely |
|---|---|---|
| Faerie Macabre | `FW-MULTITGT` | "Up to two target cards from graveyards." `TargetSpec` is one-target by construction and `DecisionRequest.ChooseTargets` is a `SingleOptionSelection` whose `init` rejects an empty option list; "up to N" needs both a cardinality on the spec and a multi-select request kind, plus the CR 608.2b partial-legality rule `allTargetsIllegal` currently documents as unreachable. The `GraveyardCardRestriction.Any` member it wants does not exist either, deliberately — no shipped card prints it. |
| Rooftop Percher | `FW-MULTITGT`, `FW-CHANGELING` | The same multi-target shape, plus changeling (CR 702.73) — every creature type at once, which nothing in the subtype model expresses. |
| Blood Fountain | `FW-MULTITGT` | "Up to two target creature cards from your graveyard", on an activated ability. The Blood token and the `{3}{B}`, `{T}`, Sacrifice cost shape already exist; only the cardinality is missing. |
| Call Damage Control | `FW-MODAL`, `FW-MULTITGT` | "Choose up to two" modes, each with its own target. `CastingPipeline.chooseModes` is a documented no-op and `SpellDefinition` cannot express modes at all; the two chosen modes then need two targets. |
| Dread Return | additional-cost shape | The main half composes fine, but the flashback cost is "Sacrifice three creatures" and `SacrificeRequirement(count, subtype)` predicates on a printed **subtype**. It needs a card-type predicate, in `AdditionalCost`/`SacrificeRequirement` — files this packet does not own. Shipping the card without its flashback line would be a plausible-looking wrong card (PLAN.md §7). It would *also* need a "return from graveyard to the battlefield **untapped**" primitive; the existing one enters tapped by contract. |
| Mortuary Mire | triage T18, optional triggers, new zone move | (i) `executePlayLand` never calls `detectEnterBattlefieldTriggers`, so a played land's ETB trigger is silently lost — the card would look perfectly encoded and do nothing; (ii) "**you may** put" is an optional trigger, which no `TriggeredAbility` shape expresses; (iii) "on top of your library" is a graveyard→library-top move that no primitive performs. Any one of the three blocks it. |

Thraben Charm (`FW-MODAL` + this framework, one mode targeting "a card in a player's graveyard") is in
the wider triage but not in this packet's card list; it is blocked on `FW-MODAL` the same way Call Damage
Control is.

---

## 7. Acceptance

`GraveyardTargetingAcceptanceSpec` runs both shipped cards end to end under the invariant checker:
Archaeomancer on the CR 603.3d trigger path with `GraveyardScope.YOURS` (an opponent's Lightning Bolt is
declined by the scope, the controller's own Grizzly Bears by the restriction), Pulse of Murasa on the
CR 601.2c cast path with `GraveyardScope.ANY`, targeting the **opponent's** creature card and returning it
to *that* player's hand while the caster gains the life. The third case is §3(c)'s ADR-007 pin.

`GraveyardTargetingSpec` (`mtg-rules`) exercises the enumeration directly on both axes, the CR 400.7
staleness that makes the fizzle reachable, and the P2.1 inert-card ruling.

---

## 8. Protocol

Held at **`5.0.0`**. `TargetDto` gains `card_in_graveyard_target`, which a `4.0.0` peer would meet as a
runtime decode failure — on its own a major bump by the standard the last three versions set. But `5.0.0`
is already the multi-framework wave version, and its own note records why: *"neither shipped separately,
so `4.0.0` is the last version any consumer can have seen and one major bump carries both breaks."* This
framework lands in the same unreleased wave, so the premise holds unchanged and a third framework is
folded into the same major bump rather than inflating the count for a version nobody could have consumed.
The `ProtocolVersion` KDoc says so explicitly.
