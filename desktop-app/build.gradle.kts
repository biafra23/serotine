// :desktop-app — the desktop JVM host (Linux, macOS, Windows). main() is the composition
// root: it builds the AppGraph from :data's adapters and opens one window on the shared
// :ui. Compose Desktop's jpackage integration below produces the three installers the
// release workflows publish (.deb, .dmg, .msi).

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin { jvmToolchain(21) }

// Installer versions, DERIVED from the root build's release version (project.version
// minus -SNAPSHOT, already shape-checked there and the value the tag guard compares
// against) so a release sweep can't leave an installer stamped with the previous release.
val releaseVersion = rootProject.extra["releaseVersion"] as String

// jpackage REJECTS a major of 0 on macOS (--app-version must start above 0), so
// the .dmg cannot carry the honest 0.x version. The rule: macOS MAJOR is always
// the project MAJOR plus one (0.1.0 -> 1.1.0, 1.0.0 -> 2.0.0). Adding a constant
// to the major is order-preserving, so the emitted version is strictly increasing
// for every bump, forever — which is what macOS needs to treat a new bundle as an
// upgrade rather than a downgrade — and it has no cliff at the real 1.0.0.
val macOsPackageVersion: String = releaseVersion.split('.').let { parts ->
    "${parts[0].toInt() + 1}.${parts[1]}.${parts[2]}"
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":data"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    // Dispatchers.Main on the desktop JVM is the Swing event thread; the ViewModels'
    // viewModelScope needs it on the runtime classpath.
    implementation(libs.kotlinx.coroutines.swing)
}

// The Myotis engine's host library (libmyotis_engine.{dylib,so,dll}) is not inside its jar
// (docs/embedding-myotis.md), and no Myotis release asset carries it yet. Until the
// installers stage it, `-PmyotisLibraryDir=<dir>` points `:desktop-app:run` at one built
// from Myotis (`./gradlew cargoBuildHost` there: rust/target/release); without it the app
// runs with no light client and its balances say so. Only the run task gets it, so a local
// packaging never bakes a developer's path into an installer.
val myotisLibraryDir: Provider<String> = providers.gradleProperty("myotisLibraryDir")
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    myotisLibraryDir.orNull?.let { jvmArgs("-Djava.library.path=$it") }
}

compose.desktop {
    application {
        mainClass = "eth.serotine.desktop.MainKt"
        // Pin the runtime jpackage bundles (via jlink) to the Java 21 toolchain, the one
        // our class files target. Without this, jpackage defaults to whatever JDK runs
        // Gradle.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            // jpackage is host-OS-bound: the .dmg can only be produced on macOS, the .deb
            // only on Linux, the .msi only on Windows (with WiX 3 on the PATH) — one
            // workflow per OS under .github/workflows.
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            packageName = "Serotine"
            packageVersion = macOsPackageVersion
            description = "Serotine — an Ethereum wallet for accounts and Safes"
            // jpackage builds the deb's Maintainer field as "<vendor> <debMaintainer>",
            // so the human name lives here and debMaintainer stays a bare email.
            vendor = "Dirk Jäckel"
            // The whole JDK module graph, until the app's real module needs are known;
            // trimming to `modules(...)` is a follow-up that saves tens of MB per installer.
            includeAllModules = true
            macOS {
                bundleID = "eth.serotine"
                dockName = "Serotine"
            }
            linux {
                packageVersion = releaseVersion
                debMaintainer = "dirk@jaeckel.com"
                menuGroup = "Utility"
            }
            windows {
                packageVersion = releaseVersion
                // The msi's UpgradeCode: a newer msi with the SAME code upgrades the
                // installed app in place; a different one installs beside it. Generated
                // once for Serotine and never to be changed (the msi workflow pins a
                // second copy so a change fails there instead of orphaning installs).
                upgradeUuid = "a7691ec4-0334-46d8-8160-599a8dcdbd18"
                // Per-user: installs under %LOCALAPPDATA%\Serotine with no UAC prompt.
                perUserInstall = true
                dirChooser = false
                menu = true
                menuGroup = "Serotine"
                shortcut = true
            }
        }
    }
}
