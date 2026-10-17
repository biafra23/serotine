// :domain — the wallet's language: entities, value objects, the ports (repository
// interfaces) the use cases need, and the use cases themselves. Pure Kotlin with
// coroutines' Flow and KotlinCrypto's pure-Kotlin Keccak-256 as its only
// dependencies: no Android, no Compose, no Myotis
// types (the engine is reached through a port that :data implements over
// io.myotis.api). The dependency rule of docs/architecture.md: everything may
// depend on this module, this module depends on nothing of ours.
//
// Targets: the desktop JVM and both iOS targets. There is deliberately NO Android
// target — Android consumers (:data, :ui, :android-app) resolve the JVM variant,
// exactly as every Android app consumes kotlinx-coroutines — which keeps the
// Android Gradle plugin, and with it any temptation to import android.*, out of
// the domain. That JVM variant is therefore what the Android app dexes and
// ships, so it emits Java 17 class files (CLAUDE.md, "Platform & language
// direction"), not the toolchain's 21. (Kotlin/Native builds the iOS targets on
// macOS only; elsewhere they are disabled, see gradle.properties.)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            // Keccak-256, pure Kotlin on every target: an EIP-1559 transaction's signing digest
            // and its transaction hash are Keccak-256 (model/Eip1559Transaction.kt).
            implementation(libs.kotlincrypto.sha3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
