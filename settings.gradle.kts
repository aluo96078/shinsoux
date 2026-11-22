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
// C1 (0007): the UI is :ui-core (design system), the feature UI modules and :ui (the shell).
// 0008 §7-5: the section UI modules (browse, library, more) sit between the feature UI modules and the shell.
include(":core")
include(":core-network")
include(":domain")
include(":data-persistence-api")
include(":data-content")
include(":data-repository")
include(":feature-sync")
include(":feature-library")
include(":feature-reader")
include(":feature-translation")
include(":feature-plugin")
include(":ui-core")
include(":ui-reader")
include(":ui-translation")
include(":ui-sync")
include(":ui-browse")
include(":ui-library")
include(":ui-settings")
include(":ui")
include(":composeApp")
