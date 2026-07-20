plugins {
    id("mtgplay.kotlin-conventions")
}

// Acceptance is a test-focused module: scripted full-game tests, the invariant checker, the fuzz
// harness, and replay tests all live in its test source, which therefore depends on every other
// module. Kept as a normal module so those tests compile against the real APIs.
dependencies {
    testImplementation(project(":mtg-core"))
    testImplementation(project(":mtg-rules"))
    testImplementation(project(":mtg-cards"))
    testImplementation(project(":mtg-pauper"))
    testImplementation(project(":mtg-protocol"))
    testImplementation(project(":mtg-cli"))
}
