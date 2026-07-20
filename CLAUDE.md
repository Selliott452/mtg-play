# CLAUDE.md

Onboarding for agent sessions. This file orients you; the linked documents are authoritative.

## What this is

A headless Magic: The Gathering rules engine in Kotlin, published as a JVM library and built as
a deterministic, fully enumerable training environment for AI agents playing Pauper. The engine
advances until it needs a decision, then hands the deciding seat a typed request with enumerated
legal options; the first playable milestone is a full game of Mono-Red Madness vs GW Bogles.

## Two non-negotiables

- **Seeded PRNG only (ADR-006).** All randomness flows through the match-owned seeded PRNG —
  `dev.mtgplay.core.random.Rng`, an in-repo pure splitmix64 whose algorithm is a frozen replay
  contract. `kotlin.random.Random` / `java.util.Random` in `mtg-*` is a review-blocking defect
  and is rejected by detekt; there are no exceptions and no suppressions anywhere.
- **Enumerated actions only (ADR-005).** Agents pick engine-enumerated options by stable index;
  they never construct actions freeform.

## Modules

Dependency rule of thumb: **nouns in core, verbs in rules.** `mtg-rules` never names a specific
card. Full dependency rules: PLAN.md §3.

| Module | Purpose | Depends on |
|---|---|---|
| `mtg-core` | Vocabulary: ids, zones, mana, characteristics, `GameState` shape, `Rng` (in-repo splitmix64), `GameEvent`. Types only, no game logic. | — |
| `mtg-rules` | The engine: turn structure, priority, stack, casting, combat, SBAs, layers, trigger/replacement frameworks, effect primitives. | core |
| `mtg-cards` | Card definitions in the DSL, plus the DSL builders. | core, rules |
| `mtg-pauper` | Format: legal sets, banned list, deck validation, Scryfall ingestion. | core, cards |
| `mtg-protocol` | JSON/WebSocket match server + message schema. | core, rules, cards |
| `mtg-cli` | Interactive text driver for hand-testing. | everything |
| `mtg-acceptance` | Scripted full-game tests, invariant checker, fuzz harness, replay tests. Test-only. | everything |

## Build

Requires JDK 21. From the repo root:

```
./gradlew build                 # compile + test + lint (ktlint + detekt), zero-warning policy
./gradlew :mtg-core:test        # test a single module (swap in any mtg-* module)
./gradlew :mtg-rules:detektMain # detekt with type resolution for one module
```

## Pointers

- [`docs/PLAN.md`](docs/PLAN.md) — the plan, architecture keystones, phases, and per-packet specs.
- [`CONVENTIONS.md`](CONVENTIONS.md) — coding style, the MTG terminology glossary, Definition of Done.
- [`docs/adr/`](docs/adr/) — the eight locked architectural decisions (ADR-001 … ADR-008).
- [`docs/decklists.md`](docs/decklists.md) — the pinned MVP card pool and its design consequences.
