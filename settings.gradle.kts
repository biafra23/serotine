rootProject.name = "serotine"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        // Compose Multiplatform Gradle plugin dev builds (org.jetbrains.compose).
        // Never probed while the stable plugin resolves from the Plugin Portal;
        // filtered so the escape hatch cannot serve arbitrary other plugin ids.
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev")
            content {
                includeGroupAndSubgroups("org.jetbrains.compose")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // The Myotis engine library (`eth.myotis:myotis-engine`, docs/embedding-myotis.md):
        // every Myotis `v*` tag merges its artifacts into the token-free `maven-repo`
        // branch of biafra23/myotis, a plain Maven layout that raw.githubusercontent.com
        // serves file by file. Filtered to the one group it provides, so resolution
        // never asks it for anything else (a dependency-confusion surface otherwise).
        // The branch appears with the first Myotis tag after its PR #654 (merged
        // 2026-10-15); the filter keeps the entry inert until a module declares an
        // `eth.myotis` dependency.
        maven {
            name = "MyotisMavenRepo"
            url = uri("https://raw.githubusercontent.com/biafra23/myotis/maven-repo")
            content {
                includeGroup("eth.myotis")
            }
        }
    }
}

include("domain", "data", "ui", "android-app", "desktop-app", "ios-app")
