plugins {
    id("mtgplay.kotlin-conventions")
    application
}

// P7.2: `./gradlew :mtg-server:run` hosts a match. The reference server needs a runnable entry
// point (ADR-008); the `application` plugin + mainClass is the only change to this pre-wired file,
// pre-authorized by the architect. The dependency block below is untouched.
application {
    mainClass.set("dev.mtgplay.server.ServerMainKt")
}

// The thin reference server (ADR-008 amendment): WebSocket host, match lifecycle, seat tokens,
// reconnection-with-resync — executable documentation of the mtg-protocol schema. The web stack
// lives HERE and only here; the schema artifact stays transport-free. Operational concerns
// (matchmaking, persistence, real auth) are consumer territory, permanently.
dependencies {
    implementation(project(":mtg-core"))
    implementation(project(":mtg-rules"))
    implementation(project(":mtg-cards"))
    implementation(project(":mtg-pauper"))
    implementation(project(":mtg-protocol"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
}
