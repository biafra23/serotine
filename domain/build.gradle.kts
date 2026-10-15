// :domain — the wallet's language: entities, value objects, the ports (repository
// interfaces) the use cases need, and the use cases themselves. Pure Kotlin with
// coroutines' Flow as its only dependency: no Android, no Compose, no Myotis
// types (the engine is reached through a port that :data implements over
// io.myotis.api). The dependency rule of docs/architecture.md: everything may
// depend on this module, this module depends on nothing of ours.
//
// Targets: the desktop JVM and both iOS targets. There is deliberately NO Android
// target — Android consumers (:data, :ui, :android-app) resolve the JVM variant,
// exactly as every Android app consumes kotlinx-coroutines — which keeps the
// Android Gradle plugin, and with it any temptation to import android.*, out of
// the domain. (Kotlin/Native builds the iOS targets on macOS only; elsewhere they
// are disabled, see gradle.properties.)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
