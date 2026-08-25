# Gauntlet completion log — autonomous run

A record of decisions taken while encoding the thirteen-deck Pauper gauntlet without a human in
the loop. Written so every judgement call is auditable after the fact rather than living in a
session transcript. Newest wave last.

**Standing authority for this run:** finish the remaining gauntlet cards, making all decisions
without asking. Recorded here for review.

---

## Standing policies applied to every packet

These are the defaults used whenever a decision arose and no human was available.

1. **Never weaken a test to make a build pass.** A pinned expectation that moves is a behaviour
   change to investigate, not a number to edit. The one exception is `GauntletCoverageSpec`'s
   burn-down pins, which exist to be updated as cards land — and those are always rewritten from
   the engine's own printed coverage table, never from arithmetic.
2. **Merge conflicts resolve by union where the change is additive** (registry entries, sealed
   `when` members, KDoc paragraphs). Where two packets genuinely disagree, the semantics are
   worked out from the code rather than by picking a side — see the `counterUnlessPaid` and
   protocol-version entries below.
3. **A dropped card is a good outcome.** Packets are instructed that a smaller correct packet
   beats a larger approximated one. Drops are re-dispatched in a later wave once their framework
   lands, not forced through.
4. **Oracle text is authority.** Above the upstream brief, above `docs/gauntlet-card-triage.md`,
   and above the packet brief. Disagreements are recorded rather than silently absorbed.
5. **Protocol version bumps coalesce per wave.** Nothing is released, so consumers can only ever
   have seen the last merged version; two parallel packets that each bump major merge into one
   bump documenting both breaks.
6. **Do not push to `origin`.** Publishing is outward-facing and was not requested. All work stays
   on local `main`; the push is left as a human decision.
7. **A full `./gradlew build` gates every merge commit.** No merge is committed on a green subtest
   alone, because textual auto-merge across parallel packets has twice produced a clean merge that
   did not compile.

---

## Wave 1 — infrastructure and the first cards

- **Gauntlet decklists went to `decks/gauntlet/`, leaving the two MVP resources untouched.** The
  gauntlet's Bogles and Madness lists had diverged from the encoded ones; repointing them before
  the four refresh cards landed would have red the acceptance suite.
- **Coverage reports mainboard and sideboard separately** rather than widening `missing`, so
  existing callers keep their meaning.
- **Accepted a committed Python fetch script** (`tools/fetch_scryfall_snapshot.py`) in a
  Kotlin-only repo. The alternative dragged live network calls into the build graph.
- **Accepted `SeatView.cards` rather than the requested `definitions`,** and visibility-scoped
  rather than whole-registry. Reusing the name `definitions` for a different type would mislead;
  whole-registry scope would turn a caller-supplied registry into a decklist leak.
- **Repaired a `Keyword.kt` KDoc corrupted by my own earlier keep-both merge resolution.**

## Wave 2 — destruction, snow, mana sickness, library looks

- **`P-MANASICK` fix accepted as a two-site change.** The packet found the gate missing in both the
  planner and the executor; fixing only the planner would have been worse than the original defect.
- **Accepted `DecisionRequest.SingleOptionSelection`,** an uninvited shared-API refactor, because a
  25th request leaf broke nine exhaustive `when`s at once and the alternative was `else` guards
  that CONVENTIONS forbids for that hierarchy.
- **Left the `TargetCreature` / `TargetPermanent(CREATURE)` redundancy in place** at merge time
  rather than silently picking one, and assigned the collapse to the packet that owned the
  hierarchy in the next wave.

## Wave 3 — clause hook, countering, Tron, sweep

- **`counterUnlessPaid` stayed on `SpellDefinition`** rather than moving into the new
  `ResolutionClauses` interface. It runs *instead of* resolution and is spell-only, so folding it
  in would have made `requireAtMostOneClause` count it wrongly.
- **Two independent `5.0.0` protocol bumps merged into one.** Countering and `FW-MANA` each bumped
  from `4.0.0`; neither shipped, so one major bump now documents both breaks.
- **Fixed two cross-packet breaks by hand:** an auto-merge that silently dropped a `StackEntry`
  import, and countering's round-trip fixture using the single-mana shape `FW-MANA` had just
  changed to multisets.
- **Routed trap T17 (an engine crash) to the live agent that owned those files** rather than
  scheduling it separately; it fixed the gate exactly and by object rather than by class.

## Wave 4 — cost modification, duration, graveyard targeting, counters, sacrifice costs, land ETB

Dispatched as six parallel packets with disjoint file ownership. All six were killed at launch by
a monthly spend limit before writing anything, and were restarted from scratch with context
intact.

All six landed. Decisions taken while merging them:

- **`counterUnlessPaid` kept on `SpellDefinition`, not moved into `ResolutionClauses`.** It runs
  *instead of* resolution and is spell-only, so folding it in would have made
  `requireAtMostOneClause` count it wrongly.
- **Six independent major protocol bumps coalesced into one `6.0.0`.** `FW-COST`, `FW-DURATION`,
  `FW-ADDSAC` and `FW-COUNTERS` each independently proposed `6.0.0` from `5.0.0`; `FW-ZONETGT` and
  the T18 fix each argued for holding. Since none shipped, `5.0.0` is the last version any consumer
  could have seen, so one bump now documents every break.
- **`ActionEnumeration` / `CastLegality` / `PendingCastRequest` composed rather than picked.**
  `FW-COST` replaced the printed cost with `totalCost`; `FW-ADDSAC` added a sacrifice gate and a
  payment reservation. Both were needed, so the merged expression prices with `totalCost` *and*
  reserves. Picking either side would have silently lost a correctness property.
- **`countMatchingPermanents` kept as one shared function with the wider matcher.** `FW-DURATION`
  extracted the count to `PermanentCount.kt`; `FW-COUNTERS` widened `PermanentFilter` with card-type
  and layered-keyword axes in the copy it could see. Merged so there is one counter supporting every
  axis, rather than two that agree by luck.
- **`Layers.kt` composed**: `FW-DURATION`'s reshaped `ActiveEffect` accessors with `FW-COUNTERS`'
  counter application at layers 6 and 7c, and the corrected CR citations.
- **Four merge defects fixed by hand**, none of which any single packet could have seen: a
  duplicated `entersTapped` declaration after the type was widened from `Boolean` to a sealed set;
  `countMatching`'s signature and body reconciled onto the perspective seat; two enum KDoc unions
  that produced malformed Kotlin (the same failure mode as the earlier `Keyword.kt` case); and an
  import-ordering violation.

Judgement calls made *by* packets that I accepted rather than overrode:

- **`FW-MANACOST` deliberately not built.** The land-ETB packet was offered a cost field on
  `ManaAbility` and declined with reasons: the activation enumerator models activations as pure
  producers, so a costed activation breaks the no-idle Hall's-theorem check, the pruning bound, and
  introduces an acyclicity constraint. Adding the field without the enumerator would have made
  Conduit Pylons read as a *free* any-colour source — a silent over-production defect. Re-scoped as
  its own wave-5 packet.
- **Surveil not added** because its only client dropped; a `LibraryLookMode` member no card uses
  would be speculative generality.
- **The CR 613.4e citation settled against the published rules text.** `FW-COUNTERS` downloaded the
  2026-08-19 Comprehensive Rules and found 613.4 has four sublayers, not five: the phantom letter had
  shifted **all four** of `Layers.kt`'s citations by one, and counters belong at 7c beside the Aura
  modifiers, not at 7d. Fixed rather than preserved.
- **T17 fixed by the `FW-MANA` packet** even though its own cards did not reach it, because the gate
  belonged in the function it had just restructured — and reserved **by object, not by class**, since
  over-reserving trades a crash for a silently missing legal play.

## Wave 5 — multi-target, costed mana abilities, modality, flicker, cycling, prevention

Dispatched as six parallel packets against the blockers wave 4 identified. All six were killed
mid-implementation by a monthly spend limit (7–31 files each, uncommitted) and resumed in place —
nothing was lost. All six then landed.

Decisions taken while merging them:

- **Seven protocol bumps coalesced, then one genuine escalation.** `FW-MULTITGT`, `FW-MODAL`,
  `FW-PREVENT`, `P-SEARCH` and the flicker packet all folded into the unreleased `6.0.0`.
  `FW-MANACOST` reshaped `PaymentPlanDto` in both directions and took it to `7.0.0` — the only bump
  this wave that describes a break a consumer could actually meet.
- **`establishTargets` composed**: multi-target's arity and CR 601.2c same-object checking, with
  modality's `effectiveTargetSpec`, so a modal spell's targets are validated against the *chosen
  mode's* spec rather than the card's.
- **`Targets.kt` needed both** prevention's `self` (protection self-exclusion) and multi-target's
  `you` (decider-relative restrictions). Either alone silently drops a correctness property.
- **One `satisfiesPermanentRestriction`, not two.** The flicker packet added a parallel
  `...For` wrapper for decider-relative restrictions; `main` had meanwhile given the original
  function the chooser. Merged to one function, wrapper deleted.
- **`declaredClauses` unioned to all seven clause types** — the search packet made `librarySearch`
  a clause while the flicker packet added two more, and each saw only its own.
- **`singleOptionSelectionToDomain` deduplicated across a file split.** Modality moved it to its own
  file; flicker added a branch to the old copy. Kept the moved file, ported the branch, and split
  the to-DTO side the same way when it outgrew its length budget.
- **Eight further merge defects fixed by hand**, all invisible to any single packet: a duplicated
  `entersTapped` after the type widened, `countMatching`'s signature vs body, `ResolutionContext`'s
  `linkedExiled` landing outside the constructor, a guard function spliced into another's KDoc, two
  more malformed enum unions, an unused import, and two detekt complexity budgets.
- **I committed one merge before its build finished** and had to amend — `main` briefly held four
  style violations. The gating rule exists precisely for that and I broke it once.

Judgement calls made *by* packets that I accepted:

- **Prevention shipped the framework and dropped all four of its cards.** CR 702.16b targeting is
  complete for spells and structurally blind for abilities; rather than ship protection with that
  hole it made the gap a **loud gate** naming the four files to fix.
- **`FW-MANACOST` engaged the prior packet's refusal rather than routing around it** — moving the
  cost onto the production alternative, replacing the Hall's-theorem no-idle check with a real
  bipartite matching on the costed path while keeping the old check where it provably agrees, and
  *deriving* activation order rather than recording it (recording would multiply every plan by its
  permutations and falsify the dedup argument).
- **The search packet made `librarySearch` a resolution clause**, which fixed a latent bug: the
  activated-ability path ran a search *instead of* the ability's ordinary effect.

Corrections to my own briefs, recorded because I wrote them wrong:

- I told the flicker packet Duress and Mesmeric Fiend were non-controller decisions. Both print
  "**You** choose" — the opponent reveals, the controller picks. Only Refurbished Familiar is a
  genuine `FW-NONCTRLDEC` card, and the distinction inverts the ADR-007 answer.
- I told the land-ETB packet that four cards print `{N}, {T}: Add …`. Only two do.
- I told the prevention packet the design note's blast-radius estimate would hold; it was far too
  large, because the replay fingerprint excludes the event log by design and `GameEvent` is not in
  the protocol at all.

## Wave 6 — the remaining tail

Dispatched as a refreshed triage plus four packets: the cards recent frameworks just unblocked,
the keyword tail (deathtouch, changeling, conditional statics), ninjutsu and the Faeries, and
X costs / kicker / alternative costs.

_(entries appended as packets report)_
