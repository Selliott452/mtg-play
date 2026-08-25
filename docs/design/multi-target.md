# Design note — spells and abilities with more than one target (`FW-MULTITGT`)

The reference for the multi-target framework: what a targeting line actually is, why the answer is a
*count* on the spec rather than a family of new specs, how CR 601.2c's same-object rule is enforced
without trusting the enumeration, and how the gauntlet's "up to two" cards decompose.

Two things anchor the design. Downward, **CR 601.2c** — "If the spell requires the player to choose
targets, the player announces those choices… The same target can't be chosen multiple times for any
one instance of the word 'target'" — and **CR 608.2b**, whose "all its targets are now illegal" test
turns out to need the count to be read correctly. Upward, the seams `FW-ZONETGT` and `FW-ABILTGT` cut
and this packet reuses unchanged: `legalTargets`/`isTargetLegal` (`Targets.kt`) as the single source
of target-legality truth, and the one target *decision* that already serves a cast (CR 601.2c), an
activation (CR 602.2b), and a trigger placement (CR 603.3d) alike.

Written to PLAN.md §5's rule that a framework packet gets a design note, in the style of
`docs/design/graveyard-targeting.md`.

---

## 1. The oracle text, and where the triage was wrong

Fetched from Scryfall (`POST /cards/collection`, 2026-08-24), all six found, no ambiguity:

| Card | Cost | Oracle text | Shape |
|---|---|---|---|
| Faerie Macabre | `{1}{B}{B}` | Flying<br>Discard this card: Exile **up to two** target cards from graveyards. | multi-target, hand-scoped ability |
| Rooftop Percher | `{5}` | **Changeling**<br>Flying<br>When this creature enters, exile **up to two** target cards from graveyards. You gain 3 life. | multi-target + changeling |
| Blood Fountain | `{B}` | When this artifact enters, create a Blood token.<br>`{3}{B}`, `{T}`, Sacrifice this artifact: Return **up to two** target creature cards from your graveyard to your hand. | multi-target, activated ability |
| Call Damage Control | `{1}{G}` | **Choose up to two.** Return those cards from your graveyard to your hand.<br>• Target artifact card. • Target creature card. • Target enchantment card. • Target land card. | modal + multi-target |
| Tamiyo's Safekeeping | `{G}` | Target **permanent you control** gains hexproof and indestructible until end of turn. You gain 2 life. | one target, control-restricted |
| Brinebarrow Intruder | `{U}` | **Flash**<br>When this creature enters, target **creature an opponent controls** gets -2/-0 until end of turn. | one target, control-restricted |

Three corrections:

**1.1 — Two of the six cards in this packet's list are not multi-target cards at all.** Tamiyo's
Safekeeping and Brinebarrow Intruder each target exactly one object. They are in the packet because a
prior packet identified them as the two cards `PERMANENT_YOU_CONTROL` and
`CREATURE_AN_OPPONENT_CONTROLS` land with no further framework work, and that was right — the two
members are one line each in `satisfiesPermanentRestriction`. The brief said as much; the point is
worth stating because the packet's name describes only two thirds of it, and the two halves share
exactly one property (§6).

**1.2 — Brinebarrow Intruder needs flash, and flash is not a keyword.** The triage lists it as a
"flash creature". `Keyword` has no `FLASH` member and does not need one: what flash *does*
(CR 702.8a) is let the spell be cast whenever its controller has priority, and `timingPermitsCast`
reads that off `TimingClass.INSTANT_SPEED`. Nothing in the engine ever asks a *permanent* whether it
has flash, because a permanent never has it in any way a rule consults. The card is encoded as an
instant-speed creature spell, which is the faithful translation rather than a shortcut.

**1.3 — `FW-ZONETGT` said four of its six drops were blocked on this framework; two of those four
are still blocked, and correctly so.** Rooftop Percher additionally needs changeling (CR 702.73) and
Call Damage Control additionally needs modality. Its list was right about the framework and right
about the extra blockers; this packet clears the multi-target half of all four and ships the two whose
*only* blocker it was. §7 gives each drop in full.

So this packet ships **four** cards — Faerie Macabre and Blood Fountain on the multi-target half,
Tamiyo's Safekeeping and Brinebarrow Intruder on the restriction half — and the framework that
unblocks the rest.

---

## 2. What a targeting line is

A targeting line is a **noun** and a **count**, and they are orthogonal.

Before this packet `TargetSpec` was the noun alone and the count was hard-coded to one, in four
separate places that each believed it independently. "Up to two target cards from graveyards" was
therefore not a new *kind* of target — the noun is `CardInGraveyard(ANY_CARD, ANY)`, which
`FW-ZONETGT` already shipped — it was a cardinality the type could not carry.

```kotlin
// mtg-core — the cardinality half.
sealed interface TargetCount {
    val minimum: Int
    val maximum: Int
    data class Exactly(val count: Int) : TargetCount   // "target creature", "two target creatures"
    data class UpTo(val limit: Int) : TargetCount      // "up to two target cards"
}

// mtg-core — carried by the spec, beside the noun.
sealed interface TargetSpec {
    val count: TargetCount
    data class CardInGraveyard(
        val restriction: GraveyardCardRestriction,
        val scope: GraveyardScope,
        override val count: TargetCount = TargetCount.ONE,
    ) : TargetSpec
    // …
}
```

Three choices worth their reasons.

**`count` is abstract on the interface, not defaulted.** A property default would let a future
`TargetSpec` member silently inherit "exactly one" — the quiet outcome the no-`else`-branch rule
exists to prevent. Every member states its cardinality out loud and a new one does not compile until
it does. `TargetSpec.None`'s count is `Exactly(0)`, which makes several downstream tests fall out
uniformly instead of special-casing it.

**`Exactly` and `UpTo` are members rather than a raw `IntRange`**, even though every consumer reads
only `minimum`/`maximum`, because the two carry different rules consequences that a range flattens —
and because `UpTo(0)` is refused at construction (it is `TargetSpec.None` spelled the long way) while
`Exactly(0)` is meaningful.

**Only the members that can carry a count do.** `TargetPermanent` and `CardInGraveyard` take it as a
defaulted constructor parameter, so every existing call site compiles unchanged; `Enchantable` fixes
it at one with a stated reason (an Aura attaches to exactly one object, CR 303.4f, so a second target
has nowhere to go); the data objects and `SpellOnStack` state `ONE` because nothing in the pool
prints otherwise. A count parameter with no client would be speculative, and CONVENTIONS' rule is
that members exist where a card prints them.

**"Two target creatures" is `TargetPermanent(CREATURE, Exactly(2))`.** No gauntlet card prints it, so
nothing constructs it — but the model, not the card list, is what has to be general, and the
framework is tested against it.

---

## 3. The CR 601.2c same-object rule

> The same target can't be chosen multiple times for any one instance of the word "target".

This is the rule a multi-target enumeration most easily gets wrong, because the natural
implementation — "offer a list, let the agent pick a subset" — is only correct if you can say
*something* about the list. The enforcement here is a chain of three links, and each is pinned by a
test rather than by a comment.

**Link 1 — the enumeration never offers one object twice.** `legalTargets` returns the *pool* of
choices and is count-independent: "up to two target cards" and "target card" enumerate identical
lists. Every branch maps over a zone whose objects are distinct, and every member of `Target` names
its referent by an id unique across the game (CR 400.7) — including the two graveyards
`GraveyardScope.ANY` draws from, which are disjoint. `MultiTargetSpec` asserts this directly over
several specs, and `ViewLeakPropertySpec.checkMultiTargetBounds` asserts it on every multi-target
pause of the matchup corpus.

**Link 2 — the answer's indices must be distinct.** `validateDecision`'s `RangedSelection` arm runs
`validateDistinctSubset`, so a `MultiSelect` naming index 1 twice is rejected loudly before anything
is applied. Given link 1, distinct indices into a duplicate-free list *are* distinct objects.

**Link 3 — the recorded targets are re-checked on the objects.** `requireWellFormedTargetChoice`,
shared by the cast pipeline's `establishTargets` and the activation pipeline's
`establishActivationTargets`, asserts `targets.distinct().size == targets.size` on the `Target`
values themselves, and `AbilityTargetInvariant` asserts the same for every ability on the stack.

Link 3 is redundant *given* links 1 and 2, and that redundancy is the point: link 2 proves the rule
only while link 1 holds, and link 1 is a property of code a future packet will edit. Checking object
identity as well means a future enumeration branch that started offering one object twice would fail
loudly rather than silently letting an agent point two targets at one card. Faerie Macabre's
acceptance case chooses one card from *each* player's graveyard, so the rule is exercised where a
positional or per-zone shortcut would have passed.

---

## 4. Where the count is read

Four consumers, each with its own failure mode. This is why the count lives on the spec rather than
being passed around: one value, four readers, so a card's printed cardinality cannot mean one thing
at cast time and another at resolution.

| Consumer | Rule | What the count decides |
|---|---|---|
| `targetsAvailable` | CR 601.2c | Castability. `legalTargets(...).size >= count.minimum` — for an exactly-one spec this is the old `isNotEmpty()` unchanged; for "up to N" the minimum is zero, so Faerie Macabre is activatable with two empty graveyards; for "exactly two" it is the real rule, and "two target creatures" is not castable with one creature out. |
| `targetChoiceBounds` | CR 115.1 | The bounds the surfaced request carries, with the maximum **clamped to the option count**: "up to two" with one legal card offers nought-or-one, not a demand for a second that does not exist. The minimum is deliberately not clamped — a spec demanding more than the board holds was never enumerated, so a minimum above the option count is an engine defect and `require` says so. |
| `requireWellFormedTargetChoice` | CR 601.2c | The re-validation's arity, shared by the cast and activation pipelines, alongside the same-object check. |
| `allTargetsIllegal` | CR 608.2b | The fizzle verdict — §5. |

A fifth site is `targetChoiceIsVacuous`, which unified three copies of "does this need a decision at
all". A choice is settled without asking when the spec targets nothing, or when the enumeration is
empty. That single predicate now serves the cast (`beginCastGathering`), the activation
(`beginActivation`), and the trigger placement (`triggerTargetPause`), which previously each spelled
the question out and could have drifted. No request with an empty option list is ever surfaced — an
option list an agent cannot pick from is not a decision.

---

## 5. The CR 608.2b divider

The subtlest consequence of the count, and the one that is silently wrong without it.

Two objects can both sit on the stack carrying an **empty** target list, and they resolve
differently:

- A *mandatory targeted trigger* whose controller had no legal target at CR 603.3d. It was put on the
  stack target-less and does nothing (CR 608.2b). `FW-ABILTGT` relied on this and called it
  "vacuously all-illegal".
- An *"up to N"* object whose controller chose none — declined, or with nothing legal to name. It has
  no illegal target, so it resolves and does everything it says that is not about a target. Rooftop
  Percher with two empty graveyards still gains 3 life.

Nothing but `count.minimum` tells them apart, and reading them the same way deletes the non-targeting
half of every "up to" card in the pool. `allTargetsIllegal` therefore became:

```kotlin
when {
    spec.count.maximum == 0 -> false          // targets nothing; nothing to re-check
    targets.isEmpty() -> spec.count.minimum > 0   // a *required* instance that was never filled
    else -> targets.none { isTargetLegal(...) }
}
```

For every spec that predates this framework `minimum` is 1 and the behaviour is byte-identical.

---

## 6. The two `PermanentRestriction` members

`PERMANENT_YOU_CONTROL` (Tamiyo's Safekeeping) and `CREATURE_AN_OPPONENT_CONTROLS` (Brinebarrow
Intruder). One arm each in `satisfiesPermanentRestriction`, plus a `you: PlayerId` parameter on it and
the matching argument in `legalTargets`. Control is read as ownership — the MVP pool's standing
simplification (`docs/design/layer-system.md` §4), since nothing in the gauntlet changes control of a
permanent.

What makes them worth a section is the property they add rather than the cards they land: they are the
first restrictions whose answer depends on **who is deciding**, so one battlefield offers the two seats
different option lists. Before them only `TargetSpec.TargetOpponent` (players) and
`GraveyardScope.YOURS` (graveyard cards) were decider-relative. `legalTargets` already threads the
deciding player through every site — the caster at CR 601.2c, the activator at CR 602.2b, the ability's
controller at CR 603.3d, and the same controller at the CR 608.2b re-check — so nothing new was needed
to make the two agree; the parameter was already there, one function short of its destination.

Two asymmetries are load-bearing and both are tested:

- **Hexproof does not narrow `PERMANENT_YOU_CONTROL`.** The `targetableBy` gate removes a hexproof
  permanent only from an *opponent's* targeting (CR 702.11), so a GW-Bogles player can always point
  Tamiyo's Safekeeping at their own Slippery Bogle — which is the board the card is printed for.
- **Hexproof does narrow `CREATURE_AN_OPPONENT_CONTROLS`,** and the restriction does not say so:
  `targetableBy` has already removed the opponent's hexproof creatures before the restriction is
  consulted. Against a Bogles board Brinebarrow Intruder's trigger frequently has no legal target,
  goes on the stack target-less, and does nothing — while the 1/2 body still enters. That is why the
  targeting sits on the *trigger* and not on the spell: on the spell it would make the creature
  uncastable against Bogles.

---

## 7. What is dropped, and exactly what each needs

| Card | Blocked on | Precisely |
|---|---|---|
| Rooftop Percher | `FW-CHANGELING` | Its targeting half is this packet's, complete and unused: "exile up to two target cards from graveyards" is `CardInGraveyard(ANY_CARD, ANY, UpTo(2))` on an enters trigger, plus a lifegain, and every piece exists. It is blocked entirely on **changeling** (CR 702.73) — "this card is every creature type", which `Subtype` cannot express: the model is a finite printed set, and changeling is a rule that makes every type-matching predicate answer yes. That is not cosmetic in a gauntlet holding tribal effects (Priest of Titania counts Elves), so shipping it with a printed `Shapeshifter` subtype would be a plausible-looking wrong card (PLAN.md §7). It needs either a computed-subtype accessor in `mtg-rules` or a characteristic flag every subtype predicate consults — files this packet does not own. |
| Call Damage Control | `FW-MODAL` | "Choose up to two" **modes**, each carrying its own instance of the word "target". `CastingPipeline.chooseModes` is a documented no-op and `SpellDefinition` cannot express modes at all. Note the shape it needs is *not* this packet's: a mode-per-target line is a **list of targeting lines**, each with its own count and its own same-object scope (CR 601.2c is per instance of the word "target", so its artifact-card mode and its creature-card mode may name different cards but each may name one). §8 records why that is deliberately not modelled here. The parallel packet that owns `chooseModes` will find the cardinality it needs already present. |
| Dread Return | additional-cost shape | Unchanged from `FW-ZONETGT` §6: "Sacrifice three creatures" is a card-type predicate and `SacrificeRequirement` predicates on a printed subtype. Not this packet's. |
| Mortuary Mire | triage T18, optional triggers, new zone move | Unchanged from `FW-ZONETGT` §6. Not this packet's. |
| Thraben Charm | `FW-MODAL` | In the wider triage, not this packet's card list; blocked the same way Call Damage Control is. |

---

## 8. What this framework deliberately does not model

**One instance of the word "target", not a list of them.** `TargetCount` is a count on *one*
targeting line. A card printing two separate instances — "target creature **and** target land",
Fire // Ice's split halves, Call Damage Control's per-mode targets — needs `SpellDefinition` and
`ActivatedAbility` to hold a *list* of `TargetSpec`s, each with its own count, its own restriction,
and its own same-object scope. That is a genuinely larger change: every dispatch site currently reads
one spec, `PendingCast.chosenTargets` is one flat list with no per-instance boundaries, and the
CR 608.2b verdict becomes per-instance.

No card in the gauntlet needs it. Modelling it speculatively would have widened every signature in
§4 for a shape nothing constructs, and the count-on-one-line model is a strict prefix of it — a
future list-of-lines framework wraps this type rather than replacing it.

---

## 9. The decision, and the wire

The engine gained one `DecisionRequest` kind and one grouping interface.

`DecisionRequest.ChooseMultipleTargets` is a **`RangedSelection`**: a distinct index subset whose size
lies in a range, answered with a `Decision.MultiSelect`. The grouping is new because neither existing
family fits — `SizedSelection` is exact-size, and its exactness is not incidental: a sized selection
pays a *cost*, and a cost is paid in full or not at all, so widening it to a range would make "discard
two cards" answerable with one. `minimumCount == maximumCount` is a legal `RangedSelection` value
("two target creatures") and still is not a `SizedSelection`, because the rules that produced it and
re-check it are CR 601.2c's.

**`ChooseTargets` is untouched**, and that is the central claim. A spec whose count is `Exactly(1)`
surfaces the same single-select request with the same fields, so every existing card, driver, replay
log, and wire message is unchanged. `TargetRequests.kt` makes the choice of kind in **one** place,
from the spec's count, so the three flows that build a target request (cast, activation, trigger
placement) cannot disagree about which. Folding the single case into the ranged shape was the
alternative and it is the wrong trade: a `MultiSelect` of arity exactly one is a worse thing to hand
an agent — the arity has to be discovered from the bounds — and it would have rewritten every
existing decision log for no behaviour a card can observe.

**Protocol held at `6.0.0`.** `DecisionRequestDto` gains `choose_multiple_targets` and
`DecisionRequestKindDto` gains `CHOOSE_MULTIPLE_TARGETS`, whose `valueOf` mapping fails at runtime
mid-match — the sharper break mode, and a major bump on its own by the standard the last four
versions set. It is folded into `6.0.0` because that version's own note already records the premise:
`5.0.0` is the last version any consumer can have seen, so one bump carries every break in the
unreleased wave. Parallel packets in this wave may reach the same conclusion, and one shared bump is
meant to carry all of them. No `TargetDto` member and no `SeatViewDto` field are added — a
multi-target choice names the same targets a single one does, and ADR-007's answer is unchanged
because the *zone* still decides visibility (`FW-ZONETGT`), not the number of objects named.

---

## 10. The dispatch sites

| Site | Module | What it gained |
|---|---|---|
| `TargetCount.kt` | core | the count type (new file) |
| `TargetSpec` | core | abstract `count`, plus the two count-bearing constructors |
| `PermanentRestriction` | core | `PERMANENT_YOU_CONTROL`, `CREATURE_AN_OPPONENT_CONTROLS` |
| `GraveyardCardRestriction` | core | `ANY_CARD`, `CREATURE` |
| `GameEvent` | core | `GraveyardCardExiled` |
| `Targets.kt` | rules | `targetChoiceBounds`, `targetChoiceIsVacuous`, `requireWellFormedTargetChoice`; count-aware `allTargetsIllegal` |
| `PermanentRestrictions.kt` | rules | the `you` parameter and two arms |
| `GraveyardCardRestrictions.kt` | rules | two arms |
| `TargetRequests.kt` | rules | the one place the request kind is chosen (new file) |
| `ActionEnumeration.targetsAvailable` | rules | count-driven, replacing a seven-member `when` |
| `CastGathering` / `ActivationGathering` / `TriggerTargeting` | rules | `targetChoiceIsVacuous`; target appliers take a list |
| `CastingPipeline.establishTargets`, `ActivationExecution` | rules | shared arity + same-object re-validation |
| `DecisionRequest` | rules | `RangedSelection`, `ChooseMultipleTargets` |
| `DecisionValidation` / `DecisionApplication` / `DecisionView` | rules | a family arm each |
| `ExileFromGraveyard.kt` | rules | `exileCardFromGraveyard` (new primitive) |
| `DecisionRequestDto` + both mappers, `ViewDecisionDtos` | protocol | `choose_multiple_targets` |
| `MenuRenderer`, `SelectionMenus`, `DecisionInput`, `DefaultDecision`, `RandomLegalChooser`, `MenuFormat` | cli | the ranged menu, parse rule, default, and random policy |
| `RandomLegalResponder`, `Responders`, `EnumerationProbe`, `AbilityTargetInvariant` | acceptance | a ranged arm each; the invariant became count-aware and gained the same-object check |
| `RandomRemoteAgent` | server | a ranged arm |

---

## 11. Acceptance

`MultiTargetAcceptanceSpec` runs all four shipped cards end to end under the invariant checker: Faerie
Macabre exiling two cards from *two different* graveyards, Faerie Macabre **declining** both targets
and still resolving, the engine **refusing** a same-card-twice answer and an over-maximum one, Blood
Fountain returning a creature card from its own graveyard through a clamped "up to two", Tamiyo's
Safekeeping seeing only its caster's permanents, and Brinebarrow Intruder's trigger seeing only the
opponent's creature and shrinking it to 0/2.

`MultiTargetSpec` (`mtg-rules`) exercises the framework directly — 28 cases organised by *rule* rather
than by card, because the count is consumed in four independent places and each has its own failure
mode.

`ViewLeakPropertySpec` was **extended and not relaxed**, twice. Its `checkTargetOptionZones` guard was
`ChooseTargets`-only; leaving it would have made every multi-target pause skip the ADR-007 property
silently, so it now covers both kinds. And `checkMultiTargetBounds` adds the ADR-005 half a
multi-target option list needs and a single-target one cannot have: the offered bounds are
satisfiable, and no object is offered twice. No assertion in it was weakened.

`AbilityTargetInvariant` was likewise strengthened: its arity check reads the spec's maximum instead
of a hard-coded one, it gained the same-object check on every ability on the stack, and its
"activated ability with no target" rule now says `count.minimum > 0` where it said "is not
`TargetSpec.None`" — the same rule stated against the count rather than against a proxy that stopped
being equivalent the moment "up to N" existed.
