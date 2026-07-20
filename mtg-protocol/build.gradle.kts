plugins {
    id("mtgplay.kotlin-conventions")
}

dependencies {
    implementation(project(":mtg-core"))
    implementation(project(":mtg-rules"))
    implementation(project(":mtg-cards"))
}
