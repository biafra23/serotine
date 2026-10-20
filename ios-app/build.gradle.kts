// :ios-app — the iOS host: a Kotlin/Native framework, SerotineKit, whose MainViewController()
// is the composition root (it builds the AppGraph from :data's adapters and renders the shared
// :ui), and the Xcode shell in xcode/ that shows it (xcode/project.yml, generated into an
// Xcode project with XcodeGen). The Xcode build runs embedAndSignAppleFrameworkForXcode, which
// links the framework for the configuration and SDK Xcode is building. Kotlin/Native builds
// on macOS only: elsewhere both targets are disabled (gradle.properties) and this module
// builds nothing.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    // The targets Myotis's iOS module has: devices and the Apple-silicon simulator.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SerotineKit"
            // Static: the Kotlin code, the Myotis engine's libmyotis_engine.a, libsodium and
            // libsecp256k1 end up in one archive the app links, with nothing to embed or load.
            isStatic = true
        }
    }

    sourceSets {
        iosMain.dependencies {
            implementation(project(":ui"))
            implementation(project(":data"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
