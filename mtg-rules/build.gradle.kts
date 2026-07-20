plugins {
    `java-library`
    id("mtgplay.kotlin-conventions")
}

dependencies {
    // `api`, not `implementation`: mtg-core types appear throughout mtg-rules' public API
    // (GameState in AdvanceResult, PlayerId/CardRef in MatchConfig, LossReason in MatchResult),
    // so consumers need core on their compile classpath. Mirrors mtg-core's own precedent.
    api(project(":mtg-core"))
}
