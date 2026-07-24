plugins {
    id("mtgplay.kotlin-conventions")
    alias(libs.plugins.kotlin.serialization)
}

// Transport-free schema artifact (ADR-008 amendment): DTOs + codec only. No server, no Ktor —
// consumers of the schema must never inherit a web stack. The reference server is P7.2's concern.
dependencies {
    api(project(":mtg-core"))
    api(project(":mtg-rules"))
    implementation(project(":mtg-cards"))
    implementation(libs.kotlinx.serialization.json)
}
