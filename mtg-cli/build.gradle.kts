plugins {
    id("mtgplay.kotlin-conventions")
    application
}

dependencies {
    implementation(project(":mtg-core"))
    implementation(project(":mtg-rules"))
    implementation(project(":mtg-cards"))
    implementation(project(":mtg-pauper"))
    implementation(project(":mtg-protocol"))
}

// The interactive text driver's entry point (P6.4): `./gradlew :mtg-cli:run` launches a playable
// hotseat game. `--args="…"` forwards the CLI flags (--seed, --seat, --vs-random).
application {
    mainClass.set("dev.mtgplay.cli.MainKt")
}

// Gradle's run task does not forward the caller's stdin by default; without this, the driver
// reads immediate EOF and its blank-input safe defaults auto-pass the entire game.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
