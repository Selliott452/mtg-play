import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.the

/**
 * Shared build conventions for every `mtg-*` module: Kotlin/JVM with a JDK 21 toolchain,
 * warnings promoted to errors, Kotest on the JUnit Platform, and ktlint + detekt lint under a
 * zero-warning policy. Modules apply this via `id("mtgplay.kotlin-conventions")` so build logic
 * is declared once rather than copy-pasted.
 */

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    id("maven-publish")
}

// Publishing coordinates (P8.1). JitPack rewrites the group to com.github.Selliott452 at serve
// time; the declared group is the eventual Maven Central identity. Version bumps happen at tag
// time — a git tag vX.Y.Z is the release artifact's version of record.
group = "dev.mtgplay"
version = "0.1.0"

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

// Version-catalog accessors (`libs`) are not generated inside precompiled script plugins, so the
// catalog is looked up explicitly to keep versions sourced from gradle/libs.versions.toml only.
val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    "testImplementation"(libs.findLibrary("kotest-runner-junit5").get())
    "testImplementation"(libs.findLibrary("kotest-assertions-core").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

detekt {
    buildUponDefaultConfig = true
    // Single, version-controlled ruleset shared by every module, layered on detekt's defaults.
    // Adds the project's two mechanical enforcements: no `!!` (UnsafeCallOnNullableType) and no
    // ad-hoc randomness (ForbiddenImport, ADR-006). See config/detekt/detekt.yml.
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

// UnsafeCallOnNullableType (and other type-dependent rules) only run under type resolution,
// which lives on the detektMain/detektTest tasks — not the plain `detekt` task that `check`
// wires in by default. Wire the type-resolving tasks into `check` (and therefore `build`) so
// the ruleset actually enforces rather than merely being declared.
tasks.named("check") {
    dependsOn(tasks.withType<Detekt>().matching { it.name == "detektMain" || it.name == "detektTest" })
}
