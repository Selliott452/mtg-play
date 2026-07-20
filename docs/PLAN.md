# Implementation Plan — mtg-play

A headless Magic: The Gathering rules engine in Kotlin, published as a JVM library, built as
a training environment for AI agents playing Pauper. First playable milestone: a full game of
Mono-Red Madness vs GW Bogles.

This document is the working plan. It is written so that individual **work packets** can be
delegated to sub-agents with minimal additional context. Read §4 (Conventions) and §5
(Delegation protocol) before executing any packet.

---

## 1. Locked decisions

Recorded here for quick reference; each gets a full ADR in `docs/adr/` (packet P0.2).

| # | Decision | ADR |
|---|----------|-----|
| 1 | Build from scratch in Kotlin; Forge/XMage consulted as reference only, never depended on | ADR-001 |
| 2 | Immutable game state with structural sharing (`kotlinx.collections.immutable`); every transition returns a new state | ADR-002 |
| 3 | Card rules authored in a Kotlin type-safe DSL composing published primitives; card metadata from Scryfall bulk data | ADR-003 |
| 4 | **Decision-point engine**: the engine is a resumable state machine — it advances until it needs a player decision, then returns a typed request with enumerated options | ADR-004 |
| 5 | **Enumerated actions only**: agents select from engine-enumerated legal options; they never construct actions freeform | ADR-005 |
| 6 | All randomness through a match-owned seeded PRNG; `(seed + decision log)` fully reproduces any game | ADR-006 |
| 7 | Per-seat state filtering: each agent sees exactly what a player in that seat may see | ADR-007 |
| 8 | Match protocol is JSON over WebSocket, layered on the library; in-process API is the primary AI interface | ADR-008 |

Tooling: Kotlin (latest stable) on JVM 21, Gradle multi-module with version catalog, Kotest,
ktlint + detekt (zero-warning policy), GitHub Actions CI, GitHub public repo. License: undecided,
must be resolved before Phase 8 (publishing).

## 2. Architectural keystones

### 2.1 The decision-point model (the load-bearing design)

```kotlin
sealed interface AdvanceResult {
    data class NeedsDecision(
        val state: GameState,
        val request: DecisionRequest,   // who decides + enumerated options
    ) : AdvanceResult
    data class GameOver(val state: GameState, val result: MatchResult) : AdvanceResult
}

interface GameEngine {
    fun start(config: MatchConfig): AdvanceResult
    fun advance(state: GameState, decision: Decision): AdvanceResult
}
```

- `DecisionRequest` is a sealed hierarchy: `ChooseAction` (priority window), `ChooseTargets`,
  `ChooseBlockerOrder`, `ChooseMulligan`, `ChooseYesNo` (e.g. madness "you may cast"),
  `ChoosePaymentPlan`, etc. Every option carries a stable index; a `Decision` is
  "request id + selected index(es)".
- **Every** interaction with a player goes through this mechanism. No callbacks, no player
  interfaces injected into resolution code. Resolution code that needs a choice suspends the
  advance and surfaces a request.
- The CLI, the WebSocket protocol, tests, and future RL loops are all thin drivers of this
  same loop.

### 2.2 Determinism and replay

- `MatchConfig` includes the seed. Shuffles, coin flips, and any future random effects draw
  from the match PRNG only. Direct use of `kotlin.random.Random` anywhere in `mtg-*` modules
  is a review-blocking defect.
- Every transition appends typed `GameEvent`s to an event log (part of the state or alongside
  it). Events are for observability/replay display; they are **derived**, never load-bearing
  for rules logic.
- Replay = `(MatchConfig, List<Decision>)`. An acceptance test replays a recorded game and
  asserts the final state hash matches.

### 2.3 Correctness infrastructure (cross-cutting, starts Phase 1)

- **Invariant checker** (`mtg-acceptance`): pure function `check(state): List<Violation>`.
  Examples: every card is in exactly one zone; zone contents are consistent with counts;
  life/damage bookkeeping consistent; mana pools empty when steps end (except declared
  exceptions); priority holder is a player in the game. Extended every phase. Test drivers
  run it after **every** transition.
- **Fuzz harness** (`mtg-acceptance`, from Phase 3): plays full games choosing uniformly
  random legal decisions, across N seeds, asserting: no exceptions, no invariant violations,
  games terminate (turn-count bound). Runs in CI nightly; a smoke-sized run on every push.
- **CR citations in tests**: tests that implement a Comprehensive Rules behaviour cite the
  rule in the test name, e.g. `"CR 704.5g: a creature with lethal damage is destroyed"`.

## 3. Modules and dependency rules

```
mtg-core        Vocabulary. Types only: ids, zones, mana, characteristics, GameState shape,
                PRNG wrapper, GameEvent. Invariants on construction; NO game logic.
mtg-rules       Grammar. The engine: turn structure, priority, stack, casting pipeline,
                combat, SBAs, layers, triggered/replacement frameworks, effect primitives.
                Depends on: core.
mtg-cards       Card definitions in the DSL + the DSL builders. Depends on: core, rules.
mtg-pauper      Format: legal sets, banned list, deck validation, Scryfall ingestion.
                Depends on: core, cards.
mtg-protocol    JSON/WebSocket match server + message schema. Depends on: core, rules, cards.
mtg-cli         Interactive text driver for hand-testing. Depends on: everything.
mtg-acceptance  Scripted full-game tests, invariant checker, fuzz harness, replay tests.
                Depends on: everything. Test-only.
```

Rules for packet authors:
- The **core/rules boundary**: if it's a noun, it's core; if it's a verb, it's rules. Core
  types may validate themselves (require positive toughness input, etc.) but never advance
  the game.
- `mtg-rules` never references a specific card. Card-specific behaviour lives in `mtg-cards`
  composed from rules primitives.
- **Vocabulary discipline**: a card definition may only use published DSL primitives. If a
  card needs a new primitive, that primitive is added to `mtg-rules`/DSL with its own unit
  tests first, in the same packet, called out explicitly in the packet report.
- Public API changes to a module you don't own in the packet: stop and flag, don't improvise.

## 4. Conventions — explicit expectations for all packets

Priorities in order: **correctness, readability, structure.** Speed of implementation is
explicitly last.

**Correctness**
- Every rules behaviour ships with tests; CR-cited names where a CR paragraph is implemented.
- Acceptance criteria listed in the packet are implemented as tests, and pass.
- No packet is complete with failing or skipped tests, or invariant-checker regressions.
- Unimplemented rules corners fail loudly (`TODO()` / explicit unsupported error), never
  silently approximate. A wrong result that looks right is the worst outcome.

**Readability**
- Use Comprehensive Rules terminology exactly: *battlefield* (not board), *library* (not
  deck), *exile*, *cast/play/activate* distinguished correctly, *controller* vs *owner*.
- KDoc on every public declaration; cite CR sections when implementing a rule.
- No `!!`. Exhaustive `when` over sealed hierarchies (no `else` on sealed). No wildcard
  imports. Small files — one concept per file.
- Comments state constraints the code can't express (usually a CR subtlety), nothing else.

**Structure**
- ktlint + detekt clean; zero-warning policy — warnings are errors in CI.
- Respect module dependency rules (§3). New dependencies require explicit justification in
  the packet report.
- Interface-first inside each phase: the phase's interface packet lands before parallel
  implementation packets start.

**Definition of Done (every packet)**
1. `./gradlew build` green (compiles, all tests pass, lint clean).
2. Packet's acceptance criteria are tests, and pass.
3. Public API has KDoc; new rules code cites CR.
4. Invariant checker extended if the packet introduces new state.
5. Short packet report: what was built, deviations from spec, new primitives added, flagged
   issues, suggested follow-ups.

## 5. Delegation protocol

Roles: the **architect** (main session) owns interfaces, ADRs, packet specs, and review.
**Implementation agents** (Opus sub-agents) execute packets. Card-encoding packets in Phase 6
are mechanical and safely parallelizable; framework packets (P4.x, P5.x) are not — one agent
at a time, with design review before merge.

Standard packet prompt template:

```
Context: read docs/PLAN.md §2–4, docs/adr/ADR-00X (listed per packet), CONVENTIONS.md,
and the interface files listed below. Do not read the whole repo.
Objective: <one paragraph>
In scope: <modules/files>
Out of scope: <explicitly>  — do not touch these even to "improve" them.
Interfaces you consume: <files>   Interfaces you must not change: <files>
Acceptance criteria: <numbered, each becomes at least one test>
Definition of Done: PLAN.md §4 applies in full.
Report back: per PLAN.md §4 item 5.
```

Review gate after every packet, before anything depends on it: architect (or a review agent)
checks DoD, spot-checks rules correctness against the CR, runs the fuzz smoke suite. Framework
packets get a design-note review *before* implementation starts.

## 6. Phases and work packets

Dependencies are strict between phases; packets within a phase list their internal order.

### Phase 0 — Scaffold and governance
- **P0.1 Repo scaffold.** Gradle multi-module skeleton (all 7 modules, empty), version
  catalog, ktlint/detekt config, `.editorconfig`, `.gitignore`, CI workflow (build + test +
  lint on push/PR), README stub. *Accept:* `./gradlew build` green in CI.
- **P0.2 Governance docs.** ADR-001…008 (one page each: context, decision, consequences),
  `CONVENTIONS.md` (expanded §4), `CLAUDE.md` for agent context (module map, DoD, terminology
  glossary). *Accept:* docs exist, plan cross-links resolve.

### Phase 1 — Turn structure and the decision loop (no cards)
- **P1.1 Core vocabulary** (`mtg-core`). Ids as inline value classes; `Zone`; mana symbols
  and costs; card characteristics; `GameState` on persistent collections; PRNG wrapper;
  `GameEvent`. Property tests for mana arithmetic. *Accept:* state construction invariants
  tested; no logic beyond validation.
- **P1.2 Engine skeleton** (`mtg-rules`). `GameEngine.advance`, `DecisionRequest`/`Decision`
  hierarchies; turn/phase/step progression (CR 500s); priority passing (CR 117); the SBA
  **loop** wired into priority (checks exist even if few SBAs do); game-over: draw from empty
  library (CR 104.3c), life ≤ 0 scaffold. Mulligans deferred to P6 (start hands as-drawn until
  then). *Accept:* two players with lands-only libraries play through turns to deck-out, every
  step/phase visited in order, priority alternates correctly.
- **P1.3 Acceptance + infra** (`mtg-acceptance`). Scripted-game test driver (fluent: expect
  decision → respond); invariant checker v1; replay test (seed + decisions → identical state).

### Phase 2 — The stack, casting, and Lightning Bolt
- **P2.1 Casting pipeline + stack** (`mtg-rules`). CR 601 as an explicit step sequence
  (propose → modes/targets → costs → payment → cast), even where steps are trivial now —
  they are the hooks later phases need. Stack with LIFO resolution; mana abilities (don't use
  the stack, CR 605); **payment enumeration design note first** (equivalent permutations
  collapsed: all untapped Mountains are one option). The mana/payment *model* must
  accommodate hybrid, Phyrexian (life payment), any-color sources, {C}, and triggered mana
  abilities firing mid-payment (Utopia Sprawl) even where implementation lands later — see
  `docs/decklists.md` design consequences. *Accept:* enumeration completeness tests.
- **P2.2 First cards** (`mtg-cards`). Basic lands (play-land action, once per turn),
  Lightning Bolt: "any target", damage effect, life loss, SBA player-loses (CR 704.5a).
  DSL primitives introduced: damage, targeting, timing restriction.
- **P2.3 Acceptance.** Bolt duels: response windows honoured, stack resolves LIFO, player
  dies mid-stack correctly ends the game, illegal casts absent from enumeration.

### Phase 3 — Permanents and combat
- **P3.1 Combat state machine** (`mtg-rules`). Declare attackers/blockers (legality), blocker
  ordering, combat damage step, first-strike step scaffolding (CR 506–511). Summoning
  sickness, tapping. *Accept:* CR-cited scenario suite.
- **P3.2 Vanilla creatures + lethality.** SBAs: lethal damage (704.5g), zero toughness
  (704.5f); damage wear-off at cleanup. Grizzly-Bears-grade cards in the DSL.
- **P3.3 Fuzz harness v1** (`mtg-acceptance`). Random legal playouts with lands + vanilla
  creatures + Bolt; N seeds in CI. *Accept:* zero crashes/violations over the seed corpus.

### Phase 4 — Continuous effects and the layer system  ⚠ highest risk
- **P4.0 Design note** (architect + review before implementation): CR 613 application —
  layers, sublayers 7a–7d, timestamps, dependency (613.8). Scope decision: implement the full
  algorithm; if dependency handling is deferred, it fails loudly on detection, never guesses.
- **P4.1 Static abilities + auras** (`mtg-rules`). Continuous-effect engine; aura attach,
  enchant restrictions, aura falls off (704.5m and 704.5n).
- **P4.2 Bogles statics** (`mtg-cards`). Ethereal Armor (dynamic P/T, layer 7c + keyword
  grant, layer 6), Rancor's static half, keyword grants generally. *Accept:* multi-aura
  timestamp scenarios compute correct P/T.
- **P4.3 Layer property tests.** Random sets of continuous effects; assert CR 613 ordering
  properties hold.

### Phase 5 — Triggered and replacement effects
- **P5.1 Triggered abilities** (`mtg-rules`). Event detection, APNAP ordering (CR 603.3b),
  triggers on the stack with targets, intervening-if. ETB/dies/attacks triggers.
- **P5.2 Replacement effects** (`mtg-rules`). CR 614/616 framework: event proposed →
  applicable replacements → affected player chooses order → modified event. Madness pathway
  (discard → exile instead → may-cast trigger, CR 702.35), lifelink as damage-result
  modification (702.15, *not* a trigger), trample assignment (702.19), first strike step
  activation, hexproof as targeting restriction (enumeration excludes illegal targets).
- **P5.3 Deck keyword set** (`mtg-cards`). Pinned by `docs/decklists.md`: first strike,
  trample, vigilance, flying, hexproof, conditional evasion (Silhana Ledgewalker); the
  cast-from-elsewhere framework covering madness, flashback, plot, escape; landcycling.
  No haste anywhere in either 75. Lifelink and indestructible arrive with sideboards later.
- **P5.4 Acceptance.** Madness end-to-end (cast Fiery Temper off a discard); hexproof
  excluded from opponent targeting but not own; lifelink + trample combat math.

### Phase 6 — The two decks, playable
- **P6.1 Scryfall + format** (`mtg-pauper`). Bulk-data ingestion for the needed cards
  (CC BY 4.0 attribution in README), deck loader, Pauper legality validation. Mulligans
  (London, CR 103.5) added to the engine.
- **P6.2 Encode both decklists** (`mtg-cards`). One packet per batch of ~5 cards; each card
  gets definition + card-specific tests. Mechanical; parallelize freely.
- **P6.3 Full-game acceptance.** Scripted representative games (curve-out Bogles vs burn
  plan); fuzz harness on the real decks across thousands of seeds.
- **P6.4 CLI driver** (`mtg-cli`). Text rendering of per-seat views, numbered decision menus.
  A human can play either seat. **MVP milestone: play a full game by hand.**

### Phase 7 — Match protocol
- **P7.1 Schema** (`mtg-protocol`). kotlinx.serialization messages mirroring
  `DecisionRequest`/`Decision` + per-seat state views (ADR-007 filter API). Versioned.
- **P7.2 Server.** Ktor WebSocket host; match lifecycle; seat tokens; reconnection with
  state resync.
- **P7.3 Reference client + random agent.** *Accept:* two separate processes complete games
  vs each other and vs the CLI.

### Phase 8 — Publishing
License decision (blocking), Maven coordinates/namespace, semver policy, Dokka API docs,
publish pipeline. Details deferred by design.

### Phase 9+ — Pauper pool expansion
Archetype-by-archetype card encoding; each archetype packet extends the fuzz corpus. New
primitives keep flowing through the vocabulary-discipline rule.

## 7. Risks

| Risk | Mitigation |
|------|------------|
| Layer system (CR 613) complexity | Design note + review before code (P4.0); property tests; Forge source as reference reading |
| Replacement-effect ordering subtleties | Framework built once in P5.2 with CR 616 tests; madness is the forcing function |
| Payment enumeration combinatorics | Design note in P2.1; equivalence-collapsing from day one |
| Silent wrongness (worst failure mode) | Loud-failure convention, invariant checker, fuzzing, CR-cited tests |
| DSL devolves into per-card special cases | Vocabulary-discipline rule + review gate |
| Solo cadence stalls in Phase 4–5 | Packets sized for single sessions; every packet leaves the build green |

## 8. Open questions (blocking marked ⛔)

1. ~~Decklists~~ **Resolved 2026-07-20**: both lists received and oracle-verified — see
   `docs/decklists.md` for lists and mechanics-to-packet mappings. Headline consequences:
   mana model must cover hybrid/Phyrexian/any-color/triggered mana abilities (P1.1, P2.1);
   cast-from-elsewhere is a four-mechanic framework (P5.2); Phase 4 layer scope bounded to
   layers 6 + 7c; no haste in MVP.
2. License — decide before Phase 8.
3. Match structure beyond single game (Bo3/sideboards) — deferred, revisit after MVP.
4. AI training approach — deliberately open; the decision-point model + in-process API keep
   RL, LLM-agent, and behavior-cloning paths all viable.
