plugins {
    `java-library`
    id("mtgplay.kotlin-conventions")
}

dependencies {
    // `api`, not `implementation`: persistent-collection types appear in mtg-core's public
    // API (the GameState zone fields), so consumers need them on their compile classpath.
    api(libs.kotlinx.collections.immutable)
}
