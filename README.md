# mtg-play

A headless Magic: The Gathering rules engine written in Kotlin and published as a JVM library.
It exists to be a deterministic, fully enumerable training environment for AI agents playing the
Pauper format — the engine advances until it needs a decision, then hands the agent a typed
request with enumerated legal options.

## Goals

- A correct, Comprehensive-Rules-faithful rules engine (correctness first, then readability, then
  structure).
- Immutable, reproducible game state: `(seed + decision log)` fully replays any game.
- A decision-point API that serves in-process AI, a text CLI, and a match protocol from one loop.
- First playable milestone: a full game of Mono-Red Madness vs GW Bogles.

## Non-goals

- No graphical user interface — this is a library, not a game client.
- Not a service for facilitating unofficial or unsanctioned play; it is a rules engine for
  research and AI training.
- No dependency on existing engines (Forge, XMage) — they are reference reading only.

## Status

Phase 0 (scaffold and governance). The build is a seven-module Gradle skeleton; engine code
lands in later phases.

## Building

Requires JDK 21. From the repository root:

```
./gradlew build
```

This compiles every module, runs the tests, and enforces the zero-warning lint policy
(ktlint + detekt).

## Card data attribution

Card metadata (names, mana costs, type lines, oracle text, and format legalities) is sourced from
[Scryfall](https://scryfall.com) and used under the
[Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/)
license (ADR-003). The `mtg-pauper` module ingests a trimmed snapshot of this data; the snapshot's
provenance is embedded in the data and surfaced through the format layer's API.

Magic: The Gathering is © Wizards of the Coast. This project is an unofficial, non-commercial rules
engine for research and AI training and is not affiliated with or endorsed by Wizards of the Coast or
Scryfall.

## Using the library

Artifacts are served via [JitPack](https://jitpack.io) from tagged releases (Maven Central
planned). In Gradle:

```kotlin
repositories { maven("https://jitpack.io") }
dependencies {
    // The engine (in-process API — the primary AI interface, ADR-008):
    implementation("com.github.Selliott452.mtg-play:mtg-rules:v0.1.0")
    implementation("com.github.Selliott452.mtg-play:mtg-cards:v0.1.0")
    implementation("com.github.Selliott452.mtg-play:mtg-pauper:v0.1.0")
    // Wire-protocol schema for remote agents (transport-free):
    implementation("com.github.Selliott452.mtg-play:mtg-protocol:v0.1.0")
    // Reference WebSocket server + client (the only artifact carrying the web stack):
    implementation("com.github.Selliott452.mtg-play:mtg-server:v0.1.0")
}
```

## License

[Apache License 2.0](LICENSE).

## More

See [`docs/PLAN.md`](docs/PLAN.md) for the full implementation plan, module map, and work-packet
breakdown.
