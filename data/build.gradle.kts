// :data — the adapters behind the domain's ports: repository implementations and the
// data sources they read (today an in-memory account store and the key adapter that
// derives an account from BIP-39 words; next the Myotis engine behind an
// `io.myotis.api` adapter, see docs/embedding-myotis.md). Depends on
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
    alias(libs.plugins.kotlin.serialization)
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
            // The persistent account list: a JSON file, written atomically.
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
            // The key adapter (eth.serotine.data.keys): libsecp256k1 and the digests.
            implementation(libs.secp256k1.kmp)
            implementation(libs.kotlincrypto.sha2)
            implementation(libs.kotlincrypto.sha3)
            implementation(libs.kotlincrypto.hmac.sha2)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // libsecp256k1's JNI bindings, which secp256k1-kmp looks up by reflection at first
        // use: the Android one (.so files for the four Android ABIs) on Android, the JVM one
        // (darwin universal, linux x64 + aarch64, windows x64) on the desktop. The Android
        // unit tests run on the build machine's JVM, where the Android binding's
        // System.loadLibrary fails and its loader falls back to the JVM binding — so that
        // classpath needs the JVM one too. The AAR ships no keep rules: whoever turns on R8
        // for the app adds `-keep class fr.acinq.secp256k1.jni.** { *; }`, or the reflective
        // lookup finds nothing and every derivation answers Unavailable, on release only.
        androidMain.dependencies {
            implementation(libs.secp256k1.kmp.jni.android)
        }
        // The key envelope's platform half (eth.serotine.data.keys): libsodium's Argon2id
        // through JNA, AES-256-GCM and durable file writes from the JDK. Android and the
        // desktop share it, as both run on a JVM; iOS gets its own half with the iOS host.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // For the libsodium binaries it ships (its Kotlin API is not used), and for
                // JNA at runtime: the jar on the desktop, the AAR (classes and the native
                // stubs for all four ABIs) on Android.
                implementation(libs.libsodium.bindings)
                // JNA's API to compile against only: a jar at runtime would duplicate the
                // AAR's classes on Android.
                compileOnly(libs.jna)
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        val desktopMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(libs.secp256k1.kmp.jni.jvm)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.secp256k1.kmp.jni.jvm)
            }
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
