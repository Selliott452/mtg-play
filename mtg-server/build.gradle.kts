plugins {
    id("mtgplay.kotlin-conventions")
    application
}

// P7.2: `./gradlew :mtg-server:run` hosts a match (ADR-008). The `application` plugin binds a single
// mainClass — the server.
application {
    mainClass.set("dev.mtgplay.server.ServerMainKt")
}

// P7.3: a second entry point, the reference client (ADR-008). The `application` plugin allows only one
// mainClass, so the client gets its own JavaExec task over the same runtime classpath:
// `./gradlew :mtg-server:runClient --args="--host 127.0.0.1 --port <p> --match <id> --token <t> --agent random --seed <n>"`.
tasks.register<JavaExec>("runClient") {
    group = "application"
    description = "Runs the reference match client (ADR-008): connects a seat and plays with a remote agent."
    mainClass.set("dev.mtgplay.server.client.ClientMainKt")
    classpath = sourceSets["main"].runtimeClasspath
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

    // P7.3: the reference client (ReferenceClient, ClientMain) ships in main source (ADR-008 amendment:
    // transport code lives in the server module), so its Ktor WebSocket client moves to implementation.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    testImplementation(libs.ktor.server.test.host)
}
