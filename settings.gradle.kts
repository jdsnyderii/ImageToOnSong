// ── Plugin repositories ───────────────────────────────────────────────────────
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// ── Foojay toolchain resolver ─────────────────────────────────────────────────
// Enables Gradle to automatically download the JDK declared in build.gradle.kts
// java { toolchain { vendor.set(JvmVendorSpec.AZUL) } }
// On first build Gradle will download and cache Azul Zulu 21 arm64 — no manual
// JDK install required on a clean CI or new dev machine.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ImageToOnSong"

buildCache {
    local {
        // In Kotlin, we use 'isEnabled' or assignment
        isEnabled = true

        // Use property assignment for the directory
        directory = File(rootDir, "build/build-cache")

    }
}