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
}
