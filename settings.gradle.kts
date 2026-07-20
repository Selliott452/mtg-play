rootProject.name = "mtg-play"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "mtg-core",
    "mtg-rules",
    "mtg-cards",
    "mtg-pauper",
    "mtg-protocol",
    "mtg-cli",
    "mtg-acceptance",
)
