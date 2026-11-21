pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Shinsou X"
// R5-1 (docs/architecture/0006 §4.2): the library modules, lowest layer first, and the app.
include(":core")
include(":core-network")
include(":domain")
include(":data-content")
include(":data-repository")
include(":feature-sync")
include(":feature-library")
include(":feature-reader")
include(":feature-translation")
include(":feature-plugin")
include(":ui")
include(":composeApp")
