plugins {
    id("mtgplay.kotlin-conventions")
}

dependencies {
    implementation(project(":mtg-core"))
    implementation(project(":mtg-cards"))

    // Runtime-only kotlinx.serialization JSON (P6.1): the Scryfall snapshot is read through the
    // JsonElement tree API (Json.parseToJsonElement), so only the runtime library is needed — the
    // serialization compiler plugin is deliberately not applied. Flagged build change (packet report).
    implementation(libs.kotlinx.serialization.json)
}
