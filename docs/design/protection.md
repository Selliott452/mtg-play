# Design note — protection (CR 702.16) and the damage-prevention prerequisite (F2.0)

The reference for the protection framework proposed as F2 by the sibling project's
`ENGINE-CARD-BRIEF.md` §4. Protection touches four subsystems at once — targeting, Aura attachment,
block legality, and damage — which is why it is a framework packet under PLAN.md §5 ("framework
packets … are not [parallelizable] — one agent at a time, with design review before merge") and why
this note exists before any implementation packet starts.

Two things anchor it. Downward, CR 702.16 and CR 615, the rules the engine must obey. Upward, the
seams already cut: `legalTargets`/`targetableBy` (`Targets.kt`), `canBlock` (`CombatActions.kt`),
`auraAttachmentIsLegal` (`StateBasedActions.kt`), and `dealDamage` (`effect/DealDamage.kt`). Three of
those four take protection with a small, local change. The fourth does not, and most of this note is
about why.

**Headline finding, stated once up front.** Of the four cards F2 claims to unblock, **two are not
protection cards at all**. Prismatic Strands is a damage-prevention effect (CR 615) and needs zero
protection code; Standard Bearer is a targeting *requirement* (CR 601.2c) and needs a different
framework again. Protection proper carries Mask of Law and Grace and Guardian of the Guildpact. A
damage-prevention *application point* is a genuine hidden prerequisite for protection's **D**; a
damage-prevention *shield store* is a further prerequisite for Prismatic Strands alone.

---

## 0. The four cards, from oracle text — and where the brief is wrong

Fetched from Scryfall (`api.scryfall.com/cards/named?exact=…`) on 2026-08-24; all four are
Pauper-legal. The brief itself warns that its §4 groupings "were derived from card names" and that
"[w]here this brief and the oracle text disagree, the oracle text wins". They disagree three times.

| Card | Cost / type | Oracle text | Verdict |
|---|---|---|---|
| **Mask of Law and Grace** | `{W}` Enchantment — Aura | Enchant creature / Enchanted creature has protection from black and from red. | **Protection.** Granted by an Aura; two qualities (CR 702.16g). |
| **Guardian of the Guildpact** | `{3}{W}` Creature — Spirit | Protection from monocolored | **Protection.** *Printed*; quality is **not a colour**. |
| **Prismatic Strands** | `{2}{W}` Instant | Prevent all damage that sources of the color of your choice would deal this turn. / Flashback—Tap an untapped white creature you control. | **Not protection.** Prevention effect, CR 615. |
| **Standard Bearer** | `{1}{W}` Creature — Human Flagbearer | While an opponent is choosing targets as part of casting a spell they control or activating an ability they control, that player must choose at least one Flagbearer on the battlefield if able. | **Not protection.** Targeting *requirement*, CR 601.2c. |

**Disagreement 1 — Prismatic Strands is not protection, and its shape is nothing like protection.**
The brief lists it under "F2 — Protection" and §3 groups it as "Fog and protection". It grants
nothing to anything. It creates one global prevention shield keyed on a colour chosen *on
resolution*, and that shield prevents damage **from** sources of that colour **to anything** — every
permanent and both players, not just the caster's creatures — for the rest of the turn. It also
prevents damage from *spells* of that colour, not only creatures. Modelling it as "protection from a
colour granted to a set of creatures" (the shape the brief's §4 sentence implies) would be wrong in
three separate directions at once and is exactly the kind of plausible-looking wrongness PLAN.md §7
calls the worst outcome.

**Disagreement 2 — Standard Bearer is not protection, and it points the opposite way.** Protection
*removes* options from an opponent's target enumeration. Standard Bearer *constrains* the opponent's
choice to include a Flagbearer: it is a CR 601.2c requirement ("If any effects say that an object or
player must be chosen as a target, the player chooses targets so that they obey the maximum possible
number of such effects without violating any rules or effects that say that an object or player
can't be chosen as a target"). It is a rules-modifying continuous effect (CR 613.11), a layer-system
corner this repo has never populated, and it needs the `Flagbearer` subtype. It shares no code with
protection beyond the enumeration site. **Recommendation: split it out of F2 entirely.**

**Disagreement 3 — "quality" is not a colour enum.** Guardian of the Guildpact's quality is
*monocolored*: a derived characteristic, not a member of `Color`. CR 702.16a is explicit that the
quality "is usually a color … but can be any characteristic value or information." A `Color`-shaped
protection field would carry Mask and silently fail Guardian. §4 fixes the representation.

**Consequence for the brief's ordering.** F2 as written claims four cards. Protection delivers two
of them (one of which is sideboard-only, tranche 5). If the motivation is Gates' best card against an
attacker, the item to build is **prevention**, and prevention needs no protection code at all. §11
takes this up.

## 1. The descoping this note argues to reverse

The brief states, of protection: "This was previously descoped from the plan. The gauntlet puts it
back." **That sentence has no verbatim source in `docs/PLAN.md`.** The word "protection" does not
appear anywhere in PLAN.md, in `docs/decklists.md`, in the ADRs, or in the current source tree. The
descoping is real but it was made **by omission from a closed list**, and the closed list is what
must be quoted. PLAN.md §6, P5.3:

> **P5.3 Deck keyword set** (`mtg-cards`). Pinned by `docs/decklists.md`: first strike, trample,
> vigilance, flying, hexproof, conditional evasion (Silhana Ledgewalker); … Lifelink and
> indestructible arrive with sideboards later.

and `docs/decklists.md`, "Design consequences":

> **No haste in either 75.** Drop it from the MVP keyword set. Combat keywords needed: first strike,
> trample, vigilance, flying, hexproof, conditional evasion; plus indestructible and lifelink from
> sideboards later.

Protection is absent from both enumerations, and `Keyword`'s KDoc records the closure explicitly:
"The MVP-minimal set: exactly the keywords the pinned pool prints or the combat engine consults".
The decision was also recorded in code and then deleted as the engine grew past it — P2.3's
`FizzleVerdictAcceptanceSpec` KDoc read "nothing in the pool grants protection, hexproof, or any
other targeting restriction" (removed in P3.2), and P3.2's `Targets.kt` KDoc read "No targeting
restriction (hexproof, protection) is granted by any card in the pool yet, so every seated player
and every battlefield creature is a legal choice" (removed in P5.3, when hexproof landed and
protection did not). Protection outlived both removals as the one named-but-unbuilt targeting
restriction.

**This matters for the argument.** The descope was not a judgement that protection is hard or
low-value; it was the pinned-pool rule doing its job. The reversal therefore does not overturn a
considered "no" — it re-runs the same rule against a pool that has changed. What must be argued on
its merits is only the cost, which §3 puts squarely on damage.

## 2. DEBT, letter by letter

CR 702.16, in the CR's own order, mapped onto this engine:

| Letter | CR | What it forbids | Enforcement site today |
|---|---|---|---|
| **T** | 702.16b | Being targeted by spells with the quality, or by abilities from a source with the quality | `Targets.kt` `targetableBy` / `legalTargets` |
| **E** | 702.16c | Being enchanted by Auras with the quality; such Auras go to the graveyard as an SBA (CR 704.5m) | `Targets.kt` `legalTargets(Enchantable)` + `StateBasedActions.kt` `auraAttachmentIsLegal` |
| **E** | 702.16d | Being equipped/fortified; such Equipment unattaches (CR 704.5n) | **No site.** No Equipment in the pool (the brief's F7) |
| **B** | 702.16f | An attacking creature with protection being blocked by creatures with the quality | `CombatActions.kt` `canBlock` |
| **D** | 702.16e | Damage from sources with the quality is **prevented** | **No site.** `dealDamage` has no source and no prevention step |

Two structural notes that apply to all four letters:

- **Protection is quality-relative, not controller-relative.** Every check needs the *other* object —
  the spell being cast, the Aura, the blocker, the damage source — and its characteristics. Hexproof
  needs only "who is deciding". This is the single biggest shape difference, and it is a signature
  change, not merely a new predicate (§2.4).
- **Protection never stops a spell being cast, an ability being activated, or a creature attacking.**
  It removes *choices*, and it prevents damage. Nothing in DEBT is a replacement of an action.

### 2.1 D — damage is prevented (CR 702.16e)

> 702.16e. Any damage that would be dealt by sources that have the stated quality to a permanent or
> player with protection is prevented.

This is **prevention** (CR 615), the CR 614/615-family shape the engine does not have. The
`ReplacementEffect` KDoc names the gap already: "CR 614's other shapes (prevention, redirection,
'enters with counters', 'enters tapped') have no MVP card and are the sealed extension point".

The engine's damage primitive is

```
fun dealDamage(state: GameState, recipient: Target, amount: Int): GameState
```

— **no source**. Combat knows the source (`DamageAssignment.source`) and drops it on the floor at
`CombatDamage.kt:136`; `GameEvent.DamageDealt(recipient, amount)` does not carry it either. Card
resolutions (`LightningBolt.kt:41`, `MadnessDeck.kt:321/349/381`) never had one to drop. So the
predicate CR 702.16e needs — "does the *source* have the quality?" — is not expressible at the point
where damage happens.

Prevention is therefore a prerequisite for **D**, and §3 sizes it. The good news, and it is the
load-bearing sizing insight of this note: **protection's prevention needs no stored effect and no
duration.** It is a static property of the recipient, re-derived from layered characteristics at the
moment damage would be dealt. What it needs is the source's identity threaded to that moment.

Three CR corners that must not be approximated:

- **Prevention is not damage reduction.** CR 615.6: "If damage that would be dealt is prevented, it
  never happens." So no `GameEvent.DamageDealt` is emitted, no damage is marked, no life is lost —
  and consequently **no lifelink** (CR 702.15 gains life as a result of damage *dealt*) and no
  damage-dealt trigger (Armadillo Cloak's twin, Spirit Link). The prevention must therefore happen
  inside `dealDamage`, upstream of both the event and `CombatDamageResults.kt`, never by subtracting
  from an assignment.
- **Lethal assignment is computed before prevention.** CR 510.1c requires a blocked attacker to assign
  at least lethal damage to each blocker; that calculation reads toughness and marked damage and is
  entirely indifferent to whether the damage will then be prevented. The trample excess
  (`TrampleAssignment.kt`, CR 702.19b) must be computed the same way. An attacker with trample facing
  a blocker with protection from it still "wastes" the lethal assignment on that blocker.
- **Fully prevented damage is not the same as zero damage.** `dealDamage` already returns unchanged on
  `amount == 0` (CR 120.8); prevented damage takes the same exit for a different reason, and whether
  that difference should be observable is an open question (§ open questions, 5).

### 2.2 E — can't be enchanted or equipped (CR 702.16c/d, CR 704.5m/n)

> 702.16c. A permanent or player with protection can't be enchanted by Auras that have the stated
> quality. Such Auras attached to the permanent or player with protection will be put into their
> owners' graveyards as a state-based action.

> 704.5m. If an Aura is attached to an illegal object or player, or is not attached to an object or
> player, that Aura is put into its owner's graveyard.

Two enforcement points, both of which already exist and both of which are currently blind:

1. **At cast time** — `legalTargets(state, TargetSpec.Enchantable(restriction), you)` must exclude an
   object with protection from the Aura's own quality. This is the same filter as **T** (an Aura
   targets what it will enchant, CR 601.2c), so it falls out of the **T** work for free.
2. **As a state-based action** — `auraAttachmentIsLegal` in `StateBasedActions.kt` asks only "is the
   attached object still on the battlefield, and does it still satisfy the enchant restriction?" Its
   comment reads: "In the MVP the reachable case is a gone enchanted object (its creature died) — no
   type-changing effect makes a still-present object illegal." **Protection makes that comment
   false.** A creature that gains protection from white while enchanted by a white Aura makes the
   attachment illegal with the creature still present, and 704.5m fires.

This matters immediately because Bogles is an Aura deck. Note the in-pool detail and why it must not
be leaned on: Mask of Law and Grace is itself an Aura, is white, and grants protection from black and
from red — so it removes no GW aura and does not remove itself. The self-removal loop is unreachable
*in these two decklists*, which is a property of two decklists and not of the rules; the SBA is
written for the rule. CR 702.16n's "this effect doesn't remove" carve-out has no card here and is an
explicit non-goal.

One ordering subtlety, which the existing design already handles: the fall-off check reads *layered*
protections, and layered characteristics are computed on read (`layer-system.md` §5), so an Aura that
grants protection is still contributing its grant while the batch is computed. `performBatch`'s
argument that "an Aura and its enchanted creature never fall in the same batch" should be re-read
against protection-driven fall-offs, but does not obviously break: here the Aura leaves while the
enchanted creature stays.

CR 702.16d (Equipment, CR 704.5n) gets no `StateBasedAction` member — the existing KDoc already says
"CR 704.5n (the Equipment analogue) has no member in the pool". It becomes live with the brief's F7.

### 2.3 B — can't be blocked (CR 702.16f)

> 702.16f. Attacking creatures with protection can't be blocked by creatures that have the stated
> quality.

Block legality lives in exactly one place: `CombatActions.kt`, private `canBlock(state, blocker,
attacker)`, consulted by `eligibleBlockPairings`, which builds the `DeclareBlockers` option list.
Today it asks one question — does the attacker's evasion require a flying blocker (flying itself, or
Silhana Ledgewalker's `Evasion.BLOCKABLE_ONLY_BY_FLYING`)? Protection adds a second, independent
restriction: if the **attacker** has protection from a quality the **blocker** has, the pairing is
illegal. CR 509.1b makes evasion restrictions cumulative, which is exactly how they compose here.

Direction matters and is easy to get backwards: protection on a *blocker* neither enables nor
prevents blocking; it only prevents the damage (**D**) and stops the creature being targeted (**T**).
Guardian of the Guildpact blocks a mono-red attacker happily and takes no damage from it.

CR 509.1b also says "[i]f an attacking creature gains or loses an evasion ability after a legal block
has been declared, it doesn't affect that block." The engine stores declared blocks in `CombatState`
and never re-derives them, so this is correct by construction — worth a CR-cited test rather than new
code.

### 2.4 T — can't be targeted (CR 702.16b), and how it differs from hexproof

> 702.16b. A permanent or player with protection can't be targeted by spells with the stated quality
> and can't be targeted by abilities from a source with the stated quality.

The existing hexproof check is:

```
private fun targetableBy(state, obj, you) = obj.owner == you || Keyword.HEXPROOF !in effectiveKeywords(state, obj.id)
```

Hexproof is **opponent-relative**: the only extra input it needs is who is deciding. Protection is
**quality-relative**: it needs the characteristics of the *prospective source* — the spell being
cast, or the ability's source — and it does not care who controls it. A player may not target their
own creature that has protection from white with their own white spell.

`legalTargets(state, spec, you)` has no parameter for that source. **Decision: extend the enumerator's
signature to carry the prospective source, and share the rest of the machinery with hexproof.** The
two restrictions then sit side by side in `targetableBy`, one reading `you`, the other reading the
source. This keeps ADR-005's "legality is defined *by* the enumeration" property intact, because
cast-time choice (`PendingCastRequest`), the CR 601.2c validation (`CastingPipeline`), and the
CR 608.2b fizzle re-check (`StackResolution`) all go through the same function and would all receive
the same new argument — cast-time and resolution-time legality still cannot drift apart.

Two live gaps to record rather than paper over:

- **Abilities do not target in this engine yet.** No activated or triggered ability uses `TargetSpec`
  (the only `legalTargets` callers are the four casting sites). So 702.16b's "abilities from a source
  with the stated quality" half has no call site today. It must be wired the moment a targeted
  ability lands, and until then it is honest to say so in KDoc rather than claim protection from
  abilities is implemented.
- **A new reachable fizzle path.** `StackResolution.kt`'s CR 608.2b re-check currently becomes true
  only when a targeted creature leaves the battlefield. Protection makes a *still-present* target
  illegal on resolution, which is a genuinely new verdict shape for `FizzleVerdictAcceptanceSpec`.

## 3. Is damage prevention a hidden prerequisite, and how big is it?

**Yes for a prevention application point.** The answer splits in three, and only one part is large.

**Part A — damage-source identity** (prerequisite for protection's **D**, and for anything that ever
asks "who dealt this"). `dealDamage` gains a source; `GameEvent.DamageDealt` gains a source;
`CombatDamage.kt` stops discarding the one it already has; the four card call sites pass theirs.
Size: **small-to-medium and entirely mechanical** — 3 rules call sites, 4 card call sites, 1 event,
plus `ResolutionContext` needing to expose the resolving object. What makes it non-trivial is not the
code but the **blast radius**: the event is rendered into the replay fingerprint and the CLI and
mirrored in `mtg-protocol`'s schema (ADR-008 versioning). §9.

CR 120.1 gives the definition ("An object that deals damage is the source of that damage") and
CR 609.7a the reference for what may *be* a source: a permanent, a spell on the stack (including a
permanent spell), or an object referred to by an object on the stack. In this pool every damage source
is either a battlefield creature (combat) or the resolving spell itself. **Decision: carry both
`ObjectId` and `CardRef`** — the object may already be gone from the stack when the damage lands, and
the quality test needs printed characteristics, which the `CardRef` resolves from `state.definitions`
regardless of zone.

**Part B — the prevention application point.** One function consulted inside `dealDamage`, before the
event and before marking, life loss, lifelink, and damage triggers: "is this damage prevented?" For
**protection alone this is a pure read** of the recipient's layered protections against the source's
characteristics. No stored state, no duration, no CR 616.1 ordering choice, no shields. Size:
**small**.

**Part C — the prevention *effect store*, needed by Prismatic Strands and nothing else in F2.** A
resolution-generated, turn-duration, non-characteristic continuous effect (CR 615.3: "Such effects
last until they're used up or their duration has expired"). This is new state on `GameState`, cleared
in the cleanup step (CR 514.2), plus a new invariant (DoD item 4) and a new fingerprint token, plus
the `layer-system.md` §2 decision "**build no duration machinery**" being consciously reopened. Size:
**medium**, and it is the only genuinely new *state* in the whole of F2.

Two things the store does **not** need, and must loud-gate rather than fake:

- **CR 615.7 numeric shields** ("prevent the next 3 damage") — no card in the four, or in the Gates
  list. Their "which shield absorbs it" choice is real work and is not needed.
- **CR 616.1 ordering.** With only "prevent all damage from sources of quality/colour X" shapes, every
  applicable prevention produces the identical outcome — the damage does not happen — so order is
  unobservable, exactly the argument `layer-system.md` §3 makes for timestamps. This is
  correct-by-construction over the closed list; a shield kind that is not idempotent must hit a loud
  gate, not a guess.

One extension point to **name now and not build**: **"damage can't be prevented"** (Flaring Pain, in
the Bogles sideboard and three further gauntlet sideboards) inverts the whole framework. Designing the
application point as one function taking recipient, source, and amount leaves room for it; sprinkling
booleans through `dealDamage` does not.

## 4. "Quality" — the shared representation

CR 702.16a: the quality "is usually a color … but can be any characteristic value or information".
The pool's actual demand is exactly two shapes:

| Card | Quality | Shape |
|---|---|---|
| Mask of Law and Grace | black; red | a colour, twice (CR 702.16g: shorthand for two separate protection abilities) |
| Guardian of the Guildpact | monocolored | a derived characteristic (exactly one colour) |

**Decision — a small sealed `Quality` in `mtg-core`, with two members: a colour, and monocolored.**
Not a `Color` field (fails Guardian); not an open "any characteristic" predicate (over-generalised
beyond the cards, and it would put rules logic in core). The sealed hierarchy is the extension point;
protection from a creature type, from a player (CR 702.16k), and from everything (CR 702.16j) are
non-goals with a member each waiting for a card that needs one. Per ADR-009 and the core/rules split
`layer-system.md` §2 establishes, core states *which* quality; `mtg-rules` owns the predicate "does
this source have it", reading colours from `PrintedCharacteristics.colors`.

**Decision — protection is not a `Keyword`.** `Keyword` is a parameterless enum; protection carries a
quality. It becomes its own characteristic: `protections: PersistentSet<Quality>` on
`PrintedCharacteristics` (Guardian prints it) and on `LayeredCharacteristics` (Mask grants it), with a
matching `grantedProtections` on `StaticContinuousEffect`. This is the one place protection cannot
simply follow hexproof's path, and it is why **T** is not a two-line change. CR 702.16m ("multiple
instances … are redundant") falls out of using a set.

**Known limit, stated rather than hidden.** `PrintedCharacteristics.colors` is derived from the mana
cost (CR 202.2): correct for every card in this pool, blind to colour indicators (CR 204) — which the
KDoc already flags as unmodelled — makes lands colourless (correct), and makes Guardian's
"monocolored" test `colors.size == 1` (correct). Once a layer-5 colour-changing effect exists, the
quality test must read *layered* colour; today the layer engine loud-gates layer 5, so the gap cannot
silently open.

## 5. Layer interaction (CR 613)

**Granted protection is layer 6 and populates no new stage.** Mask of Law and Grace is an Aura whose
static ability grants an ability to the enchanted creature — CR 613.1f, the same stage
`layer-system.md` §1 already populates with keyword and mana-ability grants, and the same
`AffectedSet.Enchanted` applicability. It slots into `layersOf`, `applyLayer`'s `ABILITY_ADDING`
branch, and `LayeredCharacteristics.granting` as one more unioned set. The §3 arguments survive
intact: the grant is additive, so within-layer order still commutes, so the `ObjectId`-entry-order
timestamp decision still holds, and no CR 613.8 dependency is created — a protection grant does not
change whether another effect exists, what it applies to, or what it does. (The Aura fall-off it may
cause is a *state-based action*, not an effect application, so it is outside 613.8 by definition.)

Printed protection (Guardian) sits in the printed base of `layeredCharacteristics`, exactly like
printed keywords.

**Prismatic Strands is not in the layer system at all.** This is the sharpest structural point in the
note. CR 613 orders effects that modify *objects' characteristics*; prevention effects are a separate
family (CR 615.1: "Some continuous effects are prevention effects … They act like 'shields' around
whatever they're affecting"), applied when the damage event would happen, not when characteristics are
computed. CR 613.10 is the instructive contrast: an effect that gives a *player* protection from red
is a continuous effect applied "in timestamp order after the determination of objects'
characteristics" — still not a layer. A prevention shield must therefore never enter
`applyContinuousEffects`; it lives in its own store and is read at `dealDamage`.

The brief's framing — "Prismatic Strands grants it to a set of creatures chosen on resolution, which
is a different shape from an Aura granting it to one" — is wrong on every clause: nothing is granted,
no creatures are chosen, and nothing is a set of objects. What is chosen is a *colour*, and the
effect is global.

**Duration.** `layer-system.md` §2 recorded: "**Decision: build no duration machinery.** Until-EOT
arrives later with Tamiyo's Safekeeping (sideboard, Phase 5+); the hook is the effect-*collection*
step". Prismatic Strands reopens that decision — but **not at that hook**, because prevention is not a
layered effect. It needs its own turn-scoped store and its own cleanup clearing. The layer note's hook
stays reserved for until-EOT *characteristic* effects (Tamiyo's Safekeeping, which grants hexproof and
indestructible until end of turn); the two mechanisms should not be conflated on first build.

## 6. ADR-005: exactly which enumerations shrink

A missed check here is "a silently illegal option offered to a training agent" — the failure mode
ADR-005 exists to prevent. Every site, named:

| # | Site | Enumeration | Change |
|---|---|---|---|
| 1 | `Targets.kt` `legalTargets(AnyTarget)` | `ChooseTargets` options | exclude objects with protection from the source's quality |
| 2 | `Targets.kt` `legalTargets(Enchantable)` | `ChooseTargets` options for an Aura | same filter; this is **E** at cast time |
| 3 | `ActionEnumeration.kt:167` `targetsAvailable` | **`ChooseAction`** — the cast option itself | a spell with no legal target is not castable; this is where a missed check becomes a phantom *action*, not just a phantom target |
| 4 | `PendingCastRequest.kt:37` | `ChooseTargets` option list | consumes (1)/(2) |
| 5 | `CastingPipeline.kt:198` `isTargetLegal` | CR 601.2c validation | consumes (1)/(2) |
| 6 | `StackResolution.kt:53` `isTargetLegal` | CR 608.2b fizzle verdict | new reachable fizzle (§2.4) |
| 7 | `CombatActions.kt` `canBlock` → `eligibleBlockPairings` | **`DeclareBlockers`** options | this is **B** |
| 8 | `CombatActions.kt` `orderBlockersRequestOrNull` | `OrderBlockers` | logic unchanged; its option set follows (7) |
| 9 | `TrampleAssignment.kt` | `AssignTrampleDamage` | **must not shrink** — lethal and excess are computed pre-prevention (§2.1) |
| 10 | *(none today)* | targeted abilities | no call site; 702.16b's ability half is a documented gap (§2.4) |

Nothing in **D** shrinks an enumeration: prevention changes an outcome, not an option set. Worth
stating, because it is tempting to "helpfully" drop a Lightning Bolt from the action list when a
creature has protection from red — wrong twice over: (3) already removes the cast when *no* legal
target remains, and a Bolt aimed at the *player* is entirely legal regardless.

**The fuzz probe cannot catch a protection bug.** `EnumerationProbe` detects phantom options by
replaying each enumerated option through `advance` and watching it throw. A missed protection check
makes both the enumerator *and* the CR 601.2c validator wrong in the same way — they are the same
function, by design (ADR-005) — so the phantom option advances cleanly and the probe stays green.
Protection's correctness rests on CR-cited unit tests plus, for **D**, acceptance scenarios. Say this
in the packet spec so nobody mistakes a clean fuzz run for evidence.

## 7. Prismatic Strands, in full

Oracle: "Prevent all damage that sources of the color of your choice would deal this turn. /
Flashback—Tap an untapped white creature you control."

- **Not protection** (§0, §5). It needs Parts A + B + C of §3, and **no** protection code.
- **The choice is on resolution** ("of your choice"), and the spell has **no target**. The engine has
  `DecisionRequest.ChooseColor` and `PendingColorChoice`, but they are wired to the *as-enters* path
  (`AsEntersColor.kt`, Utopia Sprawl, CR 614.12). Prismatic Strands needs a colour choice **as a spell
  resolves** — a resolution-time pending decision alongside the existing
  `pendingResolutionDiscard`/`pendingRevealSelection` family. It reuses the request type, not the flow.
- **The shield is global and symmetric.** "Sources of the color" — any source, either player's,
  including the caster's own. A Gates player casting it on their own turn turns off their own red
  removal too. The store is keyed by (colour, this turn), not by controller and not by protected
  object.
- **The flashback cost is a cost shape the engine does not have.**
  `CastingPermission.Flashback` carries a `ManaCost` plus an optional `SacrificeRequirement(count,
  subtype)`. "Tap an untapped white creature you control" is neither: it is a tap-a-chosen-permanent
  cost with a **colour** predicate. Two CR details that must not be lost: (a) it is *not* the `{T}`
  symbol, so a creature that entered the battlefield this turn **can** be tapped to pay it — the
  engine's `summoningSick` check must not be reused blindly; (b) the flashback mana cost is `{0}`, so
  the whole cost is non-mana, and payment enumeration must not read "no mana cost" as "no cost".
- **Verdict:** Prismatic Strands is a *prevention* packet, not a protection packet — and it is the one
  of the four cards that actually decides games.

## 8. Standard Bearer, in full

Oracle: "While an opponent is choosing targets as part of casting a spell they control or activating
an ability they control, that player must choose at least one Flagbearer on the battlefield if able."

- **Not protection.** A CR 601.2c targeting *requirement*: "If any effects say that an object or
  player must be chosen as a target, the player chooses targets so that they obey the maximum possible
  number of such effects without violating any rules or effects that say that an object or player
  can't be chosen as a target." Restrictions (hexproof, protection) beat requirements — the "if able"
  is exactly that clause, and it is why the two features touch the same file.
- **Mechanically it is a CR 613.11 rules-modifying continuous effect** ("Some continuous effects affect
  game rules rather than objects … applied after all other continuous effects have been applied"), a
  stage the layer engine reserves no slot for and the pool has never populated. It also needs the
  `Flagbearer` creature subtype, and it applies to *abilities* as well as spells — the call site §2.4
  says does not exist yet.
- **Its enumeration change is a filter today and a combinatorial rule tomorrow.** Every targeted spell
  in the current pool takes exactly one target, so "maximum possible number" collapses to: if any
  Flagbearer is a legal choice for that opponent, the enumeration contains only Flagbearers. A
  multi-target spell needs maximisation over the *combination*, a different algorithm. Build the
  single-target rule; loud-gate the multi-target case.
- **Recommendation: its own framework packet, sequenced after F2, not inside it.**

## 9. Blast radius on existing tests, by file

Changing `legalTargets`' signature, `dealDamage`'s signature, and `PrintedCharacteristics`' shape is
what makes this wide. Most of it is mechanical; the ones that need *thinking* are marked ⚠.

**`mtg-core`**
- `card/PrintedCharacteristicsSpec.kt` — new `protections` field (default-empty, so source-compatible).
- `state/GameStateSpec.kt` — only if the prevention store lands (Part C).

**`mtg-rules` (tests)**
- ⚠ `HexproofTargetingSpec.kt` — the closest analogue; gains the protection sibling cases and absorbs
  the `legalTargets` signature change.
- `CastingPipelineSpec.kt`, `ActionEnumerationSpec.kt` — signature ripple; ⚠ `StackResolutionSpec.kt`
  also gains the new CR 608.2b path.
- ⚠ `CombatDeclarationSpec.kt`, `EvasionScenarioSpec.kt` — `canBlock` gains its second restriction;
  the evasion spec is where CR 509.1b cumulativity belongs.
- ⚠ `CombatDamageScenarioSpec.kt`, `TrampleScenarioSpec.kt`, `LifelinkScenarioSpec.kt`,
  `CreatureLethalitySpec.kt` — prevention interacts with all four: no marked damage, no lifelink,
  unchanged lethal assignment.
- `DealDamageSpec.kt` — the primitive's signature and the prevented-damage case.
- `LayerSystemSpec.kt` — layer 6 gains the granted-protections union.
- `FixtureCards.kt`, `AuraFixtures.kt`, `AuraTestSupport.kt`, `CombatTestSupport.kt`, `SeatSetup.kt` —
  fixtures gain the new characteristic field.

**`mtg-cards`**
- `LightningBolt.kt` + `MvpCardsSpec.kt`, `MadnessDeck.kt` (3 sites) + `MadnessDeckSpec.kt` — the
  `dealDamage` source argument.
- `Auras.kt` + `AurasSpec.kt` — Mask of Law and Grace lands beside the existing Bogles auras;
  `Creatures.kt` + `BoglesCreaturesSpec.kt` for Guardian of the Guildpact.

**`mtg-acceptance`**
- ⚠ `FizzleVerdictAcceptanceSpec.kt` — its documented reasoning is the historical descope record (§1);
  protection falsifies the "no targeting restriction" premise and adds a verdict path.
- ⚠ `invariant/Invariant.kt` + `InvariantChecker.kt` — a new prevention-store scope invariant if Part C
  lands (DoD item 4: "the invariant checker is extended if the packet introduces new state").
- ⚠ `replay/Fingerprint.kt`, `FingerprintRenderers.kt` — a damage source on `DamageDealt` and/or a
  prevention store changes the canonical descriptor, i.e. **every recorded fingerprint**:
  `ReplaySpec.kt`, `TrampleReplayAcceptanceSpec.kt`, `MvpMatchupCorpusSpec.kt`,
  `MixedMatchupCorpusSpec.kt`, `BoglesAuraCorpusSpec.kt`, `BoglesKeywordCorpusSpec.kt`,
  `CreatureCorpusSmokeSpec.kt` need regeneration. Follow `layer-system.md` §5's rule: digest the
  *cause* (source, shield), not the *effect*.
- ⚠ `OracleCharacteristics.kt`, `SyntheticLayerAuras.kt`, `RandomLayerBoards.kt`,
  `LayerOracleEquivalenceSpec.kt`, `LayerPropertiesSpec.kt` — the naïve layer oracle must learn the
  granted-protections union, or the equivalence property fails.
- `CombatLethalityAcceptanceSpec.kt`, `BoltDuelAcceptanceSpec.kt`, `MvpCardsAcceptanceSpec.kt`,
  `fuzz/EnumerationProbe.kt`, `fuzz/ProbePolicy.kt` — ripple.

**`mtg-protocol`** — the schema mirrors `DecisionRequest`/`SeatView`; a new field on
`GameEvent.DamageDealt` and any new decision shape is a wire change. ADR-008 versioning applies; call
it out in the packet report rather than letting it ride.

## 10. Proposed packet decomposition

Sequenced, each independently testable, primitive before card (PLAN.md §5; ADR-003 vocabulary
discipline). **F2.1–F2.2 and F2.6 are prevention work; F2.3–F2.5 are protection work; F2.7 is
neither.**

| Packet | Scope | Delivers | Depends on |
|---|---|---|---|
| **F2.0** | this note | reviewed design | — |
| **F2.1** | `mtg-rules`, `mtg-core` event, `mtg-cards` call sites | **damage-source threading**: `dealDamage` and `GameEvent.DamageDealt` carry (`ObjectId`, `CardRef`); combat stops discarding the source it has; fingerprint and protocol schema updated. *No behaviour change* — the packet is provably outcome-neutral, which makes it a clean regression baseline. | F2.0 |
| **F2.2** | `mtg-rules` | **prevention application point** (CR 615): one function consulted inside `dealDamage` before the event, marking, life loss, lifelink, and damage triggers. No store, no duration. Fixture-driven (`mtg-rules` names no card). | F2.1 |
| **F2.3** | `mtg-core`, `mtg-rules` | **protection substrate**: sealed `Quality` (colour, monocolored); `protections` on printed and layered characteristics; `grantedProtections` on `StaticContinuousEffect` (layer 6); **T** and **E**-at-cast-time via the extended `legalTargets`. | F2.0 |
| **F2.4** | `mtg-rules` | **E-as-SBA** (CR 704.5m protection case in `auraAttachmentIsLegal`), **B** (`canBlock`), **D** (protection consulted at F2.2's point). Completes DEBT. | F2.2, F2.3 |
| **F2.5** | `mtg-cards` | **Mask of Law and Grace**, **Guardian of the Guildpact**, with card tests. | F2.4 |
| **F2.6** | `mtg-core` state, `mtg-rules`, `mtg-cards` | **Prismatic Strands**: turn-duration prevention store (+ cleanup clearing, invariant, fingerprint token), resolution-time colour choice, tap-a-white-creature flashback cost, then the card. Splittable into store / cost / card if it runs long. | F2.2 |
| **F2.7** | separate framework | **Standard Bearer**: CR 601.2c requirement maximisation, `Flagbearer` subtype, CR 613.11 rules-modifying statics. **Not part of F2.** | F2.3 (shares the enumeration site) |

F2.3 can run in parallel with F2.1/F2.2 (disjoint files) if the cadence wants it; F2.4 joins them.

## 11. Recommendation on the descoping reversal

**Build it — but rescope F2 first, and reorder it.**

The descope (§1) was the pinned-pool rule applied to a pool that no longer holds. Nothing about
protection is speculative now: two of these cards are real, and one (Guardian of the Guildpact) is
close to unanswerable for several gauntlet decks, which makes it valuable *as a training signal* and
not merely as coverage. The engine's seams are also unusually ready — three of DEBT's four letters
land in one function each.

Three qualifications, in order of importance:

1. **The most valuable thing in F2 is prevention, not protection.** Prismatic Strands is, in the
   brief's own words, "the deck's best card against an attacker", and it needs **no protection code**.
   If only one thing gets built, build F2.1 + F2.2 + F2.6. The tranche is protection-shaped because of
   the label; the label is wrong.
2. **The smallest honest version that carries the two real protection cards** is F2.1 → F2.2 → F2.3 →
   F2.4 → F2.5. Nothing on that path approximates: **D** is real prevention (not damage reduction),
   **E** enforces both the cast-time filter and CR 704.5m, **B** is a real block restriction, **T**
   shares the hexproof site with an honest signature. It costs one new characteristic field, one small
   sealed `Quality`, one widened function signature, and one new branch in each of four functions. It
   introduces **no new `GameState` field at all** — the prevention store belongs to Prismatic Strands.
3. **Approximations to refuse explicitly** (PLAN.md §7; CONVENTIONS.md "Fail loudly; never silently
   approximate"):
   - Prismatic Strands as protection, or as a fog that "prevents combat damage" — it prevents *all*
     damage from that colour, from spells too, to permanents *and* players, both sides.
   - Protection as a `Keyword` enum member with the quality dropped, or hard-coded to a colour —
     Guardian needs "monocolored".
   - **D** as "subtract the damage" — it must never mark, never emit `DamageDealt`, never lifelink.
   - Skipping CR 704.5m's protection case because no in-pool Aura is currently removed by an in-pool
     Mask — that is a property of two decklists, not of the rules, and it will rot silently.
   - Standard Bearer bolted onto the protection filter — it is a requirement, not a restriction, and a
     CR 613.11 effect.

## Non-goals (explicit)

Out of design scope, with where each would slot: **protection from everything** (CR 702.16j) and
**from a player** (CR 702.16k) — a `Quality` member each; **protection from a creature type or other
characteristic value** — likewise; **Equipment/Fortification unattachment** (CR 702.16d, CR 704.5n) —
a `StateBasedAction` member, arriving with the brief's F7; **"this effect doesn't remove" Auras**
(CR 702.16n) — no card; **numeric prevention shields** (CR 615.7) and their "which shield absorbs it"
choice — no card; **damage redirection** (CR 614.9) — no card; **"damage can't be prevented"**
(Flaring Pain) — a deliberately unpopulated slot in F2.2's application point; **multi-target
requirement maximisation** (CR 601.2c) — loud-gated in F2.7; **protection granted to a player**
(CR 613.10) — no card in scope grants it. Each is a refusal the framework must make loudly, never
fake.

## Open questions for the architect

1. **Representation** (§4): a new `protections: PersistentSet<Quality>` alongside `keywords` on both
   `PrintedCharacteristics` and `LayeredCharacteristics`, versus widening `Keyword` into a sealed
   hierarchy that can carry a parameter. The first is additive and default-valued; the second touches
   every combat read. Recommended: the first. Main call.
2. **`legalTargets` signature** (§2.4): pass the prospective source as an extra parameter, or introduce
   a small `TargetingContext(you, sourceCard, sourceObject)` value the four call sites build? The
   second ages better and makes the targeted-ability gap easy to close later.
3. **Prevention store** (§3 Part C, §5): accept a new `GameState` field with turn duration now — with
   `layer-system.md` §2's "no duration machinery" consciously reopened for the *prevention* family
   only — or defer Prismatic Strands until an until-EOT mechanism is designed jointly with Tamiyo's
   Safekeeping? They are different mechanisms and probably should not be unified.
4. **Damage source on the event** (§3 Part A, §9): confirm the fingerprint/corpus regeneration and the
   `mtg-protocol` schema version bump (ADR-008) are acceptable, or should the source be threaded
   through `dealDamage` **without** appearing on `GameEvent.DamageDealt`? The event is the observable
   half and is genuinely useful downstream; the corpus churn is the price.
5. **Is a prevented-damage event wanted?** CR 615.6 says the damage never happens, so no `DamageDealt`
   — but "Prismatic Strands blanked the alpha strike" is invisible in the log without one. A distinct
   `DamagePrevented` event would be derived observability (PLAN.md §2.2), never load-bearing.
6. **Is "monocolored" in scope now?** (§4) Guardian of the Guildpact is sideboard-only (brief tranche
   5). If it is deferred, `Quality` collapses to colour-only and F2.3 shrinks — at the cost of
   reopening the type when Guardian lands.
7. **Standard Bearer split** (§8): confirm it leaves F2 for its own packet, and that the brief's F2
   scope line is corrected upstream so the sibling project's sequencing does not keep assuming
   otherwise.
8. **Fuzz shape** (§6, §9): Guardian of the Guildpact against a mono-red corpus produces boards with an
   unanswerable permanent and possibly non-terminating games. Confirm the existing turn-count bound is
   the intended answer, and that such a matchup belongs in the corpus deliberately rather than being
   avoided.
