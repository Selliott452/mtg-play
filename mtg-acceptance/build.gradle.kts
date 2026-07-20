plugins {
    `java-library`
    id("mtgplay.kotlin-conventions")
}

// The correctness rig — the invariant checker, the scripted-game driver, the replay/fingerprint
// harness, and the random-legal driver — lives in this module's **main** source (P1.3), so later
// packets (the Phase 3 fuzz harness, the replay corpus) build on it as a normal library rather
// than a test fixture. It therefore depends on the engine at main scope.
dependencies {
    // `api`, not `implementation`: mtg-core and mtg-rules types appear throughout the harness'
    // public API (GameState in InvariantChecker.check and fingerprint; MatchConfig, Decision,
    // and DecisionRequest in the scripted driver and responders), so consumers need them on
    // their compile classpath. Mirrors the mtg-core/mtg-rules precedent.
    api(project(":mtg-core"))
    api(project(":mtg-rules"))

    // The remaining siblings stay test-only: the P1.3 acceptance suites drive lands-only games
    // through mtg-rules alone, and no harness main-source type references them. Future acceptance
    // suites (real decks, protocol round-trips) consume them from test source.
    testImplementation(project(":mtg-cards"))
    testImplementation(project(":mtg-pauper"))
    testImplementation(project(":mtg-protocol"))
    testImplementation(project(":mtg-cli"))
}
