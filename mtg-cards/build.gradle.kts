plugins {
    `java-library`
    id("mtgplay.kotlin-conventions")
}

dependencies {
    // `api`, not `implementation`: mtg-core types appear in mtg-cards' public API — every
    // definition is a core `CardDefinition`, and the registry is keyed by core `CardRef` —
    // so consumers need them on their compile classpath. Mirrors the mtg-core precedent.
    api(project(":mtg-core"))

    // Rules stays implementation scope: definitions *use* effect primitives (dealDamage) in
    // resolution bodies, but no mtg-rules type appears in mtg-cards' public API.
    implementation(project(":mtg-rules"))
}
