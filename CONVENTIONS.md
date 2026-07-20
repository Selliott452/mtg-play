# Conventions

The working expansion of PLAN.md §4. This is the reference every packet is held to at review.
Priorities, in order: **correctness, readability, structure.** Speed of implementation is
explicitly last.

Decisions behind these rules live in [`docs/adr/`](docs/adr/); scope lives in
[`docs/PLAN.md`](docs/PLAN.md) and [`docs/decklists.md`](docs/decklists.md). This file says how
to write the code.

---

## Correctness

- **Every rules behaviour ships with tests.** Acceptance criteria listed in a packet are
  implemented as tests, and they pass. No packet is complete with failing or skipped tests, or
  with invariant-checker regressions.
- **Cite the Comprehensive Rules in test names** where a test implements a CR paragraph. The
  citation is part of the test name so a failure points straight at the rule.

  ```kotlin
  // good — the CR reference is in the name
  test("CR 704.5g: a creature with lethal marked damage is destroyed as an SBA") { /* ... */ }

  // bad — says nothing about which rule broke
  test("creature dies") { /* ... */ }
  ```

- **Fail loudly; never silently approximate.** An unimplemented rules corner throws — `TODO()`
  or an explicit unsupported-operation error — it does not guess. A wrong result that looks
  right is the worst possible outcome (PLAN.md §7).

  ```kotlin
  // good — the gap is visible and testable
  is Dependency -> error("CR 613.8 dependency ordering not yet supported: $effect")

  // bad — silently wrong, and looks fine in a demo
  is Dependency -> applyByTimestampAnyway(effect)
  ```

- **Determinism (ADR-006).** All randomness goes through the match-owned seeded PRNG. Direct
  `kotlin.random.Random` / `java.util.Random` in any `mtg-*` module is a review-blocking
  defect and is rejected by detekt (`ForbiddenImport`, below). The **only** sanctioned use is
  the PRNG wrapper in `mtg-core`, which opts out explicitly:

  ```kotlin
  // The single sanctioned entry point for randomness; every other module draws from this.
  @Suppress("ForbiddenImport") // ADR-006: this IS the seeded wrapper all randomness flows through
  import kotlin.random.Random
  ```

- **Enumerated actions (ADR-005).** Agents choose engine-enumerated options by index; legality
  logic lives in one place, and enumeration completeness is a tested property.

## Readability

- **Use Comprehensive Rules terminology exactly** (see the glossary below). *battlefield* not
  board, *library* not deck, *exile* not removed-from-game; distinguish *cast* / *play* /
  *activate*; distinguish *controller* / *owner*. This applies to identifiers, KDoc, comments,
  test names, and log/event text — the vocabulary is the shared language of the codebase.
- **KDoc on every public declaration**, and cite the CR section when a declaration implements a
  rule.
- **No `!!`.** Rejected by detekt (`UnsafeCallOnNullableType`). Model absence with the type
  system and handle it.

  ```kotlin
  // good
  val controller = state.controllerOf(permanent) ?: error("CR 110.2: a permanent always has a controller")

  // bad — throws an undiagnosable NPE and hides the invariant
  val controller = state.controllerOf(permanent)!!
  ```

- **Exhaustive `when` over sealed hierarchies — no `else`.** A new subtype must break
  compilation everywhere it is handled, not fall through a silent branch.

  ```kotlin
  // good — adding a DecisionRequest subtype forces every site to be revisited
  when (request) {
      is ChooseAction      -> renderPriority(request)
      is ChooseTargets     -> renderTargets(request)
      is ChooseBlockerOrder -> renderBlockerOrder(request)
      is ChooseMulligan    -> renderMulligan(request)
      is ChooseYesNo       -> renderYesNo(request)
      is ChoosePaymentPlan -> renderPayment(request)
  }

  // bad — a new subtype silently falls into else and ships broken
  when (request) {
      is ChooseAction -> renderPriority(request)
      else -> renderGeneric(request)
  }
  ```

- **No wildcard imports.** Enforced by ktlint.
- **Small files — one concept per file.** A file is named for the concept it holds.
- **Comments state constraints the code cannot express** — usually a CR subtlety — and nothing
  else. Do not narrate what the code already says.

  ```kotlin
  // good — records a rule the code alone doesn't reveal
  // CR 605.3a: a mana ability resolves immediately and does not use the stack.

  // bad — restates the obvious
  // increment the counter by one
  counter += 1
  ```

## Structure

- **ktlint + detekt clean; zero-warning policy.** Warnings are errors: the Kotlin compiler runs
  with `allWarningsAsErrors`, and detekt runs at `maxIssues: 0`. A packet that adds a warning is
  not done.
- **Respect the module dependency rules** (PLAN.md §3; and the map in `CLAUDE.md`). "Nouns in
  core, verbs in rules." `mtg-rules` never references a specific card. A new inter-module
  dependency requires explicit justification in the packet report.
- **Vocabulary discipline (ADR-003).** A card definition uses only published DSL primitives. A
  card that needs a new primitive gets that primitive added to `mtg-rules`/the DSL, with its own
  unit tests, in the same packet, called out in the report. No card-local special cases.
- **Do not change a public API you do not own in this packet.** Stop and flag it; do not
  improvise (PLAN.md §3).
- **Interface-first within a phase:** the phase's interface packet lands before parallel
  implementation packets start.

## Definition of Done (every packet)

1. `./gradlew build` is green — compiles, all tests pass, lint clean (zero warnings).
2. The packet's acceptance criteria exist as tests, and they pass.
3. Public API has KDoc; new rules code cites the CR.
4. The invariant checker is extended if the packet introduces new state (PLAN.md §2.3).
5. A short packet report: what was built, deviations from spec, new primitives added, flagged
   issues, and suggested follow-ups.

---

## MTG terminology — required vocabulary

Use these Comprehensive Rules terms exactly, everywhere (code, KDoc, comments, tests, events).
The CR references are the authoritative definitions.

| Use this | Not this | CR | Note |
|---|---|---|---|
| battlefield | board, play, field | 400.1 | The shared zone where permanents exist. |
| library | deck | 401 | A player's deck *in game* is their library; "deck" is the pre-game list. |
| hand | — | 402 | Hidden zone; per-seat filtering applies (ADR-007). |
| graveyard | discard pile, gy | 404 | Face-up, ordered; several MVP abilities function here (CR 113.6). |
| stack | — | 405 | LIFO; spells and abilities resolve here. Mana abilities do **not** use it (605). |
| exile | removed from game, RFG | 406 | Where madness/plot cards wait; casting-from-exile is a real path. |
| command | — | 408 | Not exercised by the MVP pool, but a distinct zone. |
| cast | play (a spell) | 601 | You *cast* spells. Following the CR 601 pipeline. |
| play (a land) | cast a land | 305, 116.2a | You *play* a land; it is not cast and does not use the stack. |
| activate | use, tap for | 602 | You *activate* an activated ability (cost : effect). |
| controller | owner | 108.4, 109.4 | Who currently controls the object; may differ from owner. |
| owner | controller | 108.3 | Whose deck/library the card started in; fixed for the game. |
| priority | the turn, "my go" | 117 | The right to take an action; passes in APNAP order. |
| state-based action (SBA) | check, cleanup | 704 | Checked whenever a player would receive priority; loop until none apply. |
| tap / untap | turn / return | 701.21 | The state of a permanent, denoted by the `{T}` symbol. |
| trigger / triggered ability | proc | 603 | "When/whenever/at"; uses the stack (unlike a mana ability). |
| replacement effect | — | 614 | "instead"/"as"; modifies an event before it happens (e.g. madness). |
| any target | any target creature/player | 115 | The modern wording; a target that may be a creature, player, planeswalker, or battle. |

When a mechanic's wording is ambiguous, the Comprehensive Rules paragraph wins; cite it.
