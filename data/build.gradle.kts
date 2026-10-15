// :data — the adapters behind the domain's ports: repository implementations and the
// data sources they read (today an in-memory account store; next the Myotis engine
// behind an `io.myotis.api` adapter, see docs/embedding-myotis.md). Depends on
// :domain only; :ui never depends on this module — the hosts' composition roots wire
// it in.
//
// Targets: Android, the desktop JVM and both iOS targets. The Android target exists
// here (unlike :domain) because the Myotis engine library ships as an AAR with the
// engine's native libraries inside: an Android consumer must resolve that variant,
// which only an Android target does. Nothing in commonMain may import android.*
// regardless — platform code goes into androidMain / desktopMain / iosMain.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(21)

    androidTarget {
        compilerOptions {
            // Java 17 class files for Android (CLAUDE.md, "Platform & language direction").
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "eth.serotine.data"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
